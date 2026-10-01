package org.revdog.retriever.example

import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Retriever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机验收用：`adb shell am start -n org.revdog.retriever.example/.MainActivity --es scenario error|user|bulk|crash|flush|strictmode`。
 * 无 UI 自动化依赖；与 iOS 示例 `ScenarioRunner` 逐字对应：每个场景末尾记一条 warn（义务行）作为服务端可见的完成标记。
 * `strictmode` 只有 Android 有（ADR 0020 决定 6）。
 */
object ScenarioRunner {
    class ScenarioError : RuntimeException("scenario failure")

    private val main = Handler(Looper.getMainLooper())

    fun runIfRequested(name: String?) {
        if (name.isNullOrEmpty()) return
        main.postDelayed({ Thread({ run(name) }, "scenario-$name").start() }, 1000)
    }

    fun run(name: String) {
        when (name) {
            "error" -> errorLines()
            "bulk" -> {
                for (i in 0 until 5000) {
                    Retriever.log(LogLevel.INFO, "bulk info line $i 中文 emoji 😀 padding padding padding padding padding", "bulk")
                }
                Retriever.log(LogLevel.WARN, "bulk done", "scenario")
                Retriever.log(LogLevel.ERROR, "bulk error after 5000 lines", "scenario")
            }
            "user" -> {
                Retriever.setUser("device-test-user")
                Retriever.log(LogLevel.INFO, "after setUser", "scenario")
                Retriever.log(LogLevel.ERROR, "error as device-test-user", "scenario")
            }
            "flush" -> {
                Retriever.log(LogLevel.INFO, "before flush", "scenario")
                Retriever.flush(true) { r -> Retriever.log(LogLevel.WARN, "flush result: $r", "scenario") }
            }
            "crash" -> {
                for (i in 0 until 200) Retriever.log(LogLevel.INFO, "pre-crash info $i", "scenario")
                Retriever.log(LogLevel.WARN, "about to crash", "scenario")
                main.postDelayed({ throw RuntimeException("scenario crash") }, 1500)
            }
            "strictmode" -> underStrictMode { errorLines() }
            else -> Retriever.log(LogLevel.WARN, "unknown scenario $name", "scenario")
        }
        if (name != "crash") Retriever.log(LogLevel.WARN, "scenario $name done", "scenario")
    }

    private fun errorLines() {
        for (i in 0 until 50) Retriever.log(LogLevel.DEBUG, "debug line $i", "scenario", mapOf("i" to i))
        for (i in 0 until 5) Retriever.log(LogLevel.INFO, "info line $i", "scenario")
        Retriever.log(LogLevel.ERROR, "scenario error", "scenario", mapOf("code" to 42, "ok" to false), ScenarioError())
    }

    /**
     * 宿主开着 StrictMode（`ThreadPolicy` 与 `VmPolicy` 都 `detectAll().penaltyDeath()`）时跑 [body]：违规即进程死亡。
     * VmPolicy 管全进程（SDK 自己线程上的上传 / 拉配置：未打 TrafficStats tag 的套接字致死）；ThreadPolicy 只管设置它的线程，
     * 所以设在主线程上、在主线程上写日志（宿主最常见的调用线程：逐行写段的磁盘写与 unbuffered IO 都要 SDK 自己放行）。
     * 设完之后本进程一直带着这两个策略；error 的 2 s 去抖封段与上传随后在 SDK 线程上发生。
     */
    private fun underStrictMode(body: () -> Unit) {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyDeath().build())
        val done = CountDownLatch(1)
        main.post {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyDeath().build())
            try {
                body()
            } finally {
                done.countDown()
            }
        }
        done.await(30, TimeUnit.SECONDS)
    }
}
