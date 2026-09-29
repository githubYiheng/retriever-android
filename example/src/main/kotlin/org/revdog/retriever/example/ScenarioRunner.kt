package org.revdog.retriever.example

import android.os.Handler
import android.os.Looper
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Retriever

/**
 * 真机验收用：`adb shell am start -n org.revdog.retriever.example/.MainActivity --es scenario error|user|bulk|crash|flush`。
 * 无 UI 自动化依赖；与 iOS 示例 `ScenarioRunner` 逐字对应：每个场景末尾记一条 warn（义务行）作为服务端可见的完成标记。
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
            "error" -> {
                for (i in 0 until 50) Retriever.log(LogLevel.DEBUG, "debug line $i", "scenario", mapOf("i" to i))
                for (i in 0 until 5) Retriever.log(LogLevel.INFO, "info line $i", "scenario")
                Retriever.log(LogLevel.ERROR, "scenario error", "scenario", mapOf("code" to 42, "ok" to false), ScenarioError())
            }
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
            else -> Retriever.log(LogLevel.WARN, "unknown scenario $name", "scenario")
        }
        if (name != "crash") Retriever.log(LogLevel.WARN, "scenario $name done", "scenario")
    }
}
