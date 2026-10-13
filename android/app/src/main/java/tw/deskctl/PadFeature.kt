package tw.deskctl

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手機控制器: 控制器「手機控制」頁的按鍵事件 {"c":"pad","d":位置0~4,"e":手勢} 在這裡依目前模式執行。
 * 位置 0上 1右 2下 3左 4中央 (已扣掉控制器端的旋轉); 手勢 c單擊 d兩下 l長按開始 h長按重複。
 * 手機 → 控制器: {"k":"pad","n":模式名,"i":第幾組,"t":共幾組,"l":五個位置的短名稱,"g":有設兩下手勢的方向位元}
 */
class PadFeature(host: FeatureHost) : Feature(host) {
    override val id = "pad"

    private val ctx: Context = host.context
    private var seenVer = -1
    private var lastFail = 0L

    override fun info(): String {
        val p = PadStore.profile(ctx) ?: return ""
        return "手機控制模式: " + p.optString("n")
    }

    override fun onReady() {
        push()
    }

    override fun onTick(n: Int) {
        if (seenVer != PadStore.version) push()
    }

    private fun push() {
        seenVer = PadStore.version
        val p = PadStore.profile(ctx) ?: return
        val n = PadStore.load(ctx).length()
        val labels = JSONArray()
        var mask = 0
        for ((k, s) in PadStore.SLOTS.withIndex()) {
            labels.put(PadStore.label(ctx, PadStore.getAct(p, s, "c")))
            if (k < 4 && PadStore.getAct(p, s, "d") != null) mask = mask or (1 shl k)
        }
        host.send(
            JSONObject().put("k", "pad").put("n", p.optString("n").take(12)).put("i", PadStore.activeIndex(ctx))
                .put("t", n).put("l", labels).put("g", mask)
        )
    }

    override fun onCommand(c: String, o: JSONObject): Boolean {
        if (c != "pad") return false
        val e = o.optString("e")
        if (e == "hi") {
            push()
            return true
        }
        val d = o.optInt("d", -1)
        if (d < 0 || d >= PadStore.SLOTS.size) return true
        val p = PadStore.profile(ctx) ?: return true
        val slot = PadStore.SLOTS[d]
        val action: JSONObject? = when (e) {
            "c" -> PadStore.getAct(p, slot, "c")
            "d" -> PadStore.getAct(p, slot, "d")
            "l", "h" -> PadStore.getAct(p, slot, "h")
                ?: PadStore.getAct(p, slot, "c")?.takeIf { PadStore.repeatable(it) }
            else -> null
        }
        if (action != null) run(action)
        return true
    }

    private fun fail(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFail < 2000L) return
        lastFail = now
        AppLog.w("PAD", msg)
        host.send(JSONObject().put("k", "act").put("s", "fail").put("n", "手機控制").put("m", msg))
    }

    private fun xy(s: String): Pair<Float, Float> {
        val p = s.split(",")
        return Pair(p.getOrNull(0)?.trim()?.toFloatOrNull() ?: 50f, p.getOrNull(1)?.trim()?.toFloatOrNull() ?: 50f)
    }

    private fun needAcc(ok: Boolean) {
        if (!ok) fail("需要開啟無障礙服務")
    }

    private fun run(a: JSONObject) {
        val x = a.optString("a")
        val y = a.optString("b")
        try {
            when (a.optString("t")) {
                "scroll" -> needAcc(DeskAccess.scroll(x, y.toFloatOrNull() ?: 25f))
                "tap" -> {
                    val (px, py) = xy(x)
                    needAcc(DeskAccess.tap(px, py))
                }
                "long" -> {
                    val (px, py) = xy(x)
                    needAcc(DeskAccess.press(px, py, y.toLongOrNull() ?: 700L))
                }
                "swipe" -> {
                    val q = x.split(",").map { it.trim().toFloatOrNull() ?: 50f }
                    if (q.size >= 4) needAcc(DeskAccess.swipe(q[0], q[1], q[2], q[3], 350L))
                }
                "back" -> needAcc(DeskAccess.back())
                "home" -> needAcc(DeskAccess.home())
                "recents" -> needAcc(DeskAccess.recents())
                "notif" -> needAcc(DeskAccess.notifications())
                "vol" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dir = when (x) {
                        "up" -> AudioManager.ADJUST_RAISE
                        "down" -> AudioManager.ADJUST_LOWER
                        else -> AudioManager.ADJUST_TOGGLE_MUTE
                    }
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                }
                "media" -> {
                    val code = when (x) {
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    }
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                "app" -> {
                    val i = ctx.packageManager.getLaunchIntentForPackage(x)
                    if (i == null) fail("找不到 App") else if (!Launcher.start(ctx, i)) fail("無法開啟 App")
                }
                "shortcut" -> {
                    val idx = Shortcuts.load(ctx).indexOfFirst { it.name == x }
                    if (idx < 0) {
                        fail("沒有捷徑「" + x + "」")
                    } else {
                        val si = Intent(ctx, BridgeService::class.java)
                        si.action = BridgeService.ACTION_RUN
                        si.putExtra("i", idx)
                        ctx.startService(si)
                    }
                }
                "mode" -> {
                    val n = PadStore.load(ctx).length()
                    val cur = PadStore.activeIndex(ctx)
                    PadStore.setActive(ctx, if (x == "prev") cur - 1 else cur + 1)
                    push()
                    val name = PadStore.profile(ctx)?.optString("n") ?: ""
                    host.send(
                        JSONObject().put("k", "act").put("s", "done").put("n", "模式 " + name.take(10))
                            .put("m", (PadStore.activeIndex(ctx) + 1).toString() + "/" + n)
                    )
                }
            }
        } catch (e: Exception) {
            fail("錯誤 " + e.javaClass.simpleName)
        }
    }
}
