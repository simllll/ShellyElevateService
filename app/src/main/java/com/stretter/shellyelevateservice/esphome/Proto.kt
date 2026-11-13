package com.stretter.shellyelevateservice.esphome

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Minimal protobuf encoding for the ESPHome native API (only the wire types it uses:
 * varint, fixed32, fixed64 and length-delimited). Field numbers and types come from
 * aioesphomeapi's `api.proto`.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun bytes(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    /** uint32/uint64/int32 (negative int32 is sign-extended to 10 bytes like protobuf). Zero is skipped (proto3). */
    fun uint(field: Int, value: Long) {
        if (value == 0L) return
        tag(field, WIRE_VARINT)
        rawVarint(value)
    }

    /** A repeated uint32/uint64 field, packed like protobuf's proto3 default (nothing when empty). */
    fun packedUint(field: Int, values: List<Long>) {
        if (values.isEmpty()) return
        val inner = ProtoWriter()
        for (v in values) inner.rawVarint(v)
        bytes(field, inner.bytes())
    }

    fun int32(field: Int, value: Int) = uint(field, value.toLong())

    fun sint32(field: Int, value: Int) {
        if (value == 0) return
        tag(field, WIRE_VARINT)
        rawVarint(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)
    }

    fun bool(field: Int, value: Boolean) {
        if (!value) return
        tag(field, WIRE_VARINT)
        out.write(1)
    }

    fun fixed32(field: Int, value: Int) {
        if (value == 0) return
        tag(field, WIRE_FIXED32)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
    }

    fun string(field: Int, value: String?) {
        if (value.isNullOrEmpty()) return
        bytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    /** An embedded message (always written, even when empty, like a repeated element). */
    fun message(field: Int, value: ByteArray) {
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5
    }
}

/** Decoded fields of a message: field number → raw values (varints as Long, length-delimited as ByteArray). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun long(field: Int): Long = (fields[field]?.lastOrNull() as? Long) ?: 0L
    fun int(field: Int): Int = long(field).toInt()
    fun bool(field: Int): Boolean = long(field) != 0L
    fun bytes(field: Int): ByteArray = (fields[field]?.lastOrNull() as? ByteArray) ?: ByteArray(0)
    fun string(field: Int): String = String(bytes(field), Charsets.UTF_8)
    fun has(field: Int): Boolean = fields.containsKey(field)

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = HashMap<Int, MutableList<Any>>()
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    if (pos >= data.size) throw IOException("truncated varint")
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    if (shift > 63) throw IOException("varint too long")
                }
            }
            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> varint()
                    ProtoWriter.WIRE_FIXED64 -> {
                        if (pos + 8 > data.size) throw IOException("truncated fixed64")
                        var v = 0L
                        for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                        pos += 8
                        v
                    }
                    ProtoWriter.WIRE_LENGTH -> {
                        val len = varint().toInt()
                        if (len < 0 || pos + len > data.size) throw IOException("truncated field")
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    ProtoWriter.WIRE_FIXED32 -> {
                        if (pos + 4 > data.size) throw IOException("truncated fixed32")
                        var v = 0L
                        for (i in 0 until 4) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                        pos += 4
                        v
                    }
                    else -> throw IOException("unsupported wire type ${key and 7}")
                }
                fields.getOrPut(field) { ArrayList() }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}

/**
 * Plaintext frame of the native API: `0x00`, varint payload length, varint message type,
 * payload (aioesphomeapi `_frame_helper/plain_text.py`).
 */
data class ApiFrame(val type: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        val w = ProtoWriter()
        w.rawVarint(0)
        w.rawVarint(payload.size.toLong())
        w.rawVarint(type.toLong())
        return w.bytes() + payload
    }

    override fun equals(other: Any?) = other is ApiFrame && other.type == type && other.payload.contentEquals(payload)
    override fun hashCode() = type * 31 + payload.contentHashCode()

    companion object {
        const val MAX_FRAME = 65535

        /**
         * Reads one frame; null at end of stream. A first byte other than 0x00 (e.g. 0x01, the
         * Noise preamble of an encrypted client) is a protocol error.
         */
        fun read(input: InputStream): ApiFrame? {
            val preamble = input.read()
            if (preamble < 0) return null
            if (preamble != 0) throw IOException("unexpected preamble $preamble (encrypted client?)")
            val length = readVarint(input)
            if (length > MAX_FRAME) throw IOException("frame too long: $length")
            val type = readVarint(input)
            val payload = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(payload, read, length - read)
                if (n < 0) throw IOException("truncated frame")
                read += n
            }
            return ApiFrame(type, payload)
        }

        private fun readVarint(input: InputStream): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("truncated varint")
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift >= 28) throw IOException("varint too long")
            }
        }
    }
}
