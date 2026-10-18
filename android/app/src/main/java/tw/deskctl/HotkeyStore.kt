package tw.deskctl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 控制器快捷鍵 (存在手機上, 連線時推給控制器; 控制器自己也會存一份, 離線照常可用)。
 * 一筆 = 範圍 (全域 "*" 或頁面 id) + 按鍵 + 手勢 + 動作。格式與韌體 state.py 的 clean_hk 相同:
 * [範圍, 鍵0左1上2中3下4右, 手勢0雙擊1三連擊2長按, 動作, 參數, 優先於頁面0/1]
 */
object HotkeyStore {
    class Hk(val scope: String, val key: Int, val gest: Int, val act: String, val arg: String, val force: Boolean) {
        fun toJson(): JSONArray = JSONArray().put(scope).put(key).put(gest).put(act).put(arg).put(if (force) 1 else 0)
    }

    class ActDef(val id: String, val label: String, val arg: String)   // arg: "" 沒有 / "page" 選頁面 / "shortcut" 選捷徑

    const val MAX = 24
    val KEYS = arrayOf("左", "上", "中", "下", "右")
    val GESTS = arrayOf("雙擊", "三連擊", "長按")

    val ACTS = listOf(
        ActDef("goto", "切換到頁面…", "page"), ActDef("back", "回上一個頁面", ""), ActDef("home", "回首頁", ""),
        ActDef("menu", "開啟 / 關閉目錄", ""), ActDef("ring+", "快速切換: 下一個", ""), ActDef("ring-", "快速切換: 上一個", ""),
        ActDef("off", "立即熄屏 (按任一鍵喚醒)", ""), ActDef("pp", "播放 / 暫停", ""), ActDef("next", "下一首", ""),
        ActDef("prev", "上一首", ""), ActDef("mute", "靜音 / 取消靜音", ""), ActDef("vol+", "音量加", ""),
        ActDef("vol-", "音量減", ""), ActDef("seek+", "快轉", ""), ActDef("seek-", "倒轉", ""),
        ActDef("run", "執行手機捷徑…", "shortcut"), ActDef("memclean", "清理控制器記憶體", "")
    )

    /** 可切換的頁面 (id, 名稱) */
    val PAGES = listOf(
        "home" to "首頁", "desk" to "桌面", "sw" to "碼表", "music" to "音樂", "score" to "比分板", "games" to "遊戲", "dev" to "系統總覽",
        "msg" to "手機訊息", "act" to "捷徑", "pad" to "手機控制", "sys" to "手機狀態", "manual" to "說明書", "settings" to "快捷設定"
    )

    fun pageName(id: String): String = PAGES.firstOrNull { it.first == id }?.second ?: id

    fun actLabel(a: Hk): String {
        val d = ACTS.firstOrNull { it.id == a.act }
        val base = d?.label?.removeSuffix("…") ?: a.act
        return when (d?.arg) {
            "page" -> "$base ${pageName(a.arg)}"
            "shortcut" -> "$base「${a.arg}」"
            else -> base
        }
    }

    /** 各頁面自己會處理 (含攔截) 的手勢, 代碼 = 鍵編號 + 手勢編號; 由模擬器逐頁實測產生 */
    val USED: Map<String, Set<String>> = mapOf(
        "home" to "00 01 02 10 11 12 20 30 31 32 40 41 42",
        "desk" to "00 01 02 10 11 12 20 30 31 32 40 41 42",
        "sw" to "11 12 30 31 32",
        "music" to "00 01 02 10 11 12 20 30 31 32 40 41 42",
        "score" to "10 11 12 30 31 32",
        "games" to "12 32",
        "dev" to "10 11 12 20 30 31 32",
        "msg" to "02 10 11 12 20 30 31 32 42",
        "act" to "10 11 12 20 30 31 32",
        "pad" to "00 01 02 10 11 12 20 30 31 32 40 41 42",
        "sys" to "10 11 12 20 30 31 32"
    ).mapValues { it.value.split(" ").toSet() }

    /** 這個手勢在哪些頁面本來就有功能 (回傳頁面名稱) */
    fun usedBy(key: Int, gest: Int): List<String> {
        val code = "$key$gest"
        return PAGES.filter { USED[it.first]?.contains(code) == true }.map { it.second }
    }

