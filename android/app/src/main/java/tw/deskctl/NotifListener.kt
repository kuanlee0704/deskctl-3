package tw.deskctl

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 通知事件的轉接: NotifListener 收到通知 → 交給目前執行中的 MessageFeature。 */
object MsgBus {
    @Volatile var onPosted: ((StatusBarNotification) -> Unit)? = null
    @Volatile var onRemoved: ((StatusBarNotification) -> Unit)? = null
}

/**
 * 通知監聽服務: 使用者授權「通知存取」後, App 才能 (1) 讀取其他 App 的媒體工作階段 (歌名/進度)、
 * (2) 把手機通知轉發到控制器 (手機訊息功能)。
 */
class NotifListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            try {
                MsgCapture.posted(applicationContext, sbn)
            } catch (e: Exception) {
                AppLog.w("MSGDB", "收錄通知失敗: " + e)
            }
            try {
                MsgBus.onPosted?.invoke(sbn)
            } catch (e: Exception) {
                AppLog.w("MSG", "處理通知失敗: " + e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) {
            try {
                MsgCapture.removed(applicationContext, sbn)
            } catch (_: Exception) {
            }
            try {
                MsgBus.onRemoved?.invoke(sbn)
            } catch (_: Exception) {
            }
        }
    }
}
