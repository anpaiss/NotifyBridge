package com.anpaiss.notifybridge.phone

import android.content.Context

/**
 * What goes to the Karoo. Only the apps switched on pass: an app shows up in the
 * list the first time it posts a notification, switched off. Calls have their
 * own switch, whatever app rings. First run: WhatsApp, SMS and calls on.
 */
class Filter(ctx: Context) {

    companion object {
        private val DEFAULT_APPS = setOf(
            "com.whatsapp",
            "com.samsung.android.messaging",        // Samsung Messages
            "com.google.android.apps.messaging",    // Google Messages
        )
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
}
