package com.ybf.smbbak.backup

import org.json.JSONObject

/**
 * 备份计划中的单个文件（与目标无关，可跨目标续传）。
 * kind: image / video / saf / path
 */
class PlanItem(
    val kind: String,        // image|video|saf|path
    val label: String,       // 来源显示名（照片/视频/文件夹名）
    val srcId: String,       // 登记表基础 id（media_images/media_videos/folder_<hash>）
    val regKey: String,      // 登记表内 key（媒体: id；文件夹: relLocal）
    val relPath: String,     // 相对远端 root 的路径
    val size: Long,          // -1 = 未知
    val mtimeMs: Long,
    val mediaId: Long = -1L, // kind=image/video 时的 MediaStore 行 id
    val folderRoot: String = "",  // kind=saf: tree uri；kind=path: 绝对路径
    val relLocal: String = ""     // kind=saf/path：相对所选目录的路径
) {

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("label", label)
        .put("srcId", srcId)
        .put("regKey", regKey)
        .put("relPath", relPath)
        .put("size", size)
        .put("mtime", mtimeMs)
        .put("mediaId", mediaId)
        .put("folderRoot", folderRoot)
        .put("relLocal", relLocal)

    companion object {
        fun fromJson(o: JSONObject): PlanItem = PlanItem(
            kind = o.optString("kind", "image"),
            label = o.optString("label"),
            srcId = o.optString("srcId"),
            regKey = o.optString("regKey"),
            relPath = o.optString("relPath"),
            size = o.optLong("size", -1L),
            mtimeMs = o.optLong("mtime"),
            mediaId = o.optLong("mediaId", -1L),
            folderRoot = o.optString("folderRoot"),
            relLocal = o.optString("relLocal")
        )
    }
}
