package com.stretter.shellyelevateservice.esphome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.util.Log
import com.stretter.shellyelevateservice.DeviceModel
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.stretter.shellyelevateservice.esphome.EspHomeMessages as M

/**
 * ShellyElevate as an ESPHome Bluetooth proxy (setting `bleProxyEnabled`): the native API on
 * port [PORT], BLE scanning/GATT via [BleProxy] and the `_esphomelib._tcp` mDNS service, so
 * Home Assistant's ESPHome integration discovers the panel like an ESP32 proxy.
 *
 * Needs Bluetooth on, location on and the location permission (`pm grant … ACCESS_FINE_LOCATION`).
 */
class EspHomeBluetoothProxy(context: Context, private val deviceId: () -> String) {
    private val context = context.applicationContext
    private var proxy: BleProxy? = null
    private var server: EspHomeApiServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == isRunning) return
        if (enabled) start() else stop()
    }

    private fun start() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted: BLE scans return nothing " +
                "(adb shell pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION)")
        }
        val p = BleProxy(context) { type, payload -> server?.broadcast(type, payload) }
        if (!p.bluetoothAvailable) Log.w(TAG, "Bluetooth is off or missing")
        val s = EspHomeApiServer(PORT, ::deviceInfo, p)
        proxy = p
        server = s
        s.start()
        registerMdns()
        Log.i(TAG, "ESPHome Bluetooth proxy started as ${nodeName()}")
    }

    private fun stop() {
        unregisterMdns()
        server?.stop()
        proxy?.shutdown(quitThread = true)
        server = null
        proxy = null
        Log.i(TAG, "ESPHome Bluetooth proxy stopped")
    }

    /** ESPHome node name: `shellyelevate-<mqttDeviceId>`, lowercase letters, digits and dashes, max 31 chars. */
    fun nodeName(): String = nodeName(deviceId())

    private fun deviceInfo(): M.DeviceInfo {
        val model = DeviceModel.getReportedDevice()
        return M.DeviceInfo(
            name = nodeName(),
            friendlyName = "ShellyElevate ${deviceId()}",
            macAddress = wifiMac() ?: bluetoothMac() ?: "00:00:00:00:00:00",
            bluetoothMacAddress = bluetoothMac() ?: "",
            model = model.friendlyName,
            manufacturer = "ShellyElevate",
            esphomeVersion = ESPHOME_VERSION,
            compilationTime = SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.US).format(Date(installTime())),
            featureFlags = FEATURE_FLAGS,
        )
    }

    private fun installTime(): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Exception) {
        System.currentTimeMillis()
    }

    private fun bluetoothMac(): String? = try {
        Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            ?.uppercase(Locale.US)?.takeIf { MAC.matches(it) && it != "02:00:00:00:00:00" }
    } catch (_: Exception) {
        null
    }

    private fun wifiMac(): String? = try {
        NetworkInterface.getByName("wlan0")?.hardwareAddress?.takeIf { it.size == 6 }
            ?.joinToString(":") { "%02X".format(it) }?.takeIf { it != "02:00:00:00:00:00" }
    } catch (_: Exception) {
        null
    }

    private fun registerMdns() {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = deviceInfo()
        val service = NsdServiceInfo().apply {
            serviceName = info.name
            serviceType = "_esphomelib._tcp"
            port = PORT
            setAttribute("friendly_name", info.friendlyName)
            setAttribute("version", ESPHOME_VERSION)
            setAttribute("mac", info.macAddress.replace(":", "").lowercase(Locale.US))
            setAttribute("platform", "ESP32")
            setAttribute("board", "shellyelevate")
            setAttribute("network", "wifi")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "mDNS registered: ${s.serviceName}") }
            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "mDNS registration failed: $errorCode") }
            override fun onServiceUnregistered(s: NsdServiceInfo) { Log.i(TAG, "mDNS unregistered") }
            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "mDNS unregistration failed: $errorCode") }
        }
        try {
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener)
            nsdListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "mDNS registration failed: ${e.message}")
        }
    }

    private fun unregisterMdns() {
        val listener = nsdListener ?: return
        nsdListener = null
        try {
            (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unregistration failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "EspHomeProxy"
        const val PORT = 6053

        /** Reported as the ESPHome version; recent enough that HA does not flag the proxy as outdated. */
        const val ESPHOME_VERSION = "2026.10.0"

        /** Passive scanning, GATT connections with HA-side caching, pairing, cache clearing, raw advertisements, scanner state. */
        const val FEATURE_FLAGS = M.FEATURE_PASSIVE_SCAN or M.FEATURE_ACTIVE_CONNECTIONS or M.FEATURE_REMOTE_CACHING or
            M.FEATURE_PAIRING or M.FEATURE_CACHE_CLEARING or M.FEATURE_RAW_ADVERTISEMENTS or M.FEATURE_STATE_AND_MODE

        private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        fun nodeName(deviceId: String): String {
            val id = deviceId.lowercase(Locale.US).replace(Regex("[^a-z0-9-]+"), "-").trim('-')
                .removePrefix("shellyelevate-")
            return "shellyelevate-$id".take(31).trimEnd('-')
        }
    }
}
