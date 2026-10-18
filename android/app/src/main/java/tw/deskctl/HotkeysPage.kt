package tw.deskctl

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * 「按鍵」分頁: 控制器按鍵快捷鍵與控制器設定, 依「區域」整理:
 * 全域 (任何頁面都能用) 與各功能專區 (只在該頁面生效, 並放該區的控制器設定)。
 */
class HotkeysPage(private val act: Activity) {
    val view: ScrollView = ScrollView(act)
    private val col = LinearLayout(act)

    private class Region(val id: String, val title: String, val scope: String?, val desc: String, val opts: List<String>)

    private val regions = listOf(
        Region("*", "全域", "*", "任何頁面都能用的快捷鍵。頁面本來就有用到的手勢,要勾「優先於頁面」才會蓋過去 (例如音樂頁的左右雙擊是上一首 / 下一首)。", emptyList()),
        Region("home", "首頁", "home", "時鐘首頁。首頁與音樂頁共用音樂手勢。這一區的快捷鍵只在首頁生效,而且一定優先於首頁原本的功能。", listOf("h24", "secs", "tsecs")),
        Region("desk", "桌面", "desk", "桌邊擺放用的畫面 (大時鐘 + 音樂)。按鍵和首頁相同, 這一區的快捷鍵只在桌面頁生效。", listOf("h24", "secs")),
        Region("music", "音樂", "music", "音樂頁。這一區的快捷鍵只在音樂頁生效,而且一定優先於原本的功能。", listOf("seek", "vstep")),
        Region("sw", "碼表", "sw", "碼表頁。左、右、中的雙擊 / 三連擊 / 長按本來是空的,最適合放快捷鍵。", emptyList()),
        Region("score", "比分板", "score", "比分板。左右單擊是換比賽、上下單擊是換畫面,這兩個不能改;其他手勢可以自己設。", emptyList()),
        Region("msg", "訊息", "msg", "手機訊息頁。", listOf("msgw", "msgt")),
        Region("act", "捷徑", "act", "捷徑頁。", emptyList()),
        Region("pad", "手機控制", "pad", "手機控制頁會把幾乎所有手勢送去控制手機,這一區的快捷鍵會蓋過去,請小心使用。", emptyList()),
        Region("sys", "省電與系統", null, "控制器的省電、熄屏與顯示設定。", listOf("pwr", "lowt", "idle", "lsleep", "dimt", "bright"))
    )

    private var cur = 0
    private var seenHk = -1
    private var seenCfg = -1
    private var seenConn = false

