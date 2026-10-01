package org.revdog.retriever.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.revdog.retriever.core.PlatformEvent
import org.revdog.retriever.core.PlatformEventSink
import java.util.WeakHashMap

/**
 * 进程级前后台 tracker（ADR 0023 决定 6；简报 §1.4）：不依赖实例、无 I/O。默认进程里 `RetrieverInitProvider.onCreate`
 * 就注册（早于任何 Activity），状态从进程一开始就对；provider 不在（被移除 / 非默认进程）时退回实例创建时注册，初值取进程重要性。
 * 按 Activity 身份集合计数：没见过 onStart 的 Activity 的 onStop 不参与（注册晚于某个 Activity 启动时，A → B 切换不误报后台）。
 * 配置变更（旋转）重建不算进后台。
 */
internal class LifecycleTracker {
    private val lock = Any()
    private var installed = false
    private val started = WeakHashMap<Activity, Boolean>()
    private var changing = 0
    private var foreground: Boolean? = null

    @Volatile
    private var sink: PlatformEventSink? = null

    /**
     * 注册生命周期回调（只注册一次）。`early` = 由 init provider 在 Application.onCreate 之前注册——还没有任何 Activity，
     * 初值 = 后台；否则初值取 [initial]（进程重要性）。
     */
    fun install(app: Application, early: Boolean, initial: () -> Boolean?) {
        val first = synchronized(lock) {
            if (installed) {
                false
            } else {
                installed = true
                foreground = if (early) false else initial()
                true
            }
        }
        if (first) app.registerActivityLifecycleCallbacks(callbacks)
    }

    val isInstalled: Boolean get() = synchronized(lock) { installed }

    /** 当前前后台（null = 还没注册 / 不知道）。 */
    fun current(): Boolean? = synchronized(lock) { if (installed) foreground else null }

    fun subscribe(s: PlatformEventSink) {
        sink = s
    }

    fun unsubscribe(s: PlatformEventSink) {
        if (sink === s) sink = null
    }

    private fun post(e: PlatformEvent) {
        try {
            sink?.platformEvent(e)
        } catch (t: Throwable) {
            // 绝不抛进宿主的生命周期回调
        }
    }

    fun onStarted(activity: Activity) {
        val fire = synchronized(lock) {
            if (started.containsKey(activity)) return
            started[activity] = true
            if (changing > 0) {
                changing -= 1
                false
            } else if (foreground != true) {
                foreground = true
                true
            } else {
                false
            }
        }
        if (fire) post(PlatformEvent.WILL_ENTER_FOREGROUND)
    }

    fun onStopped(activity: Activity) {
        val fire = synchronized(lock) {
            // 没见过它的 onStart（注册之前就启动了）：不参与计数
            if (started.remove(activity) == null) return
            if (activity.isChangingConfigurations) {
                // 旋转等配置变更：马上会重建并 onStart，不算进后台
                changing += 1
                false
            } else if (started.isEmpty() && foreground != false) {
                foreground = false
                true
            } else {
                false
            }
        }
        if (fire) post(PlatformEvent.DID_ENTER_BACKGROUND)
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        // 宿主 Activity 覆写的 hashCode / isChangingConfigurations 抛异常也不进宿主的生命周期回调
        override fun onActivityStarted(activity: Activity) {
            try {
                onStarted(activity)
            } catch (t: Throwable) {
                // 绝不抛给宿主
            }
        }

        override fun onActivityStopped(activity: Activity) {
            try {
                onStopped(activity)
            } catch (t: Throwable) {
                // 绝不抛给宿主
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        /** 进程内唯一的 tracker。 */
        val shared = LifecycleTracker()
    }
}

/** JobScheduler 的最小面（[JobGate] 用；AndroidPlatform 给真实现，测试给假实现）。 */
internal interface JobApi {
    /** 同 id 排着（或在跑）的作业的 service 类名；没有作业 = null。 */
    fun pendingService(id: Int): String?

    /** 排作业（`persisted` = 开机后保留）；系统拒绝抛异常或返回 false。 */
    fun schedule(id: Int, persisted: Boolean): Boolean

    fun cancel(id: Int)
}

/**
 * 后台作业认归属（ADR 0024 决定 11）：只有 `getPendingJob(id)?.service` 是我们的 JobService 时才视为「已排」或才 cancel；
 * 不是自己的同号作业一律不碰（不替换、不取消，返回 false 由调用方记一次诊断）。`setPersisted(true)` 因缺
 * `RECEIVE_BOOT_COMPLETED` 抛异常时退回非持久作业。
 */
internal class JobGate(private val api: JobApi, private val ours: String) {
    fun schedule(id: Int): Boolean {
        val pending = api.pendingService(id)
        if (pending != null) return pending == ours
        val persisted = try {
            api.schedule(id, true)
        } catch (e: RuntimeException) {
            null
        }
        if (persisted == true) return true
        return try {
            api.schedule(id, false)
        } catch (e: RuntimeException) {
            false
        }
    }

    fun cancel(id: Int) {
        if (api.pendingService(id) == ours) api.cancel(id)
    }
}
