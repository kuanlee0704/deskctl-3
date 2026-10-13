package tw.deskctl

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「控制」分頁: 設定手機控制器的模式。每個模式裡, 控制器的上/右/下/左/中央各自可以設定
 * 單擊、連按兩下、長按要做什麼 (滾動、點擊指定位置、滑動、返回、音量、開 App、執行捷徑、切換模式…)。
 * 控制器的「手機控制」頁會把目前模式的每個方向功能顯示在螢幕上。
 */
class PadPage(private val act: Activity) {
    val view: ScrollView = ScrollView(act)
    private val col = LinearLayout(act)
    private var profiles: JSONArray = JSONArray()
    private var cur = 0
    private var shownVer = -1

    private val cText = 0xFF212121.toInt()
    private val cSub = 0xFF616161.toInt()
    private val cPrimary = 0xFF1565C0.toInt()
    private val cBad = 0xFFC62828.toInt()

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(16))
        view.addView(col, ViewGroup.LayoutParams(-1, -2))
        reload()
        render()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun reload() {
        profiles = PadStore.load(act)
        cur = PadStore.activeIndex(act)
        shownVer = PadStore.version
    }

    /** 切到這個分頁時呼叫; 內容沒變就不重畫 (避免每秒閃一次) */
    fun refresh() {
        if (shownVer != PadStore.version || (DeskAccess.instance == null) != hadNoAcc) {
            reload()
            render()
        }
    }

    private var hadNoAcc = false

    private fun toast(t: String) {
        Toast.makeText(act, t, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 小工具 ----------------
    private fun tv(parent: ViewGroup, t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(act)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        parent.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun card(): LinearLayout {
        val c = LinearLayout(act)
        c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(14).toFloat()
        c.background = bg
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 0, 0, dp(12))
        col.addView(c, lp)
        return c
    }

    private fun btn(parent: ViewGroup, t: String, primary: Boolean = false, weight: Float = 0f, onClick: () -> Unit): Button {
        val b = Button(act)
        b.text = t
        b.isAllCaps = false
        if (primary) {
            b.setTextColor(Color.WHITE)
            val bg = GradientDrawable()
            bg.setColor(cPrimary)
            bg.cornerRadius = dp(12).toFloat()
            b.background = bg
        }
        b.setOnClickListener { onClick() }
        val lp = if (weight > 0f) LinearLayout.LayoutParams(0, -2, weight) else LinearLayout.LayoutParams(-1, -2)
        parent.addView(b, lp)
        return b
    }

    private fun row(parent: ViewGroup): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        parent.addView(r, LinearLayout.LayoutParams(-1, -2))
        return r
    }

    private fun profile(): JSONObject = profiles.getJSONObject(cur)

    private fun commit() {
        PadStore.save(act, profiles)
        shownVer = PadStore.version
        render()
    }

    // ---------------- 畫面 ----------------
    private fun render() {
        col.removeAllViews()
        hadNoAcc = DeskAccess.instance == null
        tv(col, "手機控制器", 22f, cText, true)
        val sub = tv(col, "控制器上開啟「手機控制」頁, 五向鍵就變成手機的遙控。每個方向的功能在這裡設定, 並會顯示在控制器螢幕上。可以存多組模式隨時切換。", 13f, cSub)
        sub.setPadding(0, 0, 0, dp(12))

        if (hadNoAcc) {
            val w = card()
            tv(w, "尚未開啟無障礙服務", 15f, cBad, true)
            tv(w, "滾動、點擊、滑動、返回等動作需要它 (音量、播放控制、開 App 不需要)。", 13f, cSub)
            btn(w, "開啟無障礙設定") { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        // ---- 模式 ----
        val mc = card()
        tv(mc, "模式 " + (cur + 1) + "/" + profiles.length() + ": " + profile().optString("n"), 18f, cText, true)
        val r1 = row(mc)
        btn(r1, "◀ 上一組", false, 1f) { PadStore.setActive(act, cur - 1); reload(); render() }
        btn(r1, "下一組 ▶", false, 1f) { PadStore.setActive(act, cur + 1); reload(); render() }
        val r2 = row(mc)
        btn(r2, "新增", false, 1f) { askName("新模式名稱", "模式 " + (profiles.length() + 1)) { n -> addProfile(PadStore.newProfile(n)) } }
        btn(r2, "複製", false, 1f) {
            val p = JSONObject(profile().toString())
            p.put("n", (p.optString("n") + " 副本").take(12))
            addProfile(p)
        }
        btn(r2, "改名", false, 1f) { askName("模式名稱", profile().optString("n")) { n -> profile().put("n", n); commit() } }
        btn(r2, "刪除", false, 1f) { deleteProfile() }

        // ---- 各方向 ----
        val p = profile()
        for (s in PadStore.SLOTS) {
            val c = card()
            val title = if (s == "C") "中央鍵" else PadStore.SLOT_NAMES[s] + "鍵"
            tv(c, title, 17f, cText, true)
            val gs = if (s == "C") listOf("c" to "單擊") else listOf("c" to "單擊", "d" to "連按兩下", "h" to "長按 (不放會連續)")
            for ((g, gn) in gs) {
                val a = PadStore.getAct(p, s, g)
                val shown = if (a == null && g == "h") "(沒設定: 重複單擊動作)" else PadStore.describe(act, a)
                btn(c, gn + ": " + shown) { editAction(s, g) }
            }
            if (s == "C") {
                tv(c, "中央連按兩下 = 把四個方向的功能順時針轉 90° (控制器轉向時用)\n中央連按三下 = 回首頁\n中央長按 = 目錄", 12f, cSub)
            }
        }
        val tip = tv(col, "提示: 某個方向設了「連按兩下」, 該方向的單擊會多等約 0.26 秒判斷是不是連按; 沒設就立即反應。要連續滾動, 用單擊設「滾動」, 控制器上長按不放即可。", 12f, cSub)
        tip.setPadding(0, dp(4), 0, dp(24))
    }

    private fun addProfile(p: JSONObject) {
        if (profiles.length() >= 10) {
            toast("最多 10 組模式")
            return
        }
        profiles.put(p)
        PadStore.setActive(act, profiles.length() - 1)
        PadStore.save(act, profiles)
        reload()
        render()
    }

    private fun deleteProfile() {
        if (profiles.length() <= 1) {
            toast("至少要保留一組模式")
            return
        }
        AlertDialog.Builder(act).setMessage("刪除模式「" + profile().optString("n") + "」?")
            .setPositiveButton("確定") { _, _ ->
                profiles.remove(cur)
                PadStore.setActive(act, 0)
                PadStore.save(act, profiles)
                reload()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askName(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(act)
        e.setText(init)
        e.setSelectAllOnFocus(true)
        AlertDialog.Builder(act).setTitle(title).setView(e)
            .setPositiveButton("確定") { _, _ ->
                val n = e.text.toString().trim().take(12)
                if (n.isNotEmpty()) ok(n)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 動作編輯 ----------------
    private fun setAct(slot: String, g: String, a: JSONObject?) {
        PadStore.setAct(profile(), slot, g, a)
        commit()
    }

    private fun editAction(slot: String, g: String) {
        val types = arrayOf(
            "清除 (不設定)", "滾動一格 (上/下/左/右)", "點擊指定位置", "長按指定位置", "滑動 (起點 → 終點)",
            "返回", "回桌面", "最近使用的 App", "拉下通知欄", "音量", "播放控制", "開啟 App (例如相機)", "執行捷徑", "切換模式"
        )
        AlertDialog.Builder(act).setTitle("選擇動作").setItems(types) { _, i ->
            when (i) {
                0 -> setAct(slot, g, null)
                1 -> pick("滾動方向", arrayOf("往上", "往下", "往左", "往右")) { d ->
                    askNumber("每次滾動距離 (畫面 %)", "25") { n -> setAct(slot, g, PadStore.act("scroll", "udlr".substring(d, d + 1), n)) }
                }
                2 -> askPos("點擊位置 (畫面 %, 例如 50,50)", "50,50") { v -> setAct(slot, g, PadStore.act("tap", v)) }
                3 -> askPos("長按位置 (畫面 %)", "50,50") { v ->
                    askNumber("長按多久 (毫秒)", "700") { ms -> setAct(slot, g, PadStore.act("long", v, ms)) }
                }
                4 -> askPos("滑動起點 (畫面 %)", "50,70") { v1 ->
                    askPos("滑動終點 (畫面 %)", "50,30") { v2 -> setAct(slot, g, PadStore.act("swipe", v1 + "," + v2)) }
                }
                5 -> setAct(slot, g, PadStore.act("back"))
                6 -> setAct(slot, g, PadStore.act("home"))
                7 -> setAct(slot, g, PadStore.act("recents"))
                8 -> setAct(slot, g, PadStore.act("notif"))
                9 -> pick("音量", arrayOf("音量加", "音量減", "靜音切換")) { d ->
                    setAct(slot, g, PadStore.act("vol", arrayOf("up", "down", "mute")[d]))
                }
                10 -> pick("播放控制", arrayOf("播放/暫停", "下一首", "上一首")) { d ->
                    setAct(slot, g, PadStore.act("media", arrayOf("toggle", "next", "prev")[d]))
                }
                11 -> AppPicker.single(act, "選擇要開啟的 App") { pkg -> setAct(slot, g, PadStore.act("app", pkg)) }
                12 -> {
                    val names = Shortcuts.load(act).map { it.name }
                    if (names.isEmpty()) toast("還沒有捷徑, 請先到「捷徑」分頁新增")
                    else pick("選擇捷徑", names.toTypedArray()) { d -> setAct(slot, g, PadStore.act("shortcut", names[d])) }
                }
                13 -> pick("切換模式", arrayOf("下一組", "上一組")) { d ->
                    setAct(slot, g, PadStore.act("mode", if (d == 0) "next" else "prev"))
                }
                else -> {}
            }
        }.setNegativeButton("取消", null).show()
    }

    private fun pick(title: String, items: Array<String>, ok: (Int) -> Unit) {
        AlertDialog.Builder(act).setTitle(title).setItems(items) { _, i -> ok(i) }.setNegativeButton("取消", null).show()
    }

    private fun askNumber(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(act)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText(init)
        AlertDialog.Builder(act).setTitle(title).setView(e)
            .setPositiveButton("確定") { _, _ -> ok(e.text.toString().trim().ifEmpty { init }) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 輸入「x,y」(畫面百分比), 或按「在畫面上選取」用準星點選 */
    private fun askPos(title: String, init: String, ok: (String) -> Unit) {
        val e = EditText(act)
        e.setText(init)
        AlertDialog.Builder(act).setTitle(title).setView(e)
            .setPositiveButton("確定") { _, _ -> ok(e.text.toString().trim().ifEmpty { init }) }
            .setNeutralButton("在畫面上選取") { _, _ -> pickOnScreen(e.text.toString()) { v -> askPos(title, v, ok) } }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickOnScreen(cur: String, done: (String) -> Unit) {
        if (!Launcher.overlayOk(act)) {
            toast("請先到「設定」開啟「顯示在其他 App 上層」")
            return
        }
        val p = cur.split(",")
        val x0 = p.getOrNull(0)?.trim()?.toFloatOrNull() ?: 50f
        val y0 = p.getOrNull(1)?.trim()?.toFloatOrNull() ?: 50f
        val started = PosPicker.start(act, x0, y0) { x, y ->
            try {
                act.startActivity(
                    Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (_: Exception) {
            }
            val v = String.format("%.0f,%.0f", x, y)
            act.window.decorView.postDelayed({ done(v) }, 400)
        }
        if (!started) {
            toast("無法顯示選取準星")
            return
        }
        toast("切到要點的畫面, 拖曳準星後按「確定」")
        act.moveTaskToBack(true)
    }
}
