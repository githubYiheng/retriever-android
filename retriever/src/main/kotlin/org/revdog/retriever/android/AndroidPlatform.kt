package org.revdog.retriever.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.storage.StorageManager
import android.os.SystemClock
import android.util.Log
import org.revdog.retriever.RetrieverUploadJobService
import org.revdog.retriever.core.Clock
import org.revdog.retriever.core.Platform
import org.revdog.retriever.core.PlatformEvent
import org.revdog.retriever.core.PlatformEventSink
import java.io.File
import java.io.IOException
import java.util.Locale

/** 墙钟 `System.currentTimeMillis()`；单调时钟 `SystemClock.elapsedRealtime()`（含深睡眠，退避跨 Doze 正确）。 */
internal object AndroidClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()

    override fun monoMs(): Long = SystemClock.elapsedRealtime()

    override fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    override fun timerDelayMs(ms: Long): Long = maxOf(0, ms)
}

/**
 * Android 平台层（方案 §3.9 Android 列）：
 * - 生命周期：`registerActivityLifecycleCallbacks` 计数 started activities → 前台 / 后台（配置变更重建不算）；
 * - 网络：`registerDefaultNetworkCallback` 的 onAvailable 只用来提前唤醒（不做可达性预检）；计量判断用 NET_CAPABILITY_NOT_METERED；
 * - 后台兜底：框架 JobScheduler 一次性作业（网络约束、持久化；ADR 0003 决定 13，不引 WorkManager）；
 * - StrictMode：写入处 `allowThreadDiskWrites()` 包住并恢复；栈：`Log.getStackTraceString`。
 */
internal class AndroidPlatform(private val app: Context) : Platform {
    @Volatile
    private var sink: PlatformEventSink? = null
    private var observing = false
    private var started = 0
    private var changing = 0
    private var foreground: Boolean? = null
    private var hadNetwork: Boolean? = null

    override fun deviceFields(): Map<String, String> {
        val manufacturer = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        val fullModel = if (manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        var versionName = ""
        var versionCode = ""
        try {
            @Suppress("DEPRECATION")
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            versionName = info.versionName ?: ""
            versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // 保持空串
        }
        return mapOf(
            "os" to "android",
            "os_version" to (Build.VERSION.RELEASE ?: ""),
            "model" to fullModel,
            "app_version" to versionName,
            "build" to versionCode,
            "locale" to Locale.getDefault().toLanguageTag(),
        )
    }

    /** 进程重要性：前台 / 可见 = fg；其余（被作业、广播拉起的后台进程）= bg。之后以 Activity 回调为准。 */
    override fun isForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun autoProcessName(): String {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else readCmdline()
        val pkg = app.packageName
        return when {
            name.isNullOrEmpty() || name == pkg -> "main"
            name.startsWith("$pkg:") -> name.substring(pkg.length + 1)
            else -> name
        }
    }

    private fun readCmdline(): String? = try {
        val b = File("/proc/self/cmdline").readBytes()
        var n = b.indexOf(0.toByte())
        if (n < 0) n = b.size
        String(b, 0, n, Charsets.UTF_8).trim()
    } catch (e: IOException) {
        null
    }

    @Synchronized
    override fun startObserving(sink: PlatformEventSink) {
        this.sink = sink
        if (observing) return
        observing = true
        foreground = isForeground()
        (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                hadNetwork = cm.activeNetwork != null
                cm.registerDefaultNetworkCallback(networkCallback)
            }
        } catch (e: RuntimeException) {
            // 权限被宿主移除 / 系统异常：只少了「提前唤醒」
        }
    }

    @Synchronized
    override fun stopObserving(sink: PlatformEventSink) {
        if (this.sink === sink) this.sink = null
    }

    private fun post(e: PlatformEvent) {
        sink?.platformEvent(e)
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            val fire = synchronized(this@AndroidPlatform) {
                if (changing > 0) {
                    changing -= 1
                    started += 1
                    false
                } else {
                    started += 1
                    if (foreground != true) {
                        foreground = true
                        true
                    } else {
                        false
                    }
                }
            }
            if (fire) post(PlatformEvent.WILL_ENTER_FOREGROUND)
        }

        override fun onActivityStopped(activity: Activity) {
            val fire = synchronized(this@AndroidPlatform) {
                started = maxOf(0, started - 1)
                if (activity.isChangingConfigurations) {
                    // 旋转等配置变更：马上会重建并 onStart，不算进后台
                    changing += 1
                    false
                } else if (started == 0 && foreground != false) {
                    foreground = false
                    true
                } else {
                    false
                }
            }
            if (fire) post(PlatformEvent.DID_ENTER_BACKGROUND)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val fire = synchronized(this@AndroidPlatform) {
                val was = hadNetwork
                hadNetwork = true
                was == false
            }
            // 只用于提前唤醒，不做可达性预检。
            if (fire) post(PlatformEvent.NETWORK_RESTORED)
        }

        override fun onLost(network: Network) {
            synchronized(this@AndroidPlatform) { hadNetwork = false }
        }
    }

    override fun isExpensiveNetwork(): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (e: RuntimeException) {
        false
    }

    /** 可分配空间（API 26+ `StorageManager.getAllocatableBytes`，含系统可回收的缓存；同 iOS「重要用途」口径）。 */
    override fun availableBytes(dir: File): Long? {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val sm = app.getSystemService(StorageManager::class.java)
                if (sm != null) return sm.getAllocatableBytes(sm.getUuidForPath(dir))
            } catch (e: IOException) {
                // 回落到 usableSpace
            } catch (e: RuntimeException) {
                // 同上
            }
        }
        @Suppress("UsableSpace")
        val v = dir.usableSpace
        return if (v > 0) v else null
    }

    override fun scheduleUploadJob(jobId: Int) {
        try {
            val js = app.getSystemService(JobScheduler::class.java) ?: return
            // 已有同 id 作业在排 / 在跑：不动（schedule 同 id 会停掉正在跑的那个）
            if (js.getPendingJob(jobId) != null) return
            val info = JobInfo.Builder(jobId, ComponentName(app, RetrieverUploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            js.schedule(info)
        } catch (e: RuntimeException) {
            // 绝不抛给宿主（作业数超限、manifest 被裁掉等）
        }
    }

    override fun stackTraceString(t: Throwable): String = Log.getStackTraceString(t)

    override fun allowDiskWrites(): Any? = StrictMode.allowThreadDiskWrites()

    override fun restoreDiskPolicy(token: Any?) {
        if (token is StrictMode.ThreadPolicy) StrictMode.setThreadPolicy(token)
    }
}
