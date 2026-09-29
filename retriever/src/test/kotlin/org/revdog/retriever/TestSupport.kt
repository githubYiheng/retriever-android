package org.revdog.retriever

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.revdog.retriever.core.BackoffState
import org.revdog.retriever.core.Clock
import org.revdog.retriever.core.Engine
import org.revdog.retriever.core.Gzip
import org.revdog.retriever.core.HttpRequest
import org.revdog.retriever.core.HttpResponse
import org.revdog.retriever.core.JsonIn
import org.revdog.retriever.core.JsonOut
import org.revdog.retriever.core.Platform
import org.revdog.retriever.core.PlatformEventSink
import org.revdog.retriever.core.RetrieverClient
import org.revdog.retriever.core.SealReason
import org.revdog.retriever.core.Transport
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------- 路径

object Repo {
    /** 仓库根：从 user.dir（Gradle 单测的工作目录 = 模块目录 sdk/android/retriever）向上找 packages/core/golden。 */
    val root: File by lazy {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null && !File(d, "packages/core/golden").isDirectory) d = d.parentFile
        d ?: error("找不到仓库根（packages/core/golden）")
    }

    fun golden(name: String): Map<String, Any?> =
        JsonIn.obj(File(root, "packages/core/golden/$name").readBytes()) ?: error("golden $name 不是对象")
}

fun tempDir(tag: String = "rtv"): File = Files.createTempDirectory(tag).toFile()

// ---------------------------------------------------------------- JSON 小工具（测试用）

@Suppress("UNCHECKED_CAST")
fun obj(v: Any?): Map<String, Any?> = v as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun objs(v: Any?): List<Map<String, Any?>> = (v as? List<Any?>)?.map { it as Map<String, Any?> } ?: emptyList()

/** 数字取 Long（缺省 / 非数字 = -999，同 iOS 测试的 int()）。 */
fun int(v: Any?): Long = JsonIn.int64(v) ?: -999

fun toJson(v: Any?): String {
    val o = JsonOut()
    writeJson(o, v)
    return String(o.toByteArray(), Charsets.UTF_8)
}

private fun writeJson(o: JsonOut, v: Any?) {
    when (v) {
        null -> o.raw("null")
        is String -> o.string(v)
        is Boolean -> o.bool(v)
        is Int -> o.int(v)
        is Long -> o.int(v)
        is Number -> o.number(v.toDouble())
        is Map<*, *> -> {
            o.raw("{")
            var first = true
            for ((k, x) in v) {
                if (!first) o.raw(",")
                first = false
                o.string(k as String)
                o.raw(":")
                writeJson(o, x)
            }
            o.raw("}")
        }
        is List<*> -> {
            o.raw("[")
            v.forEachIndexed { i, x ->
                if (i > 0) o.raw(",")
                writeJson(o, x)
            }
            o.raw("]")
        }
        else -> o.string(v.toString())
    }
}

/** 一个信封：原始（解压后）字节 + 解析结果。跨语言校验用原始字节（逐字节就是服务端收到的东西）。 */
internal class Env(val name: String, val raw: ByteArray) {
    val map: Map<String, Any?> = JsonIn.obj(raw) ?: error("信封不是 JSON 对象：$name")

    operator fun get(k: String): Any? = map[k]

    val lines: List<Map<String, Any?>> get() = objs(map["lines"])

    companion object {
        fun ofGzip(name: String, gz: ByteArray): Env? = Gzip.decompress(gz)?.let { Env(name, it) }
    }
}

internal fun lines(e: Env): List<Map<String, Any?>> = e.lines

// ---------------------------------------------------------------- 假时钟

internal class FakeClock(wall: Long = 1_790_668_800_000L, mono: Long = 1_000_000L) : Clock {
    private val lock = Any()
    private var wall = wall
    private var mono = mono

    override fun wallMs(): Long = synchronized(lock) { wall }

    override fun monoMs(): Long = synchronized(lock) { mono }

