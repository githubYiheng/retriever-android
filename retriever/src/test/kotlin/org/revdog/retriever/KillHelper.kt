package org.revdog.retriever

import org.revdog.retriever.core.HttpRequest
import org.revdog.retriever.core.HttpResponse
import org.revdog.retriever.core.Platform
import org.revdog.retriever.core.PlatformEventSink
import org.revdog.retriever.core.RetrieverClient
import org.revdog.retriever.core.Transport
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

// 只给测试用（真杀进程验收 R-1，方案 §3.11）：用给定 root 写 N 行（含 warn / error），
// 可选在最后写半行（模拟撕裂的 write）或写一条 fatal（log() 一返回就杀，异步封段来不及做），
// 然后 Runtime.halt(137)——不跑任何 shutdown hook、不给任何收尾机会。
//
//   java -cp <test runtime classpath> org.revdog.retriever.KillHelperKt <root> <N> [--torn] [--fatal]

private object NoTransport : Transport {
    override fun send(request: HttpRequest): HttpResponse? = null

    override fun cancelAll() = Unit
}

private object HeadlessPlatform : Platform {
    override fun deviceFields(): Map<String, String> = mapOf(
        "os" to "android", "os_version" to "15", "model" to "helper", "app_version" to "1.0.0", "build" to "1", "locale" to "en-US",
    )

    override fun isForeground(): Boolean = true

    override fun autoProcessName(): String = "main"

    override fun startObserving(sink: PlatformEventSink) = Unit

    override fun stopObserving(sink: PlatformEventSink) = Unit

    override fun availableBytes(dir: File): Long? = null

    override fun scheduleUploadJob(jobId: Int): Boolean = true

    override fun cancelUploadJob(jobId: Int) = Unit

    override fun stackTraceString(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    override fun allowDiskWrites(): Any? = null

    override fun restoreDiskPolicy(token: Any?) = Unit
}

/** 静态入口的环境：给定 root、真时钟、不联网。 */
private fun staticEnv(root: File) = Retriever.Env(root, HeadlessPlatform, JvmClock()) { NoTransport }

/** pre 文件的测试行：每 10 行一条 warn，每 50 行一条 error，其余交替 debug / info；第 n/2 行之前 setUser。 */
private fun preLines(n: Int) {
    for (i in 1..n) {
        if (i == n / 2 + 1) Retriever.setUser("killed-user")
        val level = when {
            i % 50 == 0 -> LogLevel.ERROR
            i % 10 == 0 -> LogLevel.WARN
            i % 2 == 0 -> LogLevel.INFO
            else -> LogLevel.DEBUG
        }
        Retriever.log(level, "pre $i", "kill", mapOf("i" to i), null)
    }
}

/**
 * 简报 §11 D / E / J：
 *   --pre <root> <N>                 configure 之前写 N 行后 halt（下次启动作为孤儿收编）
 *   --pre-hold <root> <N>            configure 之前写 N 行后打印 ready 并一直活着（持 flock），等父进程杀
 *   --adopt-crash <root> <N> <point> configure 之前写 N 行，然后 configure；收编走到 <point>（record:k / before_commit / after_unlink）时 halt
 */
private fun preMode(args: Array<String>) {
    if (args[0] == "--probe-lock") {
        // 另一个进程看某个会话目录的锁：locked / free
        val l = org.revdog.retriever.core.SessionLock.tryAcquire(File(args[1]))
        println(if (l == null) "locked" else "free")
        l?.release()
        System.out.flush()
        Runtime.getRuntime().halt(0)
    }
    val root = File(args[1])
    val n = args[2].toInt()
    Retriever.resetForTesting(staticEnv(root))
    preLines(n)
    when (args[0]) {
        "--pre" -> Unit
        "--pre-hold" -> {
            println("ready")
            System.out.flush()
            System.`in`.read()
            return
        }
        "--adopt-crash", "--adopt-crash-writing" -> {
            val point = args[3]
            val writing = args[0] == "--adopt-crash-writing"
            org.revdog.retriever.core.TestHooks.adoption = { p ->
                // 收编期间宿主还在写（configure 之后的行 = r = 1，追加到 pre 文件尾）
                if (writing && p == "record:30") {
                    val t = Thread { for (k in 0 until 10) Retriever.log(LogLevel.WARN, "post $k", "kill") }
                    t.start()
                    t.join()
                }
                if (p == point) Runtime.getRuntime().halt(137)
            }
            Retriever.configure(null, "", "https://invalid.example", Options().apply { uploadLevel = LogLevel.INFO })
            // 收编在引擎线程上；走到崩溃点之前别退出（没走到 = 测试失败：父进程会看到退出码不是 137）
            Thread.sleep(10_000)
        }
    }
    println("pre=$n")
    System.out.flush()
    Runtime.getRuntime().halt(137)
}

fun main(args: Array<String>) {
    if (args[0].startsWith("--")) {
        preMode(args)
        return
    }
    val root = File(args[0])
    val n = args[1].toInt()
    val torn = args.contains("--torn")
    // key 为空：不上传（不联网），只验证落盘与恢复
    val client = RetrieverClient(root, "", "https://invalid.example", Options(), JvmClock(), NoTransport, HeadlessPlatform)
    client.start()
    for (i in 1..n) {
        val level = when {
            i % 1000 == 0 -> LogLevel.ERROR
            i % 10 == 0 -> LogLevel.WARN
            i % 2 == 0 -> LogLevel.INFO
            else -> LogLevel.DEBUG
        }
        client.log(level, "line $i " + "x".repeat(64), "kill", mapOf("i" to i), null)
    }
    if (torn) {
        // 模拟撕裂：下一行只写一半（前缀在同一次 write 里先写，所以能认出 seq / oseq）
        val f = client.debugOpenSegmentFile!!
        val (seq, oseq) = client.debugCounters
        FileOutputStream(f, true).use { it.write("{\"seq\":${seq + 1},\"oseq\":${oseq + 1},\"ts\":1,\"level\":\"warn\",\"msg\":\"to".toByteArray()) }
    }
    if (args.contains("--fatal")) {
        // fatal：调用线程上只写行与换段，封段物化在引擎线程上异步做（ADR 0020 决定 1）——下面立即杀进程
        client.log(LogLevel.FATAL, "last words", "kill", null, IllegalStateException("fatal"))
    }
    // 输出计数给测试核对，然后硬杀自己
    val (seq, oseq) = client.debugCounters
    println("seq=$seq oseq=$oseq")
    System.out.flush()
    Runtime.getRuntime().halt(137)
}
