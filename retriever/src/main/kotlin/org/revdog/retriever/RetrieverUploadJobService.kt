package org.revdog.retriever

import android.app.job.JobParameters
import android.app.job.JobService
import org.revdog.retriever.core.RetrieverClient

/**
 * 后台兜底上传（方案 §3.9；ADR 0003 决定 13：框架 JobScheduler，不引 AndroidX WorkManager）。
 * 进后台 / fatal 时由 SDK 排一个一次性作业（网络约束、持久化）；系统在有网时拉起进程（宿主 Application.onCreate
 * 里的 configure 先跑，启动恢复会合成 unclean_exit），作业里排空出站箱（拿 upload.lock，不与在途重复）后 jobFinished。
 * 宿主没 configure（没有实例）→ 不建实例、直接结束（ADR 0023）。Doze 期间不跑，按系统维护窗口节奏。宿主不需要直接使用本类。
 */
public class RetrieverUploadJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val c = try {
            Retriever.clientFor(applicationContext)
        } catch (t: Throwable) {
            null
        } ?: return false
        c.runUploadJob(RetrieverClient.JOB_BUDGET_MS) { reschedule ->
            try {
                jobFinished(params, reschedule)
            } catch (e: RuntimeException) {
                // 作业已被系统回收
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        try {
            Retriever.currentClient()?.stopUploadJob()
        } catch (t: Throwable) {
            // 绝不抛给系统回调
        }
        return true
    }
}
