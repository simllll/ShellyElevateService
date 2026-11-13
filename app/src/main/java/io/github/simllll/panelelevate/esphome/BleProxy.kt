package io.github.simllll.panelelevate.esphome

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import io.github.simllll.panelelevate.esphome.EspHomeMessages as M

/**
 * The Android side of the ESPHome Bluetooth proxy: BLE scanning (raw advertisements, batched
 * every 100 ms) and up to [MAX_CONNECTIONS] GATT client connections with ESPHome-style handles.
 *
 * Handles are the real ATT handles (Android's instance ids), so they stay stable between
 * connections and Home Assistant's service cache (REMOTE_CACHING) stays valid.
 * Android allows only one GATT operation at a time per connection: read/write requests are
 * queued per connection. Everything runs on one handler thread ([handler]); GATT callbacks are
 * delivered there too.
 */
@SuppressLint("MissingPermission")
class BleProxy(private val context: Context, private val send: (type: Int, payload: ByteArray) -> Unit) {
    private val thread = HandlerThread("ble-proxy").apply { start() }
    val handler = Handler(thread.looper)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var scanning = false
    private var scanFailed = false
    private val pending = ArrayList<M.RawAdvertisement>()
    private var flushScheduled = false
    private val connections = LinkedHashMap<Long, Connection>()
    private var bondReceiverRegistered = false
    private val pendingBonds = HashSet<Long>()

    val bluetoothAvailable: Boolean get() = adapter?.isEnabled == true

