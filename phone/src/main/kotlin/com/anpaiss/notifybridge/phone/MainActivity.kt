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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

/** Start the bridge, open the notification access settings, send a test, and choose what goes to the Karoo. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var apps: LinearLayout
    private lateinit var filter: Filter
    private lateinit var why: TextView
    private var pairing: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = Filter(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action(); refresh() } }
        col.addView(status)
        // Why pairing: shown until a Karoo is paired
        why = TextView(this).apply {
            textSize = 15f; setPadding(24, 16, 24, 16); setBackgroundColor(0x22FF5823)
            text = "Why pair? Bluetooth reaches anyone nearby. Pairing gives this phone and your Karoo a secret key " +
                "of their own: every message travels encrypted, and only your Karoo can read it."
        }
        col.addView(why)
        col.addView(button("Pair a Karoo") {
            val s = BridgeService.instance ?: run { status.text = "Tap Start bridge first"; return@button }
            fun open() {
                s.startPairing()
                pairing = AlertDialog.Builder(this).setTitle("Pairing")
                    .setMessage("On the Karoo, open NotifyBridge and tap PAIR WITH PHONE (within 2 minutes).")
                    .setNegativeButton("Cancel", null).show()
            }
            // Already paired: a tap by mistake must not start a new pairing; the old key stays until a new one succeeds
            if (PairStore(this).count == 0) open()
            else AlertDialog.Builder(this).setTitle("Already paired")
                .setMessage("A Karoo is already paired with this phone. Pair a Karoo again, or another one?")
                .setPositiveButton("Pair again") { _, _ -> open() }
                .setNegativeButton("Cancel", null).show()
        })
        // Already granted: start now; otherwise once the user answers (onRequestPermissionsResult)
        col.addView(button("Start bridge") {
            if (BridgeService.canStart(this)) BridgeService.start(this)
            else requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS), 1)
        })
        col.addView(button("Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
        // Android 13+ greys out notification access for an app installed from an APK until it is allowed here
        col.addView(button("App info (allow restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("Send test") { BridgeService.send("NotifyBridge", "Test", "Hello Karoo, this is a test from the phone") })
        col.addView(button("Refresh") {})
        col.addView(button("Forget paired Karoos") {
            confirmDanger("The keys of every paired Karoo are deleted from this phone. No message reaches a Karoo " +
                "until you pair it again, on the phone and on the Karoo.", "FORGET") {
                BridgeService.instance?.forgetAll(); refresh()
            }
        })
        col.addView(TextView(this).apply {
            textSize = 18f
            setPadding(0, 48, 0, 8)
            text = "Sent to the Karoo\nAn app, and on WhatsApp or Slack a person, group or channel, appears here after its first message, switched off."
        })
        apps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(apps)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // The code of a pairing started here, to compare with the Karoo's
        BridgeService.onPairCode = { address, code ->
            pairing?.dismiss()
            pairing = AlertDialog.Builder(this).setTitle("Pairing")
                .setMessage("Check that the Karoo shows the same code. The same code on both means no one in between: " +
                    "the key is shared only by these two.\n\n$code")
                .setPositiveButton("Same code: pair") { _, _ -> BridgeService.instance?.answerPairing(address, true); refresh() }
                .setNegativeButton("Cancel") { _, _ -> BridgeService.instance?.answerPairing(address, false); refresh() }
                .setCancelable(false).show()
            pairing?.findViewById<TextView>(android.R.id.message)?.textSize = 22f
        }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) { BridgeService.start(this); refresh() }
    }

    override fun onPause() {
        BridgeService.onPairCode = null
        super.onPause()
    }

    // An operation that deletes data: red, WARNING, what is lost, the action's name to confirm and CANCEL
    private fun confirmDanger(text: String, confirm: String, onConfirm: () -> Unit) {
        val red = 0xFFB71C1C.toInt(); val white = 0xFFFFFFFF.toInt()
        lateinit var dialog: AlertDialog
        fun btn(label: String, bg: Int, fg: Int, action: () -> Unit) = Button(this).apply {
            this.text = label; textSize = 18f; setTextColor(fg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
            setOnClickListener { dialog.dismiss(); action() }
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(42, 42, 42, 42); setBackgroundColor(red)
            addView(TextView(this@MainActivity).apply {
                this.text = "WARNING"; textSize = 22f; setTextColor(white); gravity = android.view.Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply { this.text = text; textSize = 16f; setTextColor(white); setPadding(0, 24, 0, 24) })
            addView(btn(confirm, white, red, onConfirm).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            addView(btn("CANCEL", 0xFF000000.toInt(), white) {})
        }
        dialog = AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(red))
    }

    private fun refresh() {
        val access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        // From the stored keys: they are there also while the bridge is stopped
        val paired = PairStore(this).count
        why.visibility = if (paired == 0) android.view.View.VISIBLE else android.view.View.GONE
        status.text = "Bridge: ${BridgeService.status}\n" +
            (if (paired > 0) "Paired Karoo: $paired · messages encrypted\n" else "No Karoo paired yet: tap Pair a Karoo\n") +
            if (access) "Notification access: granted ✓"
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
            // The people and groups of WhatsApp and Slack, indented under it while the app is on
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
