package com.anpaiss.notifybridge.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Every new notification of another app goes to the Karoo through [BridgeService]. */
class Listener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(BridgeService.TAG, "notification access active")
        if (BridgeService.instance == null) runCatching { BridgeService.start(this) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        Log.i(BridgeService.TAG, "notification from ${sbn.packageName}")
        BridgeService.send(app, title, text)
    }
}
