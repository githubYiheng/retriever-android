package org.revdog.retriever

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 只在进程启动时（Application.onCreate 之前）记下 Application context，不做任何磁盘 I/O；
 * 使 `configure` 之前的 `log()` 也能落盘（方案 §3.10）。只在默认进程实例化。不提供任何数据。
 */
public class RetrieverInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { Retriever.attachContext(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
