package tw.deskctl

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 「助理」分頁: 和 Gemini 對話, 查看目前設定與狀態、請它提議設定 (要按「套用」才生效)、取得設定連結、
 * 查網路 (例如球隊中文名)。金鑰與模型在上方「設定」填寫。
 */
class AiPage(private val act: Activity, private val nav: (String) -> Unit) {
    val view = LinearLayout(act)
    private val chat = LinearLayout(act)
    private val scroll = ScrollView(act)
    private val input = EditText(act)
    private lateinit var sendBtn: Button
    private val settingsBox = LinearLayout(act)
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false
    private var thinking: TextView? = null

    private val cText = 0xFF212121.toInt()
    private val cSub = 0xFF616161.toInt()
    private val cPrimary = 0xFF1565C0.toInt()
    private val cGood = 0xFF2E7D32.toInt()
    private val cBad = 0xFFC62828.toInt()

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(dp(16), dp(10), dp(16), dp(8))

        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(act)
        title.text = "助理"
        title.textSize = 22f
        title.setTextColor(cText)
        title.setTypeface(null, Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        small(head, "設定") { settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        small(head, "清除對話") {
            AiAssistant.reset()
            chat.removeAllViews()
            welcome()
        }
        view.addView(head, LinearLayout.LayoutParams(-1, -2))

        buildSettings()
        view.addView(settingsBox, LinearLayout.LayoutParams(-1, -2))

        chat.orientation = LinearLayout.VERTICAL
        scroll.addView(chat, ViewGroup.LayoutParams(-1, -2))
        view.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 常用問題
        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        val chips = LinearLayout(act)
        chips.orientation = LinearLayout.HORIZONTAL
        hs.addView(chips)
        for (q in listOf("目前設定與狀態", "控制器連不上怎麼辦", "幫我設定快捷鍵", "查球隊的中文名", "怎麼讓音樂頁更順手")) {
            small(chips, q) { send(q) }
        }
        view.addView(hs, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        input.hint = "問問題或說想怎麼設定…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.maxLines = 4
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        sendBtn = small(row, "送出") { send(input.text.toString()) }
        view.addView(row, LinearLayout.LayoutParams(-1, -2))

        welcome()
        settingsBox.visibility = if (Prefs.aiKey(act).isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun small(parent: LinearLayout, t: String, onClick: () -> Unit): Button {
        val b = Button(act)
        b.text = t
        b.isAllCaps = false
        b.textSize = 13f
        b.setOnClickListener { onClick() }
        parent.addView(b, LinearLayout.LayoutParams(-2, -2))
        return b
    }

    private fun label(parent: ViewGroup, t: String, size: Float = 12f, color: Int = cSub): TextView {
        val v = TextView(act)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        parent.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    // ---------------- 設定 ----------------
    private fun buildSettings() {
        settingsBox.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = dp(12).toFloat()
        settingsBox.background = bg
        settingsBox.setPadding(dp(14), dp(10), dp(14), dp(10))
        label(settingsBox, "Gemini API 金鑰", 13f, cText).setTypeface(null, Typeface.BOLD)
        label(settingsBox, "到 aistudio.google.com/apikey 免費申請。金鑰只存在這支手機, 只會送給 Google。")
        val key = EditText(act)
        key.hint = "貼上金鑰"
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        key.setText(Prefs.aiKey(act))
        settingsBox.addView(key, LinearLayout.LayoutParams(-1, -2))
        label(settingsBox, "模型名稱 (預設 gemini-2.5-flash; 想更聰明可改 gemini-2.5-pro, 想更省可改 gemini-2.5-flash-lite)")
        val model = EditText(act)
        model.setText(Prefs.aiModel(act))
        settingsBox.addView(model, LinearLayout.LayoutParams(-1, -2))
        val cb = CheckBox(act)
        cb.text = "允許助理上網搜尋 (查球隊名等)"
        cb.isChecked = Prefs.aiSearch(act)
        settingsBox.addView(cb, LinearLayout.LayoutParams(-1, -2))
        label(settingsBox, "隱私: 只會把你的提問、目前設定與狀態送給 Gemini; 不會送出通知與訊息內容。助理只能「提議」變更, 要你按「套用」才會改。使用額度由你的 Google 帳號計算。")
        val b = small(settingsBox, "儲存") {
            Prefs.setAiKey(act, key.text.toString())
            Prefs.setAiModel(act, model.text.toString())
            Prefs.setAiSearch(act, cb.isChecked)
            settingsBox.visibility = View.GONE
            addNote("設定已儲存" + (if (Prefs.aiKey(act).isEmpty()) ", 但還沒有金鑰" else ""), cGood)
        }
        (b.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.END
    }

    // ---------------- 對話泡泡 ----------------
    private fun welcome() {
        addBubble(
            false,
            "我是設定助理。可以問我「目前設定是什麼」「控制器狀態如何」,或說「幫我把左鍵雙擊設成上一首」「GAL 是哪支球隊、中文怎麼叫」,我會提議變更讓你按套用,也能給你設定畫面的連結。"
        )
    }

    private fun addBubble(mine: Boolean, text: String): TextView {
        val t = TextView(act)
        t.text = text.replace("**", "").replace("### ", "").replace("## ", "").replace("# ", "")
        t.textSize = 15f
        t.setTextColor(if (mine) Color.WHITE else cText)
        t.setPadding(dp(12), dp(8), dp(12), dp(8))
        t.setTextIsSelectable(true)
        if (!mine) {
            t.autoLinkMask = Linkify.WEB_URLS
            t.movementMethod = LinkMovementMethod.getInstance()
        }
        val bg = GradientDrawable()
        bg.setColor(if (mine) cPrimary else Color.WHITE)
        bg.cornerRadius = dp(14).toFloat()
        t.background = bg
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.setMargins(if (mine) dp(48) else 0, dp(4), if (mine) 0 else dp(48), dp(4))
        chat.addView(t, lp)
        scrollDown()
        return t
    }

    private fun addNote(text: String, color: Int = cSub) {
        val t = label(chat, text, 12f, color)
        t.setPadding(dp(4), dp(2), dp(4), dp(2))
        if (text.contains("http")) {
            t.autoLinkMask = Linkify.WEB_URLS
            t.movementMethod = LinkMovementMethod.getInstance()
        }
        scrollDown()
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addPending(p: Pending) {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xFFFFF3E0.toInt())
        bg.cornerRadius = dp(12).toFloat()
        card.background = bg
        card.setPadding(dp(12), dp(8), dp(12), dp(8))
        label(card, "建議的變更", 12f, cSub)
        label(card, p.desc, 14f, cText)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        fun done(msg: String, color: Int) {
            card.removeView(row)
            label(card, msg, 13f, color).setTypeface(null, Typeface.BOLD)
        }
        small(row, "略過") { done("已略過", cSub) }
        small(row, "套用") {
            try {
                p.apply()
                done("✓ 已套用", cGood)
            } catch (e: Exception) {
                AppLog.w("AI", "套用失敗: $e")
                done("套用失敗", cBad)
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(4), 0, dp(4))
        chat.addView(card, lp)
        scrollDown()
    }

    private fun addLink(text: String, target: String) {
        val b = Button(act)
        b.text = "▶ $text"
        b.isAllCaps = false
        b.setOnClickListener { nav(target) }
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.gravity = Gravity.START
        chat.addView(b, lp)
        scrollDown()
    }

    // ---------------- 送出 ----------------
    private fun send(textRaw: String) {
        val text = textRaw.trim()
        if (text.isEmpty() || busy) return
        input.setText("")
        addBubble(true, text)
        busy = true
        sendBtn.isEnabled = false
        thinking = label(chat, "思考中…", 13f, cSub)
        scrollDown()
        val ctx = act.applicationContext
        Thread {
            val r = try {
                AiAssistant.run(ctx, text)
            } catch (e: Exception) {
                AppLog.w("AI", "助理錯誤: $e")
                TurnResult("", emptyList(), emptyList(), emptyList(), "發生錯誤: " + e.javaClass.simpleName)
            }
            ui.post {
                busy = false
                sendBtn.isEnabled = true
                thinking?.let { chat.removeView(it) }
                thinking = null
                if (r.error.isNotEmpty()) addNote(r.error, cBad)
                if (r.text.isNotEmpty()) addBubble(false, r.text)
                for (p in r.pendings) addPending(p)
                for (l in r.links) addLink(l.first, l.second)
                if (r.sources.isNotEmpty()) {
                    addNote("來源:\n" + r.sources.joinToString("\n") { it.first + "\n" + it.second })
                }
            }
        }.start()
    }
}
