package io.github.simllll.panelelevate.esphome

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import io.github.simllll.panelelevate.esphome.EspHomeMessages as M

/**
 * ESPHome native API server (plaintext, no password) on TCP [port], answering just enough for
 * Home Assistant's ESPHome integration to use the device as a Bluetooth proxy: hello, device
 * info (with the Bluetooth proxy feature flags), an empty entity list, ping, time and the
 * Bluetooth proxy messages, which go to [BleProxy]. One thread per client; responses from the
 * BLE thread are sent to every client that completed the hello.
 */
class EspHomeApiServer(
    private val port: Int,
    private val deviceInfo: () -> M.DeviceInfo,
    private val proxy: BleProxy,
) {
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({ acceptLoop() }, "esphome-api").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        for (c in clients) c.close()
        clients.clear()
    }

    val clientCount: Int get() = clients.size

    /** Sends to every client that said hello (Bluetooth responses, advertisements). */
    fun broadcast(type: Int, payload: ByteArray) {
        val frame = ApiFrame(type, payload).encode()
        for (c in clients) if (c.helloDone) c.write(frame)
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
            serverSocket = ss
            Log.i(TAG, "listening on port $port")
            while (running) {
                val socket = ss.accept()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                val client = Client(socket)
                clients += client
                Thread({ client.run() }, "esphome-api-client").apply { isDaemon = true }.start()
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "server stopped: ${e.message}")
        }
    }

    private fun subscribersChanged() {
        val scanning = clients.any { it.advertisements }
        proxy.setScanning(scanning)
        if (clients.none { it.helloDone }) proxy.dropConnections()
    }

    private inner class Client(private val socket: Socket) {
        @Volatile var helloDone = false
        @Volatile var advertisements = false
        private val out: OutputStream = socket.getOutputStream()

        fun write(frame: ByteArray) {
            try {
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            } catch (e: IOException) {
                close()
            }
        }

        private fun reply(type: Int, payload: ByteArray = ByteArray(0)) = write(ApiFrame(type, payload).encode())

        fun close() {
            try { socket.close() } catch (_: IOException) {}
            if (clients.remove(this)) {
                Log.i(TAG, "client ${socket.inetAddress?.hostAddress} disconnected")
                subscribersChanged()
            }
        }

        fun run() {
            Log.i(TAG, "client ${socket.inetAddress?.hostAddress} connected")
            try {
                val input = BufferedInputStream(socket.getInputStream())
                while (true) {
                    val frame = ApiFrame.read(input) ?: break
                    handle(frame.type, ProtoMessage.parse(frame.payload))
                }
            } catch (e: IOException) {
                Log.i(TAG, "client error: ${e.message}")
            } finally {
                close()
            }
        }

        private fun handle(type: Int, msg: ProtoMessage) {
            when (type) {
                M.HELLO_REQUEST -> {
                    val info = deviceInfo()
                    Log.i(TAG, "hello from ${msg.string(1)} (API ${msg.int(2)}.${msg.int(3)})")
                    reply(M.HELLO_RESPONSE, M.helloResponse(info.name, "PanelElevate ${info.esphomeVersion}"))
                    helloDone = true
                }
                // No password: like ESPHome without auth, no AuthenticationResponse is sent.
                M.AUTHENTICATION_REQUEST -> Unit
                M.DEVICE_INFO_REQUEST -> reply(M.DEVICE_INFO_RESPONSE, M.deviceInfoResponse(deviceInfo()))
                M.LIST_ENTITIES_REQUEST -> reply(M.LIST_ENTITIES_DONE_RESPONSE)
                M.SUBSCRIBE_STATES_REQUEST, M.SUBSCRIBE_LOGS_REQUEST,
                M.SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST, M.SUBSCRIBE_HOME_ASSISTANT_STATES_REQUEST,
                M.GET_TIME_RESPONSE -> Unit
                M.PING_REQUEST -> reply(M.PING_RESPONSE)
                M.PING_RESPONSE -> Unit
                M.DISCONNECT_REQUEST -> {
                    reply(M.DISCONNECT_RESPONSE)
                    close()
                }
                M.DISCONNECT_RESPONSE -> close()

                M.SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> {
                    if (msg.int(1) and M.SUBSCRIPTION_RAW == 0) Log.w(TAG, "client wants legacy advertisements; only raw ones are sent")
                    advertisements = true
                    subscribersChanged()
                    proxy.sendScannerState()
                }
                M.UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST -> {
                    advertisements = false
                    subscribersChanged()
                }
                M.BLUETOOTH_SCANNER_SET_MODE_REQUEST -> proxy.sendScannerState() // always active on Android
                M.SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST -> proxy.sendConnectionsFree()
                M.BLUETOOTH_DEVICE_REQUEST -> proxy.deviceRequest(msg.long(1), msg.int(2))
                M.BLUETOOTH_GATT_GET_SERVICES_REQUEST -> proxy.gattGetServices(msg.long(1))
                M.BLUETOOTH_GATT_READ_REQUEST -> proxy.gattRead(msg.long(1), msg.int(2))
                M.BLUETOOTH_GATT_WRITE_REQUEST -> proxy.gattWrite(msg.long(1), msg.int(2), msg.bytes(4), msg.bool(3))
                M.BLUETOOTH_GATT_READ_DESCRIPTOR_REQUEST -> proxy.gattReadDescriptor(msg.long(1), msg.int(2))
                M.BLUETOOTH_GATT_WRITE_DESCRIPTOR_REQUEST -> proxy.gattWriteDescriptor(msg.long(1), msg.int(2), msg.bytes(3))
                M.BLUETOOTH_GATT_NOTIFY_REQUEST -> proxy.gattNotify(msg.long(1), msg.int(2), msg.bool(3))
                else -> Log.d(TAG, "ignored message type $type")
            }
        }
    }

    private companion object {
        const val TAG = "EspHomeApi"
    }
}
