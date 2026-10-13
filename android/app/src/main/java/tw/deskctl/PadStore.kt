package tw.deskctl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手機控制器的「模式」(可存多組, 隨時切換)。
 * 一個模式 = 名稱 + 5 個位置 (U 上 R 右 D 下 L 左 C 中央) 的動作:
 *   c 單擊    d 兩下 (只有上下左右)    h 長按 (沒設就重複單擊動作, 例如一直滾動)
 * 動作 = {"t":類型, "a":參數, "b":參數2}, 類型:
 *   scroll a=u/d/l/r b=距離%     往該方向滾動一格 (需無障礙服務)
 *   tap    a="x,y"                點擊畫面位置 (%)
 *   long   a="x,y" b=毫秒         長按畫面位置
 *   swipe  a="x1,y1,x2,y2"        滑動
 *   back / home / recents / notif 返回 / 桌面 / 多工 / 通知欄
 *   vol    a=up/down/mute         音量
 *   media  a=toggle/next/prev     播放控制
 *   app    a=套件名稱             開啟 App (例如相機)
 *   shortcut a=捷徑名稱           執行「捷徑」分頁裡的捷徑
 *   mode   a=next/prev            切換到下一組/上一組模式
 * 中央固定: 兩下 = 功能順時針轉 90 度, 三下 = 回首頁, 長按 = 目錄 (這些在控制器端處理)。
 */
object PadStore {
    @Volatile var version = 0

    val SLOTS = listOf("U", "R", "D", "L", "C")
    val SLOT_NAMES = mapOf("U" to "上", "R" to "右", "D" to "下", "L" to "左", "C" to "中央")

    fun act(t: String, a: String = "", b: String = ""): JSONObject = JSONObject().put("t", t).put("a", a).put("b", b)

    private fun slot(c: JSONObject?, d: JSONObject?, h: JSONObject?): JSONObject {
        val o = JSONObject()
        if (c != null) o.put("c", c)
        if (d != null) o.put("d", d)
        if (h != null) o.put("h", h)
        return o
    }

    fun newProfile(name: String): JSONObject {
        val s = JSONObject()
        for (k in SLOTS) s.put(k, JSONObject())
        return JSONObject().put("n", name).put("s", s)
    }

    private fun defaults(): JSONArray {
        val a = newProfile("瀏覽")
        val sa = a.getJSONObject("s")
        sa.put("U", slot(act("scroll", "u", "25"), null, null))
        sa.put("D", slot(act("scroll", "d", "25"), null, null))
        sa.put("L", slot(act("scroll", "l", "25"), act("mode", "next"), null))
        sa.put("R", slot(act("scroll", "r", "25"), act("back"), null))
        sa.put("C", slot(act("tap", "50,50"), null, null))
        val b = newProfile("影片")
        val sb = b.getJSONObject("s")
        sb.put("U", slot(act("vol", "up"), null, null))
        sb.put("D", slot(act("vol", "down"), null, null))
        sb.put("L", slot(act("media", "prev"), act("mode", "next"), null))
        sb.put("R", slot(act("media", "next"), act("back"), null))
        sb.put("C", slot(act("media", "toggle"), null, null))
        return JSONArray().put(a).put(b)
    }

    fun load(c: Context): JSONArray {
        try {
            val arr = JSONArray(Prefs.padProfiles(c))
            if (arr.length() > 0) return arr
        } catch (_: Exception) {
        }
        val d = defaults()
        Prefs.setPadProfiles(c, d.toString())
        return d
    }

    fun save(c: Context, arr: JSONArray) {
        Prefs.setPadProfiles(c, arr.toString())
        version++
    }

    fun activeIndex(c: Context): Int {
        val n = load(c).length()
        return Prefs.padActive(c).coerceIn(0, maxOf(0, n - 1))
    }

    fun setActive(c: Context, i: Int) {
        val n = load(c).length()
        if (n == 0) return
        Prefs.setPadActive(c, ((i % n) + n) % n)
        version++
    }

    fun profile(c: Context, i: Int = activeIndex(c)): JSONObject? = load(c).optJSONObject(i)

    fun getAct(p: JSONObject?, slot: String, g: String): JSONObject? =
        p?.optJSONObject("s")?.optJSONObject(slot)?.optJSONObject(g)

    fun setAct(p: JSONObject, slot: String, g: String, a: JSONObject?) {
        val s = p.optJSONObject("s") ?: JSONObject().also { p.put("s", it) }
        val o = s.optJSONObject(slot) ?: JSONObject().also { s.put(slot, it) }
        if (a == null || a.optString("t") == "none") o.remove(g) else o.put(g, a)
    }

    /** 長按不放時會重複執行的動作 */
    fun repeatable(a: JSONObject?): Boolean = when (a?.optString("t")) {
        "scroll", "vol", "swipe" -> true
        else -> false
    }

    private fun dirName(d: String) = when (d) {
        "u" -> "上"
        "d" -> "下"
        "l" -> "左"
        else -> "右"
    }

    /** 手機畫面上的完整說明 */
    fun describe(c: Context, a: JSONObject?): String {
        if (a == null) return "(未設定)"
        val x = a.optString("a")
        val y = a.optString("b")
        return when (a.optString("t")) {
            "scroll" -> "往" + dirName(x) + "滾動一格 (" + y.ifEmpty { "25" } + "%)"
            "tap" -> "點擊畫面 (" + x + " %)"
            "long" -> "長按畫面 (" + x + " %) " + y.ifEmpty { "700" } + " 毫秒"
            "swipe" -> "滑動 (" + x + " %)"
            "back" -> "返回"
            "home" -> "回桌面"
            "recents" -> "最近使用的 App"
            "notif" -> "拉下通知欄"
            "vol" -> "音量 " + (if (x == "up") "加" else if (x == "down") "減" else "靜音切換")
            "media" -> "播放控制: " + Shortcuts.mediaLabel(x)
            "app" -> "開啟 " + Shortcuts.appLabel(c, x)
            "shortcut" -> "執行捷徑「" + x + "」"
            "mode" -> if (x == "prev") "切換到上一組模式" else "切換到下一組模式"
            else -> "(未設定)"
        }
    }

    /** 顯示在控制器螢幕上的短名稱 (最多 4 個中文字或 8 個英數字) */
    fun label(c: Context, a: JSONObject?): String {
        if (a == null) return ""
        val x = a.optString("a")
        val s = when (a.optString("t")) {
            "scroll" -> dirName(x) + "滾"
            "tap" -> "點擊"
            "long" -> "長按"
            "swipe" -> "滑動"
            "back" -> "返回"
            "home" -> "桌面"
            "recents" -> "多工"
            "notif" -> "通知"
            "vol" -> if (x == "up") "音量+" else if (x == "down") "音量-" else "靜音"
            "media" -> when (x) { "next" -> "下一首"; "prev" -> "上一首"; else -> "播放" }
            "app" -> Shortcuts.appLabel(c, x)
            "shortcut" -> x
            "mode" -> "換模式"
            else -> ""
        }
        return fit(s)
    }

    private fun fit(s: String): String {
        var w = 0
        val sb = StringBuilder()
        for (ch in s) {
            val u = if (ch.code > 0x2E80) 2 else 1
            if (w + u > 8) break
            w += u
            sb.append(ch)
        }
        return sb.toString()
    }
}
