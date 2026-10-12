package tw.deskctl

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 三個分頁:
 *   主頁 — 平常只會用到這裡: 狀態、啟動/停止、重新連線
 *   記錄 — 所有連線與錯誤記錄集中在這裡 (可複製/分享/清除)
 *   設定 — 初次設定(做完會自動收合)、偏好、控制器管理、關於
 */
class MainActivity : Activity() {

    private companion object {
        const val C_BG = 0xFFF2F4F7.toInt()
        const val C_CARD = 0xFFFFFFFF.toInt()
        const val C_PRIMARY = 0xFF1565C0.toInt()
        const val C_OK = 0xFF2E7D32.toInt()
        const val C_WARN = 0xFFEF6C00.toInt()
        const val C_BAD = 0xFFC62828.toInt()
        const val C_GREY = 0xFF78909C.toInt()
        const val C_TEXT = 0xFF212121.toInt()
        const val C_SUB = 0xFF616161.toInt()
    }

    private val h = Handler(Looper.getMainLooper())
    private var pendingStart = false
    private var tab = 0
    private lateinit var shortcutsPage: ShortcutsPage
    private lateinit var scorePage: ScorePage

    // 版面
    private lateinit var content: FrameLayout
    private val pages = ArrayList<View>()
    private val tabViews = ArrayList<TextView>()

    // 主頁
    private lateinit var statusCard: LinearLayout
    private lateinit var tvState: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvDevice: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnReconnect: Button
    private lateinit var tvInfo: TextView
    private lateinit var setupBanner: LinearLayout
    private lateinit var tvBanner: TextView

    // 記錄
    private lateinit var logScroll: ScrollView
    private lateinit var tvLog: TextView
    private lateinit var btnFilter: Button
    private var onlyProblems = false
    private var lastLogVersion = -1
    private var lastLogFilter = false

    // 設定
    private class SetupItem(
        val title: String, val hint: String, val done: () -> Boolean,
        val btn: String, val action: () -> Unit,
        val btn2: String? = null, val action2: (() -> Unit)? = null,
        val optional: Boolean = false
    ) {
        lateinit var row: LinearLayout
        lateinit var mark: TextView
    }

    private val setupItems = ArrayList<SetupItem>()
    private lateinit var setupHeader: TextView
    private lateinit var setupBody: LinearLayout
    private var setupOpen: Boolean? = null       // null = 自動 (全部完成就收合)
    private lateinit var tvMac: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        shortcutsPage = ShortcutsPage(this)
        pages.add(buildHome())
        pages.add(shortcutsPage.view)
        scorePage = ScorePage(this)
        pages.add(scorePage.view)
        pages.add(buildStatus())
        pages.add(buildLog())
        pages.add(buildSettings())
        for (p in pages) content.addView(p, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(C_CARD)
        bar.elevation = dp(8).toFloat()
        val names = arrayOf("主頁", "捷徑", "比分", "狀態", "記錄", "設定")
        for (i in names.indices) {
            val t = TextView(this)
            t.text = names[i]
            t.textSize = 15f
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener { select(i) }
            tabViews.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        select(0)
        if (Prefs.autoOpen(this) && !BridgeService.running && hasBtPerms()) startSvc(BridgeService.ACTION_START)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacksAndMessages(null)
    }

    private fun select(i: Int) {
        tab = i
        for (k in pages.indices) pages[k].visibility = if (k == i) View.VISIBLE else View.GONE
        for (k in tabViews.indices) {
            val on = k == i
            tabViews[k].setTextColor(if (on) C_PRIMARY else C_SUB)
            tabViews[k].setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        refresh()
    }

    // ============================================================
    // 小工具
    private fun card(parent: LinearLayout, color: Int = C_CARD): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(color)
        bg.cornerRadius = dp(14).toFloat()
        c.background = bg
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, 0, 0, dp(12))
        parent.addView(c, lp)
        return c
    }

