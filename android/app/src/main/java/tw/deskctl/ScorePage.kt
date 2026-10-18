package tw.deskctl

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「比分」分頁: 選聯盟與日期 → 列出當天比賽 → 點「追蹤」加入清單 (最多 8 場)。
 * 追蹤清單的順序就是控制器上左右切換的順序。
 */
class ScorePage(private val act: Activity) {
    val view: ScrollView = ScrollView(act)
    private val col = LinearLayout(act)
    private val ui = Handler(Looper.getMainLooper())
    private val tpe = ZoneId.of("Asia/Taipei")

    private var lg = "MLB"
    private var date: LocalDate = LocalDate.now(tpe)
    private var games: List<GameInfo>? = null
    private var loading = false
    private var token = 0
    private var tracked: MutableList<Tracked> = ArrayList()
    private var seenSum = -1

    private val cText = 0xFF212121.toInt()
    private val cSub = 0xFF616161.toInt()
    private val cPrimary = 0xFF1565C0.toInt()
    private val cBad = 0xFFC62828.toInt()
    private val cGood = 0xFF2E7D32.toInt()

    init {
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(28), dp(16), dp(16))
        view.addView(col, ViewGroup.LayoutParams(-1, -2))
        tracked = ScoreStore.load(act)
        render()
        load()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

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
        // 直向容器: 滿版寬; 橫向容器: 依權重分寬 (寬度 0 在直向容器裡會讓文字一字一行、撐出一大塊空白)
        val vertical = (parent as? LinearLayout)?.orientation == LinearLayout.VERTICAL
        parent.addView(b, if (vertical) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, weight))
        return b
    }

    private fun toast(t: String) = Toast.makeText(act, t, Toast.LENGTH_SHORT).show()

    private fun fmt(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(tpe).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

    /** 切到此分頁、或即時摘要更新時呼叫 */
    fun refresh() {
        if (seenSum != ScoreFeature.version) {
            seenSum = ScoreFeature.version
            render()
        }
    }

    private fun load() {
        val my = ++token
        loading = true
        games = null
        render()
        val l = lg
        val d = date
        val soc = Prefs.soccerLeagues(act).split(",").filter { it.isNotBlank() }
        Thread {
            val r = try {
                ScoreApi.list(l, d, soc)
            } catch (e: Exception) {
                AppLog.w("SCORE", "賽程讀取失敗: $e")
                null
            }
            ui.post {
                if (my != token) return@post
                loading = false
                games = r
                render()
            }
        }.start()
    }

    private fun save() {
        ScoreStore.save(act, tracked)
    }

    /** 自訂兩隊的中文名 (留空 = 恢復內建名稱) */
    private fun rename(t: Tracked) {
        val lay = LinearLayout(act)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(dp(20), dp(8), dp(20), 0)
        fun field(label: String, code: String, en: String): android.widget.EditText {
            tv(lay, label + " " + (en.ifEmpty { code }), 12f, cSub).setPadding(0, dp(8), 0, 0)
            val e = android.widget.EditText(act)
            e.setText(Teams.custom(t.lg, code) ?: "")
            e.hint = Teams.zh(t.lg, code, en) ?: "中文名 (留空用內建)"
            lay.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }
        val ea = field("客隊", t.a, t.an)
        val eh = field("主隊", t.h, t.hn)
        android.app.AlertDialog.Builder(act).setTitle("自訂隊名").setView(lay)
            .setPositiveButton("儲存") { _, _ ->
                Teams.setCustom(act, t.lg, t.a, ea.text.toString())
                Teams.setCustom(act, t.lg, t.h, eh.text.toString())
                render()
            }.setNegativeButton("取消", null).show()
    }

    private fun isTracked(g: GameInfo) = tracked.any { it.src == g.src && it.id == g.id }

    private fun render() {
        col.removeAllViews()
        tv(col, "比分板", 22f, cText, true)
        val sub = tv(col, "選擇要追蹤的比賽, 資料由手機抓取後傳給控制器。控制器上左右鍵切換比賽、上下鍵切換主畫面/細節。", 13f, cSub)
        sub.setPadding(0, 0, 0, dp(12))

        // ----- 籃球延遲播放 -----
        val dc = card()
        tv(dc, "籃球延遲播放", 16f, cText, true)
        tv(dc, "把控制器上正在看的籃球比賽延後播出, 期間先收幾筆資料, 計時器用前後兩筆真實數據插值, 不必預測未來, 就不會算錯又更正。代價是畫面晚於實況。", 12f, cSub)
        val dly = Prefs.nbaDelay(act)
        btn(dc, "延遲: " + (if (dly == 0) "關閉 (即時, 用推算)" else "$dly 秒") + "  (點此更改)") {
            val opts = arrayOf("關閉 (即時, 用推算)", "10 秒", "15 秒", "20 秒", "30 秒")
            val vals = intArrayOf(0, 10, 15, 20, 30)
            android.app.AlertDialog.Builder(act).setTitle("籃球延遲播放").setItems(opts) { _, i ->
                Prefs.setNbaDelay(act, vals[i])
                render()
            }.setNegativeButton("取消", null).show()
        }

        // ----- 隊名 -----
        val nc = card()
        tv(nc, "隊名", 16f, cText, true)
        tv(nc, "手機畫面一律「中文 英文 (代號)」並列。控制器下方那一行的隊名可選語言; 查不到中文的球隊會自動改用英文。也可以在追蹤清單按「改名」自己填中文。", 12f, cSub)
        btn(nc, "控制器隊名: " + (if (Teams.english) "英文" else "中文優先") + "  (點此切換)") {
            Teams.setEnglish(act, !Teams.english)
            render()
        }

        // ----- 追蹤清單 -----
        val tc = card()
        tv(tc, "追蹤中 (" + tracked.size + "/" + ScoreStore.MAX + ")", 16f, cText, true)
        if (tracked.isEmpty()) tv(tc, "還沒有追蹤任何比賽, 從下方選擇。", 13f, cSub)
        for (i in tracked.indices) {
            val t = tracked[i]
            tv(tc, (i + 1).toString() + ". [" + (if (t.lg == "SOC") "足球" else t.lg) + "] " + Teams.label(t.lg, t.a, t.an) + " @ " + Teams.label(t.lg, t.h, t.hn) + "  (" + fmt(t.startMs) + ")", 14f, cText, true).setPadding(0, dp(8), 0, 0)
            ScoreFeature.summary[i]?.let { tv(tc, "   " + it, 13f, cGood) }
            val r = row(tc)
            btn(r, "↑") { if (i > 0) { tracked.add(i - 1, tracked.removeAt(i)); save(); render() } }
            btn(r, "↓") { if (i < tracked.size - 1) { tracked.add(i + 1, tracked.removeAt(i)); save(); render() } }
            btn(r, "改名") { rename(t) }
            btn(r, "移除") { tracked.removeAt(i); save(); render() }
        }

        // ----- 選比賽 -----
        val c = card()
        tv(c, "新增比賽", 16f, cText, true)
        val lr = row(c)
        for (x in listOf("MLB", "NBA", "CPBL", "SOC")) {
            btn(lr, if (x == lg) "● " + (if (x == "SOC") "足球" else x) else (if (x == "SOC") "足球" else x), x == lg) {
                if (x != lg) { lg = x; load() }
            }
        }
        if (lg == "SOC") {
            val sel = Prefs.soccerLeagues(act).split(",").filter { it.isNotBlank() }
            btn(c, "聯賽: " + (if (sel.isEmpty()) "(未選)" else sel.joinToString(" ") { ScoreSoccer.leagueName(it) }) + "  (點此更改)") {
                val names = ScoreSoccer.LEAGUES.map { it.second }.toTypedArray()
                val on = BooleanArray(names.size) { ScoreSoccer.LEAGUES[it].first in sel }
                android.app.AlertDialog.Builder(act).setTitle("要查哪些聯賽")
                    .setMultiChoiceItems(names, on) { _, i, v -> on[i] = v }
                    .setPositiveButton("確定") { _, _ ->
                        val ids = ScoreSoccer.LEAGUES.filterIndexed { i, _ -> on[i] }.map { it.first }
                        Prefs.setSoccerLeagues(act, ids.joinToString(","))
                        load()
                    }.setNegativeButton("取消", null).show()
            }
        }
        val dr = row(c)
        btn(dr, "◀ 前一天") { date = date.minusDays(1); load() }
        btn(dr, "今天") { date = LocalDate.now(tpe); load() }
        btn(dr, "後一天 ▶") { date = date.plusDays(1); load() }
        val today = LocalDate.now(tpe)
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, date).toInt()
        val wk = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val rel = when {
            diff == 0 -> "今天"
            diff > 0 -> "距今日 +" + diff + " 天 (未來)"
            else -> "距今日 " + diff + " 天 (過去)"
        }
        tv(c, date.monthValue.toString() + "/" + date.dayOfMonth + " 週" + wk[date.dayOfWeek.value - 1] + "  ·  " + rel, 15f, cText, true).setPadding(0, dp(8), 0, 0)
        tv(c, "今天是 " + today.monthValue + "/" + today.dayOfMonth + " 週" + wk[today.dayOfWeek.value - 1] + " (台灣時間), 比賽時間皆為台灣時間", 12f, cSub).setPadding(0, 0, 0, dp(6))

        val g = games
        if (loading) {
            tv(c, "讀取中…", 14f, cSub)
        } else if (g == null) {
            tv(c, "讀取失敗, 請檢查網路; 詳細原因看「記錄」分頁。" + if (lg == "CPBL") "\nCPBL 為實驗性支援 (官網無公開介面), 改版可能失效。" else "", 14f, cBad)
        } else if (g.isEmpty()) {
            tv(c, "這天沒有比賽。", 14f, cSub)
        } else {
            for (x in g) {
                val r = row(c)
                r.gravity = android.view.Gravity.CENTER_VERTICAL
                r.setPadding(0, dp(8), 0, dp(8))
                val box = LinearLayout(act)
                box.orientation = LinearLayout.VERTICAL
                r.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
                tv(box, (if (x.lg == "SOC") "[" + ScoreSoccer.leagueName(x.id.substringBefore(':')) + "] " else "") + Teams.label(x.lg, x.a, x.an) + " @ " + Teams.label(x.lg, x.h, x.hn), 16f, cText, true)
                val st = when (x.status) { "L" -> "進行中"; "F" -> "已結束"; else -> "未開賽" }
                val stCol = when (x.status) { "L" -> cGood; "F" -> cSub; else -> cPrimary }
                val l2 = tv(box, fmt(x.startMs) + "  " + st, 13f, stCol)
                l2.setPadding(0, dp(2), 0, 0)
                if (x.aScore != null && x.hScore != null) {
                    val l3 = tv(box, x.aScore.toString() + " : " + x.hScore, 15f, cText, true)
                    l3.setPadding(0, dp(2), 0, 0)
                }
                val on = isTracked(x)
                val b = Button(act)
                b.isAllCaps = false
                b.text = if (on) "取消" else "追蹤"
                b.setOnClickListener {
                    if (on) {
                        tracked.removeAll { it.src == x.src && it.id == x.id }
                    } else if (tracked.size >= ScoreStore.MAX) {
                        toast("最多追蹤 " + ScoreStore.MAX + " 場")
                        return@setOnClickListener
                    } else tracked.add(x.tracked())
                    save()
                    render()
                }
                r.addView(b, LinearLayout.LayoutParams(dp(84), -2))
                val dv = android.view.View(act)
                dv.setBackgroundColor(0xFFE0E0E0.toInt())
                c.addView(dv, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        if (lg == "CPBL") tv(c, "提醒: CPBL 資料取自官網, 屬實驗性功能。", 12f, cSub).setPadding(0, dp(8), 0, 0)
    }
}
