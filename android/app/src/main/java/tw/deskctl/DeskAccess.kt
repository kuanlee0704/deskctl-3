package tw.deskctl

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 無障礙服務 (選用): 讓捷徑能「點擊畫面位置」「點擊含某文字的按鈕」「返回」。
 * 不讀取也不上傳任何畫面內容, 只在執行捷徑的步驟時使用。
 */
class DeskAccess : AccessibilityService() {

    companion object {
        @Volatile var instance: DeskAccess? = null

        fun tap(xPct: Float, yPct: Float): Boolean {
            val s = instance ?: return false
            return s.doTap(xPct, yPct)
        }

        fun clickText(t: String): Boolean {
            val s = instance ?: return false
            return s.doClickText(t)
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 350L): Boolean {
            val s = instance ?: return false
            return s.doSwipe(x1, y1, x2, y2, ms)
        }

        /** 依序嘗試多個文字, 點到任何一個就回傳 true */
        fun clickAny(vararg ts: String): Boolean {
            val s = instance ?: return false
            for (t in ts) if (s.doClickText(t)) return true
            return false
        }

        /** 精準比對: 文字或說明「整個等於」其中一個才點 (避免「播放」誤點到「播放清單」) */
        fun clickExact(vararg ts: String): Boolean {
            val s = instance ?: return false
            return s.doClickExact(ts)
        }

        /** 把目前畫面上看得到的項目寫進記錄 (除錯用: 看無障礙服務到底讀到什麼) */
        fun dump(tag: String) {
            val s = instance
            if (s == null) {
                AppLog.w(tag, "無障礙服務未連線")
                return
            }
            s.doDump(tag)
        }

        /** 點「搜尋結果裡第一部影片」: 找畫面上第一個又寬又高的可點項目 (略過廣告 / Shorts) */
        fun clickFirstVideo(): Boolean {
            val s = instance ?: return false
            return s.doClickFirstVideo()
        }

        /** 往某方向滾動一格 (u/d/l/r, pct = 滑動距離占畫面的百分比); 上一筆手勢還沒跑完就略過這一次 */
        fun scroll(dir: String, pct: Float): Boolean {
            val s = instance ?: return false
            return s.doScroll(dir, pct)
        }

        /** 長按畫面某位置 */
        fun press(xPct: Float, yPct: Float, ms: Long): Boolean {
            val s = instance ?: return false
            return s.doPress(xPct, yPct, ms)
        }

        fun notifications(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        }

        fun recents(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        fun home(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        fun back(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i("ACC", "無障礙服務已連線")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun realMetrics(): android.util.DisplayMetrics {
        val dm = android.util.DisplayMetrics()
        val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun doTap(xp: Float, yp: Float): Boolean {
        val dm = realMetrics()
        val p = Path()
        p.moveTo(dm.widthPixels * xp / 100f, dm.heightPixels * yp / 100f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 80L))
            .build()
        return dispatchGesture(g, null, null)
    }

    @Volatile private var scrollBusyUntil = 0L

    private fun doScroll(dir: String, pct0: Float): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < scrollBusyUntil) return true
        val pct = pct0.coerceIn(5f, 70f) / 100f
        val dm = realMetrics()
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            "d" -> { p.moveTo(w * 0.5f, h * 0.70f); p.lineTo(w * 0.5f, h * (0.70f - pct)) }
            "u" -> { p.moveTo(w * 0.5f, h * 0.30f); p.lineTo(w * 0.5f, h * (0.30f + pct)) }
            "r" -> { p.moveTo(w * 0.75f, h * 0.5f); p.lineTo(w * (0.75f - pct), h * 0.5f) }
            else -> { p.moveTo(w * 0.25f, h * 0.5f); p.lineTo(w * (0.25f + pct), h * 0.5f) }
        }
        val dur = 220L
        scrollBusyUntil = now + dur + 40L
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, dur))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun doPress(xp: Float, yp: Float, ms: Long): Boolean {
        val dm = realMetrics()
        val p = Path()
        p.moveTo(dm.widthPixels * xp / 100f, dm.heightPixels * yp / 100f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, ms.coerceIn(100L, 5000L)))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun doSwipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val dm = realMetrics()
        val p = Path()
        p.moveTo(dm.widthPixels * x1 / 100f, dm.heightPixels * y1 / 100f)
        p.lineTo(dm.widthPixels * x2 / 100f, dm.heightPixels * y2 / 100f)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, ms))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun doClickFirstVideo(): Boolean {
        val root = rootInActiveWindow ?: return false
        val dm = realMetrics()
        val w = dm.widthPixels
        val h = dm.heightPixels
        val skip = arrayOf("shorts", "短片", "廣告", "sponsor", "ad ·", "赞助", "贊助", "playlist", "播放清單", "mix")
        val found = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        val q = ArrayList<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        val r = android.graphics.Rect()
        while (q.isNotEmpty() && n < 4000) {
            val node = q.removeAt(0)
            n++
            if (node.isClickable && node.isVisibleToUser) {
                node.getBoundsInScreen(r)
                val label = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
                if (r.width() >= w * 0.55 && r.height() >= h * 0.10 && r.top >= h * 0.10 && r.bottom <= h * 0.98 &&
                    skip.none { label.contains(it) }
                ) {
                    found.add(Pair(r.top, node))
                }
            }
            for (i in 0 until node.childCount) {
                val ch = node.getChild(i)
                if (ch != null) q.add(ch)
            }
        }
        val best = found.minByOrNull { it.first }?.second ?: return false
        if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        best.getBoundsInScreen(r)
        return doTap(r.centerX() * 100f / w, r.centerY() * 100f / h)
    }

    private fun doClickExact(ts: Array<out String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = ArrayList<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 4000) {
            val node = q.removeAt(0)
            n++
            val a = node.text?.toString()?.trim() ?: ""
            val b = node.contentDescription?.toString()?.trim() ?: ""
            if (ts.any { it.equals(a, true) || it.equals(b, true) }) {
                var c: AccessibilityNodeInfo? = node
                while (c != null) {
                    if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    c = c.parent
                }
            }
            for (i in 0 until node.childCount) {
                val ch = node.getChild(i)
                if (ch != null) q.add(ch)
            }
        }
        return false
    }

    private fun doDump(tag: String) {
        val root = rootInActiveWindow
        if (root == null) {
            AppLog.w(tag, "讀不到畫面內容 (rootInActiveWindow = null)")
            return
        }
        val dm = realMetrics()
        val r = android.graphics.Rect()
        val q = ArrayList<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        var shown = 0
        AppLog.w(tag, "畫面來自 " + (root.packageName ?: "?"))
        while (q.isNotEmpty() && n < 4000 && shown < 40) {
            val node = q.removeAt(0)
            n++
            val t = (node.text?.toString() ?: "").take(30)
            val d = (node.contentDescription?.toString() ?: "").take(40)
            if ((t.isNotEmpty() || d.isNotEmpty() || node.isClickable) && node.isVisibleToUser) {
                node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    shown++
                    AppLog.w(
                        tag,
                        (if (node.isClickable) "[點]" else "[ ]") + " " + t + (if (d.isNotEmpty()) " | " + d else "") +
                            "  @" + (r.centerX() * 100 / dm.widthPixels) + "," + (r.centerY() * 100 / dm.heightPixels) +
                            " 寬" + (r.width() * 100 / dm.widthPixels) + "% 高" + (r.height() * 100 / dm.heightPixels) + "%"
                    )
                }
            }
            for (i in 0 until node.childCount) {
                val ch = node.getChild(i)
                if (ch != null) q.add(ch)
            }
        }
        AppLog.w(tag, "共 " + n + " 個節點, 列出 " + shown + " 項")
    }

    private fun doClickText(t: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = ArrayList<AccessibilityNodeInfo>()
        q.add(root)
        var n = 0
        while (q.isNotEmpty() && n < 3000) {
            val node = q.removeAt(0)
            n++
            val label = (node.text?.toString() ?: "") + "|" + (node.contentDescription?.toString() ?: "")
            if (label.contains(t, ignoreCase = true)) {
                var c: AccessibilityNodeInfo? = node
                while (c != null) {
                    if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    c = c.parent
                }
            }
            for (i in 0 until node.childCount) {
                val ch = node.getChild(i)
                if (ch != null) q.add(ch)
            }
        }
        return false
    }
}
