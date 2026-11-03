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
        // WhatsApp: the person or group, by the fixed id of the chat; a group's name is the conversation title.
        // Only the chosen chats pass: a notification with no chat id is not one of them
        val chatApp = !call && sbn.packageName in Filter.CHAT_APPS
        val chat = if (chatApp) n.shortcutId?.let { "${sbn.packageName}|$it" } else null
        if (chat != null) filter.sawChat(chat,
            n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: title,
            n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        val blocked = if (call) !filter.calls else !filter.allowed(sbn.packageName) || (chatApp && (chat == null || !filter.chatAllowed(chat)))
        if (blocked) { FileLog.i("blocked: ${chat ?: sbn.packageName}${if (chatApp && chat == null) " (no chat: $title)" else ""}"); return }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        FileLog.i("notification from ${sbn.packageName}${if (call) " (call)" else ""}")
        BridgeService.send(app, title, text, call)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        callsSent.remove(sbn.key)
    }
}
