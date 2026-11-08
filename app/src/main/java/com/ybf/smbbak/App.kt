package com.ybf.smbbak

import android.app.Application
import java.io.File

/**
 * 崩溃捕获：把未捕获异常（主线程/任意后台线程）写入 filesDir/crash.log，
 * 下次启动主页会弹窗展示堆栈，便于在无法抓 logcat 的环境定位崩溃。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        filesDirRef = filesDir
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = filesDirRef
                if (dir != null) {
                    val f = File(dir, CRASH_FILE_NAME)
                    f.parentFile?.mkdirs()
                    val sb = StringBuilder()
                    sb.append("\n===== 崩溃时间：").append(java.util.Date()).append(" =====\n")
                    sb.append("线程：").append(thread.name).append('\n')
                    sb.append(android.util.Log.getStackTraceString(throwable)).append('\n')
                    f.appendText(sb.toString())
                } else {
                    android.util.Log.e("SmbBackup", "crash before filesDir ready", throwable)
                }
            }
            original?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE_NAME = "crash.log"

        @Volatile
        var filesDirRef: File? = null
            private set

        fun crashText(): String {
            return runCatching {
                val f = filesDirRef?.let { File(it, CRASH_FILE_NAME) } ?: return ""
                if (f.exists()) f.readText(Charsets.UTF_8) else ""
            }.getOrDefault("")
        }

        fun clearCrash() {
            runCatching {
                filesDirRef?.let { File(it, CRASH_FILE_NAME) }?.delete()
            }
        }
    }
}
