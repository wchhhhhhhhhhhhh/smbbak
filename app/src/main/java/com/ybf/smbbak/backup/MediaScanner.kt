package com.ybf.smbbak.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/**
 * 手机媒体（照片/视频）扫描，基于 MediaStore。
 */
object MediaScanner {

    const val KIND_IMAGE = "image"
    const val KIND_VIDEO = "video"

    data class Row(
        val id: Long,
        val displayName: String,
        val dateMs: Long,
        val size: Long,     // -1 = 未知
        val uri: Uri
    )

    /** 一个图集（按 MediaStore 的文件夹 bucket 聚合） */
    data class Album(
        val bucketId: String,
        val name: String,
        val count: Int
    )

    private const val COL_BUCKET_ID = "bucket_id"
    private const val COL_BUCKET_NAME = "bucket_display_name"

    private fun collectionOf(kind: String): Uri =
        if (kind == KIND_IMAGE) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /** 列出某类媒体在设备上的全部图集（含各 App 目录），按名称排序 */
    fun listAlbums(context: Context, kind: String): List<Album> {
        val resolver = context.contentResolver
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        resolver.query(
            collectionOf(kind),
            arrayOf(COL_BUCKET_ID, COL_BUCKET_NAME),
            null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(COL_BUCKET_ID)
            val iName = c.getColumnIndexOrThrow(COL_BUCKET_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId).toString()
                val name = c.getString(iName)?.takeIf { it.isNotBlank() } ?: "(未知)"
                val key = id to name
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .map { (k, v) -> Album(bucketId = k.first, name = k.second, count = v) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.bucketId }))
    }

    fun hasReadPermission(context: Context, kind: String): Boolean {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= 33) {
            val perm = if (kind == KIND_IMAGE) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_MEDIA_VIDEO
            ContextCompat.checkSelfPermission(app, perm) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(app, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 扫描某类媒体。
     * @param bucketFilter 非空时仅返回这些图集(bucket_id)内的媒体，用于“只备份选定图集”
     */
    fun scan(context: Context, kind: String, bucketFilter: Set<String>? = null): List<Row> {
        val collection = collectionOf(kind)
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.SIZE
        )
        val selection = if (bucketFilter.isNullOrEmpty()) null
        else "$COL_BUCKET_ID IN (${bucketFilter.joinToString(",")})"
        val rows = ArrayList<Row>()
        resolver.query(
            collection, projection, selection, null,
            MediaStore.MediaColumns.DATE_ADDED + " ASC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val takenMs = c.getLong(iTaken)
                val modSec = c.getLong(iMod)
                val dateMs = if (takenMs > 0) takenMs else modSec * 1000L
                val uri = ContentUris.withAppendedId(collection, id)
                // 媒体大小一律按“未知”处理：华为图库/卓易通等环境下 MediaStore 的 SIZE
                // 与实际可读流长度不一致（可能虚高也可能偏小），若用它做“期望字节数”校验会
                // 把正常文件误判为失败。上传时以流实际写入字节为准。
                rows.add(
                    Row(
                        id = id,
                        displayName = c.getString(iName) ?: "file_$id",
                        dateMs = dateMs,
                        size = -1L,
                        uri = uri
                    )
                )
            }
        }
        return rows
    }
}
