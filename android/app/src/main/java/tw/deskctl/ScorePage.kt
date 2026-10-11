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
        parent.addView(b, LinearLayout.LayoutParams(0, -2, weight))
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
        Thread {
            val r = try {
                ScoreApi.list(l, d)
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

    private fun isTracked(g: GameInfo) = tracked.any { it.src == g.src && it.id == g.id }

    private fun render() {
        col.removeAllViews()
        tv(col, "比分板", 22f, cText, true)
        val sub = tv(col, "選擇要追蹤的比賽, 資料由手機抓取後傳給控制器。控制器上左右鍵切換比賽、上下鍵切換主畫面/細節。", 13f, cSub)
        sub.setPadding(0, 0, 0, dp(12))

        // ----- 追蹤清單 -----
        val tc = card()
        tv(tc, "追蹤中 (" + tracked.size + "/" + ScoreStore.MAX + ")", 16f, cText, true)
        if (tracked.isEmpty()) tv(tc, "還沒有追蹤任何比賽, 從下方選擇。", 13f, cSub)
        for (i in tracked.indices) {
            val t = tracked[i]
            tv(tc, (i + 1).toString() + ". [" + t.lg + "] " + t.a + " @ " + t.h + "  (" + fmt(t.startMs) + ")", 14f, cText, true).setPadding(0, dp(8), 0, 0)
            ScoreFeature.summary[i]?.let { tv(tc, "   " + it, 13f, cGood) }
            val r = row(tc)
            btn(r, "↑") { if (i > 0) { tracked.add(i - 1, tracked.removeAt(i)); save(); render() } }
            btn(r, "↓") { if (i < tracked.size - 1) { tracked.add(i + 1, tracked.removeAt(i)); save(); render() } }
            btn(r, "移除") { tracked.removeAt(i); save(); render() }
        }

        // ----- 選比賽 -----
        val c = card()
        tv(c, "新增比賽", 16f, cText, true)
        val lr = row(c)
        for (x in listOf("MLB", "NBA", "CPBL")) {
            btn(lr, if (x == lg) "● $x" else x, x == lg) {
                if (x != lg) { lg = x; load() }
            }
        }
        val dr = row(c)
        btn(dr, "◀ 前一天") { date = date.minusDays(1); load() }
        btn(dr, "今天") { date = LocalDate.now(tpe); load() }
        btn(dr, "後一天 ▶") { date = date.plusDays(1); load() }
        tv(c, date.toString() + " (台灣時間)", 13f, cSub).setPadding(0, dp(6), 0, dp(6))

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
                r.setPadding(0, dp(6), 0, 0)
                val tx = TextView(act)
                val score = if (x.aScore != null && x.hScore != null) "  ${x.aScore}:${x.hScore}" else ""
                val st = when (x.status) { "L" -> " 進行中"; "F" -> " 結束"; else -> "" }
                tx.text = x.a + " @ " + x.h + "\n" + fmt(x.startMs) + st + score
                tx.textSize = 14f
                tx.setTextColor(cText)
                r.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
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
                r.addView(b, LinearLayout.LayoutParams(-2, -2))
            }
        }
        if (lg == "CPBL") tv(c, "提醒: CPBL 資料取自官網, 屬實驗性功能。", 12f, cSub).setPadding(0, dp(8), 0, 0)
    }
}
