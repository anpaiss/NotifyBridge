package com.anpaiss.notifybridge.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: puts a sample message in the Messages field, for screenshots without the phone.
 * adb shell am broadcast -a com.anpaiss.notifybridge.karoo.FAKE_MESSAGE -p com.anpaiss.notifybridge.karoo
 *   --es app WhatsApp --es title Anna --es text "What time are you back?"
 */
class FakeMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // --ez clear true: empty the list first
        if (intent.getBooleanExtra("clear", false)) MessageLog.clear()
        if (!intent.hasExtra("text")) return
        MessageLog.add(MessageLog.Entry(System.currentTimeMillis(), intent.getStringExtra("app") ?: "WhatsApp",
            intent.getStringExtra("title") ?: "", intent.getStringExtra("text") ?: ""))
    }
}
