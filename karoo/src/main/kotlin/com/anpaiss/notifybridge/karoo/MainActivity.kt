package com.anpaiss.notifybridge.karoo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView

/** The overlay permission that lets the popup sit at the bottom of the ride screen, and the inReach check. */
class MainActivity : Activity() {

    companion object {
        const val INREACH_CHECK = "inreach_check"
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 18f }
        val grant = Button(this).apply {
            text = "Allow popups over the ride screen"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(grant)
            // The real popup with a made-up message, as every extension with a popup has; no sound, nothing is sent
            addView(Button(this@MainActivity).apply {
                text = "TEST POPUP (shows in 3 s)"
                setOnClickListener {
                    if (!Settings.canDrawOverlays(this@MainActivity)) return@setOnClickListener
                    val popup = MessagePopup(applicationContext)
                    postDelayed({
                        popup.show("Sample sender: This is a test message, long enough to fill more than one line of the popup",
                            "WhatsApp (test)", R.color.alert_whatsapp)
                    }, 3_000L)
                }
            })
            addView(CheckBox(this@MainActivity).apply {
                text = "inReach check: a notice 1–2 min after the start when your Garmin inReach is neither " +
                    "connected to the phone nor seen by the Karoo"
                textSize = 16f
                val prefs = getSharedPreferences("bridge", Context.MODE_PRIVATE)
                isChecked = prefs.getBoolean(INREACH_CHECK, false)
                setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(INREACH_CHECK, on).apply() }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "Overlay permission: granted ✓\nMessages appear at the bottom of the ride screen."
        else
            "Overlay permission: MISSING\nMessages use the Karoo's own alert at the top."
    }
}