    private fun text(parent: ViewGroup, t: String, size: Float = 14f, color: Int = C_TEXT, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.setTypeface(null, Typeface.BOLD)
        parent.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    private fun button(parent: ViewGroup, t: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        if (primary) {
            b.setTextColor(Color.WHITE)
            val bg = GradientDrawable()
            bg.setColor(C_PRIMARY)
            bg.cornerRadius = dp(12).toFloat()
            b.background = bg
        }
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(6), 0, 0)
        parent.addView(b, lp)
        return b
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(16))
        sv.addView(col, ViewGroup.LayoutParams(-1, -2))
        return Pair(sv, col)
    }

    private fun tint(v: View, color: Int) {
        val bg = v.background
        if (bg is GradientDrawable) bg.setColor(color)
    }

    // ============================================================
    // 主頁
    private fun buildHome(): View {
        val (sv, col) = page()
        text(col, "桌邊控制器", 24f, C_TEXT, true)
        text(col, "ESP32 桌邊藍牙控制器的手機端", 13f, C_SUB).setPadding(0, 0, 0, dp(14))

        setupBanner = card(col, 0xFFFFF3E0.toInt())
        tvBanner = text(setupBanner, "", 14f, C_TEXT)
        button(setupBanner, "前往設定") { select(5) }

        statusCard = card(col, C_GREY)
        tvState = text(statusCard, "", 26f, Color.WHITE, true)
        tvDetail = text(statusCard, "", 14f, Color.WHITE)
        tvDevice = text(statusCard, "", 13f, 0xFFE0E0E0.toInt())

        btnToggle = button(col, "啟動", true) { toggleService() }
        btnToggle.textSize = 18f
        btnToggle.setPadding(0, dp(14), 0, dp(14))
        btnReconnect = button(col, "重新連線") { startSvc(BridgeService.ACTION_RECONNECT) }

        val info = card(col)
        tvInfo = text(info, "", 14f, C_TEXT)
        info.setPadding(dp(16), dp(14), dp(16), dp(14))
        (info.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(14), 0, 0)
        return sv
    }

    private fun toggleService() {
        if (BridgeService.running) {
            startSvc(BridgeService.ACTION_STOP)
        } else {
            startSvc(BridgeService.ACTION_START)
        }
        h.postDelayed({ refresh() }, 300)
    }

    private fun updateHome() {
        val running = BridgeService.running
        val connected = BridgeService.connected
        val color: Int
        if (!running) {
            color = C_GREY
            tvState.text = "已停止"
            tvDetail.text = "按下方「啟動」開始連線"
            tvDevice.text = ""
        } else if (!connected) {
            color = C_WARN
            tvState.text = "連線中…"
            tvDetail.text = BridgeService.status
            tvDevice.text = Prefs.mac(this)?.let { "控制器 $it" } ?: "尚未配對, 搜尋中 (請確認控制器已開機)"
        } else {
            color = C_OK
            tvState.text = "已連線"
            tvDetail.text = BridgeService.status
            val mins = (SystemClock.elapsedRealtime() - BridgeService.connectedSince) / 60000
            val dur = if (mins < 1) "剛連上" else if (mins < 60) "已連線 $mins 分鐘" else "已連線 ${mins / 60} 小時 ${mins % 60} 分"
            tvDevice.text = (Prefs.mac(this) ?: "") + "  ·  " + dur
        }
        tint(statusCard, color)
        btnToggle.text = if (running) "停止" else "啟動"
        val bg = btnToggle.background
        if (bg is GradientDrawable) bg.setColor(if (running) C_BAD else C_PRIMARY)
        btnReconnect.visibility = if (running) View.VISIBLE else View.GONE
        btnReconnect.text = if (BridgeService.paused) "重新搜尋控制器" else "重新連線"

        val missing = setupItems.filter { !it.optional && !it.done() }
        if (missing.isEmpty()) {
            setupBanner.visibility = View.GONE
        } else {
            setupBanner.visibility = View.VISIBLE
            tvBanner.text = "⚠ 還有 ${missing.size} 項初次設定沒完成:\n" + missing.joinToString("\n") { "• " + it.title }
        }
        val lines = BridgeService.infoLines
        tvInfo.visibility = if (running && lines.isNotEmpty()) View.VISIBLE else View.GONE
        (tvInfo.parent as View).visibility = tvInfo.visibility
        tvInfo.text = lines.joinToString("\n")
    }

    // ============================================================
    // 記錄
    private fun buildLog(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(28), dp(12), dp(8))

        text(root, "記錄", 22f, C_TEXT, true).setPadding(dp(4), 0, 0, dp(8))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun small(t: String, f: () -> Unit): Button {
            val b = Button(this)
            b.text = t
            b.isAllCaps = false
            b.textSize = 13f
            b.setOnClickListener { f() }
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            return b
        }
        small("複製") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DeskCtl log", AppLog.text(false)))
            Toast.makeText(this, "已複製", Toast.LENGTH_SHORT).show()
        }
        small("分享") {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, AppLog.text(false))
            startActivity(Intent.createChooser(i, "分享記錄"))
        }
        small("清除") { AppLog.clear(); refresh() }
        btnFilter = small("只看異常") {
            onlyProblems = !onlyProblems
            refresh()
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        logScroll = ScrollView(this)
        tvLog = TextView(this)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.textSize = 11.5f
        tvLog.setTextColor(C_TEXT)
        tvLog.setTextIsSelectable(true)
        tvLog.setPadding(dp(6), dp(6), dp(6), dp(6))
        logScroll.addView(tvLog, ViewGroup.LayoutParams(-1, -2))
        val bg = GradientDrawable()
        bg.setColor(C_CARD)
        bg.cornerRadius = dp(10).toFloat()
        logScroll.background = bg
        val lp = LinearLayout.LayoutParams(-1, 0, 1f)
        lp.setMargins(0, dp(8), 0, 0)
        root.addView(logScroll, lp)
        return root
    }

    private fun updateLog() {
        if (AppLog.version == lastLogVersion && onlyProblems == lastLogFilter) return
        lastLogVersion = AppLog.version
        lastLogFilter = onlyProblems
        btnFilter.text = if (onlyProblems) "顯示全部" else "只看異常"
        val child = logScroll.getChildAt(0)
        val atBottom = child == null || child.bottom - (logScroll.height + logScroll.scrollY) < dp(48)
        val t = AppLog.text(onlyProblems)
        tvLog.text = if (t.isEmpty()) "(沒有記錄)" else t
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ============================================================
    // 設定
    private fun buildSettings(): View {
        val (sv, col) = page()
        text(col, "設定", 22f, C_TEXT, true).setPadding(0, 0, 0, dp(12))

        // ---- 初次設定 ----
        val setup = card(col)
        setupHeader = text(setup, "", 16f, C_TEXT, true)
        setupHeader.setOnClickListener {
            setupOpen = !(setupOpen ?: setupItems.any { !it.optional && !it.done() })
            refresh()
        }
        setupBody = LinearLayout(this)
        setupBody.orientation = LinearLayout.VERTICAL
        setup.addView(setupBody, LinearLayout.LayoutParams(-1, -2))

        setupItems.add(
            SetupItem("藍牙與通知權限", "讓 App 能連接控制器並顯示常駐通知。", { hasBtPerms() }, "授權", { requestPerms() })
        )
        setupItems.add(
            SetupItem(
                "通知存取 (讀取音樂資訊)", "開啟後才讀得到歌名與播放狀態。若無法勾選, 請先按下方「允許受限設定」。",
                { NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) },
                "開啟通知存取",
                { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                "允許受限設定 (App 資訊 → ⋮)",
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            )
        )
        setupItems.add(
            SetupItem(
                "允許背景執行 (電池不受限)", "避免系統在螢幕關閉後把連線殺掉。",
                {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(packageName)
                },
                "開啟",
                { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            )
        )
        setupItems.add(
            SetupItem(
                "顯示在其他 App 上層 (捷徑用)", "讓捷徑能從背景開啟 App / 網址。只用捷徑功能才需要。",
                { Launcher.overlayOk(this) }, "開啟",
                { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                optional = true
            )
        )
        setupItems.add(
            SetupItem(
                "無障礙服務 (捷徑的點擊步驟用)", "「點擊畫面/點擊文字/返回」步驟需要; 開啟後在清單中找「桌邊控制器」。不讀取畫面內容。",
                { Launcher.accessibilityOn(this) }, "開啟",
                { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "若無法勾選: 允許受限設定 (App 資訊 → ⋮)",
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                optional = true
            )
        )
        for (it in setupItems) addSetupRow(it)

        // ---- 偏好 ----
        val pref = card(col)
        text(pref, "偏好", 16f, C_TEXT, true)
        val s1 = Switch(this)
        s1.text = "手機開機後自動啟動"
        s1.isChecked = Prefs.autostart(this)
        s1.setOnCheckedChangeListener { _, c -> Prefs.setAutostart(this, c) }
        pref.addView(s1, LinearLayout.LayoutParams(-1, -2))
        val s2 = Switch(this)
        s2.text = "開啟 App 時自動啟動"
        s2.isChecked = Prefs.autoOpen(this)
        s2.setOnCheckedChangeListener { _, c -> Prefs.setAutoOpen(this, c) }
        pref.addView(s2, LinearLayout.LayoutParams(-1, -2))
        val s3 = Switch(this)
        s3.text = "連線時保持 CPU 不休眠 (手機放著也不斷線, 較耗電)"
        s3.isChecked = Prefs.wakeLock(this)
        s3.setOnCheckedChangeListener { _, c ->
            Prefs.setWakeLock(this, c)
            Toast.makeText(this, "下次啟動服務時生效 (停止後再啟動)", Toast.LENGTH_SHORT).show()
        }
        pref.addView(s3, LinearLayout.LayoutParams(-1, -2))
        val s4 = Switch(this)
        s4.text = "智慧省電: 找不到控制器時, 每 10 分鐘才搜尋一次 (連上後保持連線)"
        s4.isChecked = Prefs.autoPause(this)
        s4.setOnCheckedChangeListener { _, c -> Prefs.setAutoPause(this, c) }
        pref.addView(s4, LinearLayout.LayoutParams(-1, -2))
        val s5 = Switch(this)
        s5.text = "設定「完全不搜尋」時段 (這段時間斷線也不會去搜尋)"
        s5.isChecked = Prefs.quietOn(this)
        pref.addView(s5, LinearLayout.LayoutParams(-1, -2))
        val btnQuiet = Button(this)
        fun quietText() {
            btnQuiet.text = "不搜尋時段: " + String.format("%02d:%02d", Prefs.quietFrom(this) / 60, Prefs.quietFrom(this) % 60) +
                " ~ " + String.format("%02d:%02d", Prefs.quietTo(this) / 60, Prefs.quietTo(this) % 60)
            btnQuiet.isEnabled = Prefs.quietOn(this)
        }
        quietText()
        s5.setOnCheckedChangeListener { _, c -> Prefs.setQuietOn(this, c); quietText() }
        btnQuiet.setOnClickListener {
            val f = Prefs.quietFrom(this)
            android.app.TimePickerDialog(this, { _, fh, fm ->
                val t = Prefs.quietTo(this)
                android.app.TimePickerDialog(this, { _, th, tm ->
                    Prefs.setQuiet(this, fh * 60 + fm, th * 60 + tm)
                    quietText()
                }, t / 60, t % 60, true).apply { setTitle("結束時間 (恢復搜尋)") }.show()
            }, f / 60, f % 60, true).apply { setTitle("開始時間 (停止搜尋)") }.show()
        }
        pref.addView(btnQuiet, LinearLayout.LayoutParams(-1, -2))

        // ---- 手機訊息 ----
        val msgc = card(col)
        text(msgc, "手機訊息 (轉發通知到控制器)", 16f, C_TEXT, true)
        text(msgc, "需要先開啟「通知存取」。LINE、簡訊等新通知會顯示在控制器的「手機訊息」頁並跳提示。", 12f, C_SUB)
        val m1 = Switch(this)
        m1.text = "轉發手機通知"
        m1.isChecked = Prefs.msgOn(this)
        m1.setOnCheckedChangeListener { _, c -> Prefs.setMsgOn(this, c) }
        msgc.addView(m1, LinearLayout.LayoutParams(-1, -2))
        val m2 = Switch(this)
        m2.text = "隱藏內容 (只顯示 App 名稱, 保護隱私)"
        m2.isChecked = Prefs.msgHide(this)
        m2.setOnCheckedChangeListener { _, c -> Prefs.setMsgHide(this, c) }
        msgc.addView(m2, LinearLayout.LayoutParams(-1, -2))
        tvMsgSel = text(msgc, "", 13f, C_PRIMARY)
        button(msgc, "選擇要轉發的 App", true) {
            pickApps("只轉發這些 App 的通知", Prefs.msgAllow(this)) {
                Prefs.setMsgAllow(this, it)
                tvMsgSel.text = msgSelText()
            }
        }
        tvMsgSel.text = msgSelText()

        // ---- 控制器目錄 ----
        val lay = card(col)
        text(lay, "控制器目錄順序", 16f, C_TEXT, true)
        text(lay, "調整控制器選單的順序、隱藏不用的頁面, ★ 表示加入「左右鍵快速切換」。連線後會自動送出。", 12f, C_SUB)
        layoutBox = LinearLayout(this)
        layoutBox.orientation = LinearLayout.VERTICAL
        lay.addView(layoutBox, LinearLayout.LayoutParams(-1, -2))

        // ---- 控制器 ----
        val dev = card(col)
        text(dev, "控制器", 16f, C_TEXT, true)
        tvMac = text(dev, "", 14f, C_SUB)
        button(dev, "向控制器重新同步") { startSvc(BridgeService.ACTION_SYNC) }
        button(dev, "忘記已記住的控制器 (重新搜尋)") {
            startSvc(BridgeService.ACTION_FORGET)
            Toast.makeText(this, "已忘記, 會重新搜尋", Toast.LENGTH_SHORT).show()
            refresh()
        }

        // ---- 關於 ----
        val about = card(col)
        text(about, "關於", 16f, C_TEXT, true)
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        text(about, "桌邊控制器 App v$ver", 14f, C_SUB)
        text(about, "已載入功能: " + Features.registry.joinToString("、") { it.title }, 14f, C_SUB)
        text(about, "新增功能的方法見 README「手機 App 新增功能」", 12f, C_GREY)
        return sv
    }

    private fun addSetupRow(it: SetupItem) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, dp(4))
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        val mark = TextView(this)
        mark.textSize = 18f
        top.addView(mark, LinearLayout.LayoutParams(dp(30), -2))
        val t = TextView(this)
        t.text = it.title + (if (it.optional) "  (選用)" else "")
        t.textSize = 15f
        t.setTextColor(C_TEXT)
        top.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(top, LinearLayout.LayoutParams(-1, -2))
        val hint = TextView(this)
        hint.text = it.hint
        hint.textSize = 12f
        hint.setTextColor(C_SUB)
        hint.setPadding(dp(30), 0, 0, 0)
        row.addView(hint, LinearLayout.LayoutParams(-1, -2))
        val b = Button(this)
        b.text = it.btn
        b.isAllCaps = false
        b.setOnClickListener { _ -> it.action(); }
        row.addView(b, LinearLayout.LayoutParams(-1, -2))
        if (it.btn2 != null && it.action2 != null) {
            val b2 = Button(this)
            b2.text = it.btn2
            b2.isAllCaps = false
            b2.setOnClickListener { _ -> it.action2.invoke() }
            row.addView(b2, LinearLayout.LayoutParams(-1, -2))
        }
        it.row = row
        it.mark = mark
        setupBody.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun updateSettings() {
        val req = setupItems.filter { !it.optional }
        val doneCount = req.count { it.done() }
        val all = doneCount == req.size
        val open = setupOpen ?: !all
        setupHeader.text = (if (all) "✔ 初次設定已完成" else "初次設定 ($doneCount/${req.size})") +
            (if (open) "  ▲" else "  ▼ 點開檢查")
        setupBody.visibility = if (open) View.VISIBLE else View.GONE
        for (it in setupItems) {
            val ok = it.done()
            it.mark.text = if (ok) "✔" else if (it.optional) "○" else "✘"
            it.mark.setTextColor(if (ok) C_OK else if (it.optional) C_GREY else C_BAD)
            // 做完的項目把按鈕收起來, 版面更清爽 (按鈕在 row 的第 3 個之後)
            for (k in 2 until it.row.childCount) it.row.getChildAt(k).visibility = if (ok) View.GONE else View.VISIBLE
        }
        tvMac.text = Prefs.mac(this)?.let { "已記住: $it" } ?: "尚未配對 (啟動後會自動搜尋 DeskCtl)"
    }

    // ============================================================
    // 狀態 (手機 + 控制器)
    private lateinit var tvPhone: TextView
    private lateinit var tvCtl: TextView
    private lateinit var tvSpark: TextView
    private lateinit var tvMemClean: TextView
    private lateinit var tvClean: TextView
    private lateinit var tvMsgSel: TextView

    private fun msgSelText(): String {
        val n = Prefs.msgAllow(this).size
        return if (n == 0) "尚未選擇任何 App: 目前不會轉發任何通知" else "已選 $n 個 App 會轉發"
    }

    private fun pickApps(title: String, current: Set<String>, save: (Set<String>) -> Unit) {
        AppPicker.multi(this, title, current, save)
    }

    private fun buildStatus(): View {
        val (sv, col) = page()
        text(col, "狀態", 22f, C_TEXT, true).setPadding(0, 0, 0, dp(12))
        val pc = card(col)
        text(pc, "手機", 16f, C_TEXT, true)
        tvPhone = text(pc, "", 14f, C_TEXT)
        tvPhone.typeface = Typeface.MONOSPACE
        val cc = card(col)
        text(cc, "控制器 (開發板)", 16f, C_TEXT, true)
        tvCtl = text(cc, "", 14f, C_TEXT)
        tvCtl.typeface = Typeface.MONOSPACE
        text(cc, "剩餘記憶體趨勢 (約每 10 秒一點)", 12f, C_SUB).setPadding(0, dp(8), 0, 0)
        tvSpark = text(cc, "", 18f, C_PRIMARY)
        text(cc, "記憶體持續下降不回升, 代表可能有洩漏; 韌體會自動回收。", 12f, C_GREY)
        button(cc, "清理控制器記憶體") { startSvc(BridgeService.ACTION_MEMCLEAN) }
        tvMemClean = text(cc, "", 13f, C_PRIMARY)

        val cl = card(col)
        text(cl, "手機清理", 16f, C_TEXT, true)
        text(
            cl,
            "已自動保護: 本 App、有媒體工作階段的 App (音樂 / Brave 影片)、輸入法、桌面與你指定的 App。" +
                "別的 App 的快取 Android 不允許第三方清除, 這裡只能結束背景 App (釋放記憶體) 與清除本 App 快取。",
            12f, C_SUB
        )
        tvClean = text(cl, "", 13f, C_PRIMARY)
        button(cl, "結束背景 App + 清快取", true) {
            tvClean.text = "清理中…"
            Cleaner.cleanMemory(this, h) { r ->
                tvClean.text = "已嘗試結束 ${r.tried} 個 App, 清快取 ${r.cacheKb}KB, 釋放約 ${r.freedMb}MB"
            }
        }
        button(cl, "清除最近使用的 App (需無障礙服務)") {
            tvClean.text = "開啟最近工作…"
            Cleaner.clearRecents(h) { ok, why ->
                // 清完會回到桌面; 結果在記錄與下次開啟時可見
                AppLog.i("CLEAN", if (ok) "已清除最近使用清單" else "清除最近使用失敗: $why")
            }
        }
        button(cl, "選擇清理時要保護的 App") {
            pickApps("清理時不要結束這些 App", Prefs.cleanProtect(this)) { Prefs.setCleanProtect(this, it) }
        }
        return sv
    }

    private fun updateStatus() {
        val o = PhoneStats.collect(this)
        val sb = StringBuilder()
        try {
            val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (b != null) {
                val lv = b.getIntExtra("level", -1) * 100 / b.getIntExtra("scale", 100).coerceAtLeast(1)
                sb.append(String.format("%-14s%s\n", "電量", "$lv%"))
            }
        } catch (_: Exception) {
        }
        for ((k, v) in PhoneStats.lines(o)) sb.append(String.format("%-12s%s\n", k, v))
        sb.append(String.format("%-12s%s", "背景服務", if (BridgeService.running) "執行中" else "未啟動"))
        tvPhone.text = sb.toString()

        if (DeviceInfo.at == 0L) {
            tvCtl.text = if (BridgeService.connected) "已連線, 等待控制器回報…" else "未連線"
            tvSpark.text = ""
            return
        }
        val age = (SystemClock.elapsedRealtime() - DeviceInfo.at) / 1000
        val up = DeviceInfo.upSec + age
        val tot = (DeviceInfo.memFree + DeviceInfo.memAlloc).coerceAtLeast(1)
        val c = StringBuilder()
        c.append(String.format("%-12s%s\n", "韌體版本", "v" + DeviceInfo.ver))
        c.append(String.format("%-12s%s\n", "已運作", fmtDur(up)))
        c.append(String.format("%-12s%s\n", "目前頁面", pageName(DeviceInfo.page)))
        c.append(String.format("%-12s%d KB (%d%%)\n", "剩餘記憶體", DeviceInfo.memFree / 1024, DeviceInfo.memFree * 100L / tot))
        c.append(String.format("%-12s%d KB\n", "已用記憶體", DeviceInfo.memAlloc / 1024))
        c.append(String.format("%-12s%d 次\n", "藍牙連線", DeviceInfo.conn))
        c.append(String.format("%-12s%d 次\n", "藍牙斷線", DeviceInfo.disc))
        c.append(String.format("%-12s%s", "回報", if (age < 30) "${age} 秒前" else "已過期 (${age} 秒)"))
        tvCtl.text = c.toString()
        tvSpark.text = spark(DeviceInfo.memHistory.toList())
        tvMemClean.text = DeviceInfo.cleanMsg
    }

    private fun pageName(id: String): String = DeviceInfo.pages.firstOrNull { it.first == id }?.second ?: id

    private fun fmtDur(sec: Long): String = when {
        sec >= 3600 -> "${sec / 3600} 小時 ${sec % 3600 / 60} 分"
        sec >= 60 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "$sec 秒"
    }

    private fun spark(v: List<Int>): String {
        if (v.size < 2) return ""
        val lo = v.min()
        val hi = v.max()
        val bars = "▁▂▃▄▅▆▇█"
        val sb = StringBuilder()
        for (x in v) sb.append(if (hi == lo) bars[3] else bars[(x - lo) * 7 / (hi - lo)])
        return sb.toString()
    }

    // ============================================================
    // 控制器目錄編輯
    private lateinit var layoutBox: LinearLayout
    private var edMenu = ArrayList<String>()
    private var edRing = ArrayList<String>()
    private var edInit = false
    private var edPagesN = -1

    private var edVer = -1

    private fun updateLayoutEditor() {
        if (edVer != LayoutStore.version) {          // 已存的目錄有變動 (例如自動補入新頁面) -> 重新載入
            edVer = LayoutStore.version
            edInit = false
        }
        val pgs = DeviceInfo.pages
        if (!edInit && pgs.isNotEmpty()) {
            val saved = LayoutStore.load(this)
            val m = saved?.menu?.takeIf { it.isNotEmpty() } ?: DeviceInfo.menu.ifEmpty { pgs.map { it.first } }
            edMenu = ArrayList(m.filter { id -> pgs.any { it.first == id } })
            if (!edMenu.contains("home")) edMenu.add(0, "home")
            if (!edMenu.contains("settings")) edMenu.add("settings")
            edRing = ArrayList((saved?.ring?.takeIf { it.isNotEmpty() } ?: DeviceInfo.ring.ifEmpty { listOf("home", "sw", "music") }))
            edInit = true
            edPagesN = -1
        }
        if (edPagesN != pgs.size + (if (edInit) 1000 else 0)) {
            edPagesN = pgs.size + (if (edInit) 1000 else 0)
            renderLayout()
        }
    }

    private fun renderLayout() {
        layoutBox.removeAllViews()
        val pgs = DeviceInfo.pages
        if (pgs.isEmpty() || !edInit) {
            text(layoutBox, "連線到控制器後才能編輯 (需要先取得頁面清單)", 13f, C_GREY)
            return
        }
        fun title(id: String) = pgs.firstOrNull { it.first == id }?.second ?: id
        fun small(row: LinearLayout, t: String, enabled: Boolean = true, f: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.isAllCaps = false
            b.isEnabled = enabled
            b.minWidth = 0
            b.minimumWidth = 0
            b.setOnClickListener { f() }
            row.addView(b, LinearLayout.LayoutParams(-2, -2))
        }
        for ((i, id) in edMenu.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val t = TextView(this)
            t.text = title(id) + if (id == "home") " (固定第一)" else ""
            t.textSize = 15f
            t.setTextColor(C_TEXT)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            val locked = id == "home"
            small(row, "↑", i > 1) { edMenu.removeAt(i); edMenu.add(i - 1, id); renderLayout() }
            small(row, "↓", !locked && i < edMenu.size - 1) { edMenu.removeAt(i); edMenu.add(i + 1, id); renderLayout() }
            small(row, if (edRing.contains(id)) "★" else "☆") {
                if (edRing.contains(id)) { if (edRing.size > 1) edRing.remove(id) } else edRing.add(id)
                renderLayout()
            }
            small(row, "隱藏", id != "home" && id != "settings") { edMenu.removeAt(i); edRing.remove(id); renderLayout() }
            layoutBox.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        val hidden = pgs.filter { !edMenu.contains(it.first) }
        if (hidden.isNotEmpty()) {
            text(layoutBox, "已隱藏", 13f, C_SUB).setPadding(0, dp(10), 0, 0)
            for (p in hidden) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val t = TextView(this)
                t.text = p.second
                t.textSize = 15f
                t.setTextColor(C_GREY)
                row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                small(row, "顯示") {
                    edMenu.add(maxOf(1, edMenu.size - 1), p.first)    // 放在「設定」前面
                    renderLayout()
                }
                layoutBox.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        button(layoutBox, "儲存並送出到控制器", true) {
            LayoutStore.save(this, edMenu, edRing)
            Toast.makeText(this, "已儲存" + if (BridgeService.connected) ", 已送出" else ", 連線後會自動送出", Toast.LENGTH_SHORT).show()
        }
        button(layoutBox, "還原為預設順序") {
            Prefs.setLayout(this, "")
            edInit = false
            Toast.makeText(this, "已還原手機端設定 (控制器維持目前順序)", Toast.LENGTH_SHORT).show()
            edPagesN = -2
        }
    }

    // ============================================================
    // 權限與服務
    private fun startSvc(action: String) {
        if (action == BridgeService.ACTION_START) {
            if (!hasBtPerms()) {
                pendingStart = true
                requestPerms()
                return
            }
            try {
                ContextCompat.startForegroundService(this, Intent(this, BridgeService::class.java).setAction(action))
            } catch (e: Exception) {
                AppLog.e("APP", "啟動服務失敗: " + e)
                Toast.makeText(this, "啟動失敗: " + e.message, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!BridgeService.running) {
            // 服務沒在執行: 只有「忘記控制器」有意義, 直接清掉設定
            if (action == BridgeService.ACTION_FORGET) Prefs.setMac(this, null)
            return
        }
        try {
            startService(Intent(this, BridgeService::class.java).setAction(action))
        } catch (e: Exception) {
            AppLog.e("APP", "指令失敗: " + e)
        }
    }

    private fun neededPerms(): Array<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN)
            l.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l.toTypedArray()
    }

    private fun hasBtPerms(): Boolean = neededPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() {
        requestPermissions(neededPerms(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (pendingStart && hasBtPerms()) {
            pendingStart = false
            startSvc(BridgeService.ACTION_START)
        }
        pendingStart = false
        refresh()
    }

    // ============================================================
    private fun refresh() {
        updateHome()
        updateSettings()
        if (tab == 1) shortcutsPage.refresh()
        if (tab == 2) scorePage.refresh()
        if (tab == 3) updateStatus()
        if (tab == 4) updateLog()
        if (tab == 5) updateLayoutEditor()
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ refresh() }, 1000)
    }
}
