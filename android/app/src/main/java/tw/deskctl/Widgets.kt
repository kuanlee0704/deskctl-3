package tw.deskctl

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 桌面小工具 (四種, 可同時放多個):
 *  1. 開關   1x1  點一下啟動連線 (搜尋並連上控制器), 再點一下停止; 顏色=灰 已停止 / 橘 連線中 / 綠 已連線
 *  2. 控制列 4x1  開關 · 重新連線 · 訊息 (未讀數, 點開訊息資料庫) · 清理控制器記憶體
 *  3. 訊息   2x2  最新幾則訊息, 點開訊息資料庫 (不用啟動原本的 App)
 *  4. 捷徑   4x1  前四個捷徑一鍵執行 (連線中才能執行)
 * 狀態改變 (連線、斷線、啟停、新訊息、已讀) 時由 Widgets.updateAll() 更新全部小工具。
 * 新增一種小工具: 寫一個 BaseWidget 子類別 + layout + xml/xxx_info, 在 Manifest 登記, 並加進 providers 與 build()。
 */
object Widgets {
    const val ACT_TOGGLE = "tw.deskctl.W_TOGGLE"
    const val ACT_RECONNECT = "tw.deskctl.W_RECONNECT"
    const val ACT_MEMCLEAN = "tw.deskctl.W_MEMCLEAN"
    const val ACT_RUN = "tw.deskctl.W_RUN"

    private val providers: List<Class<out AppWidgetProvider>> =
        listOf(ToggleWidget::class.java, BarWidget::class.java, MsgWidget::class.java, ScWidget::class.java)

    private var lastKey = ""

    /** 目前連線狀態: 文字與底色 */
    private fun stateLabel(): String = when {
        BridgeService.connected -> "已連線"
        BridgeService.running && BridgeService.paused -> "休息中"
        BridgeService.running -> "連線中"
        else -> "已停止"
    }

    private fun stateBg(): Int = when {
        BridgeService.connected -> R.drawable.widget_bg_on
        BridgeService.running -> R.drawable.widget_bg_wait
        else -> R.drawable.widget_bg_off
    }

    fun updateAll(ctx: Context, force: Boolean = false) {
        try {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val unread = MsgDb.unread(app)
            val top = MsgDb.query(app, "", null, false, 4)
            val key = stateLabel() + "|" + unread + "|" + top.joinToString("|") { it.id.toString() + (if (it.read) "r" else "u") } +
                "|" + Shortcuts.version
            if (!force && key == lastKey) return
            lastKey = key
            for (cls in providers) {
                val ids = mgr.getAppWidgetIds(ComponentName(app, cls))
                if (ids.isEmpty()) continue
                mgr.updateAppWidget(ids, build(app, cls, unread, top))
            }
        } catch (e: Exception) {
            AppLog.w("WIDGET", "更新小工具失敗: $e")
        }
    }

