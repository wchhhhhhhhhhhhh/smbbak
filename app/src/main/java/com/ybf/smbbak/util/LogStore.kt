package com.ybf.smbbak.util

import android.content.Context
import java.io.File

/**
 * 简单的追加式日志：写入 filesDir/logs/backup.log，超过上限自动轮转。
 */
class LogStore private constructor(private val file: File) {

    fun append(line: String) {
        runCatching {
            if (file.exists() && file.length() > MAX_SIZE) rotate()
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    fun readTail(maxChars: Int = 60_000): String {
        return runCatching {
            if (!file.exists()) return ""
            val text = file.readText(Charsets.UTF_8)
            if (text.length <= maxChars) text else text.substring(text.length - maxChars)
        }.getOrDefault("")
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun rotate() {
        val old = File(file.parentFile, file.name + ".old")
        if (old.exists()) old.delete()
        file.renameTo(old)
    }

    companion object {
        private const val MAX_SIZE = 512 * 1024

        @Volatile
        private var instance: LogStore? = null

        fun get(context: Context): LogStore {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            return instance ?: synchronized(this) {
                instance ?: LogStore(File(dir, "backup.log")).also { instance = it }
            }
        }
    }
}
