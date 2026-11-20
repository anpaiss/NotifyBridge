package com.anpaiss.notifybridge.karoo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SystemNotification
import org.json.JSONObject
import java.util.UUID

/**
 * Test of the Karoo side: finds the phone by the service it advertises (the
 * phone's address changes, the service does not), subscribes to its message
 * characteristic and shows each message as a Karoo in-ride alert.
 */
@SuppressLint("MissingPermission")
class BridgeExtension : KarooExtension("notifybridge", BuildConfig.VERSION_NAME) {

    companion object {
        val SERVICE: UUID = UUID.fromString("00000101-6b61-726f-6f00-616e70616973")
        val MESSAGE: UUID = UUID.fromString("00000102-6b61-726f-6f00-616e70616973")
        val CCCD: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // A message: two short high beeps. A call: three rings of two alternating tones, unlike any Karoo sound
        private fun tone(hz: Int?, ms: Int) = PlayBeepPattern.Tone(frequency = hz, durationMs = ms)
        val MESSAGE_BEEP = listOf(tone(2000, 80), tone(null, 60), tone(2000, 80))
        // A siren of two high tones, every 10 s until the alarm is closed: not to be missed
        val INREACH_BEEP = List(6) { listOf(tone(2600, 120), tone(1800, 120)) }.flatten()
        private const val INREACH_REPEAT_MS = 10_000L
        private const val INREACH_REPEAT_MAX = 60            // 10 min, then quiet: the Karoo may be left alone
        private const val INREACH_DELAY_MS = 90_000L     // the phone's link and its first check come within a minute
        private const val INREACH_SCAN_MS = 20_000L
        val CALL_BEEP = List(3) { listOf(tone(1300, 150), tone(1000, 150), tone(1300, 150), tone(null, 350)) }.flatten()
    }

    private lateinit var karoo: KarooSystemService
    private lateinit var manager: BluetoothManager
    private lateinit var popup: MessagePopup
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var scanFast = false
    private var lostAt = 0L
    private var count = 0
    private var checkPending = false
    @Volatile private var riding = false
    // The inReach as the phone last told it: its address (kept across restarts) and whether it is connected there
    private var inReachAddress: String? = null
    private var inReachUp = false
    private var inReachAt = 0L

    override fun onCreate() {
        super.onCreate()
        karoo = KarooSystemService(applicationContext)
        FileLog.init(this)
        FileLog.i("extension started")
        karoo.connect { ok ->
            if (ok) karoo.addConsumer(RideState.Params, { _ -> }, {}) { s: RideState ->
                val now = s !is RideState.Idle
                if (now != riding) FileLog.i(if (now) "ride started" else "ride ended")
                if (now && !riding) main.postDelayed(inReachCheck, INREACH_DELAY_MS)
                if (!now) main.removeCallbacks(inReachCheck)
                riding = now
            }
        }
        main.postDelayed(heartbeat, 15 * 60_000L)
        manager = getSystemService(BluetoothManager::class.java)
        popup = MessagePopup(this)
        inReachAddress = getSharedPreferences("bridge", Context.MODE_PRIVATE).getString("inreach", null)
        main.postDelayed({ startScan() }, 5_000)
    }

