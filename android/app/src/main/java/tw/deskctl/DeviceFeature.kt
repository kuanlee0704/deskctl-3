package tw.deskctl

import android.os.SystemClock
import org.json.JSONObject

/** 控制器回報的狀態 (給「狀態」分頁顯示)。 */
object DeviceInfo {
    @Volatile var ver = ""
    @Volatile var upSec = 0L
    @Volatile var memFree = 0
    @Volatile var memAlloc = 0
    @Volatile var page = ""
    @Volatile var conn = 0
    @Volatile var disc = 0
    @Volatile var lastUp = 0
    @Volatile var at = 0L                       // 最後一次收到 stat 的 elapsedRealtime
    @Volatile var pages: List<Pair<String, String>> = emptyList()     // 控制器目錄可用的頁面 (id, 名稱)
    @Volatile var menu: List<String> = emptyList()                    // 控制器目前的目錄順序
    @Volatile var ring: List<String> = emptyList()                    // 控制器目前的快速切換環
    val memHistory = ArrayDeque<Int>()          // 剩餘記憶體歷史 (每次 stat 一點, 最多 30 點)
    @Volatile var version = 0                   // 有變動就 +1
    @Volatile var cleanMsg = ""                 // 最近一次「清理控制器記憶體」的結果

    fun reset() {
        at = 0L
        ver = ""
    }
}

/** 目錄設定 (存在手機上, 連線時推給控制器)。 */
object LayoutStore {
    class Layout(val menu: List<String>, val ring: List<String>)

