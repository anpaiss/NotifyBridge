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
        if (sbn.packageName == SLACK && debuggable) slackShape(sbn)
        val call =n.category == Notification.CATEGORY_CALL && sbn.isOngoing
        if (sbn.packageName == packageName || (sbn.isOngoing && !call)) return
        if (call && !callsSent.add(sbn.key)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        if (!call) filter.saw(sbn.packageName)
        // WhatsApp, Slack: the person, group or channel, by the fixed id of the chat; a group's name is the conversation title.
        // Only the chosen chats pass: a notification with no chat id is not one of them
        val chatApp = !call && sbn.packageName in Filter.CHAT_APPS
        val chat = if (chatApp) n.shortcutId?.let { "${sbn.packageName}|$it" } else null
        if (chat != null) filter.sawChat(chat,
            chatName(sbn.packageName, n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: title),
            n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
        val blocked = if (call) !filter.calls else !filter.allowed(sbn.packageName) || (chatApp && (chat == null || !filter.chatAllowed(chat)))
        if (blocked) { FileLog.i("blocked: ${chat ?: sbn.packageName}${if (chatApp && chat == null) " (no chat: $title)" else ""}"); return }
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName)
        FileLog.i("notification from ${sbn.packageName}${if (call) " (call)" else ""}")
        BridgeService.send(app, title, text, call)
    }

    // Slack: a channel's thread has the channel's id but a title of its own, "Thread in #name" in the phone's
    // language: keep "#name", so the chat's name does not change with each message (a channel name has no '#')
    private fun chatName(pkg: String, name: String) =
        if (pkg == SLACK && '#' in name) name.substring(name.lastIndexOf('#')) else name

    private val debuggable by lazy { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }

    // Diagnostics, debug builds and Slack only: the shape of each notification, never its words. Whether a chat id
    // (shortcutId) comes and stays the same per channel/DM decides if Slack can join Filter.CHAT_APPS;
    // key, postTime and the alert-once flag show whether Slack reposts the same conversation
    private fun slackShape(sbn: StatusBarNotification) {
        val n = sbn.notification
        val x = n.extras
        fun has(k: String) = if (x.getCharSequence(k).isNullOrEmpty()) "-" else "y"
        // The typed getters need API 33, minSdk is 31
        @Suppress("DEPRECATION") val msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = msgs?.lastOrNull() as? android.os.Bundle
        @Suppress("DEPRECATION") val person = last?.getParcelable<android.app.Person>("sender_person")
        val sender = when {
            last == null -> "none"
            person != null -> "person"
            !last.getCharSequence("sender").isNullOrEmpty() -> "name"
            else -> "empty"
        }
        FileLog.i("slack shape: key=${sbn.key} id=${sbn.id} tag=${sbn.tag != null} post=${sbn.postTime}" +
            " ongoing=${sbn.isOngoing} summary=${n.flags and Notification.FLAG_GROUP_SUMMARY != 0}" +
            " alertOnce=${n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0}" +
            " category=${n.category} channel=${n.channelId} group=${n.group} shortcut=${n.shortcutId}" +
            " style=${x.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')}" +
            " title=${has(Notification.EXTRA_TITLE)} text=${has(Notification.EXTRA_TEXT)}" +
            " convTitle=${has(Notification.EXTRA_CONVERSATION_TITLE)} isGroup=${x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)}" +
            " messages=${msgs?.size ?: 0} lastSender=$sender")
    }

    private companion object { const val SLACK = "com.Slack" }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        callsSent.remove(sbn.key)
    }
}
