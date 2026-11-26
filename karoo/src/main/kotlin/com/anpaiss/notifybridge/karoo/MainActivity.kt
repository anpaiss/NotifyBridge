package com.anpaiss.notifybridge.karoo

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.view.Gravity
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.view.View
import android.widget.TextView
import com.anpaiss.notifybridge.karoo.Ui.styled

/** The overlay permission that lets the popup sit at the bottom of the ride screen, and the inReach check. */
class MainActivity : Activity() {

    companion object {
        const val INREACH_CHECK = "inreach_check"
        private val BT_PERMISSIONS = arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    private lateinit var status: TextView
    private lateinit var overlayState: TextView
    private lateinit var why: TextView
    private lateinit var pairButton: Button
    private lateinit var pairState: TextView
    private var dialog: AlertDialog? = null
    private var askedBluetooth = false

    // The steps of a pairing, from the extension
    private val pairUi = object : BridgeExtension.Companion.PairUi {
        override fun code(code: String) {
            dialog?.dismiss()
            dialog = AlertDialog.Builder(this@MainActivity).setTitle("Same code on the phone?")
                .setMessage(code)
                .setPositiveButton("Same code: pair") { _, _ -> BridgeExtension.instance?.answerPairing(true) }
                .setNegativeButton("Cancel") { _, _ -> BridgeExtension.instance?.answerPairing(false) }
                .setCancelable(false).show()
            dialog?.findViewById<TextView>(android.R.id.message)?.apply { textSize = 40f; gravity = Gravity.CENTER }
        }
        override fun waiting() {
            dialog?.dismiss()
            dialog = AlertDialog.Builder(this@MainActivity).setTitle("Pairing")
                .setMessage("Waiting for the OK on the phone…")
                .setNegativeButton("Cancel") { _, _ -> BridgeExtension.instance?.answerPairing(false) }.show()
        }
        override fun done(ok: Boolean, message: String) {
            dialog?.dismiss(); dialog = null
            pairState.text = message
            // A failure stays on screen until OK: the line under the button went unseen
            if (!ok) dialog = AlertDialog.Builder(this@MainActivity).setTitle("Not paired")
                .setMessage(message).setPositiveButton("OK", null).show()
            refresh()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = Ui.text(this)
        overlayState = Ui.text(this)
        val grant = Ui.button(this, "Allow popups over the ride screen").apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        // Scrolls: the inReach check below the text sizes did not fit on the Karoo's screen
        setContentView(ScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(Ui.text(this@MainActivity, "NotifyBridge", 26f).apply { typeface = Ui.MEDIUM })
            addView(Ui.heading(this@MainActivity, "Phone"))
            addView(status)
            // Pairing with the phone: why, and the button; the why goes away once paired
            why = Ui.panel(this@MainActivity,
                "Pair once with your phone: your messages then travel encrypted, and no other device nearby can read them.")
            addView(why)
            pairButton = Ui.button(this@MainActivity, "", primary = true).apply {
                setOnClickListener {
                    val ext = BridgeExtension.instance ?: run { pairState.text = "NotifyBridge is not running: restart the Karoo"; return@setOnClickListener }
                    fun start() {
                        BridgeExtension.pairUi = pairUi
                        dialog?.dismiss()
                        dialog = AlertDialog.Builder(this@MainActivity).setTitle("Pairing")
                            .setMessage("Looking for the phone's answer…\nOn the phone: NotifyBridge, Pair a Karoo.")
                            .setNegativeButton("Cancel") { _, _ -> ext.answerPairing(false) }.show()
                        ext.startPairing()
                    }
                    // Already paired: a tap by mistake must not start a new pairing; the key stays until a new one succeeds
                    val paired = getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("pair_key", null) != null
                    if (!paired) start()
                    else { dialog?.dismiss(); dialog = AlertDialog.Builder(this@MainActivity).setTitle("Already paired")
                        .setMessage("This Karoo is already paired with a phone. Pair again?")
                        .setPositiveButton("Pair again") { _, _ -> start() }
                        .setNegativeButton("Cancel", null).show() }
                }
            }
            addView(pairButton)
            pairState = Ui.text(this@MainActivity, size = 15f, color = Ui.MUTED)
            addView(pairState)
            addView(Ui.heading(this@MainActivity, "Popup"))
            addView(overlayState)
            addView(grant)
            // The real popup with a made-up message, as every extension with a popup has; no sound, nothing is sent
            addView(Ui.button(this@MainActivity, "Test popup (shows in 3 s)").apply {
                setOnClickListener {
                    if (!Settings.canDrawOverlays(this@MainActivity)) return@setOnClickListener
                    val popup = MessagePopup(applicationContext)
                    postDelayed({
                        popup.show("Sample sender: This is a test message, long enough to fill more than one line of the popup",
                            "WhatsApp (test)", R.color.alert_whatsapp)
                    }, 3_000L)
                }
            })
            // Text size of the message popup; TEST POPUP above shows the one chosen
            addView(Ui.heading(this@MainActivity, "Message text size"))
            val prefs = getSharedPreferences("bridge", Context.MODE_PRIVATE)
            addView(RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL   // one per row: on one row "Medium" broke in two on the Karoo
                val chosen = prefs.getString(MessagePopup.TEXT_SIZE, MessagePopup.DEFAULT_TEXT_SIZE)
                MessagePopup.TEXT_SIZES.keys.forEach { name ->
                    addView(RadioButton(this@MainActivity).styled().apply {
                        id = View.generateViewId(); text = name; isChecked = name == chosen
                        setOnClickListener { prefs.edit().putString(MessagePopup.TEXT_SIZE, name).apply() }
                    })
                }
            })
            addView(Ui.heading(this@MainActivity, "inReach check"))
            addView(CheckBox(this@MainActivity).styled().apply {
                text = "A notice 1–2 min after the start when your Garmin inReach is neither " +
                    "connected to the phone nor seen by the Karoo"
                val prefs = getSharedPreferences("bridge", Context.MODE_PRIVATE)
                isChecked = prefs.getBoolean(INREACH_CHECK, false)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(INREACH_CHECK, on).apply() }
            })
        }) })
    }

    override fun onPause() {
        BridgeExtension.pairUi = null
        dialog?.dismiss(); dialog = null
        super.onPause()
    }

    private fun refresh() {
        val paired = getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("pair_key", null) != null
        why.visibility = if (paired) View.GONE else View.VISIBLE
        pairButton.text = if (paired) "Pair again" else "Pair with phone"
        status.text = (if (bluetoothAllowed()) BridgeExtension.linkState.replaceFirstChar { it.uppercase() }
            else "Bluetooth permission (Nearby devices): MISSING\nThe Karoo cannot find the phone: reopen NotifyBridge and allow it.") + (if (paired) "\nMessages encrypted: only this Karoo can read them." else "")
        overlayState.text = overlayText()
    }

    override fun onResume() {
        super.onResume()
        BridgeExtension.pairUi = pairUi
        // Without them the extension cannot find the phone; an install may leave them off.
        // Once per opening: the permission dialog itself pauses and resumes us
        if (!bluetoothAllowed() && !askedBluetooth) { askedBluetooth = true; requestPermissions(BT_PERMISSIONS, 1) }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun bluetoothAllowed() = BT_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun overlayText() =
        if (Settings.canDrawOverlays(this))
            "Allowed ✓ Messages appear at the bottom of the ride screen."
        else
            "Not allowed: messages use the Karoo's own alert at the top."
}