    fun load(c: android.content.Context): Layout? {
        val s = Prefs.layout(c)
        if (s.isEmpty()) return null
        return try {
            val o = JSONObject(s)
            fun arr(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return List(a.length()) { a.optString(it) }
            }
            Layout(arr("m"), arr("r"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 控制器新增了頁面 (例如「手機訊息」) 但手機上已存的自訂目錄沒有它 → 自動補進去 (放在「設定」前面)。
     * 用「已見過的頁面」判斷, 所以你刻意隱藏的頁面不會被加回來。
     */
    fun mergeNewPages(c: android.content.Context, pages: List<Pair<String, String>>) {
        val known = HashSet(Prefs.layoutKnown(c))
        val fresh = pages.map { it.first }.filter { it !in known }
        if (fresh.isEmpty()) return
        val l = load(c)
        if (l != null) {
            val m = ArrayList(l.menu)
            for (id in fresh) {
                if (id in m) continue
                val at = m.indexOf("settings")
                if (at >= 0) m.add(at, id) else m.add(id)
            }
            save(c, m, l.ring)
            AppLog.i("DEVICE", "目錄補入新頁面: " + fresh.joinToString(","))
        }
        known.addAll(fresh)
        Prefs.setLayoutKnown(c, known)
    }

    fun save(c: android.content.Context, menu: List<String>, ring: List<String>) {
        val o = JSONObject()
        o.put("m", org.json.JSONArray(menu))
        o.put("r", org.json.JSONArray(ring))
        Prefs.setLayout(c, o.toString())
        version++
    }

    @Volatile var version = 0
}

/**
 * 控制器 ⇄ 手機: 接收控制器的 stat(運作狀態/記憶體)、pages(可用頁面)、layout(目前目錄);
 * 並把手機上設定的目錄順序 (k=layout, m=順序, r=快速切換) 推給控制器。
 */
class DeviceFeature(host: FeatureHost) : Feature(host) {
    override val id = "device"
    private var lastVer = -1
    private var lastHk = -1

    override fun onReady() {
        pushLayout()
        pushHk()
        pushCfg()
    }

    override fun onTick(n: Int) {
        if (LayoutStore.version != lastVer) pushLayout()
        if (HotkeyStore.version != lastHk) pushHk()
        if (BoardCfg.hasPending()) pushCfg()
    }

    private fun pushHk() {
        lastHk = HotkeyStore.version
        if (!Prefs.hkTouched(host.context)) return        // 手機上沒改過: 以控制器上的為準, 不覆蓋
        host.send(JSONObject().put("k", "hk").put("l", HotkeyStore.toJson(HotkeyStore.load(host.context))))
    }

    private fun pushCfg() {
        val p = BoardCfg.takePending()
        if (p.isEmpty()) return
        val d = JSONObject()
        for ((k, v) in p) d.put(k, v)
        host.send(JSONObject().put("k", "cfg").put("d", d))
        AppLog.i("DEVICE", "已送出控制器設定: " + p.keys.joinToString(","))
    }

    private fun pushLayout() {
        lastVer = LayoutStore.version
        val l = LayoutStore.load(host.context) ?: return
        host.send(
            JSONObject().put("k", "layout")
                .put("m", org.json.JSONArray(l.menu))
                .put("r", org.json.JSONArray(l.ring))
        )
        AppLog.i("DEVICE", "已送出目錄設定 (" + l.menu.size + " 項)")
    }

    override fun onCommand(c: String, o: JSONObject): Boolean {
        when (c) {
            "stat" -> {
                DeviceInfo.ver = o.optString("ver")
                DeviceInfo.upSec = o.optLong("up")
                DeviceInfo.memFree = o.optInt("mf")
                DeviceInfo.memAlloc = o.optInt("ma")
                DeviceInfo.page = o.optString("pg")
                DeviceInfo.conn = o.optInt("cn")
                DeviceInfo.disc = o.optInt("dc")
                DeviceInfo.lastUp = o.optInt("lu")
                DeviceInfo.at = SystemClock.elapsedRealtime()
                val h = DeviceInfo.memHistory
                h.addLast(DeviceInfo.memFree)
                while (h.size > 30) h.removeFirst()
                DeviceInfo.version++
            }
            "memclean" -> {
                val f = o.optInt("f")
                val b = o.optInt("b")
                DeviceInfo.cleanMsg = "已清理: 釋放 " + ((f - b).coerceAtLeast(0) / 1024) + " KB, 現在剩 " + f / 1024 + " KB"
                DeviceInfo.memFree = f
                AppLog.i("DEVICE", DeviceInfo.cleanMsg)
                DeviceInfo.version++
            }
            "pages" -> {
                val a = o.optJSONArray("l") ?: return true
                DeviceInfo.pages = List(a.length()) {
                    val p = a.optJSONArray(it)
                    (p?.optString(0) ?: "") to (p?.optString(1) ?: "")
                }
                LayoutStore.mergeNewPages(host.context, DeviceInfo.pages)
                DeviceInfo.version++
            }
            "cfg" -> {
                BoardCfg.report(o.optJSONObject("d") ?: JSONObject(), o.optInt("bl") != 0)
                val hk = o.optJSONArray("hk") ?: org.json.JSONArray()
                val c = host.context
                if (!Prefs.hkTouched(c)) {
                    // 手機沒改過: 採用控制器上現有的快捷鍵 (換手機或重裝後不會把它清掉)
                    if (hk.toString() != Prefs.hotkeys(c)) {
                        Prefs.setHotkeys(c, hk.toString())
                        HotkeyStore.version++
                        lastHk = HotkeyStore.version
                    }
                } else if (hk.toString() != HotkeyStore.toJson(HotkeyStore.load(c)).toString()) {
                    pushHk()
                }
            }
            "layout" -> {
                fun arr(k: String): List<String> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return List(a.length()) { a.optString(it) }
                }
                DeviceInfo.menu = arr("m")
                DeviceInfo.ring = arr("r")
                DeviceInfo.version++
            }
            else -> return false
        }
        return true
    }

    override fun stop() {
        DeviceInfo.reset()
        BoardCfg.reset()
    }

    override fun info(): String =
        if (DeviceInfo.at == 0L) "" else "控制器 v" + DeviceInfo.ver + " · 剩餘記憶體 " + DeviceInfo.memFree / 1024 + " KB"
}
