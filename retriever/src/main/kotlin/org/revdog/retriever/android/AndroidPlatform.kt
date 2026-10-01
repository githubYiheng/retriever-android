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
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.os.storage.StorageManager
import android.os.SystemClock
import org.revdog.retriever.RetrieverUploadJobService
import org.revdog.retriever.core.Clock
import org.revdog.retriever.core.HttpRequest
import org.revdog.retriever.core.HttpResponse
import org.revdog.retriever.core.Platform
import org.revdog.retriever.core.PlatformEvent
import org.revdog.retriever.core.PlatformEventSink
import org.revdog.retriever.core.Transport
import org.revdog.retriever.core.stackTraceText
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
 * - 生命周期：进程级 [LifecycleTracker]（init provider 里注册，按 Activity 身份集合计数）→ 前台 / 后台（配置变更重建不算）；
 * - 网络：`registerDefaultNetworkCallback` 的 onAvailable 只用来提前唤醒（不做可达性预检）；
 * - 后台兜底：框架 JobScheduler 一次性作业（网络约束、持久化，缺开机权限退回非持久；ADR 0003 决定 13，不引 WorkManager），
 *   只认 / 只取消自己的同号作业（[JobGate]）；`setEnabled(false)` 取消它；
 * - StrictMode：宿主线程上碰磁盘处放行磁盘读写与 unbuffered IO 并恢复；网络请求打 `TrafficStats` 标签（[TaggedTransport]）；
 * - 栈：`printStackTrace` 文本（[stackTraceText]，不用 `Log.getStackTraceString`）。
 * configure 之前只用到它的进程名、设备字段与 StrictMode 放行（不注册任何监听）。
 */
internal class AndroidPlatform(private val app: Context, private val tracker: LifecycleTracker = LifecycleTracker.shared) : Platform {
    @Volatile
    private var sink: PlatformEventSink? = null
    private var observing = false
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

    /** 前后台：tracker 已注册取它的状态；否则取进程重要性（前台 / 可见 = fg；被作业、广播拉起的后台进程 = bg）。 */
    override fun isForeground(): Boolean = tracker.current() ?: importanceForeground()

    private fun importanceForeground(): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (e: RuntimeException) {
        false
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

    /**
     * 实例创建时：订阅 tracker（provider 已注册时状态从进程一开始就对；没注册且 context 是 Application → 现在注册，初值取进程重要性；
     * context 不是 Application（`attachBaseContext` 里传 base）→ 等 provider 稍后给出 Application 时由 tracker 补注册）。
     */
    @Synchronized
    override fun startObserving(sink: PlatformEventSink) {
        this.sink = sink
        tracker.subscribe(sink)
        if (observing) return
        observing = true
        (app as? Application)?.let { a -> tracker.install(a, false) { importanceForeground() } }
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
        tracker.unsubscribe(sink)
    }

    private fun post(e: PlatformEvent) {
        sink?.platformEvent(e)
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

    private val jobs = JobGate(object : JobApi {
        override fun pendingService(id: Int): String? {
            val js = app.getSystemService(JobScheduler::class.java) ?: return null
            val p = js.getPendingJob(id) ?: return null
            return p.service?.className ?: ""
        }

        override fun schedule(id: Int, persisted: Boolean): Boolean {
            val js = app.getSystemService(JobScheduler::class.java) ?: return false
            val b = JobInfo.Builder(id, ComponentName(app, RetrieverUploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (persisted) b.setPersisted(true)
            return js.schedule(b.build()) == JobScheduler.RESULT_SUCCESS
        }

        override fun cancel(id: Int) {
            app.getSystemService(JobScheduler::class.java)?.cancel(id)
        }
    }, RetrieverUploadJobService::class.java.name)

    override fun scheduleUploadJob(jobId: Int): Boolean = try {
        jobs.schedule(jobId)
    } catch (e: RuntimeException) {
        // 绝不抛给宿主（作业数超限、manifest 被裁掉等）
        false
    }

    override fun cancelUploadJob(jobId: Int) {
        try {
            jobs.cancel(jobId)
        } catch (e: RuntimeException) {
            // 绝不抛给宿主
        }
    }

    override fun stackTraceString(t: Throwable): String = stackTraceText(t)

    /**
     * `allowThreadDiskWrites()` 只放行磁盘读写两位；`ThreadPolicy.detectAll()` 在 targetSdk ≥ 26 时还包含 unbuffered IO
     * （逐行一次 write 恰好命中：一个流上 > 10 次操作且累计 < 5 KB），API 26+ 另加 `permitUnbufferedIo()`（ADR 0020 决定 6）。
     */
    override fun allowDiskWrites(): Any? {
        val old = StrictMode.allowThreadDiskWrites()
        if (Build.VERSION.SDK_INT >= 26) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitUnbufferedIo().build())
        }
        return old
    }

    override fun restoreDiskPolicy(token: Any?) {
        if (token is StrictMode.ThreadPolicy) StrictMode.setThreadPolicy(token)
    }

    companion object {
        /** SDK 自己的上传 / 拉配置套接字的 `TrafficStats` 标签（宿主开 `VmPolicy.detectUntaggedSockets` 不报违规）。 */
        const val TRAFFIC_TAG = 0x5254
    }
}

/**
 * 网络请求前后打 `TrafficStats` 线程标签（ADR 0020 决定 6）：`VmPolicy.detectAll()` 在 targetSdk ≥ 26 时包含
 * untagged sockets 检查；`HttpURLConnection` 在调用线程上建连，线程标签会落到套接字上。
 */
internal class TaggedTransport(private val inner: Transport) : Transport {
    override fun send(request: HttpRequest): HttpResponse? {
        TrafficStats.setThreadStatsTag(AndroidPlatform.TRAFFIC_TAG)
        try {
            return inner.send(request)
        } finally {
            TrafficStats.clearThreadStatsTag()
        }
    }

    override fun cancelAll() {
        inner.cancelAll()
    }
}
