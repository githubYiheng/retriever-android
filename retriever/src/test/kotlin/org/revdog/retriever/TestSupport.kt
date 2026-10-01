package org.revdog.retriever

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
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
import org.revdog.retriever.core.stackTraceText
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

    /** 调度器请求过的定时延迟（断言「不以 0 ms 自旋」用；不真的调度）。 */
    val timerDelays = CopyOnWriteArrayList<Long>()

    /** 调度器不自动唤醒（测试手动 tick）。 */
    override fun timerDelayMs(ms: Long): Long? {
        timerDelays.add(ms)
        return null
    }
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

        /** 任意字节的响应体（HTML 错误页等非 JSON） */
        class Raw(val code: Int, val body: ByteArray) : Reply()

        /** 网络错误 */
        object Network : Reply()

        /** 挂起直到 cancelAll */
        object Hang : Reply()

        /** 等 [gate] 放行后回 [then]（模拟「请求在途时宿主换了 key」） */
        class Gated(val gate: CountDownLatch, val then: Reply) : Reply()
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

    /**
     * 回显模式（与 ingest 一致，ADR 0022）：响应 = configBody 里远程明确给的字段 + 没给的宿主型字段按请求头补齐，
     * 并带 `from_host`（[hostDerivedFields] 的结果）。
     */
    @Volatile
    var echoHost = false

    /** 非 null 时配置请求挂在这里直到放行（模拟「拉配置在途」）。 */
    @Volatile
    var configGate: CountDownLatch? = null
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
            configGate?.await(30, TimeUnit.SECONDS)
            val c = cfg ?: return HttpResponse(404)
            return HttpResponse(200, emptyMap(), toJson(if (echoHost) echo(c, request.headers) else c).toByteArray())
        }
        return when (rep) {
            Reply.Echo -> HttpResponse(200, emptyMap(), toJson(mapOf("batch_id" to (bid ?: ""), "status" to "stored", "config_etag" to etag)).toByteArray())
            Reply.EchoWrong -> HttpResponse(
                200, emptyMap(),
                toJson(mapOf("batch_id" to "00000000-0000-4000-8000-000000000000", "status" to "stored", "config_etag" to etag)).toByteArray(),
            )
            is Reply.Status -> HttpResponse(rep.code, rep.headers, rep.body?.let { toJson(it).toByteArray() } ?: ByteArray(0))
            is Reply.Raw -> HttpResponse(rep.code, emptyMap(), rep.body)
            Reply.Network -> null
            Reply.Hang -> {
                val latch = CountDownLatch(1)
                synchronized(lock) { hanging.add(latch) }
                latch.await(30, TimeUnit.SECONDS)
                null
            }
            is Reply.Gated -> {
                rep.gate.await(30, TimeUnit.SECONDS)
                when (val t = rep.then) {
                    is Reply.Status -> HttpResponse(t.code, t.headers, t.body?.let { toJson(it).toByteArray() } ?: ByteArray(0))
                    else -> HttpResponse(200, emptyMap(), toJson(mapOf("batch_id" to (bid ?: ""), "status" to "stored", "config_etag" to etag)).toByteArray())
                }
            }
        }
    }

    private fun echo(c: Map<String, Any?>, headers: Map<String, String>): Map<String, Any?> {
        val out = LinkedHashMap(c)
        val fromHost = hostDerivedFields(c)
        for (f in fromHost) {
            out[f] = when (f) {
                "upload_level" -> headers["X-Rtv-Upload-Level"]
                "local_level" -> headers["X-Rtv-Local-Level"]
                "local_cap_bytes" -> headers["X-Rtv-Local-Cap-Bytes"]?.toLong()
                else -> headers["X-Rtv-Daily-Batch-Cap"]?.toLong()
            }
        }
        out["from_host"] = fromHost
        return out
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
    var available: Long? = null

    @Volatile
    var foreground: Boolean? = true

    @Volatile
    var processName = "main"
    val scheduledJobs = CopyOnWriteArrayList<Int>()
    val cancelledJobs = CopyOnWriteArrayList<Int>()

    /** 记下 scheduleUploadJob 被调用时所在的线程名（fatal 在调用线程上排作业）。 */
    val scheduleThreads = CopyOnWriteArrayList<String>()
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

    override fun availableBytes(dir: File): Long? = available

    /** 返回值：false = 同 id 被别人占着（测试可设）。 */
    @Volatile
    var scheduleResult = true

    override fun scheduleUploadJob(jobId: Int): Boolean {
        scheduleThreads.add(Thread.currentThread().name)
        scheduledJobs.add(jobId)
        return scheduleResult
    }

    override fun cancelUploadJob(jobId: Int) {
        cancelledJobs.add(jobId)
    }

    /** 与 AndroidPlatform 同一个实现（纯 JVM）。 */
    override fun stackTraceString(t: Throwable): String = stackTraceText(t)

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
    initialEnabled: Boolean? = null,
    initialUser: String? = null,
) {
    val root: File = root ?: tempDir()
    val errors = CopyOnWriteArrayList<Throwable>()
    val client: RetrieverClient = RetrieverClient(this.root, key, BASE, options, clock, transport, platform, initialEnabled, initialUser).also { c ->
        c.onInternalError = { t -> errors.add(t) }
        c.start()
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
        client.reconfigure(key, BASE, options, client.processName)
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

    /** root 同级的禁用标记（ADR 0020 决定 2）。 */
    val disabledMarker: File get() = File(root.absoluteFile.parentFile, root.name + ".disabled")

    /** 堵住引擎线程直到 [release]（模拟引擎忙：冷启动恢复、大批物化等）。 */
    fun blockEngine(): CountDownLatch {
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        client.workAsync {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
        }
        entered.await(10, TimeUnit.SECONDS)
        return gate
    }

    fun purgeBlocking() {
        val done = CountDownLatch(1)
        client.purgeLocal { done.countDown() }
        if (!done.await(30, TimeUnit.SECONDS)) fail("purgeLocal 回调 30 s 未到")
        settle()
    }

    companion object {
        const val BASE = "https://logs-test.invalid"
        val LIVE = CopyOnWriteArrayList<Harness>()

        /** 关闭本用例建的全部实例；断言没有内部异常。root 同级的标记 / 清空残留一并删掉。 */
        fun closeAll(expectErrors: Boolean = false) {
            val all = ArrayList(LIVE)
            LIVE.clear()
            val errs = all.flatMap { it.errors }
            for (h in all) h.client.closeForTesting()
            for (h in all) {
                h.root.deleteRecursively()
                val parent = h.root.absoluteFile.parentFile
                parent?.listFiles()?.filter { it.name.startsWith(h.root.name + ".") }?.forEach { it.deleteRecursively() }
            }
            if (!expectErrors && errs.isNotEmpty()) {
                val sw = StringWriter()
                errs.first().printStackTrace(PrintWriter(sw))
                fail("内部异常 ${errs.size} 个：$sw")
            }
        }
    }
}

/** 所有用例的基类：开始前清空静态入口的进程内状态；结束时关掉实例、断言无内部异常。 */
abstract class RtvTest {
    open val expectInternalErrors: Boolean = false

    @Before
    fun resetStatics() {
        Retriever.resetForTesting(null)
        StaticHarness.STATIC_LIVE.clear()
    }

    @After
    fun closeHarnesses() {
        Retriever.resetForTesting(null)
        org.revdog.retriever.core.TestHooks.adoption = null
        org.revdog.retriever.core.Fs.writeAtomicFaultForTesting = null
        Harness.closeAll(expectInternalErrors)
        if (expectInternalErrors) StaticHarness.STATIC_LIVE.clear() else StaticHarness.assertNoErrors()
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

// ---------------------------------------------------------------- packages/core hostDerivedFields 的移植（回显模式与 golden 回放用）

private val LEVELS = setOf("debug", "info", "warn", "error", "fatal")

/** `raw` 交给 clampConfig 时取宿主回落的宿主型字段（顺序固定；ADR 0022）。 */
fun hostDerivedFields(raw: Any?): List<String> {
    @Suppress("UNCHECKED_CAST")
    val r = (raw as? Map<String, Any?>) ?: emptyMap()
    return listOf("upload_level", "local_level", "local_cap_bytes", "daily_batch_cap").filter { f ->
        val v = r[f]
        if (f == "upload_level" || f == "local_level") v !in LEVELS else !(v is Double && !v.isNaN() && !v.isInfinite()) && !(v is Int || v is Long)
    }
}

// ---------------------------------------------------------------- 静态入口夹具（简报 §0：新行为在 Retriever.* 层面测）

/**
 * 静态入口测试：把 [Retriever] 的平台环境换成临时目录 + 假时钟 + 假传输 + 假平台（`noContext` = 模拟拿不到 context 的进程）。
 * 每个用例开始前 RtvTest 已清空静态状态；[restart] 模拟进程重启（实例不收尾地关掉、pre 文件放锁不删）。
 */
internal class StaticHarness(
    val root: File = tempDir(),
    val clock: FakeClock = FakeClock(),
    val transport: FakeTransport = FakeTransport(),
    val platform: FakePlatform = FakePlatform(),
    noContext: Boolean = false,
) {
    val errors = CopyOnWriteArrayList<Throwable>()

    init {
        Retriever.resetForTesting(env(noContext))
        Retriever.internalErrorSink = { errors.add(it) }
        STATIC_LIVE.add(this)
    }

    fun env(noContext: Boolean = false): Retriever.Env = Retriever.Env(if (noContext) null else root, platform, clock) { transport }

    val client: RetrieverClient get() = Retriever.currentClient() ?: error("还没有实例")

    fun configure(key: String? = "", options: Options? = Options()) {
        Retriever.configure(null, key, Harness.BASE, options)
    }

    fun settle() {
        Retriever.currentClient()?.settle()
    }

    fun log(level: LogLevel, msg: String, attrs: Map<String, Any?>? = null, error: Throwable? = null) {
        Retriever.log(level, msg, "t", attrs, error)
    }

    /** 模拟进程重启：实例不收尾地停掉、静态状态清空（pre 文件放锁不删），之后用同一个 root。 */
    fun restart(transport: FakeTransport = this.transport): StaticHarness {
        Retriever.currentClient()?.simulateCrash()
        return StaticHarness(root, clock, transport, platform)
    }

    val preFiles: List<File> get() = (File(root, "pre").listFiles()?.toList() ?: emptyList()).filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }

    val outbox: File get() = File(root, "outbox")

    fun outboxFiles(prefix: String? = null): List<String> =
        (outbox.list()?.toList() ?: emptyList()).filter { it.endsWith(".gz") && (prefix == null || it.startsWith(prefix)) }.sorted()

    fun envelopes(prefix: String? = null): List<Env> = outboxFiles(prefix).mapNotNull { n -> Env.ofGzip(n, File(outbox, n).readBytes()) }

    fun readJsonl(name: String): List<Map<String, Any?>> {
        val f = File(root, name)
        if (!f.exists()) return emptyList()
        return f.readText().split("\n").filter { it.isNotEmpty() }.mapNotNull { JsonIn.obj(it) }
    }

    fun currentSessionDir(): File = File(File(root, "proc-${client.processName}"), client.writer.currentSessionId)

    /** 本进程名下全部会话目录（含当前）。 */
    fun sessionDirs(proc: String = "main"): List<File> = (File(root, "proc-$proc").listFiles()?.toList() ?: emptyList()).filter { it.isDirectory }

    companion object {
        val STATIC_LIVE = CopyOnWriteArrayList<StaticHarness>()

        fun assertNoErrors() {
            val all = ArrayList(STATIC_LIVE)
            STATIC_LIVE.clear()
            val errs = all.flatMap { it.errors }
            for (h in all) h.root.deleteRecursively()
            if (errs.isNotEmpty()) {
                val sw = StringWriter()
                errs.first().printStackTrace(PrintWriter(sw))
                fail("内部异常 ${errs.size} 个：$sw")
            }
        }
    }
}

/** 一个会话目录里全部段的行（按段号，跳过段头），每行附原始字节。 */
internal class SegLineRec(val map: Map<String, Any?>, val raw: String) {
    operator fun get(k: String): Any? = map[k]
}

internal fun sessionLines(dir: File): List<SegLineRec> {
    val out = ArrayList<SegLineRec>()
    val segs = (dir.list() ?: emptyArray()).filter { it.startsWith("seg-") }
        .sortedWith(compareBy<String>({ it.substring(4, 10).toInt() }, { if (it.endsWith(".open")) 1 else 0 }))
    for (n in segs) {
        val ls = File(dir, n).readText().split("\n").filter { it.isNotEmpty() }
        for ((i, l) in ls.withIndex()) if (i > 0) JsonIn.obj(l)?.let { out.add(SegLineRec(it, l)) }
    }
    return out
}

internal fun segHeaders(dir: File): List<Map<String, Any?>> =
    (dir.list() ?: emptyArray()).filter { it.startsWith("seg-") }.sortedBy { it.substring(4, 10).toInt() }
        .mapNotNull { n -> File(dir, n).readText().split("\n").firstOrNull()?.let { JsonIn.obj(it) } }