    private val cText = 0xFF212121.toInt()
    private val cSub = 0xFF616161.toInt()
    private val cPrimary = 0xFF1565C0.toInt()
    private val cBad = 0xFFC62828.toInt()
    private val cGood = 0xFF2E7D32.toInt()
    private val cWarn = 0xFFE65100.toInt()

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(16))
        view.addView(col, ViewGroup.LayoutParams(-1, -2))
        render()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    /** 切到此分頁、或每秒呼叫; 資料沒變就不重畫 (避免閃爍) */
    fun refresh() {
        val conn = BridgeService.connected
        if (seenHk != HotkeyStore.version || seenCfg != BoardCfg.version || seenConn != conn) render()
    }

    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()

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

    private fun row(parent: ViewGroup): LinearLayout {
        val r = LinearLayout(act)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = android.view.Gravity.CENTER_VERTICAL
        parent.addView(r, LinearLayout.LayoutParams(-1, -2))
        return r
    }

    private fun btn(parent: ViewGroup, t: String, primary: Boolean = false, weight: Float = 1f, onClick: () -> Unit): Button {
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
        val vertical = (parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL
        parent.addView(
            b,
            if (vertical) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(if (weight > 0) 0 else -2, -2, weight)
        )
        return b
    }

    private fun render() {
        seenHk = HotkeyStore.version
        seenCfg = BoardCfg.version
        seenConn = BridgeService.connected
        col.removeAllViews()
        tv(col, "按鍵", 22f, cText, true)
        val sub = tv(col, "控制器上的快捷鍵與設定。分成「全域」和各功能專區,先選區域再編輯。", 13f, cSub)
        sub.setPadding(0, 0, 0, dp(8))
        val conn = BridgeService.connected
        tv(col, if (conn) "● 控制器已連線, 修改會立即套用" else "○ 尚未連線: 快捷鍵會在連線後套用; 控制器設定要連線後才能顯示與修改", 12f, if (conn) cGood else cWarn)
            .setPadding(0, 0, 0, dp(8))

        // ----- 區域選擇 -----
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(act)
        chips.orientation = LinearLayout.HORIZONTAL
        hs.addView(chips)
        val all = HotkeyStore.load(act)
        for ((i, r) in regions.withIndex()) {
            val n = if (r.scope != null) all.count { it.scope == r.scope } else 0
            val b = Button(act)
            b.isAllCaps = false
            b.text = (if (i == cur) "● " else "") + r.title + (if (n > 0) " ($n)" else "")
            b.setOnClickListener { cur = i; render() }
            chips.addView(b, LinearLayout.LayoutParams(-2, -2))
        }
        col.addView(hs, LinearLayout.LayoutParams(-1, -2))

        val r = regions[cur]
        val rc = card()
        (rc.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        tv(rc, r.title, 18f, cText, true)
        tv(rc, r.desc, 13f, cSub).setPadding(0, dp(2), 0, 0)

        if (r.scope != null) renderBindings(r, all)
        if (r.opts.isNotEmpty()) renderOpts(r)
        if (r.id == "sys") renderSys(all)
    }

    // ---------------- 快捷鍵清單 ----------------
    private fun renderBindings(r: Region, all: MutableList<HotkeyStore.Hk>) {
        val sc = r.scope ?: return
        val c = card()
        val mine = all.filter { it.scope == sc }
        tv(c, "快捷鍵 (" + mine.size + ")", 16f, cText, true)
        if (sc != "*") {
            val used = HotkeyStore.USED[sc].orEmpty().sorted().mapNotNull { code ->
                val k = code[0] - '0'
                val g = code[1] - '0'
                if (k in 0..4 && g in 0..2) HotkeyStore.KEYS[k] + HotkeyStore.GESTS[g] else null
            }
            if (used.isNotEmpty()) {
                tv(c, "本頁自己用到 (綁定會蓋過): " + used.joinToString("、"), 12f, cSub).setPadding(0, dp(2), 0, dp(4))
            }
        } else {
            tv(c, "固定給系統, 不能綁: 中鍵三連擊 = 回首頁、中鍵長按 = 目錄、中鍵超長按 = 重開機。", 12f, cSub).setPadding(0, dp(2), 0, dp(4))
        }
        if (mine.isEmpty()) tv(c, "還沒有快捷鍵。", 13f, cSub).setPadding(0, dp(6), 0, dp(4))
        for (h in mine) {
            val rr = row(c)
            rr.setPadding(0, dp(6), 0, dp(2))
            val box = LinearLayout(act)
            box.orientation = LinearLayout.VERTICAL
            rr.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
            tv(box, "「" + HotkeyStore.KEYS[h.key] + "」" + HotkeyStore.GESTS[h.gest] + "  →  " + HotkeyStore.actLabel(h), 15f, cText, true)
            if (sc == "*") tv(box, if (h.force) "優先於頁面" else "頁面沒用到時才生效", 12f, cSub)
            btn(rr, "編輯", false, 0f) { edit(h, sc) }
            btn(rr, "刪除", false, 0f) {
                val l = HotkeyStore.load(act)
                l.removeAll { it.scope == h.scope && it.key == h.key && it.gest == h.gest }
                HotkeyStore.save(act, l)
                render()
            }
        }
        btn(c, "＋ 新增快捷鍵", true) {
            if (all.size >= HotkeyStore.MAX) toast("最多 " + HotkeyStore.MAX + " 個快捷鍵") else edit(null, sc)
        }
    }

    /** 新增 / 編輯對話框 */
    private fun edit(old: HotkeyStore.Hk?, sc: String) {
        val ctx = act
        val lay = LinearLayout(ctx)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(8), dp(20), 0)

        fun label(t: String) = tv(lay, t, 12f, cSub).also { it.setPadding(0, dp(8), 0, 0) }
        fun spin(items: List<String>): Spinner {
            val s = Spinner(ctx)
            s.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
            lay.addView(s, LinearLayout.LayoutParams(-1, -2))
            return s
        }

        label("按鍵 (橫放時依螢幕看到的方向)")
        val sKey = spin(HotkeyStore.KEYS.toList())
        label("手勢")
        val sGest = spin(HotkeyStore.GESTS.toList())
        label("動作")
        val sAct = spin(HotkeyStore.ACTS.map { it.label })
        val lArg = label("參數")
        val pageIds = HotkeyStore.PAGES.map { it.first }
        val sPage = spin(HotkeyStore.PAGES.map { it.second })
        val names = Shortcuts.load(ctx).map { it.name }
        val sSc = spin(if (names.isEmpty()) listOf("(手機還沒有捷徑)") else names)
        val cbForce = CheckBox(ctx)
        cbForce.text = "優先於頁面 (蓋過頁面原本的功能)"
        cbForce.isChecked = old?.force ?: true
        if (sc == "*") lay.addView(cbForce, LinearLayout.LayoutParams(-1, -2))
        val warn = tv(lay, "", 12f, cWarn)
        warn.setPadding(0, dp(8), 0, 0)

        if (old != null) {
            sKey.setSelection(old.key)
            sAct.setSelection(HotkeyStore.ACTS.indexOfFirst { it.id == old.act }.coerceAtLeast(0))
            val pi = pageIds.indexOf(old.arg)
            if (pi >= 0) sPage.setSelection(pi)
            val si = names.indexOf(old.arg)
            if (si >= 0) sSc.setSelection(si)
        }

        var gestInit = old?.gest ?: 0
        fun gestItems(): List<String> = if (sKey.selectedItemPosition == 2) listOf(HotkeyStore.GESTS[0]) else HotkeyStore.GESTS.toList()
        fun fillGest() {
            sGest.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, gestItems())
            sGest.setSelection(gestInit.coerceIn(0, gestItems().size - 1))
        }
        fun update() {
            val a = HotkeyStore.ACTS[sAct.selectedItemPosition]
            lArg.visibility = if (a.arg.isEmpty()) View.GONE else View.VISIBLE
            sPage.visibility = if (a.arg == "page") View.VISIBLE else View.GONE
            sSc.visibility = if (a.arg == "shortcut") View.VISIBLE else View.GONE
            val k = sKey.selectedItemPosition
            val g = sGest.selectedItemPosition.coerceAtLeast(0)
            val sb = StringBuilder()
            val users = HotkeyStore.usedBy(k, g)
            val exist = HotkeyStore.load(ctx).any { it.scope == sc && it.key == k && it.gest == g && it !== old && !(old != null && it.key == old.key && it.gest == old.gest) }
            if (exist) sb.append("這個手勢已有設定, 儲存後會取代。\n")
            if (sc == "*" && users.isNotEmpty()) {
                sb.append(if (cbForce.isChecked) "會蓋掉這些頁面原本的功能: " else "這些頁面本來就會用到這個手勢, 在那裡不會生效: ").append(users.joinToString("、"))
            } else if (sc != "*" && HotkeyStore.USED[sc]?.contains("$k$g") == true) {
                sb.append("這一頁原本有用到這個手勢, 會被你的設定蓋過。")
            }
            if (g == 1 && k != 2) sb.append("\n三連擊會讓這個鍵的雙擊多等約 0.26 秒。")
            warn.text = sb.toString()
        }
        fillGest()
        val ch = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (p === sKey) { gestInit = sGest.selectedItemPosition.coerceAtLeast(0); fillGest() }
                update()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sKey.onItemSelectedListener = ch
        sGest.onItemSelectedListener = ch
        sAct.onItemSelectedListener = ch
        cbForce.setOnCheckedChangeListener { _, _ -> update() }
        update()

        AlertDialog.Builder(ctx).setTitle(if (old == null) "新增快捷鍵" else "編輯快捷鍵").setView(lay)
            .setPositiveButton("儲存") { _, _ ->
                val a = HotkeyStore.ACTS[sAct.selectedItemPosition]
                val arg = when (a.arg) {
                    "page" -> pageIds[sPage.selectedItemPosition]
                    "shortcut" -> if (names.isEmpty()) "" else names[sSc.selectedItemPosition]
                    else -> ""
                }
                if (a.arg == "shortcut" && arg.isEmpty()) {
                    toast("手機還沒有捷徑, 先到「捷徑」分頁建立")
                    return@setPositiveButton
                }
                val k = sKey.selectedItemPosition
                val g = sGest.selectedItemPosition.coerceAtLeast(0)
                val l = HotkeyStore.load(ctx)
                if (old != null) l.removeAll { it.scope == old.scope && it.key == old.key && it.gest == old.gest }
                l.removeAll { it.scope == sc && it.key == k && it.gest == g }
                if (l.size >= HotkeyStore.MAX) {
                    toast("最多 " + HotkeyStore.MAX + " 個快捷鍵")
                    return@setPositiveButton
                }
                l.add(HotkeyStore.Hk(sc, k, g, a.id, arg, if (sc == "*") cbForce.isChecked else true))
                HotkeyStore.save(ctx, l)
                render()
            }
            .setNegativeButton("取消", null).show()
    }

    // ---------------- 該區的控制器設定 ----------------
    private fun renderOpts(r: Region) {
        val c = card()
        tv(c, "控制器設定", 16f, cText, true)
        val conn = BridgeService.connected
        for (key in r.opts) {
            val o = BoardCfg.OPTS[key] ?: continue
            if ((key == "dimt" || key == "bright") && !BoardCfg.backlight) continue     // 這塊板子無法調背光時不顯示
            val rr = row(c)
            rr.setPadding(0, dp(4), 0, 0)
            tv(rr, o.title, 14f, cText).also { rr.removeView(it); rr.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            val v = BoardCfg.values[key]
            val b = Button(act)
            b.isAllCaps = false
            b.text = if (v == null) "—" else o.fmt(v)
            b.isEnabled = v != null
            b.setOnClickListener {
                val cur = BoardCfg.values[key] ?: return@setOnClickListener
                val i = o.vals.indexOf(cur)
                BoardCfg.set(key, o.vals[(i + 1) % o.vals.size])
                render()
            }
            rr.addView(b, LinearLayout.LayoutParams(dp(128), -2))
        }
        if (BoardCfg.values.isEmpty()) {
            tv(c, if (conn) "等待控制器回報設定…" else "連線一次後就會顯示控制器目前的設定。", 12f, cSub).setPadding(0, dp(6), 0, 0)
        } else {
            tv(c, "點按鈕切換到下一個選項。這些設定也能在控制器的「快捷設定」頁修改, 兩邊會同步。", 12f, cSub).setPadding(0, dp(6), 0, 0)
        }
    }

    private fun renderSys(all: MutableList<HotkeyStore.Hk>) {
        val c = card()
        tv(c, "快捷鍵管理", 16f, cText, true)
        tv(c, "目前共 " + all.size + " 個快捷鍵 (上限 " + HotkeyStore.MAX + ")。快捷鍵存在手機與控制器兩邊, 離線時控制器照常使用。", 12f, cSub)
        btn(c, "清除全部快捷鍵") {
            AlertDialog.Builder(act).setTitle("清除全部快捷鍵?").setMessage("全域與各專區的快捷鍵都會刪除。")
                .setPositiveButton("清除") { _, _ ->
                    HotkeyStore.save(act, emptyList())
                    render()
                }.setNegativeButton("取消", null).show()
        }
    }
}
