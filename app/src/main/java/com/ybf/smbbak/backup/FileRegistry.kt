package com.ybf.smbbak.backup

import org.json.JSONObject
import java.io.File

/**
 * 增量备份登记表：记录某个备份源里“已成功上传”的文件。
 * key 为本地文件标识，value 为 "远端路径|修改时间ms|大小"。
 * 通过比对远端路径 + 大小 + 修改时间判断是否已备份，实现增量跳过。
 */
class FileRegistry private constructor(private val file: File) {

    private val json: JSONObject = load()

    fun get(key: String): String? =
        if (json.has(key)) json.getString(key) else null

    fun put(key: String, value: String) {
        json.put(key, value)
    }

    fun prune(keep: Set<String>) {
        val it = json.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in keep) it.remove()
        }
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
        /**
         * id 仅允许 [a-zA-Z0-9_-]；targetKey 用于按“目标”隔离登记表（避免切换目标后误判已备份）。
         *
         * 旧版升级兼容：v1.2 之前的登记文件名为 reg_<id>.json（无目标后缀，格式 "路径|时间|大小"）。
         * 首次打开某个目标的新登记表时，若旧登记存在则自动迁移（转成 "U|路径|时间|大小"），
         * 引擎随后会再对照“远端同路径同大小”做二次校验，从而识别旧版已上传的文件、跳过不重传。
         */
        fun open(context: android.content.Context, id: String, targetKey: String): FileRegistry {
            val dir = File(context.filesDir, "registry").apply { mkdirs() }
            val suffix = if (targetKey.isBlank()) "default"
            else com.ybf.smbbak.util.Hash.tag(targetKey, 10)
            val file = File(dir, "reg_${id}_$suffix.json")
            if (!file.exists()) {
                val legacy = File(dir, "reg_$id.json")
                if (legacy.exists()) {
                    runCatching {
                        val src = JSONObject(legacy.readText(Charsets.UTF_8))
                        val dst = JSONObject()
                        val it = src.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            val v = src.optString(k, "")
                            // 旧格式 "p|m|s"（3 段）→ 新格式 "U|p|m|s"；其它原样保留
                            dst.put(k, if (v.split('|').size == 3) "U|$v" else v)
                        }
                        file.writeText(dst.toString(), Charsets.UTF_8)
                    }
                }
            }
            return FileRegistry(file)
        }
    }
}
