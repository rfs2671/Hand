package com.hand.bridge.atv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Minimal protobuf encoder: just what the Android TV remote protocol needs. */
class PbWriter {
    private val out = ByteArrayOutputStream()

    fun varint(v: Long): PbWriter {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
        return this
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, v: Int): PbWriter = tag(field, 0).varint(v.toLong())
    fun bool(field: Int, v: Boolean): PbWriter = int(field, if (v) 1 else 0)

    fun bytes(field: Int, v: ByteArray): PbWriter {
        tag(field, 2).varint(v.size.toLong())
        out.write(v)
        return this
    }

    fun string(field: Int, v: String): PbWriter = bytes(field, v.toByteArray(Charsets.UTF_8))
    fun message(field: Int, v: PbWriter): PbWriter = bytes(field, v.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

class PbField(val number: Int, val wire: Int, val value: Long, val bytes: ByteArray?) {
    val string get() = bytes?.toString(Charsets.UTF_8) ?: ""
    fun message() = PbMessage.parse(bytes ?: ByteArray(0))
}

/** Parsed protobuf message: fields in wire order. */
class PbMessage(val fields: List<PbField>) {
    fun has(n: Int) = fields.any { it.number == n }
    fun get(n: Int) = fields.lastOrNull { it.number == n }
    fun int(n: Int, default: Int = 0) = get(n)?.value?.toInt() ?: default
    fun string(n: Int) = get(n)?.string ?: ""
    fun message(n: Int) = get(n)?.message()

    companion object {
        fun parse(b: ByteArray): PbMessage {
            val fields = ArrayList<PbField>()
            var pos = 0
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    if (pos >= b.size || shift > 63) throw IOException("bad varint")
                    val c = b[pos++].toInt()
                    result = result or ((c and 0x7F).toLong() shl shift)
                    if (c and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (pos < b.size) {
                val key = varint()
                val number = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    0 -> fields += PbField(number, wire, varint(), null)
                    1 -> { pos += 8; if (pos > b.size) throw IOException("truncated"); fields += PbField(number, wire, 0, null) }
                    5 -> { pos += 4; if (pos > b.size) throw IOException("truncated"); fields += PbField(number, wire, 0, null) }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || pos + len > b.size) throw IOException("truncated")
                        fields += PbField(number, wire, 0, b.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    else -> throw IOException("unsupported wire type $wire")
                }
            }
            return PbMessage(fields)
        }
    }
}

/** Messages on both TLS channels are prefixed with their length as a varint. */
object Framing {
    private const val MAX = 1 shl 20

    fun write(out: OutputStream, payload: ByteArray) {
        val buf = ByteArrayOutputStream()
        var x = payload.size.toLong()
        while (x and 0x7FL.inv() != 0L) {
            buf.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        buf.write(x.toInt())
        buf.write(payload)
        out.write(buf.toByteArray())
        out.flush()
    }

    /** Returns null on a clean end of stream. */
    fun read(input: InputStream): ByteArray? {
        var len = 0
        var shift = 0
        while (true) {
            val c = input.read()
            if (c < 0) return null
            len = len or ((c and 0x7F) shl shift)
            if (c and 0x80 == 0) break
            shift += 7
            if (shift > 28) throw IOException("bad frame length")
        }
        if (len < 0 || len > MAX) throw IOException("frame too large: $len")
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(data, off, len - off)
            if (n < 0) throw IOException("stream ended mid-frame")
            off += n
        }
        return data
    }
}
