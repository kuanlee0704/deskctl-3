package tw.deskctl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 助理提出、等使用者按「套用」才會執行的變更 */
class Pending(val desc: String, val apply: () -> Unit)

class TurnResult(
    val text: String,
    val links: List<Pair<String, String>>,          // (按鈕文字, 目標代號)
    val pendings: List<Pending>,
    val sources: List<Pair<String, String>>,        // 搜尋來源 (標題, 網址)
    val error: String
)

/**
 * AI 助理 (Gemini): 讀目前設定與狀態、查網路、給設定連結、提出設定變更 (要使用者確認才套用)。
 * 工具由本檔實作; 模型只能「提議」變更, 不能自己改設定。
 */
object AiAssistant {
    private val history = JSONArray()

    const val LINK_TARGETS = "home,score,shortcuts,pad,hotkeys,assistant,status,log,settings,messages,notif_access,battery,bluetooth,app_info,accessibility,overlay"

    private val SYSTEM = """
你是「桌邊控制器」手機 App 內的設定助理, 回答一律用繁體中文, 簡短直接, 不要長篇大論。
【系統簡介】桌邊控制器是 ESP32-C3 小板子 (160x80 螢幕 + 上下左右中 5 個鍵), 用藍牙連到這支手機 App。App 下方分頁: 主頁、比分、功能(捷徑/手機控制/按鍵/助理)、狀態(狀態/記錄)、設定。
【快捷鍵】一筆 = 範圍(scope: "*" 全域, 或頁面代號) + 按鍵(key 0左 1上 2中 3下 4右) + 手勢(gesture 0雙擊 1三連擊 2長按) + 動作(action) + 參數(arg) + 是否優先於頁面(force)。中鍵只能綁雙擊 (三連擊=回首頁、長按=目錄固定給系統)。專區快捷鍵永遠先於該頁原功能; 全域的 force=true 才會蓋過頁面原本功能。三連擊會讓該鍵雙擊多等約 0.26 秒。上限 24 個。
動作代號: goto(切到頁面, arg=頁面代號) back home menu ring+ ring- off(立即熄屏) pp next prev mute vol+ vol- seek+ seek- run(執行手機捷徑, arg=捷徑名稱) memclean。
頁面代號: home首頁 desk桌面(桌邊擺放用: 大時鐘+日期+電量+正在播放的音樂) sw碼表 music音樂 score比分板 games遊戲 dev系統總覽 msg手機訊息 act捷徑 pad手機控制 sys手機狀態 manual說明書 settings快捷設定。
【隊名】比分板的球隊中文名由內建對照或使用者自訂 (set_team_name); 聯盟代號 MLB NBA CPBL SOC(足球)。球隊代號 (code) 請用 get_app_state 裡追蹤清單的代號。查不到的球隊先用 search_web 查台灣常用譯名, 再用 set_team_name 提議。
【規則】
1. 需要知道目前設定、連線狀態、追蹤的比賽、快捷鍵時, 先呼叫 get_app_state, 不要猜。
2. 要改設定時呼叫 set_board_setting / set_hotkey / remove_hotkey / set_team_name / set_team_language。這些只是「提議」, 使用者要在畫面上按「套用」才會生效, 所以你要說「已提出, 請按套用」, 不要說已經改好。
3. 使用者想前往某個設定畫面時, 呼叫 show_link 給可點的連結按鈕 (target 只能是: $LINK_TARGETS)。
4. 需要最新或不確定的外部資訊 (球隊名、規則、新聞) 才用 search_web; 沒有把握的內容要說明。
5. 控制器未連線時, 控制器設定值是未知; 快捷鍵與隊名仍可修改。
6. 不要求或複述使用者的 API 金鑰; 不處理與本 App 無關的事。
""".trimIndent()

    fun reset() {
        synchronized(history) {
            while (history.length() > 0) history.remove(0)
        }
    }

    // ---------------- 工具宣告 ----------------
    private fun prop(type: String, desc: String, enum: List<String>? = null): JSONObject {
        val o = JSONObject().put("type", type).put("description", desc)
        if (enum != null) o.put("enum", JSONArray(enum))
        return o
    }

