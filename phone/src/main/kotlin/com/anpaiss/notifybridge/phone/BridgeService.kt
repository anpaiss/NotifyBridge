package com.anpaiss.notifybridge.phone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Test of the phone side: a Bluetooth LE peripheral with one service, whose
 * characteristic notifies each phone notification as a small JSON to the
 * Karoo that subscribed. Runs as a foreground service so it stays up with the
 * screen off, next to the Hammerhead Companion.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service() {

    companion object {
        val SERVICE: UUID = UUID.fromString("00000101-6b61-726f-6f00-616e70616973")
        val MESSAGE: UUID = UUID.fromString("00000102-6b61-726f-6f00-616e70616973")
        val CCCD: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile var instance: BridgeService? = null
        @Volatile var status: String = "stopped"

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, BridgeService::class.java))

        fun send(app: String, title: String, text: String, call: Boolean = false) {
            val s = instance ?: run { FileLog.w("not running: '$app' dropped"); return }
            s.notifyAll(JSONObject().put("app", app).put("title", title).put("text", text).put("call", call))
        }
    }

    private lateinit var manager: BluetoothManager
    private var server: BluetoothGattServer? = null
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtu = ConcurrentHashMap<String, Int>()
    private var sent = 0
    private val main = Handler(Looper.getMainLooper())

    // Every 15 min a line with the battery: the log shows the cost even when no message comes
    private val heartbeat = object : Runnable {
        override fun run() {
            FileLog.i("alive: ${subscribers.size} Karoo subscribed, $sent sent")
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FileLog.init(this)
        FileLog.i("bridge started")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "Bridge to the Karoo", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("NotifyBridge")
            .setContentText("Forwarding notifications to the Karoo")
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        manager = getSystemService(BluetoothManager::class.java)
        openServer()
        advertise()
        instance = this
        main.postDelayed(heartbeat, 15 * 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        status = "stopped"
        main.removeCallbacks(heartbeat)
        FileLog.i("bridge stopped")
        runCatching { manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback) }
        runCatching { server?.close() }
        super.onDestroy()
    }

    private fun openServer() {
        val ch = BluetoothGattCharacteristic(MESSAGE,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ)
        ch.addDescriptor(BluetoothGattDescriptor(CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(ch)
        server = manager.openGattServer(this, gattCallback)?.also { it.addService(svc) }
        FileLog.i("GATT server ${if (server != null) "open" else "FAILED"}")
    }

    private fun advertise() {
        val adv = manager.adapter.bluetoothLeAdvertiser ?: run { status = "no advertiser"; FileLog.w(status); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build()
        adv.startAdvertising(settings, data, advCallback)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { status = "advertising"; FileLog.i("advertising") }
        override fun onStartFailure(errorCode: Int) { status = "advertising FAILED $errorCode"; FileLog.w(status) }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                FileLog.i("connected: ${device.address}")
            } else {
                subscribers.remove(device.address); mtu.remove(device.address)
                FileLog.i("disconnected: ${device.address}")
            }
            status = "advertising, ${subscribers.size} Karoo subscribed"
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) { mtu[device.address] = m; FileLog.i("mtu $m from ${device.address}") }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == CCCD) {
                if (value != null && value.isNotEmpty() && value[0].toInt() != 0) subscribers[device.address] = device
                else subscribers.remove(device.address)
                status = "advertising, ${subscribers.size} Karoo subscribed"
                FileLog.i("subscription from ${device.address}: ${subscribers.containsKey(device.address)}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        // The Karoo's periodic check: is it still subscribed here? After this service restarts it no longer is
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val sub = JSONObject().put("subscribed", subscribers.containsKey(device.address)).toString()
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, sub.toByteArray())
        }
    }

    fun notifyAll(json: JSONObject) {
        val srv = server ?: return
        val ch = srv.getService(SERVICE)?.getCharacteristic(MESSAGE) ?: return
        if (subscribers.isEmpty()) { FileLog.i("no Karoo subscribed: '${json.optString("app")}' not sent"); return }
        for (d in subscribers.values) {
            // One notification carries at most MTU-3 bytes: shorten the text to fit
            val room = (mtu[d.address] ?: 23) - 3
            var j = json
            var bytes = j.toString().toByteArray()
            while (bytes.size > room && j.optString("text").isNotEmpty()) {
                val t = j.optString("text"); j = JSONObject(j.toString()).put("text", t.take(maxOf(0, t.length - (bytes.size - room) - 1)))
                bytes = j.toString().toByteArray()
            }
            val r = srv.notifyCharacteristicChanged(d, ch, false, bytes)
            sent++
            FileLog.i("sent #$sent to ${d.address} (${bytes.size} B, result $r): ${j.optString("app")}")
        }
    }
}
