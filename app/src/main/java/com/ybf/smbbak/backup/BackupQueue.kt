package com.ybf.smbbak.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 备份待办队列（文件级断点续传用）：暂停时把未完成列表落盘，可稍后继续或换目标续传。
 */
object BackupQueue {

    private fun queueFile(context: Context): File =
        File(context.filesDir, "backup_queue.json")

    fun exists(context: Context): Boolean = queueFile(context).exists()

    fun save(context: Context, items: List<PlanItem>) {
        val arr = JSONArray()
        for (it in items) arr.put(it.toJson())
        val o = JSONObject().put("v", 1).put("items", arr)
        runCatching {
            val f = queueFile(context)
            f.parentFile?.mkdirs()
            f.writeText(o.toString(), Charsets.UTF_8)
        }
    }

    fun load(context: Context): List<PlanItem>? {
        return runCatching {
            val f = queueFile(context)
            if (!f.exists()) return null
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<PlanItem>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(PlanItem.fromJson(arr.optJSONObject(i) ?: continue))
            }
            out
        }.getOrNull()
    }

    fun delete(context: Context) {
        runCatching { queueFile(context).delete() }
    }
}
