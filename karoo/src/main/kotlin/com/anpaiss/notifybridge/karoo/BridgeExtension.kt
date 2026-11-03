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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
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
class BridgeExtension : KarooExtension("notifybridge", "0.1") {

    companion object {
        const val TAG = "NotifyBridge"
        val SERVICE: UUID = UUID.fromString("00000101-6b61-726f-6f00-616e70616973")
        val MESSAGE: UUID = UUID.fromString("00000102-6b61-726f-6f00-616e70616973")
        val CCCD: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var karoo: KarooSystemService
    private lateinit var manager: BluetoothManager
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var count = 0
    @Volatile private var riding = false

    override fun onCreate() {
        super.onCreate()
        karoo = KarooSystemService(applicationContext)
        karoo.connect { ok -> if (ok) karoo.addConsumer(RideState.Params, { _ -> }, {}) { s: RideState -> riding = s !is RideState.Idle } }
        manager = getSystemService(BluetoothManager::class.java)
        main.postDelayed({ startScan() }, 5_000)
    }

    override fun onDestroy() {
        stopScan()
        runCatching { gatt?.close() }
        karoo.disconnect()
        super.onDestroy()
    }

    private fun startScan() {
        if (scanning || gatt != null) return
        val s = manager.adapter?.bluetoothLeScanner ?: run { Log.w(TAG, "no scanner"); main.postDelayed({ startScan() }, 30_000); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { s.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { Log.w(TAG, "scan failed: ${it.message}"); main.postDelayed({ startScan() }, 30_000); return }
        scanning = true
        Log.i(TAG, "looking for the phone")
    }

    private fun stopScan() {
        if (scanning) runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (gatt != null) return@post
                Log.i(TAG, "phone found: ${result.device.address} rssi ${result.rssi}")
                stopScan()
                gatt = result.device.connectGatt(this@BridgeExtension, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed $errorCode"); scanning = false; main.postDelayed({ startScan() }, 30_000) }
    }

    private fun retry(why: String) {
        Log.i(TAG, "link lost ($why): looking again in 10 s")
        runCatching { gatt?.close() }
        gatt = null
        main.postDelayed({ startScan() }, 10_000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) { Log.i(TAG, "connected"); g.requestMtu(247) }
                else retry("state $newState, status $status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { Log.i(TAG, "mtu $mtu"); g.discoverServices() }

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
            Log.i(TAG, "subscribed")
        }

        // Android 12 calls this form; the (g, ch, value) one only exists from Android 13
        @Deprecated("Android 12 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { ch.value?.let { onMessage(it) } }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) = onMessage(value)

        private fun onMessage(value: ByteArray) {
            val j = runCatching { JSONObject(value.decodeToString()) }.getOrNull() ?: return
            count++
            val app = j.optString("app"); val title = j.optString("title"); val text = j.optString("text")
            Log.i(TAG, "message #$count from $app (${value.size} B)")
            // Riding: a popup on the ride screen. Otherwise InRideAlert is not shown, the Karoo's notification centre is
            if (riding) karoo.dispatch(InRideAlert(
                id = "notifybridge-$count",
                icon = R.drawable.ic_bridge,
                title = if (title.isNotEmpty()) "$app: $title" else app,
                detail = text,
                autoDismissMs = 10_000,
                backgroundColor = R.color.alert_bg,
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
