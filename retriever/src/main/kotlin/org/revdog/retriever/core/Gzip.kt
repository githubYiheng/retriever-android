package org.revdog.retriever.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/**
 * gzip（§3.9）：`GZIPOutputStream` → 单成员标准 gzip、无尾随字节（否则服务端按损坏隔离）。
 * 解压用于读回出站箱批次（元数据、413 切分、驱逐墓碑）：严格模式——必须恰好一个成员、正常结束、
 * CRC / ISIZE 对得上、无尾随字节（`GZIPInputStream` 会吞多成员与尾随垃圾，不用它）。
 */
internal object Gzip {
    fun compress(input: ByteArray): ByteArray? = try {
        val bos = ByteArrayOutputStream(input.size / 4 + 64)
        GZIPOutputStream(bos, 64 * 1024).use { it.write(input) }
        bos.toByteArray()
    } catch (e: IOException) {
        null
    }

    fun decompress(input: ByteArray, limit: Int = 64 * 1024 * 1024): ByteArray? {
        if (input.size < 18) return null
        if ((input[0].toInt() and 0xFF) != 0x1F || (input[1].toInt() and 0xFF) != 0x8B || input[2].toInt() != 8) return null
        val flg = input[3].toInt() and 0xFF
        if (flg and 0xE0 != 0) return null
        var pos = 10
        if (flg and 0x04 != 0) {
            if (pos + 2 > input.size) return null
            val xlen = (input[pos].toInt() and 0xFF) or ((input[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2 + xlen
        }
        if (flg and 0x08 != 0) pos = skipZ(input, pos) ?: return null
        if (flg and 0x10 != 0) pos = skipZ(input, pos) ?: return null
        if (flg and 0x02 != 0) pos += 2
        if (pos >= input.size) return null
        val inf = Inflater(true)
        try {
            inf.setInput(input, pos, input.size - pos)
            val out = ByteArrayOutputStream(minOf(input.size * 6, limit))
            val chunk = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(chunk)
                if (n > 0) {
                    out.write(chunk, 0, n)
                    if (out.size() > limit) return null
                } else if (inf.needsInput() || inf.needsDictionary()) {
                    return null
                }
            }
            // 恰好剩 8 字节 trailer（CRC32 + ISIZE），多一个字节都算尾随垃圾
            if (inf.remaining != 8) return null
            val t = input.size - 8
            val bytes = out.toByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            if (le32(input, t) != crc) return null
            if (le32(input, t + 4) != (bytes.size.toLong() and 0xFFFFFFFFL)) return null
            return bytes
        } catch (e: DataFormatException) {
            return null
        } finally {
            inf.end()
        }
    }

    private fun skipZ(b: ByteArray, from: Int): Int? {
        var p = from
        while (p < b.size && b[p].toInt() != 0) p++
        return if (p < b.size) p + 1 else null
    }

    private fun le32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
            ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)
}
