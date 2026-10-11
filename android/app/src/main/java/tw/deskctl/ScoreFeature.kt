package tw.deskctl

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 比分板: 依「比分」分頁設定的追蹤清單, 在背景抓 MLB / NBA / CPBL 資料, 傳給控制器的「比分板」頁。
 * 協定: 手機→控制器 {"k":"games","l":清單(每項 聯盟,客隊,主隊)} (順序=欄位編號)、
 *       {"k":"score","i":欄位編號,"lg":..,"st":"P|L|F",...}; 控制器→手機 {"c":"sc","on":1/0} (比分頁是否開著)。
 * 更新頻率: 控制器正在看比分頁 8 秒; 否則有比賽進行中 30 秒; 都沒進行中 3 分鐘。
 */
class ScoreFeature(host: FeatureHost) : Feature(host) {
    override val id = "score"

    private var boardOn = false
    private var lastPoll = 0L
    private var running = false
    private var anyLive = false
    private var seenVer = -1
    private var stopped = false

    companion object {
        /** 給手機畫面顯示: 追蹤中每場的即時摘要 (欄位編號 → 文字) */
        @Volatile var summary: Map<Int, String> = emptyMap()
        @Volatile var version = 0
    }

    override fun start() {
        stopped = false
    }

    override fun stop() {
        stopped = true
    }

    override fun onReady() {
        seenVer = -1
        lastPoll = 0L
        pushGames()
    }

    override fun onTick(n: Int) {
        if (seenVer != ScoreStore.version) pushGames()
        val iv = if (boardOn) 8000L else if (anyLive) 30000L else 180000L
        if (SystemClock.elapsedRealtime() - lastPoll >= iv) poll()
    }

    override fun onCommand(c: String, o: JSONObject): Boolean {
        if (c != "sc") return false
        boardOn = o.optInt("on") == 1
        if (boardOn) poll()
        return true
    }

    override fun info(): String {
        val n = ScoreStore.load(host.context).size
        return if (n == 0) "" else "比分板: 追蹤 $n 場" + if (anyLive) " (有比賽進行中)" else ""
    }

    private fun pushGames() {
        seenVer = ScoreStore.version
        val l = ScoreStore.load(host.context)
        val arr = JSONArray()
        for (t in l) arr.put(JSONArray().put(t.lg).put(t.a).put(t.h))
        host.send(JSONObject().put("k", "games").put("l", arr))
        poll()
    }

    private fun poll() {
        if (running) return
        val l = ScoreStore.load(host.context)
        lastPoll = SystemClock.elapsedRealtime()
        if (l.isEmpty()) {
            summary = emptyMap()
            version++
            return
        }
        running = true
        Thread {
            val res = try {
                ScoreApi.fetch(l)
            } catch (e: Exception) {
                AppLog.w("SCORE", "抓取失敗: $e")
                emptyMap()
            }
            host.handler.post {
                running = false
                if (stopped) return@post
                var live = false
                val sum = HashMap<Int, String>()
                for ((i, o) in res) {
                    host.sendIfChanged("score$i", o)
                    if (o.optString("st") == "L") live = true
                    sum[i] = describe(o)
                }
                anyLive = live
                summary = sum
                version++
            }
        }.start()
    }

    private fun describe(o: JSONObject): String = when (o.optString("st")) {
        "P" -> "未開賽 " + o.optString("tm")
        "F" -> "結束 " + o.optInt("as") + ":" + o.optInt("hs")
        else -> if (o.optString("lg") == "NBA") {
            "第" + o.optInt("q") + "節 " + o.optString("c") + "  " + o.optInt("as") + ":" + o.optInt("hs")
        } else {
            (o.optString("tx").ifEmpty { o.optInt("in").toString() + "局" }) + "  " + o.optInt("as") + ":" + o.optInt("hs")
        }
    }
}
