package com.kite.zmusic.plugin

import java.util.zip.CRC32
import java.util.zip.Deflater

/** 固定的纯白 PNG，不读取屏幕。 */
internal object BetterNcmImages {
    fun whitePng(edge: Int = 8): ByteArray {
        val size = edge.coerceIn(1, 64)
        val row = ByteArray(1 + size * 3)
        for (x in 0 until size) {
            row[1 + x * 3] = 0xFF.toByte()
            row[2 + x * 3] = 0xFF.toByte()
            row[3 + x * 3] = 0xFF.toByte()
        }
        val raw = ByteArray(size * row.size)
        for (y in 0 until size) {
            row.copyInto(raw, y * row.size)
        }
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val compressed = ByteArray(raw.size + 64)
        val n = deflater.deflate(compressed)
        deflater.end()
        val idat = compressed.copyOf(n)
        val out = ArrayList<Byte>(64 + idat.size)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).forEach { out.add(it) }
        chunk(out, "IHDR", ihdr(size))
        chunk(out, "IDAT", idat)
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ihdr(size: Int): ByteArray {
        val data = ByteArray(13)
        writeInt(data, 0, size)
        writeInt(data, 4, size)
        data[8] = 8
        data[9] = 2
        return data
    }

    private fun chunk(out: ArrayList<Byte>, type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeInt(len, 0, data.size)
        len.forEach { out.add(it) }
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        typeBytes.forEach { out.add(it) }
        data.forEach { out.add(it) }
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val sum = ByteArray(4)
        writeInt(sum, 0, crc.value.toInt())
        sum.forEach { out.add(it) }
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }
}
