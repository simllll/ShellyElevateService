package com.stretter.shellyelevateservice.esphome

import java.util.UUID

/**
 * Message ids and encoders of the ESPHome native API subset a Bluetooth proxy needs, taken
 * from aioesphomeapi 46.25.0 (`api.proto`, the version Home Assistant 2026.11 uses).
 */
object EspHomeMessages {
    // Connection
    const val HELLO_REQUEST = 1
    const val HELLO_RESPONSE = 2
    const val AUTHENTICATION_REQUEST = 3
    const val AUTHENTICATION_RESPONSE = 4
    const val DISCONNECT_REQUEST = 5
    const val DISCONNECT_RESPONSE = 6
    const val PING_REQUEST = 7
    const val PING_RESPONSE = 8
    const val DEVICE_INFO_REQUEST = 9
    const val DEVICE_INFO_RESPONSE = 10
    const val LIST_ENTITIES_REQUEST = 11
    const val LIST_ENTITIES_DONE_RESPONSE = 19
    const val SUBSCRIBE_STATES_REQUEST = 20
    const val SUBSCRIBE_LOGS_REQUEST = 28
    const val SUBSCRIBE_HOMEASSISTANT_SERVICES_REQUEST = 34
    const val GET_TIME_REQUEST = 36
    const val GET_TIME_RESPONSE = 37
    const val SUBSCRIBE_HOME_ASSISTANT_STATES_REQUEST = 38

    // Bluetooth proxy
    const val SUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST = 66
    const val BLUETOOTH_DEVICE_REQUEST = 68
    const val BLUETOOTH_DEVICE_CONNECTION_RESPONSE = 69
    const val BLUETOOTH_GATT_GET_SERVICES_REQUEST = 70
    const val BLUETOOTH_GATT_GET_SERVICES_RESPONSE = 71
    const val BLUETOOTH_GATT_GET_SERVICES_DONE_RESPONSE = 72
    const val BLUETOOTH_GATT_READ_REQUEST = 73
    const val BLUETOOTH_GATT_READ_RESPONSE = 74
    const val BLUETOOTH_GATT_WRITE_REQUEST = 75
    const val BLUETOOTH_GATT_READ_DESCRIPTOR_REQUEST = 76
    const val BLUETOOTH_GATT_WRITE_DESCRIPTOR_REQUEST = 77
    const val BLUETOOTH_GATT_NOTIFY_REQUEST = 78
    const val BLUETOOTH_GATT_NOTIFY_DATA_RESPONSE = 79
    const val SUBSCRIBE_BLUETOOTH_CONNECTIONS_FREE_REQUEST = 80
    const val BLUETOOTH_CONNECTIONS_FREE_RESPONSE = 81
    const val BLUETOOTH_GATT_ERROR_RESPONSE = 82
    const val BLUETOOTH_GATT_WRITE_RESPONSE = 83
    const val BLUETOOTH_GATT_NOTIFY_RESPONSE = 84
    const val BLUETOOTH_DEVICE_PAIRING_RESPONSE = 85
    const val BLUETOOTH_DEVICE_UNPAIRING_RESPONSE = 86
    const val UNSUBSCRIBE_BLUETOOTH_LE_ADVERTISEMENTS_REQUEST = 87
    const val BLUETOOTH_DEVICE_CLEAR_CACHE_RESPONSE = 88
    const val BLUETOOTH_LE_RAW_ADVERTISEMENTS_RESPONSE = 93
    const val BLUETOOTH_SCANNER_STATE_RESPONSE = 126
    const val BLUETOOTH_SCANNER_SET_MODE_REQUEST = 127

    // BluetoothDeviceRequestType
    const val REQUEST_CONNECT = 0
    const val REQUEST_DISCONNECT = 1
    const val REQUEST_PAIR = 2
    const val REQUEST_UNPAIR = 3
    const val REQUEST_CONNECT_V3_WITH_CACHE = 4
    const val REQUEST_CONNECT_V3_WITHOUT_CACHE = 5
    const val REQUEST_CLEAR_CACHE = 6

    // BluetoothProxyFeature (aioesphomeapi model.py)
    const val FEATURE_PASSIVE_SCAN = 1 shl 0
    const val FEATURE_ACTIVE_CONNECTIONS = 1 shl 1
    const val FEATURE_REMOTE_CACHING = 1 shl 2
    const val FEATURE_PAIRING = 1 shl 3
    const val FEATURE_CACHE_CLEARING = 1 shl 4
    const val FEATURE_RAW_ADVERTISEMENTS = 1 shl 5
    const val FEATURE_STATE_AND_MODE = 1 shl 6

    /** BluetoothProxySubscriptionFlag.RAW_ADVERTISEMENTS */
    const val SUBSCRIPTION_RAW = 1

    // BluetoothScannerState / BluetoothScannerMode
    const val SCANNER_STATE_IDLE = 0
    const val SCANNER_STATE_RUNNING = 2
    const val SCANNER_STATE_FAILED = 3
    const val SCANNER_MODE_PASSIVE = 0
    const val SCANNER_MODE_ACTIVE = 1

    /** The API version we speak (aioesphomeapi sends 1.19 and accepts any 1.x). */
    const val API_MAJOR = 1
    const val API_MINOR = 12

    /** Advertisements per BluetoothLERawAdvertisementsResponse (ESPHome's batch size). */
    const val ADVERTISEMENT_BATCH = 16