    fun advance(ms: Long) {
        synchronized(lock) {
            wall += ms
            mono += ms
        }
    }

    /** 流程内等待：直接推进假时间。 */
    override fun sleep(ms: Long) {
        advance(maxOf(ms, 0))
    }

    /** 调度器不自动唤醒（测试手动 tick）。 */
    override fun timerDelayMs(ms: Long): Long? = null
}

/** 真实时钟（真杀进程子进程用）：单调时钟用 System.nanoTime()。 */
internal class JvmClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()

    override fun monoMs(): Long = System.nanoTime() / 1_000_000

    override fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    override fun timerDelayMs(ms: Long): Long = maxOf(0, ms)
}

// ---------------------------------------------------------------- 假传输

internal class FakeTransport : Transport {
    sealed class Reply {
        /** 200 回显本批 batch_id */
        object Echo : Reply()

        /** 200 回显别的 batch_id */
        object EchoWrong : Reply()

        class Status(val code: Int, val body: Map<String, Any?>? = null, val headers: Map<String, String> = emptyMap()) : Reply()

        /** 网络错误 */
        object Network : Reply()

        /** 挂起直到 cancelAll */
        object Hang : Reply()
    }

    private val lock = Any()
    private val script = ArrayDeque<Reply>()

    @Volatile
    var defaultReply: Reply = Reply.Echo

    /** 按批决定回复（优先于 script）。 */
    @Volatile
    var responder: ((String) -> Reply?)? = null

    @Volatile
    var configBody: Map<String, Any?>? = null

    @Volatile
    var configEtag = "etag-0"
    private val requests = ArrayList<HttpRequest>()
    private val hanging = ArrayList<CountDownLatch>()

    @Volatile
    var cancelCount = 0
        private set

    fun setScript(s: List<Reply>) {
        synchronized(lock) {
            script.clear()
            script.addAll(s)
        }
    }

    val allRequests: List<HttpRequest> get() = synchronized(lock) { ArrayList(requests) }
    val batchRequests: List<HttpRequest> get() = synchronized(lock) { requests.filter { it.url.endsWith("/v1/batches") } }
    val configRequests: List<HttpRequest> get() = synchronized(lock) { requests.filter { it.url.endsWith("/v1/config") } }
    val hangingCount: Int get() = synchronized(lock) { hanging.size }

    override fun send(request: HttpRequest): HttpResponse? {
        var reply: Reply? = null
        var cfg: Map<String, Any?>? = null
        var bid: String? = null
        val etag: String
        synchronized(lock) {
            requests.add(request)
            etag = configEtag
            if (request.url.endsWith("/v1/config")) {
                cfg = configBody
            } else {
                bid = batchId(request.body)
                val r = responder?.invoke(bid ?: "")
                reply = r ?: script.removeFirstOrNull() ?: defaultReply
            }
        }
        val rep = reply
        if (rep == null) {
            val c = cfg ?: return HttpResponse(404)
            return HttpResponse(200, emptyMap(), toJson(c).toByteArray())
        }
        return when (rep) {
            Reply.Echo -> HttpResponse(200, emptyMap(), toJson(mapOf("batch_id" to (bid ?: ""), "status" to "stored", "config_etag" to etag)).toByteArray())
            Reply.EchoWrong -> HttpResponse(
                200, emptyMap(),
                toJson(mapOf("batch_id" to "00000000-0000-4000-8000-000000000000", "status" to "stored", "config_etag" to etag)).toByteArray(),
            )
            is Reply.Status -> HttpResponse(rep.code, rep.headers, rep.body?.let { toJson(it).toByteArray() } ?: ByteArray(0))
            Reply.Network -> null
            Reply.Hang -> {
                val latch = CountDownLatch(1)
                synchronized(lock) { hanging.add(latch) }
                latch.await(30, TimeUnit.SECONDS)
                null
            }
        }
    }

