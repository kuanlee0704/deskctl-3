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
    private var selNbaLive = false
    private var selMlbLive = false
    private var fails = 0             // 連續抓取失敗次數 (用來拉長間隔, 避免沒網路/被限流時狂打)
    private var seenVer = -1
    private var stopped = false
    private var sel = 0               // 控制器正在看的欄位
    private var forceSel = false      // 剛換場: 下一次要把完整資料硬送一次 (控制器會把沒在看的比賽縮成精簡版)
    private var rerun = false

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
        clearQueue()
    }

    override fun onReady() {
        seenVer = -1
        lastPoll = 0L
        pushGames()
    }

    override fun onTick(n: Int) {
        if (seenVer != ScoreStore.version) pushGames()
        var iv = if (boardOn) (if (selNbaLive) 4000L else if (selMlbLive) 5000L else 8000L) else if (anyLive) 30000L else 180000L
        if (fails > 0) iv *= (1 + minOf(fails, 6))
        if (SystemClock.elapsedRealtime() - lastPoll >= iv) poll()
    }

    override fun onCommand(c: String, o: JSONObject): Boolean {
        if (c != "sc") return false
        boardOn = o.optInt("on") == 1
        if (!boardOn) clearQueue()
        if (boardOn) {
            val ns = o.optInt("sel", 0)
            if (ns != sel) clearQueue()
            sel = ns
            forceSel = true
            if (running) rerun = true else poll()
        }
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
                ScoreApi.fetch(l, sel, boardOn)
            } catch (e: Exception) {
                AppLog.w("SCORE", "抓取失敗: $e")
                emptyMap()
            }
            host.handler.post {
                running = false
                if (stopped) return@post
                var live = false
                val sum = HashMap<Int, String>()
                val force = forceSel
                forceSel = false
                selNbaLive = false
                selMlbLive = false
                if (res.isEmpty()) fails++ else fails = 0
                val delayMs = if (boardOn) Prefs.nbaDelay(host.context) * 1000L else 0L
                val delayed = HashSet<Int>()
                for ((i0, o0) in res) {
                    val i = i0
                    // 中文隊名在手機端組好 (tn); 非選取中的欄位只送精簡欄位, 減輕控制器記憶體負擔
                    val o = if (boardOn && i == sel) o0 else liteOf(o0)
                    val nbaSel = boardOn && i == sel && o0.optString("lg") == "NBA"
                    if (nbaSel && delayMs > 0 && (o0.optString("st") == "L" || (playing && qSlot == i))) {
                        enqueue(i, o0, delayMs)        // 延遲播放: 先收進佇列, 時間到才送給控制器
                        delayed.add(i)
                    } else {
                        if (i == qSlot) clearQueue()
                        if (force && i == sel) host.send(o) else host.sendIfChanged("score$i", o)
                    }
                    if (i == sel && o.optString("lg") == "NBA" && o.optString("st") == "L") selNbaLive = true
                    if (i == sel && o.optString("lg") == "MLB" && o.optString("st") == "L") selMlbLive = true
                    if (o.optString("st") == "L") live = true
                    sum[i] = describe(o)
                }
                // 這幾場剛確認過資料 (沒變動也算): 控制器用來顯示「距離上次更新」
                val ok = JSONArray()
                for (i in res.keys) ok.put(i)
                if (ok.length() > 0) host.send(JSONObject().put("k", "scok").put("l", ok))
                anyLive = live
                summary = sum
                version++
                if (rerun) {
                    rerun = false
                    poll()
                }
            }
        }.start()
    }

    private fun describe(o: JSONObject): String = when (o.optString("st")) {
        "P" -> "未開賽 " + o.optString("tm")
        "F" -> "結束 " + o.optInt("as") + ":" + o.optInt("hs")
        else -> if (o.optString("lg") == "SOC") {
            o.optString("c") + "  " + o.optInt("as") + ":" + o.optInt("hs")
        } else if (o.optString("lg") == "NBA") {
            "第" + o.optInt("q") + "節 " + o.optString("c") + "  " + o.optInt("as") + ":" + o.optInt("hs")
        } else {
            (o.optString("tx").ifEmpty { o.optInt("in").toString() + "局" }) + "  " + o.optInt("as") + ":" + o.optInt("hs")
        }
    }

    // ---------------- 延遲播放 (籃球, 控制器正在看的那場) ----------------
    private class Snap(val recv: Long, val due: Long, val o: JSONObject)

    private val queue = ArrayList<Snap>()
    private var qSlot = -1
    private var playing = false
    private val flushRun = Runnable { flush() }

    private fun clearQueue() {
        queue.clear()
        playing = false
        qSlot = -1
        host.handler.removeCallbacks(flushRun)
    }

    private fun enqueue(i: Int, o: JSONObject, delayMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (qSlot != i) {
            clearQueue()
            qSlot = i
        }
        val sec = (delayMs / 1000).toInt()
        if (!playing) {
            // 第一筆立刻顯示 (標示「緩衝中」), 之後的資料都延遲 delayMs 才播出, 這段時間足夠收到好幾筆
            playing = true
            val first = JSONObject(o.toString())
            first.put("bf", sec).put("dl", sec).put("cr", 0)
            host.send(first)
            // 同一筆也排進佇列: 緩衝結束時從它開始, 順著後面收到的資料一路倒數 (不會停在原地再跳)
            queue.add(Snap(now, now + delayMs, JSONObject(o.toString())))
            host.handler.postDelayed(flushRun, delayMs)
            return
        }
        queue.add(Snap(now, now + delayMs, JSONObject(o.toString())))
        host.handler.postDelayed(flushRun, delayMs)
    }

    /** 到時間的那一筆送出; 計時器附上「下一筆已知的剩餘時間 (cn) 與相隔毫秒 (cd)」, 控制器在兩筆之間插值 */
    private fun flush() {
        val now = SystemClock.elapsedRealtime()
        var cur: Snap? = null
        while (queue.isNotEmpty() && queue[0].due <= now + 60L) cur = queue.removeAt(0)
        val c = cur ?: return
        val o = c.o
        o.put("dl", ((c.due - c.recv) / 1000).toInt()).put("cr", 0)
        val next = queue.firstOrNull()
        if (next != null && next.o.has("cs")) {
            o.put("cn", next.o.optInt("cs")).put("cd", (next.recv - c.recv).toInt())
        }
        host.send(o)
    }

    private val LITE = listOf("lg", "st", "a", "h", "as", "hs", "in", "hf", "tx", "mid", "tm", "q", "c", "brk", "tn")

    private fun liteOf(o: JSONObject): JSONObject {
        val r = JSONObject()
        for (k in LITE) if (o.has(k)) r.put(k, o.get(k))
        return r
    }
}