    fun macToLong(mac: String): Long {
        var v = 0L
        for (part in mac.split(':')) v = (v shl 8) or part.toLong(16)
        return v
    }

    fun longToMac(value: Long): String =
        (5 downTo 0).joinToString(":") { i -> "%02X".format((value ushr (8 * i)) and 0xFF) }

    fun helloResponse(name: String, serverInfo: String): ByteArray = ProtoWriter().apply {
        uint(1, API_MAJOR.toLong())
        uint(2, API_MINOR.toLong())
        string(3, serverInfo)
        string(4, name)
    }.bytes()

    data class DeviceInfo(
        val name: String,
        val friendlyName: String,
        val macAddress: String,
        val bluetoothMacAddress: String,
        val model: String,
        val manufacturer: String,
        val esphomeVersion: String,
        val compilationTime: String,
        val featureFlags: Int,
        val suggestedArea: String? = null,
    )

    fun deviceInfoResponse(info: DeviceInfo): ByteArray = ProtoWriter().apply {
        string(2, info.name)
        string(3, info.macAddress)
        string(4, info.esphomeVersion)
        string(5, info.compilationTime)
        string(6, info.model)
        string(12, info.manufacturer)
        string(13, info.friendlyName)
        uint(15, info.featureFlags.toLong())
        string(16, info.suggestedArea)
        string(18, info.bluetoothMacAddress)
    }.bytes()

    fun getTimeResponse(epochSeconds: Long): ByteArray = ProtoWriter().apply { fixed32(1, epochSeconds.toInt()) }.bytes()

    data class RawAdvertisement(val address: Long, val rssi: Int, val addressType: Int, val data: ByteArray)

    fun rawAdvertisements(ads: List<RawAdvertisement>): ByteArray = ProtoWriter().apply {
        for (ad in ads) {
            message(1, ProtoWriter().apply {
                uint(1, ad.address)
                sint32(2, ad.rssi)
                uint(3, ad.addressType.toLong())
                bytes(4, ad.data)
            }.bytes())
        }
    }.bytes()

    fun connectionResponse(address: Long, connected: Boolean, mtu: Int, error: Int): ByteArray = ProtoWriter().apply {
        uint(1, address)
        bool(2, connected)
        uint(3, mtu.toLong())
        int32(4, error)
    }.bytes()

    fun connectionsFree(free: Int, limit: Int, allocated: List<Long>): ByteArray = ProtoWriter().apply {
        uint(1, free.toLong())
        uint(2, limit.toLong())
        packedUint(3, allocated)
    }.bytes()

    fun addressHandle(address: Long, handle: Int): ByteArray = ProtoWriter().apply {
        uint(1, address)
        uint(2, handle.toLong())
    }.bytes()

    fun addressHandleData(address: Long, handle: Int, data: ByteArray): ByteArray = ProtoWriter().apply {
        uint(1, address)
        uint(2, handle.toLong())
        bytes(3, data)
    }.bytes()

    fun gattError(address: Long, handle: Int, error: Int): ByteArray = ProtoWriter().apply {
        uint(1, address)
        uint(2, handle.toLong())
        int32(3, error)
    }.bytes()

    fun addressResult(address: Long, ok: Boolean, error: Int): ByteArray = ProtoWriter().apply {
        uint(1, address)
        bool(2, ok)
        int32(3, error)
    }.bytes()

    fun addressOnly(address: Long): ByteArray = ProtoWriter().apply { uint(1, address) }.bytes()

    fun scannerState(state: Int, mode: Int, configuredMode: Int): ByteArray = ProtoWriter().apply {
        uint(1, state.toLong())
        uint(2, mode.toLong())
        uint(3, configuredMode.toLong())
    }.bytes()

    data class GattDescriptor(val uuid: UUID, val handle: Int)
    data class GattCharacteristic(val uuid: UUID, val handle: Int, val properties: Int, val descriptors: List<GattDescriptor>)
    data class GattService(val uuid: UUID, val handle: Int, val characteristics: List<GattCharacteristic>)

    /** UUIDs as the split 128-bit `repeated uint64 uuid = 1` (high, low). */
    private fun ProtoWriter.uuid(uuid: UUID) {
        packedUint(1, listOf(uuid.mostSignificantBits, uuid.leastSignificantBits))
    }

    fun gattServices(address: Long, services: List<GattService>): ByteArray = ProtoWriter().apply {
        uint(1, address)
        for (s in services) {
            message(2, ProtoWriter().apply {
                uuid(s.uuid)
                uint(2, s.handle.toLong())
                for (c in s.characteristics) {
                    message(3, ProtoWriter().apply {
                        uuid(c.uuid)
                        uint(2, c.handle.toLong())
                        uint(3, c.properties.toLong())
                        for (d in c.descriptors) {
                            message(4, ProtoWriter().apply {
                                uuid(d.uuid)
                                uint(2, d.handle.toLong())
                            }.bytes())
                        }
                    }.bytes())
                }
            }.bytes())
        }
    }.bytes()

    /**
     * The AD structures of an Android scan record: `ScanRecord.getBytes()` is the advertisement
     * plus scan response padded with zeros to 62 bytes; the padding (a zero-length structure)
     * is cut off.
     */
    fun trimScanRecord(record: ByteArray): ByteArray {
        var pos = 0
        while (pos < record.size) {
            val len = record[pos].toInt() and 0xFF
            if (len == 0) break
            if (pos + 1 + len > record.size) break
            pos += 1 + len
        }
        return record.copyOf(pos)
    }
}