    // Every 15 min a line with the battery: the log shows the cost even when no message comes
    private val heartbeat = object : Runnable {
        override fun run() {
            FileLog.i("alive: link ${if (gatt != null) "up" else "down"}, $count received, ${if (riding) "riding" else "idle"}")
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(check)
        main.removeCallbacks(inReachCheck)
        main.removeCallbacks(siren)
        stopInReachScan()
        FileLog.i("extension stopped")
        stopScan()
        runCatching { gatt?.close() }
        popup.remove()
        karoo.disconnect()
        super.onDestroy()
    }

    // The first 2 min without the phone a full scan, to get it back at once; then the low power one,
    // less than half the radio time: a phone left at home or out of range costs little, and is found in seconds
    private fun startScan() {
        if (scanning || gatt != null) return
        val s = manager.adapter?.bluetoothLeScanner ?: run { FileLog.w("no scanner"); main.postDelayed({ startScan() }, 30_000); return }
        if (lostAt == 0L) lostAt = System.currentTimeMillis()
        scanFast = System.currentTimeMillis() - lostAt < 2 * 60_000L
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (scanFast) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { s.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { FileLog.w("scan failed: ${it.message}"); main.postDelayed({ startScan() }, 30_000); return }
        scanning = true
        FileLog.i("looking for the phone${if (scanFast) "" else " (low power)"}")
        main.removeCallbacks(slowDown)
        if (scanFast) main.postDelayed(slowDown, lostAt + 2 * 60_000L - System.currentTimeMillis())
    }

    private val slowDown = Runnable { if (scanning && scanFast) { stopScan(); startScan() } }

    private fun stopScan() {
        main.removeCallbacks(slowDown)
        if (scanning) runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (gatt != null) return@post
                FileLog.i("phone found: ${result.device.address} rssi ${result.rssi}")
                stopScan()
                gatt = result.device.connectGatt(this@BridgeExtension, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) { FileLog.w("scan failed $errorCode"); scanning = false; main.postDelayed({ startScan() }, 30_000) }
    }

    // Every minute a read of the message characteristic: the phone answers whether we are still
    // subscribed. A link can look up while the phone's service restarted and forgot us
    private val check = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            val ch = g.getService(SERVICE)?.getCharacteristic(MESSAGE)
            if (checkPending || ch == null || !g.readCharacteristic(ch)) { retry(if (checkPending) "no answer to the check" else "check not sent"); return }
            checkPending = true
            main.postDelayed(this, 60_000)
        }
    }

    private fun retry(why: String) {
        FileLog.i("link lost ($why): looking again in 10 s")
        main.removeCallbacks(check)
        checkPending = false
        runCatching { gatt?.close() }
        gatt = null
        main.postDelayed({ startScan() }, 10_000)
    }

    // At the start of a ride: is the inReach on? Connected to the phone (the phone says so in its check)
    // or advertising (seen by a short scan for its address). Neither: a notice. No inReach paired: nothing
    private var inReachScanning = false

    private val inReachCheck = Runnable {
        if (!getSharedPreferences("bridge", Context.MODE_PRIVATE).getBoolean(MainActivity.INREACH_CHECK, false)) return@Runnable
        val address = inReachAddress ?: run { FileLog.i("inReach check on, but no inReach paired with the phone"); return@Runnable }
        // The phone's last answer, at most a couple of minutes old: an older "connected" says nothing about now
        if (inReachUp && System.currentTimeMillis() - inReachAt < 2 * 60_000L) { FileLog.i("inReach connected to the phone"); return@Runnable }
        val s = manager.adapter?.bluetoothLeScanner ?: return@Runnable
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (runCatching { s.startScan(listOf(filter), settings, inReachScanCallback) }.isFailure) return@Runnable
        inReachScanning = true
        FileLog.i("inReach not connected to the phone: looking for it")
        main.postDelayed(inReachMissing, INREACH_SCAN_MS)
    }

    private val inReachMissing = Runnable {
        stopInReachScan()
        if (!riding) return@Runnable
        FileLog.i("inReach not found")
        val title = "inReach not found"; val detail = "Off, or its Bluetooth is off"
        if (popup.canShow()) popup.alarm(title, detail) { FileLog.i("inReach alarm closed"); main.removeCallbacks(siren) }
        else karoo.dispatch(InRideAlert(id = "notifybridge-inreach", icon = R.drawable.ic_bridge, title = title, detail = detail,
            autoDismissMs = null, backgroundColor = R.color.alert_inreach, textColor = R.color.alert_text))
        sirens = 0
        main.post(siren)
    }

