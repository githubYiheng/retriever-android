package org.revdog.retriever

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.revdog.retriever.core.Gzip
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** gzip：GZIPInputStream 与标准 gunzip 都能解开，单成员、无尾随字节（否则服务端按损坏隔离）。 */
class GzipTests : RtvTest() {
    private fun run(vararg cmd: String): Pair<Int, ByteArray> {
        val p = ProcessBuilder(*cmd).redirectError(File("/dev/null")).start()
        val out = p.inputStream.readBytes()
        p.waitFor(60, TimeUnit.SECONDS)
        return Pair(p.exitValue(), out)
    }

    @Test
    fun gunzipAcceptsSingleMemberWithoutTrailingBytes() {
        val input = ByteArray(200_000) { i -> ((i * 31) xor (i shr 3)).toByte() } + "日志🐶 {\"seq\":1}".toByteArray()
        val gz = Gzip.compress(input)!!
        assertEquals(listOf(0x1F, 0x8B, 0x08), gz.take(3).map { it.toInt() and 0xFF })
        // ISIZE = 原长 mod 2^32（小端），且就在文件末尾（无尾随字节）
        val isize = (0 until 4).fold(0L) { acc, k -> acc or ((gz[gz.size - 4 + k].toLong() and 0xFF) shl (8 * k)) }
        assertEquals(input.size.toLong(), isize)
        // GZIPInputStream 能解，且读完后底层流恰好耗尽（尾随字节由上面的 ISIZE 位置与下面的严格解压把关）
        val src = ByteArrayInputStream(gz)
        val decoded = GZIPInputStream(src).readBytes()
        assertArrayEquals(input, decoded)
        assertEquals(0, src.available())
        // 标准 gunzip
        val f = File(tempDir("rtv-gz"), "b.gz")
        f.writeBytes(gz)
        assertEquals(0, run("gunzip", "-t", f.path).first)
        val (st, out) = run("gunzip", "-c", f.path)
        assertEquals(0, st)
        assertArrayEquals(input, out)
        assertArrayEquals(input, Gzip.decompress(gz))
        // 严格解压：多一个成员或尾随字节都拒绝
        assertNull(Gzip.decompress(gz + gz))
        assertNull(Gzip.decompress(gz + byteArrayOf(0)))
        assertArrayEquals(ByteArray(0), Gzip.decompress(Gzip.compress(ByteArray(0))!!))
        f.parentFile?.deleteRecursively()
    }

    @Test
    fun outboxFileIsExactRequestBodyAndGunzipClean() {
        val h = Harness()
        h.settle()
        h.transport.defaultReply = FakeTransport.Reply.Status(503)
        h.client.log(LogLevel.WARN, "w", null, null, null)
        h.sealAndDrain()
        val name = h.outboxFiles().first()
        val f = File(h.outbox, name)
        assertArrayEquals("文件字节即请求体", f.readBytes(), h.transport.batchRequests.first().body)
        assertEquals(0, run("gunzip", "-t", f.path).first)
        // 重试原样重发
        h.tick(5000)
        assertEquals(2, h.transport.batchRequests.size)
        assertArrayEquals(h.transport.batchRequests[0].body, h.transport.batchRequests[1].body)
    }
}
