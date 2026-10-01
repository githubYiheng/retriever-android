package org.revdog.retriever

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 只在进程启动时（Application.onCreate 之前）记下 Application context、注册进程级前后台 tracker，不做任何磁盘 I/O；
 * 使 `configure` 之前的 `log()` 也能落盘（ADR 0023：写进 pre 文件，configure 时收编）、前后台状态从进程一开始就对。
 * 只在默认进程实例化（manifest 里 initOrder 调高，早于多数库的 provider）。不提供任何数据。
 */
public class RetrieverInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        try {
            context?.let { Retriever.attachFromProvider(it) }
        } catch (t: Throwable) {
            // 绝不抛给宿主
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