    private fun pi(ctx: Context, cls: Class<*>, act: String, i: Int = 0): PendingIntent {
        val intent = Intent(ctx, cls).setAction(act).putExtra("i", i)
        intent.data = Uri.parse("deskctl://widget/$act/$i")        // 讓每個按鈕的 PendingIntent 彼此不同
        return PendingIntent.getBroadcast(ctx, (act.hashCode() * 31 + i), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openMsgs(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MessagesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openMain(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 78, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun build(ctx: Context, cls: Class<out AppWidgetProvider>, unread: Int, top: List<Msg>): RemoteViews {
        return when (cls) {
            ToggleWidget::class.java -> buildToggle(ctx, cls)
            BarWidget::class.java -> buildBar(ctx, cls, unread)
            MsgWidget::class.java -> buildMsgs(ctx, unread, top)
            else -> buildSc(ctx, cls)
        }
    }

    private fun buildToggle(ctx: Context, cls: Class<*>): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_toggle)
        rv.setInt(R.id.w_root, "setBackgroundResource", stateBg())
        rv.setTextViewText(R.id.w_state, stateLabel())
        rv.setTextViewText(R.id.w_hint, if (BridgeService.running) "點一下停止" else "點一下啟動")
        rv.setOnClickPendingIntent(R.id.w_root, pi(ctx, cls, ACT_TOGGLE))
        return rv
    }

    private fun buildBar(ctx: Context, cls: Class<*>, unread: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_bar)
        rv.setInt(R.id.b1_root, "setBackgroundResource", stateBg())
        rv.setTextViewText(R.id.b1_top, if (BridgeService.running) "停止" else "啟動")
        rv.setTextViewText(R.id.b1_sub, stateLabel())
        rv.setOnClickPendingIntent(R.id.b1_root, pi(ctx, cls, ACT_TOGGLE))
        rv.setTextViewText(R.id.b2_top, "重連")
        rv.setTextViewText(R.id.b2_sub, "重新搜尋")
        rv.setOnClickPendingIntent(R.id.b2_root, pi(ctx, cls, ACT_RECONNECT))
        rv.setTextViewText(R.id.b3_top, if (unread > 0) "訊息 $unread" else "訊息")
        rv.setTextViewText(R.id.b3_sub, if (unread > 0) "未讀" else "資料庫")
        rv.setOnClickPendingIntent(R.id.b3_root, openMsgs(ctx))
        rv.setTextViewText(R.id.b4_top, "清理")
        rv.setTextViewText(R.id.b4_sub, "控制器記憶體")
        rv.setOnClickPendingIntent(R.id.b4_root, pi(ctx, cls, ACT_MEMCLEAN))
        return rv
    }

    private fun buildMsgs(ctx: Context, unread: Int, top: List<Msg>): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_msgs)
        rv.setTextViewText(R.id.m_head, if (unread > 0) "手機訊息  ·  $unread 則未讀" else "手機訊息")
        val ids = intArrayOf(R.id.m_r1, R.id.m_r2, R.id.m_r3, R.id.m_r4)
        for ((n, id) in ids.withIndex()) {
            val m = top.getOrNull(n)
            if (m == null) {
                rv.setTextViewText(id, if (n == 0) "還沒有訊息 (需開啟通知存取並選擇 App)" else "")
            } else {
                val line = (if (m.read) "" else "● ") + m.app + "  " + (if (m.title.isNotEmpty()) m.title + ": " else "") + m.body.replace('\n', ' ')
                rv.setTextViewText(id, line)
            }
        }
        rv.setOnClickPendingIntent(R.id.m_root, openMsgs(ctx))
        return rv
    }

    private fun buildSc(ctx: Context, cls: Class<*>): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_sc)
        val list = try { Shortcuts.load(ctx) } catch (_: Exception) { ArrayList<Shortcut>() }
        val tops = intArrayOf(R.id.s1_top, R.id.s2_top, R.id.s3_top, R.id.s4_top)
        val subs = intArrayOf(R.id.s1_sub, R.id.s2_sub, R.id.s3_sub, R.id.s4_sub)
        val roots = intArrayOf(R.id.s1_root, R.id.s2_root, R.id.s3_root, R.id.s4_root)
        for (n in 0 until 4) {
            val s = list.getOrNull(n)
            if (s == null) {
                rv.setTextViewText(tops[n], if (n == 0) "尚無捷徑" else "")
                rv.setTextViewText(subs[n], if (n == 0) "到 App 新增" else "")
                rv.setOnClickPendingIntent(roots[n], openMain(ctx))
            } else {
                rv.setTextViewText(tops[n], s.name.take(6))
                rv.setTextViewText(subs[n], if (BridgeService.connected) "執行" else "需連線")
                rv.setOnClickPendingIntent(roots[n], pi(ctx, cls, ACT_RUN, n))
            }
        }
        return rv
    }

    // ---------------- 按鈕動作 ----------------
    private fun hasBt(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startSvc(ctx: Context, action: String): Boolean {
        if (!hasBt(ctx)) {
            Toast.makeText(ctx, "請先開啟 App 完成藍牙授權", Toast.LENGTH_LONG).show()
            try { openMain(ctx).send() } catch (_: Exception) { }
            return false
        }
        return try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BridgeService::class.java).setAction(action))
            true
        } catch (e: Exception) {
            AppLog.e("WIDGET", "啟動服務失敗: $e")
            Toast.makeText(ctx, "啟動失敗: " + e.message, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun sendSvc(ctx: Context, action: String, i: Int = -1) {
        try {
            val intent = Intent(ctx, BridgeService::class.java).setAction(action)
            if (i >= 0) intent.putExtra("i", i)
            ctx.startService(intent)
        } catch (e: Exception) {
            AppLog.e("WIDGET", "傳送指令失敗: $e")
        }
    }

    fun handle(ctx: Context, intent: Intent) {
        AppLog.init(ctx)
        when (intent.action) {
            ACT_TOGGLE -> {
                if (BridgeService.running) {
                    AppLog.i("WIDGET", "小工具: 停止")
                    sendSvc(ctx, BridgeService.ACTION_STOP)
                } else {
                    AppLog.i("WIDGET", "小工具: 啟動")
                    startSvc(ctx, BridgeService.ACTION_START)
                }
            }
            ACT_RECONNECT -> {
                if (BridgeService.running) sendSvc(ctx, BridgeService.ACTION_RECONNECT)
                else startSvc(ctx, BridgeService.ACTION_START)
            }
            ACT_MEMCLEAN -> {
                if (BridgeService.connected) sendSvc(ctx, BridgeService.ACTION_MEMCLEAN)
                else Toast.makeText(ctx, "控制器尚未連線", Toast.LENGTH_SHORT).show()
            }
            ACT_RUN -> {
                if (BridgeService.connected) sendSvc(ctx, BridgeService.ACTION_RUN, intent.getIntExtra("i", -1))
                else Toast.makeText(ctx, "控制器尚未連線, 無法執行捷徑", Toast.LENGTH_SHORT).show()
            }
        }
        updateAll(ctx, true)
    }
}

/** 四種小工具共用: 收到系統更新或我們自己的按鈕動作就交給 Widgets */
open class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Widgets.updateAll(context, true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val a = intent.action ?: return
        if (a.startsWith("tw.deskctl.W_")) Widgets.handle(context, intent)
    }
}

class ToggleWidget : BaseWidget()
class BarWidget : BaseWidget()
class MsgWidget : BaseWidget()
class ScWidget : BaseWidget()
