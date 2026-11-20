package com.anpaiss.notifybridge.phone

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

/** Start the bridge, open the notification access settings, send a test, and choose what goes to the Karoo. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var apps: LinearLayout
    private lateinit var filter: Filter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = Filter(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action(); refresh() } }
        col.addView(status)
        col.addView(button("Start bridge") {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS), 1)
            BridgeService.start(this)
        })
        col.addView(button("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        // Android 13+ greys out notification access for an app installed from an APK until it is allowed here
        col.addView(button("App info (allow restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("Send test") { BridgeService.send("NotifyBridge", "Test", "Hello Karoo, this is a test from the phone") })
        col.addView(button("Refresh") {})
        col.addView(TextView(this).apply {
            textSize = 18f
            setPadding(0, 48, 0, 8)
            text = "Sent to the Karoo\nAn app, and on WhatsApp a person or group, appears here after its first message, switched off."
        })
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(apps)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = "Bridge: ${BridgeService.status}\n" + if (access) "Notification access: granted ✓"
        else "Notification access: MISSING. Tap Notification access and turn NotifyBridge on. If its switch is greyed " +
            "out (\"Restricted setting\"): App info, the ⋮ menu at the top right, Allow restricted settings, then try again."
        apps.removeAllViews()
        apps.addView(Switch(this).apply {
            text = "Calls"; textSize = 18f; isChecked = filter.calls
            setOnCheckedChangeListener { _, on -> filter.calls = on }
        })
        // Apps by name; the ones not installed (a default SMS app of another brand) are left out
        filter.seen.mapNotNull { pkg ->
            runCatching { pkg to packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }.sortedBy { it.second.lowercase() }.forEach { (pkg, label) ->
            apps.addView(Switch(this).apply {
                text = label; textSize = 18f; isChecked = filter.allowed(pkg)
                setOnCheckedChangeListener { _, on -> filter.setAllowed(pkg, on); refresh() }
            })
            // The people and groups of WhatsApp, indented under it while the app is on
            if (filter.allowed(pkg)) filter.chats(pkg).sortedBy { it.name.lowercase() }.forEach { c ->
                apps.addView(Switch(this).apply {
                    text = if (c.group) "${c.name} · group" else c.name
                    textSize = 16f; setPadding(64, 0, 0, 0); isChecked = filter.chatAllowed(c.key)
                    setOnCheckedChangeListener { _, on -> filter.setChatAllowed(c.key, on) }
                })
            }
        }
    }
}
