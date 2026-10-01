package org.revdog.retriever

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.revdog.retriever.core.DropCounter
import java.util.ConcurrentModificationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 公开入口不抛、不崩宿主（ADR 0024 决定 5；简报 §5 / §11 O）：null 参数、回调抛 Throwable、宿主对象的 toString / toDouble /
 * getMessage 抛异常、循环引用、并发修改——进程不崩、行落盘（或计数），flush 回调必回。全部走静态入口。
 */
class NeverThrowTests : RtvTest() {
    class EvilThrowable : RuntimeException() {
        override val message: String get() = throw IllegalStateException("getMessage boom")

        override fun toString(): String = throw IllegalStateException("toString boom")
    }

    private class EvilNumber : Number() {
        override fun toByte(): Byte = throw IllegalStateException()

        override fun toDouble(): Double = throw IllegalStateException("toDouble boom")

        override fun toFloat(): Float = throw IllegalStateException()

        override fun toInt(): Int = throw IllegalStateException()

        override fun toLong(): Long = throw IllegalStateException()

        override fun toShort(): Short = throw IllegalStateException()
    }

    private class Ping(var other: Any? = null) {
        override fun toString(): String = "ping(${other})"
    }

    /** 迭代到第 3 个键时抛并发修改。 */
    private class Mutating : AbstractMap<String, Any?>() {
        override val entries: Set<Map.Entry<String, Any?>>
            get() = object : AbstractSet<Map.Entry<String, Any?>>() {
                override val size: Int get() = 5

                override fun iterator(): Iterator<Map.Entry<String, Any?>> = object : Iterator<Map.Entry<String, Any?>> {
                    var i = 0

                    override fun hasNext(): Boolean = true

                    override fun next(): Map.Entry<String, Any?> {
                        if (i == 2) throw ConcurrentModificationException()
                        i++
                        return java.util.AbstractMap.SimpleEntry("k$i", i)
                    }
                }
            }
    }

    private fun lines(h: StaticHarness) = sessionLines(h.currentSessionDir())

    @Test
    fun o_nullArgumentsNeverThrow() {
        val h = StaticHarness()
        // configure 之前 null level：计数
        Retriever.log(null, "pre null level")
        assertEquals(1L, DropCounter.pending)
        Retriever.configure(null, null, null, null)
        h.settle()
        assertEquals("", h.client.engine.key)
        assertEquals("baseUrl null = 默认", "https://logs.revdog.org", h.client.engine.baseUrl)
        val d = lines(h).single { it["tag"] == DropCounter.TAG }
        assertEquals(1L, int(obj(d["attrs"])["count"]))
        // configure 之后 null level：只计数（不每条写一行合成行），下一次调度唤醒统一上报一条
        repeat(5) { Retriever.log(null, "null level") }
        assertEquals(1, lines(h).count { it["tag"] == DropCounter.TAG })
        assertEquals(5L, DropCounter.pending)
        h.client.tickNow()
        val all = lines(h).filter { it["tag"] == DropCounter.TAG }
        assertEquals(2, all.size)
        assertEquals(5L, int(obj(all[1]["attrs"])["count"]))
        Retriever.flush(true, null)
        Retriever.purgeLocal(null)
        Retriever.setUser(null)
        h.settle()
        // Options 的 Java 侧 setter 接受 null
        val o = Options()
        o.setUploadLevel(null)
        o.setLocalLevel(null)
        o.setSdkVersion(null)
        assertEquals(LogLevel.WARN, o.uploadLevel)
        assertEquals(LogLevel.DEBUG, o.localLevel)
        assertEquals(RetrieverVersion.CURRENT, o.sdkVersion)
    }

