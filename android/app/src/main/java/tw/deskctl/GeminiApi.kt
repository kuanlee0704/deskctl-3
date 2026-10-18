package tw.deskctl

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Gemini REST 介面 (generativelanguage.googleapis.com)。金鑰放在標頭, 不會寫進記錄。
 * 必須在背景執行緒呼叫。
 */
object GeminiApi {
    class Result(val code: Int, val json: JSONObject?, val error: String)

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    private fun post(model: String, key: String, body: JSONObject): Result {
        var c: HttpURLConnection? = null
        try {
            c = URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return Result(code, JSONObject(txt), "")
            val msg = try {
                JSONObject(txt).optJSONObject("error")?.optString("message").orEmpty()
            } catch (_: Exception) {
                txt.take(200)
            }
            AppLog.w("AI", "Gemini HTTP $code: " + msg.take(160))
            return Result(code, null, msg)
        } catch (e: Exception) {
            AppLog.w("AI", "Gemini 連線失敗: " + e.javaClass.simpleName)
            return Result(0, null, "連線失敗 (" + e.javaClass.simpleName + "), 請檢查網路")
        } finally {
            c?.disconnect()
        }
    }

    /** 一次對話請求。contents 是完整對話 (含工具呼叫與結果); tools 可為空 */
    fun generate(model: String, key: String, system: String, contents: JSONArray, tools: JSONArray?): Result {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
        if (tools != null && tools.length() > 0) body.put("tools", tools)
        body.put("generationConfig", JSONObject().put("temperature", 0.4))
        return post(model, key, body)
    }

    /** 用 Google 搜尋輔助回答 (查球隊中文名等); 回傳 (答案文字, 來源清單 標題→網址) */
    fun search(model: String, key: String, query: String): Pair<String, List<Pair<String, String>>> {
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put(
                "parts", JSONArray().put(JSONObject().put("text", "請用網路搜尋回答, 用繁體中文, 簡短、直接給結論 (若在查球隊, 請給英文原名與台灣常用的中文譯名): $query"))
            )
        )
        val tools = JSONArray().put(JSONObject().put("google_search", JSONObject()))
        val r = generate(model, key, "你是搜尋助手。只根據搜尋結果回答, 查不到就明說查不到。", contents, tools)
        val j = r.json ?: return Pair("搜尋失敗: " + r.error, emptyList())
        val cand = j.optJSONArray("candidates")?.optJSONObject(0)
        val text = textOf(cand?.optJSONObject("content"))
        val src = ArrayList<Pair<String, String>>()
        val chunks = cand?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks != null) for (i in 0 until chunks.length()) {
            val w = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
            val uri = w.optString("uri")
            if (uri.isNotEmpty()) src.add(Pair(w.optString("title").ifEmpty { uri }, uri))
        }
        return Pair(text.ifEmpty { "(沒有搜尋結果)" }, src.take(4))
    }

    fun textOf(content: JSONObject?): String {
        val parts = content?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotEmpty()) sb.append(t)
        }
        return sb.toString()
    }
}
