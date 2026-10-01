package org.revdog.retriever.example

import android.app.Application
import android.os.Process
import org.revdog.retriever.LogLevel
import org.revdog.retriever.Options
import org.revdog.retriever.Retriever
import org.revdog.retriever.timber.RetrieverTree
import timber.log.Timber

/**
 * 三种接入方式：直接调 `Retriever.log`、Timber（`RetrieverTree`）、`RetrieverLog`（android.util.Log 替身）。
 *
 * 正常启动：`Application.onCreate` 第一行 configure（推荐写法）。验收场景 `preconfigure` / `preconfigure_kill` / `preconfigure_strict` / `late_configure`
 * 要在 configure 之前就有日志：它们先由 [ScenarioRunner] 记一个一次性标记并退出进程，下一次冷启动这里读到标记、推迟 configure
 * （只影响那一次启动；没有标记时与原来完全一样）。
 */
class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val deferred = ScenarioRunner.takeDeferred(this)
        deferredScenario = deferred
        when (deferred) {
            ScenarioRunner.PRECONFIGURE -> {
                // configure 之前先各级别 log（写进 pre 文件），再 configure：这些行按这次 configure 的设定判定、过 redact、编号
                ScenarioRunner.preConfigureLines(deferred)
                configureNow(this)
                Retriever.log(LogLevel.WARN, "scenario $deferred done", "scenario")
            }
            ScenarioRunner.PRECONFIGURE_KILL -> {
                // configure 之前就死：pre 文件成了孤儿，下次（正常）启动 configure 时收编为独立会话上传
                ScenarioRunner.preConfigureLines(deferred)
                Retriever.log(LogLevel.WARN, "scenario $deferred killing before configure", "scenario")
                Process.killProcess(Process.myPid())
            }
            ScenarioRunner.PRECONFIGURE_STRICT -> {
                // StrictMode（detectAll + penaltyDeath）先开，再第一次触达 SDK：configure 之前的入口与 configure 都不得触发违规
                ScenarioRunner.strictBeforeConfigure()
                Retriever.isEnabled
                Retriever.setUser("strict-user")
                ScenarioRunner.preConfigureLines(deferred)
                configureNow(this)
                Retriever.log(LogLevel.WARN, "scenario $deferred done", "scenario")
            }
            // late_configure：Activity 已在前台之后才 configure（MainActivity.onResume 里）
            ScenarioRunner.LATE_CONFIGURE -> lateConfigurePending = true
            else -> configureNow(this)
        }
        // 2. Timber：宿主现有 Timber.x(...) 调用不改（configure 之前种下也行：之前的行进 pre 文件）
        Timber.plant(RetrieverTree())
        if (deferred == null) Retriever.log(LogLevel.INFO, "example app launched", "example")
    }

    companion object {
        val hasKey: Boolean get() = BuildConfig.RETRIEVER_KEY.isNotEmpty()

        /** 这次启动推迟了 configure 的场景（null = 正常启动）。 */
        @Volatile
        var deferredScenario: String? = null
            private set

        /** late_configure：等 MainActivity 到前台再 configure。 */
        @Volatile
        var lateConfigurePending = false

        /** 1. key 为空 = 只写本地不上传。 */
        fun configureNow(app: Application) {
            Retriever.configure(app, BuildConfig.RETRIEVER_KEY, BuildConfig.RETRIEVER_BASE_URL, Options())
        }
    }
}
