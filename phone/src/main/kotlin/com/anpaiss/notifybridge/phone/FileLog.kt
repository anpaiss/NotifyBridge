package com.anpaiss.notifybridge.phone

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat plus a file that survives a ride: each line carries the battery level,
 * so a ride's log also tells what the bridge cost. 1 MB, then the previous file
 * is kept as .old. Read with: adb shell run-as <package> cat files/notifybridge.log
 */
object FileLog {
    private const val TAG = "NotifyBridge"
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null
    private var battery: BatteryManager? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "notifybridge.log")
        battery = ctx.getSystemService(BatteryManager::class.java)
    }

    fun i(msg: String) { Log.i(TAG, msg); write("I", msg) }
    fun w(msg: String) { Log.w(TAG, msg); write("W", msg) }

    @Synchronized private fun write(level: String, msg: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".old"))
            val pct = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            f.appendText("${time.format(Date())} $level bat $pct% $msg\n")
        }
    }
}