    @Test
    fun o_callbacksThatThrowAreSwallowedAndFlushAlwaysCallsBack() {
        val h = StaticHarness()
        // configure 之前：flush 回 paused，回调抛 Error 也不崩
        val pre = AtomicReference<FlushResult>()
        val preDone = CountDownLatch(1)
        Retriever.flush(true) { r ->
            pre.set(r)
            preDone.countDown()
            throw AssertionError("host bug")
        }
        assertTrue(preDone.await(10, TimeUnit.SECONDS))
        assertEquals(FlushResult.Pending("paused"), pre.get())
        val purged = CountDownLatch(1)
        Retriever.purgeLocal {
            purged.countDown()
            throw OutOfMemoryError("host bug")
        }
        assertTrue(purged.await(10, TimeUnit.SECONDS))
        h.configure("")
        h.settle()
        val got = CountDownLatch(1)
        Retriever.flush(true) {
            got.countDown()
            throw StackOverflowError()
        }
        assertTrue(got.await(30, TimeUnit.SECONDS))
        val p2 = CountDownLatch(1)
        Retriever.purgeLocal {
            p2.countDown()
            throw IllegalStateException("host bug")
        }
        assertTrue(p2.await(10, TimeUnit.SECONDS))
        h.settle()
        // 实例的执行器已关：flush 也必回（timeout）
        h.client.closeForTesting()
        val closed = AtomicReference<FlushResult>()
        val c = CountDownLatch(1)
        Retriever.flush(true) { r ->
            closed.set(r)
            c.countDown()
        }
        assertTrue(c.await(10, TimeUnit.SECONDS))
        assertEquals(FlushResult.Pending("timeout"), closed.get())
        h.errors.clear()
    }

    /** 宿主对象出错只影响那个值：行照常落盘，值写占位串并标 truncated（configure 之前与之后同一套编码）。 */
    @Test
    fun o_hostObjectsThatThrowOnlyAffectThatValue() {
        val h = StaticHarness()
        val a = Ping()
        val b = Ping(a)
        a.other = b
        val selfList = ArrayList<Any?>().apply { add(1); add(this) }
        val attrs = linkedMapOf<String, Any?>(
            "evil" to object {
                override fun toString(): String = throw IllegalStateException("toString boom")
            },
            "num" to EvilNumber(),
            "cycle" to a,
            "self" to selfList,
            "ok" to "fine",
            "arr" to intArrayOf(1, 2, 3),
            "list" to listOf("a", 2, null, mapOf("k" to true)),
        )
        Retriever.log(LogLevel.ERROR, null, "t", attrs, EvilThrowable())
        Retriever.log(LogLevel.WARN, "mutating", "t", Mutating(), null)
        h.configure("")
        h.log(LogLevel.ERROR, "after", attrs = attrs, error = EvilThrowable())
        h.settle()
        val ls = lines(h)
        assertEquals(3, ls.size)
        for (l in listOf(ls[0], ls[2])) {
            val at = obj(l["attrs"])
            assertEquals("<unprintable>", at["evil"])
            assertEquals("<unprintable>", at["num"])
            assertEquals("<unprintable>", at["cycle"])
            assertEquals("<unprintable>", at["self"])
            assertEquals("fine", at["ok"])
            assertEquals("数组输出元素", "[1,2,3]", at["arr"])
            assertEquals("[\"a\",2,null,{\"k\":true}]", at["list"])
            assertEquals(true, l["truncated"])
            assertEquals("<unprintable>", obj(l["exc"])["message"])
            assertEquals(NeverThrowTests::class.java.name + "\$EvilThrowable", obj(l["exc"])["type"])
        }
        assertEquals("msg 为 null 且 toString 抛：占位串", "<unprintable>", ls[0]["msg"])
        assertEquals("after", ls[2]["msg"])
        assertEquals("并发修改：已拿到的键照用", mapOf("k1" to 1.0, "k2" to 2.0), ls[1]["attrs"])
        assertEquals(true, ls[1]["truncated"])
        assertEquals(0L, DropCounter.pending)
    }

    /** RetrieverLog 的两个落点抛任何 Throwable（含 Error）都不抛给宿主。 */
    @Test
    fun o_retrieverLogSwallowsThrowables() {
        val saved = RetrieverLog.sink
        val savedCat = RetrieverLog.logcat
        try {
            RetrieverLog.sink = { _, _, _, _ -> throw AssertionError("sink") }
            RetrieverLog.logcat = { _, _, _, _ -> throw LinkageError("logcat") }
            assertEquals(0, RetrieverLog.e("T", null, EvilThrowable()))
            assertEquals(0, RetrieverLog.wtf("T", EvilThrowable()))
        } finally {
            RetrieverLog.sink = saved
            RetrieverLog.logcat = savedCat
        }
    }
}
