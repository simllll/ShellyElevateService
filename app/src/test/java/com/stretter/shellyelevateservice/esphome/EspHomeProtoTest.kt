package com.stretter.shellyelevateservice.esphome

import com.stretter.shellyelevateservice.esphome.EspHomeMessages as M
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.UUID

/**
 * Expected bytes were produced with aioesphomeapi 46.25.0's generated protobuf classes
 * (`api_pb2`, the version Home Assistant 2026.11 uses).
 */
class EspHomeProtoTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parses a HelloRequest`() {
        val msg = ProtoMessage.parse(hex("0a18486f6d6520417373697374616e7420323032362e31312e3110011813"))
        assertEquals("Home Assistant 2026.11.1", msg.string(1))
        assertEquals(1, msg.int(2))
        assertEquals(19, msg.int(3))
    }

    @Test
    fun `encodes HelloResponse and DeviceInfoResponse like protobuf`() {
        assertArrayEquals(
            hex("0801100c1a175368656c6c79456c657661746520323032362e31302e3022157368656c6c79656c65766174652d6e7370616e656c"),
            M.helloResponse("shellyelevate-nspanel", "ShellyElevate 2026.10.0"),
        )
        val info = M.DeviceInfo(
            name = "shellyelevate-nspanel", friendlyName = "ShellyElevate nspanel",
            macAddress = "7C:88:99:05:FF:E8", bluetoothMacAddress = "7C:88:99:05:FF:E9",
            model = "NSPanel Pro", manufacturer = "ShellyElevate", esphomeVersion = "2026.10.0",
            compilationTime = "Nov 13 2026, 07:00:00", featureFlags = 127,
        )
        assertArrayEquals(
            hex(
                "12157368656c6c79656c65766174652d6e7370616e656c1a1137433a38383a39393a30353a46463a4538220932" +
                    "3032362e31302e302a154e6f7620313320323032362c2030373a30303a3030320b4e5350616e656c2050726f62" +
                    "0d5368656c6c79456c65766174656a155368656c6c79456c6576617465206e7370616e656c787f92011137433a" +
                    "38383a39393a30353a46463a4539"
            ),
            M.deviceInfoResponse(info),
        )
    }

    @Test
    fun `encodes raw advertisements with zigzag rssi`() {
        val bytes = M.rawAdvertisements(
            listOf(
                M.RawAdvertisement(0xC4D0E3A1B2C3L, -67, 1, hex("0201061aff4c000215")),
                M.RawAdvertisement(0x7C8899000001L, -90, 0, hex("020106")),
            )
        )
        assertArrayEquals(hex("0a1808c3e5869d8e9a31108501180122090201061aff4c0002150a1008818080c889911f10b3012203020106"), bytes)
    }

    @Test
    fun `encodes connection, slots, services, time and scanner state`() {
        val address = 0x001122334455L
        assertArrayEquals(hex("08d588cd91920220ffffffffffffffffff01"), M.connectionResponse(address, false, 0, -1))
        assertArrayEquals(hex("080210031a06d588cd919202"), M.connectionsFree(2, 3, listOf(address)))
        val services = listOf(
            M.GattService(
                UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), 40,
                listOf(
                    M.GattCharacteristic(
                        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"), 42, 0x12,
                        listOf(M.GattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), 43)),
                    )
                ),
            )
        )
        assertArrayEquals(
            hex(
                "08d588cd91920212450a1180a08080f08106fbe9ecfc85908080800110281a2e0a1180a0808090c30afbe9ecfc85" +
                    "9080808001102a181222150a1180a08080a0a00afbe9ecfc859080808001102b"
            ),
            M.gattServices(address, services),
        )
        assertArrayEquals(hex("0d407d1569"), M.getTimeResponse(1763016000))
        assertArrayEquals(hex("080210011801"), M.scannerState(2, 1, 1))
    }

    @Test
    fun `parses device and write requests`() {
        val req = ProtoMessage.parse(hex("08d588cd919202100518012001"))
        assertEquals(0x001122334455L, req.long(1))
        assertEquals(M.REQUEST_CONNECT_V3_WITHOUT_CACHE, req.int(2))
        assertEquals(1, req.int(4))
        val write = ProtoMessage.parse(hex("08d588cd919202102a180122020102"))
        assertEquals(42, write.int(2))
        assertTrue(write.bool(3))
        assertArrayEquals(byteArrayOf(1, 2), write.bytes(4))
    }

    @Test
    fun `frames round-trip and reject the noise preamble`() {
        val empty = ApiFrame(M.PING_REQUEST, ByteArray(0))
        assertArrayEquals(byteArrayOf(0, 0, 7), empty.encode())
        val big = ApiFrame(M.DEVICE_INFO_RESPONSE, ByteArray(200) { 'x'.code.toByte() })
        assertArrayEquals(hex("00c8010a"), big.encode().copyOf(4))
        val stream = ByteArrayInputStream(empty.encode() + big.encode())
        assertEquals(empty, ApiFrame.read(stream))
        assertEquals(big, ApiFrame.read(stream))
        assertNull(ApiFrame.read(stream))
        try {
            ApiFrame.read(ByteArrayInputStream(byteArrayOf(1, 0, 0)))
            fail("noise preamble accepted")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `mac conversion, scan record trimming, address type and node name`() {
        assertEquals(0x7C889905FFE9L, M.macToLong("7C:88:99:05:FF:E9"))
        assertEquals("7C:88:99:05:FF:E9", M.longToMac(0x7C889905FFE9L))
        val record = hex("0201060303aafe") + ByteArray(55)
        assertArrayEquals(hex("0201060303aafe"), M.trimScanRecord(record))
        assertEquals(1, BleProxy.addressType(0xC4D0E3A1B2C3L))
        assertEquals(0, BleProxy.addressType(0x00A90B701489L))
        assertEquals(0, BleProxy.addressType(0x6CC8407DE30EL)) // Espressif OUI, not a resolvable address
        assertEquals("shellyelevate-c89", EspHomeBluetoothProxy.nodeName("shellyelevate-c89"))
        assertEquals("shellyelevate-nspanel-eingang", EspHomeBluetoothProxy.nodeName("NSPanel Eingang"))
        assertTrue(EspHomeBluetoothProxy.nodeName("a-very-long-device-identifier-for-testing").length <= 31)
    }
}
