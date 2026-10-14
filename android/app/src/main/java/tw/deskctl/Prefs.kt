package tw.deskctl

import android.content.Context
import android.content.SharedPreferences

/** 所有設定集中在這裡 (SharedPreferences "deskctl")。新增設定項: 加一對 getter/setter 即可。 */
object Prefs {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("deskctl", Context.MODE_PRIVATE)

    /** 已記住的控制器 MAC (找到後自動儲存, 之後直連) */
    fun mac(c: Context): String? = sp(c).getString("mac", null)
    fun setMac(c: Context, v: String?) {
        val e = sp(c).edit()
        if (v == null) e.remove("mac") else e.putString("mac", v)
        e.apply()
    }

    /** 手機開機後, 若關機前是啟動狀態, 自動啟動服務 */
    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", true)
    fun setAutostart(c: Context, v: Boolean) { sp(c).edit().putBoolean("autostart", v).apply() }

    /** 開啟 App 時自動啟動服務 */
    fun autoOpen(c: Context): Boolean = sp(c).getBoolean("auto_open", false)
    fun setAutoOpen(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_open", v).apply() }

    /** 服務是否「被使用者啟動」(按停止會變 false, 開機自動啟動以此為準) */
    fun wasStarted(c: Context): Boolean = sp(c).getBoolean("was_started", false)
    fun setWasStarted(c: Context, v: Boolean) { sp(c).edit().putBoolean("was_started", v).apply() }

    /** 連線期間持有 CPU 喚醒鎖 (螢幕關閉也不中斷), 預設開啟 */
    fun wakeLock(c: Context): Boolean = sp(c).getBoolean("wake_lock", true)
    fun setWakeLock(c: Context, v: Boolean) { sp(c).edit().putBoolean("wake_lock", v).apply() }

    /** 籃球延遲播放秒數 (0 = 關閉, 即時顯示並用推算補中間); 延遲期間收集幾筆資料, 用已知的前後兩筆插值, 不必猜未來 */
    fun nbaDelay(c: Context): Int = sp(c).getInt("nba_delay", 15)
    fun setNbaDelay(c: Context, v: Int) { sp(c).edit().putInt("nba_delay", v).apply() }

    /** 找不到控制器時, 每輪只搜尋約 1 分鐘, 之後休息 10 分鐘再試 (省電), 預設開啟; 連上就一直保持連線 */
    fun autoPause(c: Context): Boolean = sp(c).getBoolean("auto_pause", true)
    fun setAutoPause(c: Context, v: Boolean) { sp(c).edit().putBoolean("auto_pause", v).apply() }

    /** 「完全不搜尋」時段 (例如睡覺時間), 單位: 一天中的第幾分鐘。這段時間內斷線也不會去搜尋 */
    fun quietOn(c: Context): Boolean = sp(c).getBoolean("quiet_on", false)
    fun setQuietOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("quiet_on", v).apply() }
    fun quietFrom(c: Context): Int = sp(c).getInt("quiet_from", 23 * 60)
    fun quietTo(c: Context): Int = sp(c).getInt("quiet_to", 7 * 60)
    fun setQuiet(c: Context, from: Int, to: Int) { sp(c).edit().putInt("quiet_from", from).putInt("quiet_to", to).apply() }

    /** 目錄設定 (JSON 物件, m=目錄順序 r=快速切換), 空字串 = 沿用控制器自己的設定 */
    fun layout(c: Context): String = sp(c).getString("layout", "") ?: ""
    fun setLayout(c: Context, v: String) { sp(c).edit().putString("layout", v).apply() }

    /** 手機訊息: 是否轉發通知 / 是否隱藏內容 / 要轉發的 App / 清理時要保護的 App */
    fun msgOn(c: Context): Boolean = sp(c).getBoolean("msg_on", true)
    fun setMsgOn(c: Context, v: Boolean) { sp(c).edit().putBoolean("msg_on", v).apply() }
    fun msgHide(c: Context): Boolean = sp(c).getBoolean("msg_hide", false)
    fun setMsgHide(c: Context, v: Boolean) { sp(c).edit().putBoolean("msg_hide", v).apply() }
    /** 只轉發這些 App 的通知 (白名單; 空 = 都不轉發) */
    fun msgAllow(c: Context): Set<String> = getSet(c, "msg_allow")
    fun setMsgAllow(c: Context, v: Collection<String>) = putSet(c, "msg_allow", v)

    /** 目錄編輯器已經見過的頁面 id (用來把控制器新增的頁面自動補進自訂目錄) */
    fun layoutKnown(c: Context): Set<String> =
        if (sp(c).contains("layout_known")) getSet(c, "layout_known")
        else setOf("home", "sw", "music", "games", "act", "sys", "manual", "settings")
    fun setLayoutKnown(c: Context, v: Collection<String>) = putSet(c, "layout_known", v)
    fun cleanProtect(c: Context): Set<String> = getSet(c, "clean_protect")
    fun setCleanProtect(c: Context, v: Collection<String>) = putSet(c, "clean_protect", v)

    private fun getSet(c: Context, k: String): Set<String> = try {
        val a = org.json.JSONArray(sp(c).getString(k, "[]"))
        HashSet<String>().also { s -> for (i in 0 until a.length()) s.add(a.optString(i)) }
    } catch (_: Exception) {
        emptySet()
    }

    private fun putSet(c: Context, k: String, v: Collection<String>) {
        sp(c).edit().putString(k, org.json.JSONArray(v.toList()).toString()).apply()
    }

    /** 比分板: 追蹤中的比賽清單 (JSON, 格式見 ScoreApi.kt 的 Tracked) */
    fun scoreTracked(c: Context): String = sp(c).getString("score_tracked", "[]") ?: "[]"
    fun setScoreTracked(c: Context, v: String) { sp(c).edit().putString("score_tracked", v).apply() }

    /** 捷徑清單 (JSON 字串, 格式見 Shortcuts.kt) */
    /** 手機控制器的模式 (JSON 陣列) 與目前使用第幾組 */
    fun padProfiles(c: Context): String = sp(c).getString("pad_profiles", "") ?: ""
    fun setPadProfiles(c: Context, v: String) { sp(c).edit().putString("pad_profiles", v).apply() }
    fun padActive(c: Context): Int = sp(c).getInt("pad_active", 0)
    fun setPadActive(c: Context, v: Int) { sp(c).edit().putInt("pad_active", v).apply() }

    fun shortcuts(c: Context): String = sp(c).getString("shortcuts", "[]") ?: "[]"
    fun setShortcuts(c: Context, v: String) { sp(c).edit().putString("shortcuts", v).apply() }
}