    // ------------------------------------------------------------------ scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = queue(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::queue)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            handler.post {
                scanning = false
                scanFailed = true
                sendScannerState()
            }
        }
    }

    private fun queue(result: ScanResult) {
        val record = result.scanRecord?.bytes ?: return
        val mac = result.device.address ?: return
        val address = M.macToLong(mac)
        val ad = M.RawAdvertisement(address, result.rssi, addressType(address), M.trimScanRecord(record))
        handler.post {
            pending += ad
            if (pending.size >= M.ADVERTISEMENT_BATCH * 4) flush()
            else if (!flushScheduled) {
                flushScheduled = true
                handler.postDelayed(::flush, FLUSH_MS)
            }
        }
    }

    private fun flush() {
        flushScheduled = false
        if (pending.isEmpty()) return
        val batch = ArrayList(pending)
        pending.clear()
        if (!scanning) return
        for (chunk in batch.chunked(M.ADVERTISEMENT_BATCH)) send(M.BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE, M.rawAdvertisements(chunk))
    }

    /** Starts/stops scanning (on the handler thread). */
    fun setScanning(on: Boolean) = handler.post {
        val scanner = adapter?.bluetoothLeScanner
        if (on == scanning) return@post
        if (on) {
            if (scanner == null) {
                Log.w(TAG, "no BLE scanner (Bluetooth off?)")
                scanFailed = true
            } else try {
                val settings = ScanSettings.Builder()
                    .setScanMode(SCAN_MODE)
                    .setReportDelay(0)
                    .build()
                // An (empty) filter: Android 8.1+ pauses completely unfiltered scans while the
                // screen is off.
                scanner.startScan(listOf(ScanFilter.Builder().build()), settings, scanCallback)
                scanning = true
                scanFailed = false
                Log.i(TAG, "scanning started (mode $SCAN_MODE)")
            } catch (e: Exception) {
                Log.w(TAG, "startScan failed: ${e.message}")
                scanFailed = true
            }
        } else {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan failed: ${e.message}")
            }
            scanning = false
            pending.clear()
            Log.i(TAG, "scanning stopped")
        }
        sendScannerState()
    }

    fun sendScannerState() = handler.post {
        val state = when {
            scanning -> M.SCANNER_STATE_RUNNING
            scanFailed -> M.SCANNER_STATE_FAILED
            else -> M.SCANNER_STATE_IDLE
        }
        // Android always scans actively (requests scan responses).
        send(M.BLUETOOTH_SCANNER_STATE_RESPONSE, M.scannerState(state, M.SCANNER_MODE_ACTIVE, M.SCANNER_MODE_ACTIVE))
    }

    // ------------------------------------------------------------------ connections

    fun sendConnectionsFree() = handler.post {
        send(M.BLUETOOTH_CONNECTIONS_FREE_RESPONSE, M.connectionsFree(MAX_CONNECTIONS - connections.size, MAX_CONNECTIONS, connections.keys.toList()))
    }

    fun deviceRequest(address: Long, type: Int) = handler.post {
        when (type) {
            M.REQUEST_CONNECT, M.REQUEST_CONNECT_V3_WITH_CACHE, M.REQUEST_CONNECT_V3_WITHOUT_CACHE -> connect(address)
            M.REQUEST_DISCONNECT -> {
                val c = connections[address]
                if (c == null) send(M.BLUETOOTH_DEVICE_CONNECTION_RESPONSE, M.connectionResponse(address, false, 0, 0))
                else c.disconnect()
            }
            M.REQUEST_PAIR -> pair(address)
            M.REQUEST_UNPAIR -> unpair(address)
            M.REQUEST_CLEAR_CACHE -> {
                val ok = connections[address]?.refreshCache() ?: true
                send(M.BLUETOOTH_DEVICE_CLEAR_CACHE_RESPONSE, M.addressResult(address, ok, if (ok) 0 else GATT_ERROR))
            }
            else -> Log.w(TAG, "unknown device request $type")
        }
    }

    private fun connect(address: Long) {
        val existing = connections[address]
        if (existing != null) {
            if (existing.ready) send(M.BLUETOOTH_DEVICE_CONNECTION_RESPONSE, M.connectionResponse(address, true, existing.mtu, 0))
            return
        }
        val a = adapter
        if (a == null || !a.isEnabled || connections.size >= MAX_CONNECTIONS) {
            send(M.BLUETOOTH_DEVICE_CONNECTION_RESPONSE, M.connectionResponse(address, false, 0, if (a == null || !a.isEnabled) GATT_ERROR else NO_FREE_SLOT))
            return
        }
        val c = Connection(address, a.getRemoteDevice(M.longToMac(address)))
        connections[address] = c
        sendConnectionsFree()
        c.connect()
    }

    fun gattGetServices(address: Long) = handler.post {
        val c = connections[address]?.takeIf { it.ready }
        if (c == null) {
            send(M.BLUETOOTH_GATT_ERROR_RESPONSE, M.gattError(address, 0, NOT_CONNECTED))
            return@post
        }
        for (s in c.services) send(M.BLUETOOTH_GATT_GET_SERVICES_RESPONSE, M.gattServices(address, listOf(s)))
        send(M.BLUETOOTH_GATT_GET_SERVICES_DONE_RESPONSE, M.addressOnly(address))
    }

    fun gattRead(address: Long, handle: Int) = enqueue(address, handle) { it.readCharacteristic(handle) }
    fun gattWrite(address: Long, handle: Int, data: ByteArray, response: Boolean) = enqueue(address, handle) { it.writeCharacteristic(handle, data, response) }
    fun gattReadDescriptor(address: Long, handle: Int) = enqueue(address, handle) { it.readDescriptor(handle) }
    fun gattWriteDescriptor(address: Long, handle: Int, data: ByteArray) = enqueue(address, handle) { it.writeDescriptor(handle, data) }

    fun gattNotify(address: Long, handle: Int, enable: Boolean) = handler.post {
        val c = connections[address]?.takeIf { it.ready }
        if (c == null) {
            send(M.BLUETOOTH_GATT_ERROR_RESPONSE, M.gattError(address, handle, NOT_CONNECTED))
            return@post
        }
        c.setNotify(handle, enable)
    }

    private fun enqueue(address: Long, handle: Int, op: (Connection) -> Unit) = handler.post {
        val c = connections[address]?.takeIf { it.ready }
        if (c == null) send(M.BLUETOOTH_GATT_ERROR_RESPONSE, M.gattError(address, handle, NOT_CONNECTED))
        else c.enqueue(handle) { op(c) }
    }

    /** Stops scanning and closes every connection (proxy turned off or all API clients gone). */
    fun shutdown(quitThread: Boolean) = handler.post {
        setScanning(false)
        for (c in connections.values.toList()) c.close(reportError = 0)
        connections.clear()
        if (bondReceiverRegistered) {
            try { context.unregisterReceiver(bondReceiver) } catch (_: Exception) {}
            bondReceiverRegistered = false
        }
        if (quitThread) thread.quitSafely()
    }

    /** All API clients disconnected: ESPHome drops the proxy's connections too. */
    fun dropConnections() = handler.post {
        for (c in connections.values.toList()) c.close(reportError = 0)
        connections.clear()
    }

    // ------------------------------------------------------------------ pairing

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val address = M.macToLong(device.address)
            handler.post {
                if (address !in pendingBonds) return@post
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        pendingBonds -= address
                        send(M.BLUETOOTH_DEVICE_PAIRING_RESPONSE, M.addressResult(address, true, 0))
                    }
                    BluetoothDevice.BOND_NONE -> {
                        pendingBonds -= address
                        send(M.BLUETOOTH_DEVICE_PAIRING_RESPONSE, M.addressResult(address, false, GATT_ERROR))
                    }
                }
            }
        }
    }

    private fun pair(address: Long) {
        val device = adapter?.getRemoteDevice(M.longToMac(address))
        if (device == null) {
            send(M.BLUETOOTH_DEVICE_PAIRING_RESPONSE, M.addressResult(address, false, GATT_ERROR))
            return
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            send(M.BLUETOOTH_DEVICE_PAIRING_RESPONSE, M.addressResult(address, true, 0))
            return
        }
        if (!bondReceiverRegistered) {
            context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), null, handler)
            bondReceiverRegistered = true
        }
        pendingBonds += address
        if (!device.createBond()) {
            pendingBonds -= address
            send(M.BLUETOOTH_DEVICE_PAIRING_RESPONSE, M.addressResult(address, false, GATT_ERROR))
        }
    }

    private fun unpair(address: Long) {
        val device = adapter?.getRemoteDevice(M.longToMac(address))
        val ok = try {
            device != null && (device.bondState == BluetoothDevice.BOND_NONE ||
                device.javaClass.getMethod("removeBond").invoke(device) as Boolean)
        } catch (e: Exception) {
            Log.w(TAG, "removeBond failed: ${e.message}")
            false
        }
        send(M.BLUETOOTH_DEVICE_UNPAIRING_RESPONSE, M.addressResult(address, ok, if (ok) 0 else GATT_ERROR))
    }

    // ------------------------------------------------------------------ one GATT connection

    private inner class Connection(val address: Long, val device: BluetoothDevice) : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null
        var ready = false
        var mtu = DEFAULT_MTU
        var services: List<M.GattService> = emptyList()
        private val characteristics = HashMap<Int, BluetoothGattCharacteristic>()
        private val descriptors = HashMap<Int, BluetoothGattDescriptor>()
        private val queue = ArrayDeque<Pair<Int, () -> Unit>>()
        private var currentHandle: Int? = null
        private var currentWriteNeedsResponse = false
        private val timeout = Runnable { onTimeout() }

        fun connect() {
            Log.i(TAG, "connecting to ${device.address}")
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
            handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        }

        fun disconnect() {
            val g = gatt ?: return close(0)
            if (!ready) return close(0)
            g.disconnect()
            // onConnectionStateChange(DISCONNECTED) closes; force it if the stack stays silent.
            handler.postDelayed({ if (connections[address] === this) close(0) }, DISCONNECT_TIMEOUT_MS)
        }

        fun close(reportError: Int) {
            handler.removeCallbacks(timeout)
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
            val wasRegistered = connections[address] === this
            if (wasRegistered) connections.remove(address)
            ready = false
            queue.clear()
            currentHandle = null
            if (wasRegistered) {
                send(M.BLUETOOTH_DEVICE_CONNECTION_RESPONSE, M.connectionResponse(address, false, 0, reportError))
                sendConnectionsFree()
            }
        }

        private fun onTimeout() {
            val handle = currentHandle
            if (!ready) {
                Log.w(TAG, "connect to ${device.address} timed out")
                close(CONNECT_TIMEOUT_ERROR)
            } else if (handle != null) {
                Log.w(TAG, "GATT operation on handle $handle timed out")
                finish(handle) { send(M.BLUETOOTH_GATT_ERROR_RESPONSE, M.gattError(address, handle, GATT_ERROR)) }
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (connections[address] !== this) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "connected to ${device.address}")
                if (!g.requestMtu(REQUESTED_MTU)) g.discoverServices()
            } else {
                Log.i(TAG, "${device.address} disconnected (status $status)")
                close(if (status == BluetoothGatt.GATT_SUCCESS) 0 else status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            if (!ready) g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (connections[address] !== this || ready) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                close(status)
                return
            }
            buildServices(g)
            handler.removeCallbacks(timeout)
            ready = true
            Log.i(TAG, "${device.address} ready: ${services.size} services, mtu $mtu")
            send(M.BLUETOOTH_DEVICE_CONNECTION_RESPONSE, M.connectionResponse(address, true, mtu, 0))
        }

        private fun buildServices(g: BluetoothGatt) {
            characteristics.clear()
            descriptors.clear()
            services = g.services.map { s ->
                M.GattService(s.uuid, s.instanceId, s.characteristics.map { c ->
                    characteristics[c.instanceId] = c
                    var synthetic = c.instanceId
                    M.GattCharacteristic(c.uuid, c.instanceId, c.properties, c.descriptors.map { d ->
                        val handle = descriptorHandle(d) ?: ++synthetic
                        descriptors[handle] = d
                        M.GattDescriptor(d.uuid, handle)
                    })
                })
            }
        }

        fun refreshCache(): Boolean = try {
            gatt?.javaClass?.getMethod("refresh")?.invoke(gatt) as? Boolean ?: true
        } catch (e: Exception) {
            false
        }

        fun enqueue(handle: Int, op: () -> Unit) {
            queue.addLast(handle to op)
            if (currentHandle == null) next()
        }

        private fun next() {
            val (handle, op) = queue.removeFirstOrNull() ?: return
            currentHandle = handle
            handler.postDelayed(timeout, OPERATION_TIMEOUT_MS)
            op()
        }

        /** Completes the running operation (if it is [handle]) and starts the next. */
        private fun finish(handle: Int, report: () -> Unit) {
            if (currentHandle != handle) return
            handler.removeCallbacks(timeout)
            currentHandle = null
            report()
            next()
        }

        private fun fail(handle: Int, error: Int) = finish(handle) { send(M.BLUETOOTH_GATT_ERROR_RESPONSE, M.gattError(address, handle, error)) }

        fun readCharacteristic(handle: Int) {
            val c = characteristics[handle] ?: return fail(handle, INVALID_HANDLE)
            if (gatt?.readCharacteristic(c) != true) fail(handle, GATT_ERROR)
        }

        @Suppress("DEPRECATION")
        fun writeCharacteristic(handle: Int, data: ByteArray, response: Boolean) {
            val c = characteristics[handle] ?: return fail(handle, INVALID_HANDLE)
            c.writeType = if (response) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = data
            currentWriteNeedsResponse = response
            if (gatt?.writeCharacteristic(c) != true) fail(handle, GATT_ERROR)
        }

        fun readDescriptor(handle: Int) {
            val d = descriptors[handle] ?: return fail(handle, INVALID_HANDLE)
            if (gatt?.readDescriptor(d) != true) fail(handle, GATT_ERROR)
        }

        @Suppress("DEPRECATION")
        fun writeDescriptor(handle: Int, data: ByteArray) {
            val d = descriptors[handle] ?: return fail(handle, INVALID_HANDLE)
            d.value = data
            if (gatt?.writeDescriptor(d) != true) fail(handle, GATT_ERROR)
        }

        /** Registers locally for notifications; Home Assistant writes the CCCD itself (REMOTE_CACHING). */
        fun setNotify(handle: Int, enable: Boolean) {
            val c = characteristics[handle]
            if (c == null || gatt?.setCharacteristicNotification(c, enable) != true) {
                send(M.BLUETOOTH_GATT_ERROR_RESPONSE, M.gattError(address, handle, if (c == null) INVALID_HANDLE else GATT_ERROR))
                return
            }
            send(M.BLUETOOTH_GATT_NOTIFY_RESPONSE, M.addressHandle(address, handle))
        }

        @Deprecated("API < 33 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val handle = c.instanceId
            if (status == BluetoothGatt.GATT_SUCCESS) finish(handle) { send(M.BLUETOOTH_GATT_READ_RESPONSE, M.addressHandleData(address, handle, c.value ?: ByteArray(0))) }
            else fail(handle, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val handle = c.instanceId
            if (status != BluetoothGatt.GATT_SUCCESS) return fail(handle, status)
            val respond = currentWriteNeedsResponse
            finish(handle) { if (respond) send(M.BLUETOOTH_GATT_WRITE_RESPONSE, M.addressHandle(address, handle)) }
        }

        @Deprecated("API < 33 callback")
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val handle = handleOf(d)
            if (status == BluetoothGatt.GATT_SUCCESS) finish(handle) { send(M.BLUETOOTH_GATT_READ_RESPONSE, M.addressHandleData(address, handle, d.value ?: ByteArray(0))) }
            else fail(handle, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val handle = handleOf(d)
            if (status == BluetoothGatt.GATT_SUCCESS) finish(handle) { send(M.BLUETOOTH_GATT_WRITE_RESPONSE, M.addressHandle(address, handle)) }
            else fail(handle, status)
        }

        @Deprecated("API < 33 callback")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            send(M.BLUETOOTH_GATT_NOTIFY_DATA_RESPONSE, M.addressHandleData(address, c.instanceId, c.value ?: ByteArray(0)))
        }

        private fun handleOf(d: BluetoothGattDescriptor): Int =
            descriptors.entries.firstOrNull { it.value === d }?.key ?: descriptorHandle(d) ?: currentHandle ?: 0
    }

    companion object {
        private const val TAG = "BleProxy"
        const val MAX_CONNECTIONS = 3
        private const val FLUSH_MS = 100L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val DISCONNECT_TIMEOUT_MS = 5_000L
        private const val OPERATION_TIMEOUT_MS = 15_000L
        private const val REQUESTED_MTU = 517
        private const val DEFAULT_MTU = 23
        val SCAN_MODE = ScanSettings.SCAN_MODE_LOW_LATENCY

        // Error codes in the ESP-IDF style ESPHome reports (esp_gatt_status_t / esp_err_t).
        const val GATT_ERROR = 0x85
        const val INVALID_HANDLE = 0x01
        const val NOT_CONNECTED = -1
        const val NO_FREE_SLOT = -2
        const val CONNECT_TIMEOUT_ERROR = 0x08 // GATT_CONN_TIMEOUT

        /** Android hides the descriptor's ATT handle (`getInstanceId()` is @hide). */
        private fun descriptorHandle(d: BluetoothGattDescriptor): Int? = try {
            d.javaClass.getMethod("getInstanceId").invoke(d) as? Int
        } catch (_: Exception) {
            null
        }

        /**
         * Address type as ESPHome reports it (0 public, 1 random). Android < 14 does not expose
         * it; only random static addresses (top bits 11) are recognisable. Resolvable private
         * addresses (01) collide with many public OUIs (Espressif, Xiaomi), so those count as
         * public. Home Assistant only hands the value back with a connect request, and Android
         * connects by the type it saw while scanning anyway.
         */
        fun addressType(address: Long): Int = if (((address ushr 40) and 0xC0).toInt() == 0xC0) 1 else 0
    }
}
