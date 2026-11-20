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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.view.View
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
        // Scrolls: the inReach check below the text sizes did not fit on the Karoo's screen
        setContentView(ScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply {
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
            // Text size of the message popup; TEST POPUP above shows the one chosen
            addView(TextView(this@MainActivity).apply { text = "Text size of the messages"; textSize = 16f; setPadding(0, pad, 0, 0) })
            val prefs = getSharedPreferences("bridge", Context.MODE_PRIVATE)
            addView(RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL   // one per row: on one row "Medium" broke in two on the Karoo
                val chosen = prefs.getString(MessagePopup.TEXT_SIZE, MessagePopup.DEFAULT_TEXT_SIZE)
                MessagePopup.TEXT_SIZES.keys.forEach { name ->
                    addView(RadioButton(this@MainActivity).apply {
                        id = View.generateViewId(); text = name; textSize = 16f; isChecked = name == chosen
                        setOnClickListener { prefs.edit().putString(MessagePopup.TEXT_SIZE, name).apply() }
                    })
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
        }) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "Overlay permission: granted ✓\nMessages appear at the bottom of the ride screen."
        else
            "Overlay permission: MISSING\nMessages use the Karoo's own alert at the top."
    }
}
