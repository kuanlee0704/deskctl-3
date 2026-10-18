package tw.deskctl

import android.content.Context
import org.json.JSONObject

/**
 * 設定備份/還原: 把 SharedPreferences "deskctl" 整包轉成一段 JSON 文字。
 * 用途: 萬一必須解除安裝重裝 (簽章不同時 Android 不允許覆蓋), 還原即可一鍵取回所有設定。
 * AI 金鑰屬於機密, 預設不含, 匯出時要勾選才會寫入。
 */
object SettingsBackup {
    private const val MAGIC = "deskctl-settings"
    private val SECRET = setOf("ai_key")
    /** 只屬於這台手機當下狀態的鍵, 不備份 */
    private val SKIP = setOf("was_started")

    fun export(c: Context, withSecrets: Boolean): String {
        val sp = c.getSharedPreferences("deskctl", Context.MODE_PRIVATE)
        val data = JSONObject()
        for ((k, v) in sp.all) {
            if (k in SKIP || (!withSecrets && k in SECRET)) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> { o.put("t", "b"); o.put("v", v) }
                is Int -> { o.put("t", "i"); o.put("v", v) }
                is Long -> { o.put("t", "l"); o.put("v", v) }
                is Float -> { o.put("t", "f"); o.put("v", v.toDouble()) }
                is String -> { o.put("t", "s"); o.put("v", v) }
                is Set<*> -> { o.put("t", "ss"); o.put("v", org.json.JSONArray(v.map { it.toString() })) }
                else -> continue
            }
            data.put(k, o)
        }
        return JSONObject().put("magic", MAGIC).put("ver", 1).put("data", data).toString()
    }

    /** 還原; 回傳還原的項目數, 內容不是備份檔則回傳 -1。不會動到備份裡沒有的鍵 (例如沒備份的 AI 金鑰)。 */
    fun import(c: Context, json: String): Int {
        val root = try { JSONObject(json.trim()) } catch (_: Exception) { return -1 }
        if (root.optString("magic") != MAGIC) return -1
        val data = root.optJSONObject("data") ?: return -1
        val e = c.getSharedPreferences("deskctl", Context.MODE_PRIVATE).edit()
        var n = 0
        val it = data.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k in SKIP) continue
            val o = data.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "s" -> e.putString(k, o.optString("v"))
                "ss" -> {
                    val a = o.optJSONArray("v") ?: continue
                    e.putStringSet(k, HashSet<String>().also { s -> for (i in 0 until a.length()) s.add(a.optString(i)) })
                }
                else -> continue
            }
            n++
        }
        e.commit()
        return n
    }

    /** 已安裝 App 的簽章 SHA-256 (回傳完整冒號分隔的大寫十六進位), 取不到回傳 null */
    fun signatureSha256(c: Context): String? = try {
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val pi = c.packageManager.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            pi.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            c.packageManager.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(sigs!![0].toByteArray())
        d.joinToString(":") { "%02X".format(it) }
    } catch (_: Exception) {
        null
    }
}