    @Volatile var version = 0

    fun parse(s: String): MutableList<Hk> {
        val out = ArrayList<Hk>()
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val e = a.optJSONArray(i) ?: continue
                out.add(Hk(e.optString(0), e.optInt(1), e.optInt(2), e.optString(3), e.optString(4), e.optInt(5) != 0))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun load(c: Context): MutableList<Hk> = parse(Prefs.hotkeys(c))

    fun toJson(l: List<Hk>): JSONArray {
        val a = JSONArray()
        for (h in l.take(MAX)) a.put(h.toJson())
        return a
    }

    fun save(c: Context, l: List<Hk>) {
        Prefs.setHotkeys(c, toJson(l).toString())
        Prefs.setHkTouched(c, true)
        version++
    }
}

private fun dur(v: Int): String = when {
    v == 0 -> "永不"
    v < 60 -> "$v 秒"
    v < 3600 -> "${v / 60} 分"
    v % 3600 == 0 -> "${v / 3600} 小時"
    else -> "%.1f 小時".format(v / 3600.0)
}

private fun onoff(v: Int) = if (v != 0) "開" else "關"

/** 控制器上可由手機修改的設定 (和控制器「快捷設定」頁一致; 值的白名單在韌體 state.py 的 CFG_OPTS)。 */
object BoardCfg {
    class Opt(val key: String, val title: String, val vals: IntArray, val fmt: (Int) -> String)

    val OPTS: Map<String, Opt> = listOf(
        Opt("h24", "時間制", intArrayOf(1, 0)) { if (it != 0) "24 小時" else "12 小時" },
        Opt("secs", "首頁顯示秒", intArrayOf(0, 1), ::onoff),
        Opt("tsecs", "狀態欄顯示秒", intArrayOf(0, 1), ::onoff),
        Opt("seek", "快轉 / 倒轉秒數", intArrayOf(5, 10, 15, 30)) { "$it 秒" },
        Opt("vstep", "音量步進", intArrayOf(1, 2, 3)) { "$it 格" },
        Opt("msgw", "新訊息喚醒螢幕", intArrayOf(1, 0), ::onoff),
        Opt("msgt", "狀態欄訊息圖示", intArrayOf(1, 0), ::onoff),
        Opt("pwr", "閒置省電", intArrayOf(1, 0), ::onoff),
        Opt("lowt", "低亮度 / 時鐘待機", intArrayOf(60, 120, 300, 600, 1200, 1800, 3600), ::dur),
        Opt("idle", "自動熄屏", intArrayOf(0, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200), ::dur),
        Opt("lsleep", "深度睡眠 (實驗)", intArrayOf(0, 1), ::onoff),
        Opt("dimt", "變暗時間 (需可調背光)", intArrayOf(15, 30, 60, 120, 300, 600), ::dur),
        Opt("bright", "背光亮度 (需可調背光)", intArrayOf(30, 50, 80, 100)) { "$it%" }
    ).associateBy { it.key }

    /** 控制器最近回報的值 (加上手機剛改、還沒送出去的) */
    @Volatile var values: Map<String, Int> = emptyMap()
    @Volatile var backlight = false
    @Volatile var version = 0
    private val pending = HashMap<String, Int>()

    fun set(key: String, v: Int) {
        synchronized(pending) { pending[key] = v }
        values = values + (key to v)
        version++
    }

    fun hasPending(): Boolean = synchronized(pending) { pending.isNotEmpty() }

    fun takePending(): Map<String, Int> = synchronized(pending) {
        val m = HashMap(pending)
        pending.clear()
        m
    }

    /** 控制器回報: 還沒送出的修改優先 (不被舊值蓋掉) */
    fun report(d: JSONObject, bl: Boolean) {
        val m = HashMap<String, Int>()
        for (k in OPTS.keys) if (d.has(k)) m[k] = d.optInt(k)
        synchronized(pending) { for ((k, v) in pending) m[k] = v }
        values = m
        backlight = bl
        version++
    }

    fun reset() {
        values = emptyMap()
    }
}
