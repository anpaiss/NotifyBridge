package com.anpaiss.notifybridge.phone

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Test screen: start the bridge, open the notification access settings, send a test message. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        col.addView(button("Send test") { BridgeService.send("NotifyBridge", "Test", "Hello Karoo, this is a test from the phone") })
        col.addView(button("Refresh") {})
        setContentView(col)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() { status.text = "Bridge: ${BridgeService.status}" }
}