    override fun cancelAll() {
        val h = synchronized(lock) {
            cancelCount += 1
            val x = ArrayList(hanging)
            hanging.clear()
            x
        }
        for (l in h) l.countDown()
    }

    companion object {
        fun batchId(body: ByteArray?): String? = envOf(body)?.get("batch_id") as? String

        fun envOf(body: ByteArray?): Env? = body?.let { Env.ofGzip("request", it) }
    }
}

// ---------------------------------------------------------------- 假平台

internal class FakePlatform : Platform {
    @Volatile
    var expensive = false

    @Volatile
    var available: Long? = null

    @Volatile
    var foreground: Boolean? = true

    @Volatile
    var processName = "main"
    val scheduledJobs = CopyOnWriteArrayList<Int>()
    val diskAllowances = AtomicInteger()
    val diskRestores = AtomicInteger()

    override fun deviceFields(): Map<String, String> = mapOf(
        "os" to "android", "os_version" to "15", "model" to "Google Pixel-test", "app_version" to "1.2.3", "build" to "45",
        "locale" to "zh-CN",
    )

    override fun isForeground(): Boolean? = foreground

    override fun autoProcessName(): String = processName

    override fun startObserving(sink: PlatformEventSink) = Unit

    override fun stopObserving(sink: PlatformEventSink) = Unit

    override fun isExpensiveNetwork(): Boolean = expensive

    override fun availableBytes(dir: File): Long? = available

    override fun scheduleUploadJob(jobId: Int) {
        scheduledJobs.add(jobId)
    }

    override fun stackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    override fun allowDiskWrites(): Any? {
        diskAllowances.incrementAndGet()
        return "policy"
    }

    override fun restoreDiskPolicy(token: Any?) {
        if (token == "policy") diskRestores.incrementAndGet()
    }
}

// ---------------------------------------------------------------- 测试夹具

internal class Harness(
    root: File? = null,
    val key: String = "lk_test_demo_abc_12345678",
    options: Options = Options(),
    val clock: FakeClock = FakeClock(),
    val transport: FakeTransport = FakeTransport(),
    val platform: FakePlatform = FakePlatform(),
) {
    val root: File = root ?: tempDir()
    val errors = CopyOnWriteArrayList<Throwable>()
    val client: RetrieverClient = RetrieverClient(this.root, key, BASE, options, clock, transport, platform).also { c ->
        c.onInternalError = { t -> errors.add(t) }
    }

    init {
        LIVE.add(this)
    }

    val engine: Engine get() = client.engine
    val outbox: File get() = File(root, "outbox")

    fun settle() {
        client.settle()
    }

    /** 立即封当前段并处理（不走定时器）。 */
    fun seal(reason: SealReason = SealReason.TIMER) {
        client.writer.rotate(reason)
        client.onWork { engine.processSeals() }
        settle()
    }

    /** 封当前段并按正常路径触发排空（封段是排空触发点之一）。 */
    fun sealAndDrain(reason: SealReason = SealReason.TIMER) {
        client.writer.rotate(reason)
        client.onWork { client.afterSeal() }
        settle()
    }

    fun tick(advance: Long = 0) {
        if (advance > 0) clock.advance(advance)
        client.tickNow()
    }

    fun enableUpload(key: String = "lk_test_demo_abc_12345678", options: Options = Options()) {
        client.reconfigure(key, BASE, options)
        settle()
    }

    fun <T> work(body: (Engine) -> T): T = client.onWork { body(engine) }

    fun outboxFiles(prefix: String? = null): List<String> =
        (outbox.list()?.toList() ?: emptyList()).filter { it.endsWith(".gz") && (prefix == null || it.startsWith(prefix)) }.sorted()

    fun envelopes(prefix: String? = null): List<Env> =
        outboxFiles(prefix).mapNotNull { n -> Env.ofGzip(n, File(outbox, n).readBytes()) }

    fun readJsonl(name: String): List<Map<String, Any?>> {
        val f = File(root, name)
        if (!f.exists()) return emptyList()
        return f.readText().split("\n").filter { it.isNotEmpty() }.mapNotNull { JsonIn.obj(it) }
    }

    fun readJson(f: File): Map<String, Any?> = JsonIn.obj(f.readBytes()) ?: error("不是 JSON 对象：$f")

    fun sessionDir(c: RetrieverClient = client): File = File(File(root, "proc-${c.processName}"), c.writer.currentSessionId)

    fun log(level: LogLevel, msg: String, count: Int = 1, pad: Int = 0) {
        for (i in 0 until count) client.log(level, (if (count > 1) "$msg $i" else msg) + "p".repeat(pad), null, null, null)
    }

    fun flushBlocking(includeContext: Boolean = true): FlushResult {
        val latch = CountDownLatch(1)
        var result: FlushResult? = null
        client.flush(includeContext) { r ->
            result = r
            latch.countDown()
        }
        if (!latch.await(30, TimeUnit.SECONDS)) fail("flush 回调 30 s 未到")
        return result!!
    }

    val backoff: BackoffState get() = work { it.backoff.copy() }
    val nowMono: Long get() = clock.monoMs()

    companion object {
        const val BASE = "https://logs-test.invalid"
        val LIVE = CopyOnWriteArrayList<Harness>()

        /** 关闭本用例建的全部实例；断言没有内部异常。 */
        fun closeAll(expectErrors: Boolean = false) {
            val all = ArrayList(LIVE)
            LIVE.clear()
            val errs = all.flatMap { it.errors }
            for (h in all) h.client.closeForTesting()
            for (h in all) h.root.deleteRecursively()
            if (!expectErrors && errs.isNotEmpty()) {
                val sw = StringWriter()
                errs.first().printStackTrace(PrintWriter(sw))
                fail("内部异常 ${errs.size} 个：$sw")
            }
        }
    }
}

