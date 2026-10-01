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

    override fun scheduleUploadJob(jobId: Int) = Unit

    override fun cancelUploadJob(jobId: Int) = Unit

    override fun stackTraceString(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    override fun allowDiskWrites(): Any? = null

    override fun restoreDiskPolicy(token: Any?) = Unit
}

fun main(args: Array<String>) {
    val root = File(args[0])
    val n = args[1].toInt()
    val torn = args.contains("--torn")
    // key 为空：不上传（不联网），只验证落盘与恢复
    val client = RetrieverClient(root, "", "https://invalid.example", Options(), JvmClock(), NoTransport, HeadlessPlatform)
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
