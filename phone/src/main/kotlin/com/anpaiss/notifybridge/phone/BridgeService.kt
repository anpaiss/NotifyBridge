package com.anpaiss.notifybridge.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import org.json.JSONObject
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone side: a Bluetooth LE peripheral with one service, whose
 * characteristic notifies each phone notification as a small JSON to the
 * Karoo that subscribed, sealed with the key of that Karoo ([PairCrypto]).
 * A Karoo is paired once through the PAIR characteristic, while the user
 * tapped "Pair a Karoo" and after the same code was confirmed on both
 * screens; after each connection it says which key it holds (hello).
 * Runs as a foreground service so it stays up with the screen off.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service() {

    companion object {
        val SERVICE: UUID = UUID.fromString("00000101-6b61-726f-6f00-616e70616973")
        val MESSAGE: UUID = UUID.fromString("00000102-6b61-726f-6f00-616e70616973")
        val CCCD: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Pairing and hello; the first byte of each write and reply tells what it is (see the write handler)
        val PAIR: UUID    = UUID.fromString("00000103-6b61-726f-6f00-616e70616973")
        private const val PAIR_WINDOW_MS = 120_000L

        /** The code to compare, for the open app: the Karoo's address and the code. Called on the main thread. */
        @Volatile var onPairCode: ((String, String) -> Unit)? = null

        @Volatile var instance: BridgeService? = null
        @Volatile var status: String = "stopped"

        /** Both Bluetooth permissions granted: without them Android kills the app as the service goes foreground. */
        fun canStart(ctx: Context) = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        /** Starts the bridge, unless the Bluetooth permissions are missing (a fresh install: "Start bridge" asks them). */
        fun start(ctx: Context) {
            if (!canStart(ctx)) { FileLog.w("Bluetooth permissions missing: bridge not started"); status = "Bluetooth permission missing"; return }
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
        }

        fun send(app: String, title: String, text: String, call: Boolean = false) {
            val s = instance ?: run { FileLog.w("not running: '$app' dropped"); return }
            // "t": the Karoo drops a message much older than now, so one recorded on the air cannot be shown again later
            s.notifyAll(JSONObject().put("app", app).put("title", title).put("text", text).put("call", call)
                .put("t", System.currentTimeMillis()))
        }
    }

    private lateinit var manager: BluetoothManager
    private var server: BluetoothGattServer? = null
    private val subscribers = ConcurrentHashMap<String, BluetoothDevice>()
    private val mtu = ConcurrentHashMap<String, Int>()
    private val store by lazy { PairStore(this) }
    // The key of each connected Karoo that said hello with an id this phone knows
    private val sessions = ConcurrentHashMap<String, ByteArray>()
    // The answer the next read of PAIR gets, per Karoo
    private val replies = ConcurrentHashMap<String, ByteArray>()
    private class Pending(val commit: ByteArray, val keys: KeyPair) {
        var paired: PairCrypto.Paired? = null
        var answer = 0   // 0 waiting for the user, 1 paired, 2 refused
    }
    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var pairingUntil = 0L

    /** "Pair a Karoo": a Karoo may start pairing for the next 2 minutes. */
    fun startPairing() {
        pending.clear()
        pairingUntil = System.currentTimeMillis() + PAIR_WINDOW_MS
        FileLog.i("pairing open for 2 min")
    }

    /** The user's answer to the code on the phone. */
    fun answerPairing(address: String, same: Boolean) {
        val p = pending[address] ?: return
        val r = p.paired ?: return
        if (same) { store.add(r.id, r.key); sessions[address] = r.key; p.answer = 1 } else p.answer = 2
        replies[address] = byteArrayOf(4, p.answer.toByte())
        pairingUntil = 0L
        FileLog.i("pairing with $address: ${if (same) "paired" else "refused"}")
        status = statusLine()
    }

    /** Forget every paired Karoo: they must pair again before any message reaches them. */
    fun forgetAll() {
        store.clear(); sessions.clear()
        FileLog.i("paired Karoos forgotten")
        status = statusLine()
    }

    private fun statusLine() = "advertising, ${subscribers.size} Karoo connected, ${sessions.size} paired"
    private var sent = 0
    private val main = Handler(Looper.getMainLooper())

    // Every 15 min a line with the battery: the log shows the cost even when no message comes
    private val heartbeat = object : Runnable {
        override fun run() {
            FileLog.i("alive: ${subscribers.size} Karoo subscribed, $sent sent")
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    // Bluetooth switched off closes the server and the advertising: open them again when it comes back
    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> {
                    FileLog.i("Bluetooth off")
                    runCatching { server?.close() }; server = null
                    subscribers.clear(); mtu.clear()
                    status = "Bluetooth off"
                }
                BluetoothAdapter.STATE_ON -> { FileLog.i("Bluetooth on"); openServer(); advertise() }
            }
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
        registerReceiver(btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        instance = this
        main.postDelayed(heartbeat, 15 * 60_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        status = "stopped"
        main.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(btState) }
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
        svc.addCharacteristic(BluetoothGattCharacteristic(PAIR,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE))
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
                sessions.remove(device.address); replies.remove(device.address)
                FileLog.i("disconnected: ${device.address}")
            }
            status = statusLine()
        }

        // The Karoo asks the MTU first thing; the phone's own links (inReach, watch) do not come here.
        // The server takes the link as its own: without it the stack sees no app on a link the
        // Karoo opened, and may close it a few seconds later (status 19 on the Karoo)
        override fun onMtuChanged(device: BluetoothDevice, m: Int) {
            mtu[device.address] = m; FileLog.i("mtu $m from ${device.address}")
            server?.connect(device, false)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (descriptor.uuid == CCCD) {
                if (value != null && value.isNotEmpty() && value[0].toInt() != 0) subscribers[device.address] = device
                else subscribers.remove(device.address)
                status = statusLine()
                FileLog.i("subscription from ${device.address}: ${subscribers.containsKey(device.address)}")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        // PAIR, first byte of the write:
        //   1 + SHA-256 commitment of the Karoo's public key: start pairing (only within 2 min of "Pair a Karoo");
        //       the next read answers 2 + the phone's public key, or just 2 when pairing is not open
        //   3 + the Karoo's public key, which must match the commitment: both sides now show the code;
        //       the next read answers 4 + 0 waiting for the user here, 1 paired, 2 refused
        //   5 + the 8-byte id of the key the Karoo holds (after each connection): the next read answers 6 + 1 known, 0 not
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            val a = device.address
            var ok = characteristic.uuid == PAIR && value != null && value.isNotEmpty()
            if (ok) {
                val body = value!!.copyOfRange(1, value.size)
                when (value[0].toInt()) {
                    1 -> if (System.currentTimeMillis() < pairingUntil && body.size == 32) {
                        val keys = PairCrypto.newKeyPair()
                        pending[a] = Pending(body, keys)
                        replies[a] = byteArrayOf(2) + keys.public.encoded
                        FileLog.i("pairing: a Karoo asks ($a)")
                    } else { replies[a] = byteArrayOf(2); FileLog.i("pairing asked by $a, but not open on the phone") }
                    3 -> {
                        val p = pending[a]
                        if (p != null && PairCrypto.commit(body).contentEquals(p.commit)) {
                            val r = PairCrypto.derive(p.keys, body, p.keys.public.encoded)
                            p.paired = r
                            replies[a] = byteArrayOf(4, 0)
                            main.post { onPairCode?.invoke(a, r.code) }
                            FileLog.i("pairing: code shown")
                        } else { replies[a] = byteArrayOf(4, 2); FileLog.w("pairing: key does not match its commitment ($a)") }
                    }
                    5 -> {
                        val key = store.find(body)
                        if (key != null) sessions[a] = key else sessions.remove(a)
                        replies[a] = byteArrayOf(6, if (key != null) 1 else 0)
                        FileLog.i("hello from $a: ${if (key != null) "paired" else "unknown key"}")
                        status = statusLine()
                    }
                    else -> ok = false
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId,
                if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
        }

        // MESSAGE: the Karoo's periodic check (is it still subscribed here? After this service restarts it no
        // longer is), sealed with its key once paired. PAIR: the answer to its last write. A value longer than
        // one packet is read in parts, from [offset]
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = if (characteristic.uuid == PAIR) replies[device.address] ?: byteArrayOf(0) else check(device.address)
            val part = if (offset >= value.size) ByteArray(0) else value.copyOfRange(offset, value.size)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
        }
    }

    // In clear only "subscribed" and "paired": the inReach's address goes to a paired Karoo only, sealed
    private fun check(address: String): ByteArray {
        val key = sessions[address]
        val sub = JSONObject().put("subscribed", subscribers.containsKey(address)).put("paired", key != null)
        if (key == null) return sub.toString().toByteArray()
        inReach()?.let { (ir, up) -> sub.put("ir", ir).put("irUp", up) }
        return PairCrypto.seal(key, sub.toString().toByteArray())
    }

    // The Garmin inReach paired with this phone, and whether its app (Messenger, Explore) holds the link now:
    // connected to the phone it stops advertising, so the Karoo cannot see it by itself
    private fun inReach(): Pair<String, Boolean>? {
        val ir = runCatching { manager.adapter.bondedDevices.firstOrNull { it.name?.startsWith("inReach", true) == true } }.getOrNull() ?: return null
        val up = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT).any { it.address == ir.address } }.getOrDefault(false)
        return ir.address to up
    }

    fun notifyAll(json: JSONObject) {
        val srv = server ?: return
        val ch = srv.getService(SERVICE)?.getCharacteristic(MESSAGE) ?: return
        if (subscribers.isEmpty()) { FileLog.i("no Karoo subscribed: '${json.optString("app")}' not sent"); return }
        for (d in subscribers.values) {
            val key = sessions[d.address]
            if (key == null) { FileLog.i("${d.address} not paired: '${json.optString("app")}' not sent"); continue }
            // One notification carries at most MTU-3 bytes, the seal included: shorten the text to fit
            val room = (mtu[d.address] ?: 23) - 3 - PairCrypto.OVERHEAD
            var j = json
            var bytes = j.toString().toByteArray()
            while (bytes.size > room && j.optString("text").isNotEmpty()) {
                val t = j.optString("text"); j = JSONObject(j.toString()).put("text", t.take(maxOf(0, t.length - (bytes.size - room) - 1)))
                bytes = j.toString().toByteArray()
            }
            bytes = PairCrypto.seal(key, bytes)
            val r = srv.notifyCharacteristicChanged(d, ch, false, bytes)
            sent++
            FileLog.i("sent #$sent to ${d.address} (${bytes.size} B, result $r): ${j.optString("app")}")
        }
    }
}
