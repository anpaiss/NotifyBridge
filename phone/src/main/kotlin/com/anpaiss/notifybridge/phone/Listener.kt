package com.anpaiss.notifybridge.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Every new notification of the apps chosen in [Filter] goes to the Karoo through [BridgeService]. */
class Listener : NotificationListenerService() {

    // Incoming calls are ongoing and repost while ringing: each one goes out once
    private val callsSent = mutableSetOf<String>()
    private val filter by lazy { Filter(this) }

    override fun onListenerConnected() {
        FileLog.init(this)
        FileLog.i("notification access active")
        if (BridgeService.instance == null) runCatching { BridgeService.start(this) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val call = n.category == Notification.CATEGORY_CALL && sbn.isOngoing
        if (sbn.packageName == packageName || (sbn.isOngoing && !call)) return
        if (call && !callsSent.add(sbn.key)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        if (!call) filter.saw(sbn.packageName)
        if (if (call) !filter.calls else !filter.allowed(sbn.packageName)) { FileLog.i("blocked: ${sbn.packageName}"); return }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        FileLog.i("notification from ${sbn.packageName}${if (call) " (call)" else ""}")
        BridgeService.send(app, title, text, call)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        callsSent.remove(sbn.key)
    }
}
