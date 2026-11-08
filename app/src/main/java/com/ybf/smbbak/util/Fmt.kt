package com.ybf.smbbak.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun timestamp(ms: Long): String = dateFmt.format(Date(ms))

    /** 人类可读的文件大小 */
    fun bytes(n: Long): String {
        if (n < 0) return "未知"
        if (n < 1024) return "$n B"
        val kb = n / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /** 人类可读的速率（字节/秒 → B/s、KB/s、MB/s、GB/s） */
    fun speed(bps: Long): String {
        if (bps <= 0) return "—"
        if (bps < 1024) return "$bps B/s"
        val kb = bps / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.2f MB/s", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB/s", gb)
    }

    /** 适合做文件名的紧凑时间戳 */
    fun fileStamp(ms: Long = System.currentTimeMillis()): String {
        val f = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return f.format(java.util.Date(ms))
    }

    /** 去掉文件名中不允许出现在远端路径里的字符 */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim()
        return when {
            cleaned.isEmpty() -> "unnamed"
            cleaned == "." || cleaned == ".." -> "unnamed"
            else -> cleaned
        }
    }

    /** 给重名文件追加序号：a.jpg -> a (2).jpg */
    fun withSuffix(base: String, index: Int): String {
        if (index <= 1) return base
        val dot = base.lastIndexOf('.')
        return if (dot > 0) {
            base.substring(0, dot) + " ($index)" + base.substring(dot)
        } else {
            "$base ($index)"
        }
    }
}
