package com.ybf.smbbak.backup

import android.content.Context
import com.ybf.smbbak.util.Hash
import org.json.JSONObject
import java.io.File

/**
 * 内容哈希索引：hash(sha256) -> 远端路径。
 * 按目标隔离存储；用于跨运行的“内容相同不重复上传”。
 */
class ContentIndex private constructor(private val file: File) {

    private val json: JSONObject = load()

    fun get(hash: String): String? =
        if (json.has(hash)) json.getString(hash) else null

    fun put(hash: String, relPath: String) {
        json.put(hash, relPath)
    }

    fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.toString(), Charsets.UTF_8)
        }
    }

    private fun load(): JSONObject {
        return runCatching {
            if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
        }.getOrDefault(JSONObject())
    }

    companion object {
        fun open(context: Context, targetKey: String): ContentIndex {
            val dir = File(context.filesDir, "registry").apply { mkdirs() }
            val suffix = if (targetKey.isBlank()) "default" else Hash.tag(targetKey, 10)
            return ContentIndex(File(dir, "content_$suffix.json"))
        }
    }
}