    private var sirens = 0
    private val siren = object : Runnable {
        override fun run() {
            karoo.dispatch(PlayBeepPattern(INREACH_BEEP))
            if (++sirens < INREACH_REPEAT_MAX && popup.canShow()) main.postDelayed(this, INREACH_REPEAT_MS)
        }
    }

    private fun stopInReachScan() {
        main.removeCallbacks(inReachMissing)
        if (inReachScanning) runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(inReachScanCallback) }
        inReachScanning = false
    }

    private val inReachScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { if (inReachScanning) { FileLog.i("inReach found: rssi ${result.rssi}"); stopInReachScan() } }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) { FileLog.i("connected"); g.requestMtu(247) }
                else retry("state $newState, status $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { FileLog.i("mtu $mtu"); g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(MESSAGE)
            if (ch == null) { main.post { retry("service missing") }; return }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(CCCD)
            // The Karoo runs Android 12: the Android 13 writeDescriptor(d, value) does not exist there
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
            FileLog.i("subscribed")
            main.post { lostAt = 0L; main.removeCallbacks(check); checkPending = false; main.postDelayed(check, 60_000) }
        }

        // Android 12 calls this form; the (g, ch, value, status) one only exists from Android 13
        @Deprecated("Android 12 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = onCheck(g, ch.value, status)

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) = onCheck(g, value, status)

        private fun onCheck(g: BluetoothGatt, value: ByteArray?, status: Int) { main.post {
            checkPending = false
            val answer = runCatching { JSONObject(value!!.decodeToString()) }.getOrNull()
            val subscribed = answer?.optBoolean("subscribed") ?: false
            answer?.optString("ir")?.takeIf { it.isNotEmpty() }?.let { ir ->
                if (ir != inReachAddress) {
                    inReachAddress = ir
                    getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putString("inreach", ir).apply()
                    FileLog.i("inReach paired with the phone: $ir")
                }
                inReachUp = answer.optBoolean("irUp")
                inReachAt = System.currentTimeMillis()
            }
            when {
                status != BluetoothGatt.GATT_SUCCESS -> retry("check failed, status $status")
                !subscribed -> { FileLog.i("phone forgot the subscription: subscribing again"); g.discoverServices() }
            }
        } }

        // Android 12 calls this form; the (g, ch, value) one only exists from Android 13
        @Deprecated("Android 12 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { ch.value?.let { onMessage(it) } }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = onMessage(value)

        // The popup takes the colour of the app it comes from, orange for the others
        private fun appColor(app: String) = when {
            app.contains("whatsapp", true) -> R.color.alert_whatsapp
            app.contains("telegram", true) -> R.color.alert_telegram
            else -> R.color.alert_bg
        }

        private fun onMessage(value: ByteArray) {
            val j = runCatching { JSONObject(value.decodeToString()) }.getOrNull() ?: return
            count++
            val app = j.optString("app"); val title = j.optString("title"); val text = j.optString("text")
            FileLog.i("message #$count from $app (${value.size} B)")
            // The message is the large line, the app below
            val message = listOf(title, text).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { app }
            // Riding: our popup at the bottom, or the Karoo's alert at the top without the overlay permission.
            // Otherwise InRideAlert is not shown, the Karoo's notification centre is
            if (riding && popup.canShow()) {
                popup.show(message, app, appColor(app))
                karoo.dispatch(PlayBeepPattern(if (j.optBoolean("call")) CALL_BEEP else MESSAGE_BEEP))
            }
            else if (riding) karoo.dispatch(InRideAlert(
                id = "notifybridge-$count",
                icon = R.drawable.ic_bridge,
                title = message,
                detail = app,
                autoDismissMs = 10_000,
                backgroundColor = appColor(app),
                textColor = R.color.alert_text,
            )) else karoo.dispatch(SystemNotification(
                id = "notifybridge-$count",
                header = app,
                message = if (title.isNotEmpty()) title else app,
                subText = text,
            ))
        }
    }
}