/** 所有用例的基类：结束时关掉实例、断言无内部异常。 */
abstract class RtvTest {
    open val expectInternalErrors: Boolean = false

    @After
    fun closeHarnesses() {
        Harness.closeAll(expectInternalErrors)
    }
}

// ---------------------------------------------------------------- 跨语言校验

/**
 * 跑 `npx tsx packages/core/scripts/validate-envelope.ts <files>`（cwd = 仓库根），把 SDK 物化出的**原始信封字节**逐个交给
 * 服务端同一份 validateEnvelope。返回每个文件一行的结果对象。
 */
internal fun runValidator(envs: List<Env>): List<Map<String, Any?>> {
    val dir = tempDir("rtv-validate")
    val paths = envs.mapIndexed { i, e ->
        val f = File(dir, "env-$i.json")
        f.writeBytes(e.raw)
        f.absolutePath
    }
    val cmd = listOf("npx", "--no-install", "tsx", "packages/core/scripts/validate-envelope.ts") + paths
    val pb = ProcessBuilder(cmd).directory(Repo.root)
    val env = pb.environment()
    val extra = listOfNotNull(System.getenv("NVM_BIN"), "/opt/homebrew/bin", "/usr/local/bin") +
        (File(System.getProperty("user.home"), ".nvm/versions/node").listFiles()?.map { File(it, "bin").path } ?: emptyList())
    env["PATH"] = (extra + (env["PATH"] ?: "/usr/bin:/bin")).joinToString(":")
    val p = pb.start()
    val out = p.inputStream.readBytes()
    val err = p.errorStream.readBytes()
    p.waitFor(120, TimeUnit.SECONDS)
    val text = String(out, Charsets.UTF_8)
    val results = text.split("\n").filter { it.isNotBlank() }.mapNotNull { JsonIn.obj(it) }
    if (results.size != paths.size) fail("validator 输出不符：$text ${String(err, Charsets.UTF_8)}")
    dir.deleteRecursively()
    return results
}

fun assertAllValid(results: List<Map<String, Any?>>) {
    for (r in results) assertEquals("$r", true, r["ok"])
}
