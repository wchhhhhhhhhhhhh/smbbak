package com.ybf.smbbak.util

import android.content.Context
import com.ybf.smbbak.data.Prefs
import java.io.File

/**
 * WorkManager 自愈：若上次自动备份调度异常（WorkManager 数据库可能损坏导致重开 App 崩溃），
 * 下次启动时删除其数据库文件让它重建。
 */
object WorkReset {

    private const val DB_NAME = "androidx.work.workdb"

    fun apply(context: Context) {
        val app = context.applicationContext
        if (!Prefs.isScheduleCrash(app)) return
        Prefs.clearScheduleCrash(app)
        runCatching {
            val base = File(app.getDatabasePath(DB_NAME).path)
            listOf(base, File(base.path + "-wal"), File(base.path + "-shm"))
                .forEach { if (it.exists()) it.delete() }
        }
    }
}