    private fun fn(name: String, desc: String, props: JSONObject? = null, required: List<String> = emptyList()): JSONObject {
        val f = JSONObject().put("name", name).put("description", desc)
        if (props != null) {
            f.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required)))
        }
        return f
    }

    private fun tools(ctx: Context): JSONArray {
        val decl = JSONArray()
        decl.put(fn("get_app_state", "讀取 App 與控制器目前的完整狀態: 連線、版本與記憶體、控制器設定值與可選值、快捷鍵、追蹤中的比賽與隊名、捷徑名稱、手機控制模式等"))
        decl.put(
            fn(
                "set_board_setting", "提議修改一項控制器設定 (使用者確認後才套用)",
                JSONObject().put("key", prop("STRING", "設定代號", BoardCfg.OPTS.keys.toList()))
                    .put("value", prop("INTEGER", "新的值 (原始數字, 必須是 get_app_state 列出的可選值之一)")),
                listOf("key", "value")
            )
        )
        decl.put(
            fn(
                "set_hotkey", "提議新增或取代一個快捷鍵 (同範圍同按鍵同手勢會被取代)",
                JSONObject().put("scope", prop("STRING", "範圍: * 全域, 或頁面代號"))
                    .put("key", prop("INTEGER", "按鍵 0左 1上 2中 3下 4右"))
                    .put("gesture", prop("INTEGER", "手勢 0雙擊 1三連擊 2長按; 中鍵只能 0"))
                    .put("action", prop("STRING", "動作代號", HotkeyStore.ACTS.map { it.id }))
                    .put("arg", prop("STRING", "參數: goto 填頁面代號, run 填捷徑名稱, 其他留空"))
                    .put("force", prop("BOOLEAN", "僅全域有效: 是否優先於頁面原本功能, 預設 true")),
                listOf("scope", "key", "gesture", "action")
            )
        )
        decl.put(
            fn(
                "remove_hotkey", "提議刪除一個快捷鍵",
                JSONObject().put("scope", prop("STRING", "範圍")).put("key", prop("INTEGER", "按鍵 0-4")).put("gesture", prop("INTEGER", "手勢 0-2")),
                listOf("scope", "key", "gesture")
            )
        )
        decl.put(
            fn(
                "set_team_name", "提議自訂某球隊的中文名 (留空 name 代表恢復內建名稱)",
                JSONObject().put("league", prop("STRING", "聯盟", listOf("MLB", "NBA", "CPBL", "SOC")))
                    .put("code", prop("STRING", "球隊代號 (來自 get_app_state 追蹤清單)"))
                    .put("name", prop("STRING", "中文隊名")),
                listOf("league", "code", "name")
            )
        )
        decl.put(fn("set_team_language", "提議切換控制器下方隊名語言", JSONObject().put("english", prop("BOOLEAN", "true=英文, false=中文優先")), listOf("english")))
        decl.put(
            fn(
                "show_link", "在對話裡顯示一個可點的連結按鈕, 讓使用者直接前往某個設定畫面",
                JSONObject().put("target", prop("STRING", "目標", LINK_TARGETS.split(",")))
                    .put("label", prop("STRING", "按鈕上的文字, 例如「前往按鍵設定」")),
                listOf("target", "label")
            )
        )
        if (Prefs.aiSearch(ctx)) {
            decl.put(fn("search_web", "用 Google 搜尋網路取得最新或不確定的資訊 (例如球隊的中文譯名)", JSONObject().put("query", prop("STRING", "搜尋問題")), listOf("query")))
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decl))
    }

    // ---------------- 狀態 ----------------
    private fun stateJson(ctx: Context): JSONObject {
        val o = JSONObject()
        val ver = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        o.put("app", JSONObject().put("版本", ver).put("連線服務執行中", BridgeService.running).put("已連線控制器", BridgeService.connected).put("狀態", BridgeService.status))
        if (DeviceInfo.at != 0L) {
            o.put(
                "board", JSONObject().put("韌體版本", DeviceInfo.ver).put("剩餘記憶體KB", DeviceInfo.memFree / 1024)
                    .put("目前頁面", DeviceInfo.page).put("已運作秒", DeviceInfo.upSec).put("藍牙連線/斷線次數", "${DeviceInfo.conn}/${DeviceInfo.disc}")
            )
        }
        val st = JSONArray()
        for (opt in BoardCfg.OPTS.values) {
            if ((opt.key == "dimt" || opt.key == "bright") && !BoardCfg.backlight) continue
            val v = BoardCfg.values[opt.key]
            val options = JSONArray()
            for (x in opt.vals) options.put(JSONObject().put("值", x).put("顯示", opt.fmt(x)))
            st.put(
                JSONObject().put("代號", opt.key).put("名稱", opt.title)
                    .put("目前值", if (v == null) "未知 (控制器未連線)" else JSONObject().put("值", v).put("顯示", opt.fmt(v))).put("可選", options)
            )
        }
        o.put("控制器設定", st)
        val hk = JSONArray()
        for (h in HotkeyStore.load(ctx)) {
            hk.put(
                JSONObject().put("範圍", if (h.scope == "*") "全域" else HotkeyStore.pageName(h.scope) + "(" + h.scope + ")")
                    .put("按鍵", HotkeyStore.KEYS[h.key.coerceIn(0, 4)]).put("手勢", HotkeyStore.GESTS[h.gest.coerceIn(0, 2)])
                    .put("動作", HotkeyStore.actLabel(h)).put("優先於頁面", h.force)
            )
        }
        o.put("快捷鍵", hk)
        val tr = JSONArray()
        for (t in ScoreStore.load(ctx)) {
            tr.put(
                JSONObject().put("聯盟", t.lg).put("客隊代號", t.a).put("主隊代號", t.h).put("客隊英文", t.an).put("主隊英文", t.hn)
                    .put("客隊中文", Teams.zh(t.lg, t.a, t.an) ?: "(無)").put("主隊中文", Teams.zh(t.lg, t.h, t.hn) ?: "(無)")
            )
        }
        o.put("追蹤中的比賽", tr)
        o.put("自訂隊名", try { JSONObject(Prefs.teamNames(ctx)) } catch (_: Exception) { JSONObject() })
        o.put("控制器隊名語言", if (Teams.english) "英文" else "中文優先")
        o.put("手機捷徑名稱", JSONArray(Shortcuts.load(ctx).map { it.name }))
        o.put("手機控制目前模式", PadStore.profile(ctx)?.optString("n").orEmpty())
        o.put("訊息資料庫", JSONObject().put("啟用", Prefs.msgDb(ctx)).put("保留天數(0=永久)", Prefs.msgKeepDays(ctx)))
        o.put("助理可上網搜尋", Prefs.aiSearch(ctx))
        return o
    }

    // ---------------- 工具執行 ----------------
    private class Turn {
        val links = ArrayList<Pair<String, String>>()
        val pendings = ArrayList<Pending>()
        val sources = ArrayList<Pair<String, String>>()
    }

    private fun err(msg: String) = JSONObject().put("error", msg)

    private fun exec(ctx: Context, name: String, a: JSONObject, turn: Turn): JSONObject {
        when (name) {
            "get_app_state" -> return stateJson(ctx)
            "set_board_setting" -> {
                val key = a.optString("key")
                val v = a.optInt("value", Int.MIN_VALUE)
                val opt = BoardCfg.OPTS[key] ?: return err("沒有這個設定代號")
                if (opt.vals.none { it == v }) return err("值不合法, 可選: " + opt.vals.joinToString(",") { "$it(${opt.fmt(it)})" })
                turn.pendings.add(Pending("控制器設定「" + opt.title + "」改成 " + opt.fmt(v)) { BoardCfg.set(key, v) })
                return JSONObject().put("status", "已提出, 等使用者按套用")
            }
            "set_hotkey" -> {
                val scope = a.optString("scope")
                val key = a.optInt("key", -1)
                val g = a.optInt("gesture", -1)
                val act = a.optString("action")
                val arg = a.optString("arg")
                val def = HotkeyStore.ACTS.firstOrNull { it.id == act } ?: return err("沒有這個動作")
                if (scope != "*" && HotkeyStore.PAGES.none { it.first == scope }) return err("範圍不合法")
                if (key !in 0..4 || g !in 0..2) return err("按鍵或手勢不合法")
                if (key == 2 && g != 0) return err("中鍵只能綁雙擊")
                if (def.arg == "page" && HotkeyStore.PAGES.none { it.first == arg }) return err("goto 需要合法頁面代號 arg")
                if (def.arg == "shortcut" && Shortcuts.load(ctx).none { it.name == arg }) return err("手機沒有這個名稱的捷徑")
                val force = if (scope == "*") (if (a.has("force")) a.optBoolean("force") else true) else true
                val hk = HotkeyStore.Hk(scope, key, g, act, if (def.arg.isEmpty()) "" else arg, force)
                turn.pendings.add(
                    Pending("新增快捷鍵: " + (if (scope == "*") "全域" else HotkeyStore.pageName(scope)) + "「" + HotkeyStore.KEYS[key] + "」" +
                        HotkeyStore.GESTS[g] + " → " + HotkeyStore.actLabel(hk) + (if (scope == "*" && force) " (優先於頁面)" else "")) {
                        val l = HotkeyStore.load(ctx)
                        l.removeAll { it.scope == scope && it.key == key && it.gest == g }
                        if (l.size < HotkeyStore.MAX) {
                            l.add(hk)
                            HotkeyStore.save(ctx, l)
                        }
                    }
                )
                return JSONObject().put("status", "已提出, 等使用者按套用")
            }
            "remove_hotkey" -> {
                val scope = a.optString("scope")
                val key = a.optInt("key", -1)
                val g = a.optInt("gesture", -1)
                if (HotkeyStore.load(ctx).none { it.scope == scope && it.key == key && it.gest == g }) return err("找不到這個快捷鍵")
                turn.pendings.add(
                    Pending("刪除快捷鍵: " + (if (scope == "*") "全域" else HotkeyStore.pageName(scope)) + "「" + HotkeyStore.KEYS[key] + "」" + HotkeyStore.GESTS[g]) {
                        val l = HotkeyStore.load(ctx)
                        l.removeAll { it.scope == scope && it.key == key && it.gest == g }
                        HotkeyStore.save(ctx, l)
                    }
                )
                return JSONObject().put("status", "已提出, 等使用者按套用")
            }
            "set_team_name" -> {
                val lg = a.optString("league")
                val code = a.optString("code")
                val nm = a.optString("name").trim()
                if (lg !in listOf("MLB", "NBA", "CPBL", "SOC") || code.isEmpty()) return err("聯盟或球隊代號不合法")
                if (nm.length > 20) return err("名稱太長")
                turn.pendings.add(
                    Pending(if (nm.isEmpty()) "恢復 $lg $code 的內建隊名" else "把 $lg $code 的中文隊名設為「$nm」") { Teams.setCustom(ctx, lg, code, nm) }
                )
                return JSONObject().put("status", "已提出, 等使用者按套用")
            }
            "set_team_language" -> {
                val en = a.optBoolean("english")
                turn.pendings.add(Pending("控制器隊名語言改成" + (if (en) "英文" else "中文優先")) { Teams.setEnglish(ctx, en) })
                return JSONObject().put("status", "已提出, 等使用者按套用")
            }
            "show_link" -> {
                val t = a.optString("target")
                if (t !in LINK_TARGETS.split(",")) return err("目標不合法")
                turn.links.add(Pair(a.optString("label").ifEmpty { "前往" }.take(20), t))
                return JSONObject().put("status", "連結按鈕已顯示")
            }
            "search_web" -> {
                if (!Prefs.aiSearch(ctx)) return err("使用者關閉了網路搜尋")
                val q = a.optString("query").take(200)
                if (q.isEmpty()) return err("缺少搜尋問題")
                val (ans, src) = GeminiApi.search(Prefs.aiModel(ctx), Prefs.aiKey(ctx), q)
                turn.sources.addAll(src)
                return JSONObject().put("answer", ans.take(1500)).put("sources", JSONArray(src.map { it.first }))
            }
        }
        return err("未知的工具")
    }

    // ---------------- 一次使用者提問 ----------------
    /** 背景執行緒呼叫。會把對話記在記憶體 (離開 App 就忘記; 可按「清除」重來) */
    fun run(ctx: Context, userText: String): TurnResult {
        val key = Prefs.aiKey(ctx)
        if (key.isEmpty()) return TurnResult("", emptyList(), emptyList(), emptyList(), "還沒填 Gemini API 金鑰, 請先在上方「設定」填入。")
        val model = Prefs.aiModel(ctx)
        val turn = Turn()
        synchronized(history) {
            history.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))
            // 對話太長就從最舊的丟; 開頭必須是使用者的文字訊息 (不能是孤立的工具結果)
            while (history.length() > 40 || (history.length() > 1 && !isUserText(history.optJSONObject(0)))) history.remove(0)
        }
        var finalText = ""
        for (round in 0 until 6) {
            val snapshot = synchronized(history) { JSONArray(history.toString()) }
            val r = GeminiApi.generate(model, key, SYSTEM, snapshot, tools(ctx))
            val j = r.json
            if (j == null) {
                val hint = when (r.code) {
                    400 -> "請求被拒 (金鑰或模型名稱可能不對)"
                    401, 403 -> "金鑰無效或沒有權限"
                    404 -> "找不到這個模型, 請到設定檢查模型名稱"
                    429 -> "超過額度或太頻繁, 稍後再試"
                    else -> "出錯了"
                }
                synchronized(history) { history.remove(history.length() - 1) }      // 失敗的提問不留在對話裡
                return TurnResult("", emptyList(), emptyList(), emptyList(), hint + (if (r.error.isNotEmpty()) "\n(" + r.error.take(200) + ")" else ""))
            }
            val cand = j.optJSONArray("candidates")?.optJSONObject(0)
            val content = cand?.optJSONObject("content")
            if (content == null || content.optJSONArray("parts") == null) {
                val why = cand?.optString("finishReason").orEmpty().ifEmpty { j.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty() }
                return TurnResult("", turn.links, turn.pendings, turn.sources, "這次沒有回應" + (if (why.isNotEmpty()) " (" + why + ")" else "") + ", 換個說法再試。")
            }
            synchronized(history) { history.put(content) }
            val parts = content.getJSONArray("parts")
            val responses = JSONArray()
            for (i in 0 until parts.length()) {
                val fc = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
                val name = fc.optString("name")
                val res = try {
                    exec(ctx, name, fc.optJSONObject("args") ?: JSONObject(), turn)
                } catch (e: Exception) {
                    AppLog.w("AI", "工具 $name 失敗: $e")
                    err("執行失敗")
                }
                var rs = res.toString()
                if (rs.length > 6000) rs = rs.take(6000)
                val body = try { JSONObject(rs) } catch (_: Exception) { JSONObject().put("result", rs) }
                responses.put(JSONObject().put("functionResponse", JSONObject().put("name", name).put("response", body)))
            }
            if (responses.length() == 0) {
                finalText = visibleText(content)
                break
            }
            synchronized(history) { history.put(JSONObject().put("role", "user").put("parts", responses)) }
        }
        if (finalText.isEmpty()) finalText = if (turn.pendings.isNotEmpty() || turn.links.isNotEmpty()) "好了, 請看下方。" else "(沒有文字回覆)"
        return TurnResult(finalText, turn.links, turn.pendings, turn.sources, "")
    }

    private fun isUserText(o: JSONObject?): Boolean {
        if (o == null || o.optString("role") != "user") return false
        val p = o.optJSONArray("parts")?.optJSONObject(0) ?: return false
        return p.has("text")
    }

    private fun visibleText(content: JSONObject): String {
        val parts = content.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought")) continue
            sb.append(p.optString("text"))
        }
        return sb.toString().trim()
    }
}
