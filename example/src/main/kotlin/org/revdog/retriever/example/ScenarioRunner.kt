package org.revdog.retriever.example

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StrictMode
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Retriever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机验收用：`adb shell am start -n org.revdog.retriever.example/.MainActivity --es scenario <name>`，
 * name = error | user | bulk | crash | flush | strictmode | preconfigure | preconfigure_kill | preconfigure_strict | late_configure。
 * 无 UI 自动化依赖；与 iOS 示例 `ScenarioRunner` 逐字对应：每个场景末尾记一条 warn（义务行）作为服务端可见的完成标记。
 * `strictmode`、`preconfigure_strict`、`late_configure` 只有 Android 有。
 *
 * configure 之前的场景（0.3.0，ADR 0023）要两步：第一次 `am start --es scenario <name>` 只记一个一次性标记并退出进程；
 * 再 `am start`（不带参数）冷启动，[ExampleApp] 读到标记、推迟 configure 跑场景。
 */
object ScenarioRunner {
    const val PRECONFIGURE = "preconfigure"
    const val PRECONFIGURE_KILL = "preconfigure_kill"
    const val PRECONFIGURE_STRICT = "preconfigure_strict"
    const val LATE_CONFIGURE = "late_configure"
    private val DEFERRED = setOf(PRECONFIGURE, PRECONFIGURE_KILL, PRECONFIGURE_STRICT, LATE_CONFIGURE)
    private const val FLAG = "retriever-example-deferred-scenario"

    class ScenarioError : RuntimeException("scenario failure")

    private val main = Handler(Looper.getMainLooper())

    fun runIfRequested(name: String?) {
        if (name.isNullOrEmpty()) return
        main.postDelayed({ Thread({ run(name) }, "scenario-$name").start() }, 1000)
    }

    /** 读并删一次性标记（只在 Application.onCreate 里调）。 */
    fun takeDeferred(context: Context): String? {
        val f = File(context.filesDir, FLAG)
        if (!f.exists()) return null
        val name = f.readText().trim()
        f.delete()
        return name.takeIf { it in DEFERRED }
    }

    /** configure 之前各级别 log 若干（debug / info / warn / error）。 */
    fun preConfigureLines(name: String) {
        for (i in 0 until 5) Retriever.log(LogLevel.DEBUG, "$name pre debug $i", "scenario", mapOf("i" to i))
        for (i in 0 until 3) Retriever.log(LogLevel.INFO, "$name pre info $i", "scenario")
        Retriever.log(LogLevel.WARN, "$name pre warn", "scenario")
        Retriever.log(LogLevel.ERROR, "$name pre error", "scenario", mapOf("code" to 7), ScenarioError())
    }

    /**
     * `preconfigure_strict`：在主线程上（Application.onCreate）先开 `ThreadPolicy` / `VmPolicy` 的 `detectAll().penaltyDeath()`，
     * 之后才有第一次触达 SDK——configure 之前的 `log()`、`isEnabled`、`setUser` 与 configure 本身的磁盘访问都要 SDK 自己放行，
     * 任何违规进程即死（ADR 0024 决定 5；盲审 O1）。
     */
    fun strictBeforeConfigure() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyDeath().build())
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyDeath().build())
    }

    /** 第一步：记标记、退出进程；下一次冷启动跑场景。 */
    private fun arm(name: String) {
        val app = MainActivity.appContext ?: return
        File(app.filesDir, FLAG).writeText(name)
        Retriever.log(LogLevel.WARN, "scenario $name armed; relaunch the app to run it", "scenario")
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    fun run(name: String) {
        if (name in DEFERRED) {
            arm(name)
            return
        }
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
