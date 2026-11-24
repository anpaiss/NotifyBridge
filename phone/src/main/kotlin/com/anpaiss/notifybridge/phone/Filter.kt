package com.anpaiss.notifybridge.phone

import android.content.Context

/**
 * What goes to the Karoo. Only the apps switched on pass: an app shows up in the
 * list the first time it posts a notification, switched off. Calls have their
 * own switch, whatever app rings. First run: WhatsApp, SMS and calls on.
 * In the apps of [CHAT_APPS] each person and group is chosen too, by the chat's
 * fixed id (it survives a rename): a chat appears switched off at its first message.
 */
class Filter(ctx: Context) {

    companion object {
        private val DEFAULT_APPS = setOf(
            "com.whatsapp",
            "com.samsung.android.messaging",        // Samsung Messages
            "com.google.android.apps.messaging",    // Google Messages
        )
        /** Apps whose notifications carry a fixed chat id and a group flag, checked on the phone.
         *  Slack: the id of the DM (D…) or channel (C…), a channel's name as conversation title. */
        val CHAT_APPS = setOf("com.whatsapp", "com.Slack")
    }

    private val prefs = ctx.getSharedPreferences("filter", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains("allowed")) prefs.edit()
            .putStringSet("allowed", DEFAULT_APPS)
            .putStringSet("seen", DEFAULT_APPS)
            .putBoolean("calls", true)
            .apply()
    }

    var calls: Boolean
        get() = prefs.getBoolean("calls", true)
        set(v) = prefs.edit().putBoolean("calls", v).apply()

    /** Apps that posted at least one notification, plus the defaults. */
    val seen: Set<String> get() = prefs.getStringSet("seen", emptySet())!!

    fun allowed(pkg: String) = pkg in prefs.getStringSet("allowed", emptySet())!!

    fun setAllowed(pkg: String, on: Boolean) {
        val s = prefs.getStringSet("allowed", emptySet())!!.toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        prefs.edit().putStringSet("allowed", s).apply()
    }

    /** Records an app the first time it posts, so it appears in the list. */
    fun saw(pkg: String) {
        val s = seen
        if (pkg !in s) prefs.edit().putStringSet("seen", s + pkg).apply()
    }

    /** A person or group of an app in [CHAT_APPS]; [key] is "pkg|chat id". */
    data class Chat(val key: String, val name: String, val group: Boolean)

    fun chats(pkg: String): List<Chat> = prefs.getStringSet("chats_seen", emptySet())!!
        .filter { it.startsWith("$pkg|") }
        .map { Chat(it, prefs.getString("name:$it", it)!!, prefs.getBoolean("group:$it", false)) }

    fun chatAllowed(key: String) = key in prefs.getStringSet("chats_allowed", emptySet())!!

    fun setChatAllowed(key: String, on: Boolean) {
        val s = prefs.getStringSet("chats_allowed", emptySet())!!.toMutableSet()
        if (on) s.add(key) else s.remove(key)
        prefs.edit().putStringSet("chats_allowed", s).apply()
    }

    /** Records a chat the first time it writes, and keeps its name up to date. */
    fun sawChat(key: String, name: String, group: Boolean) {
        val s = prefs.getStringSet("chats_seen", emptySet())!!
        if (key in s && prefs.getString("name:$key", null) == name) return
        prefs.edit().putStringSet("chats_seen", s + key).putString("name:$key", name).putBoolean("group:$key", group).apply()
    }
}
