package com.ybf.smbbak.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ybf.smbbak.data.AppConfig
import java.util.concurrent.TimeUnit

/** 自动备份调度（WorkManager 周期任务，设备重启后自动恢复） */
object Scheduler {

    private const val UNIQUE_AUTO = "auto_backup"

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_AUTO) }
    }

    fun apply(context: Context, cfg: AppConfig) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(UNIQUE_AUTO)
        val hours = cfg.autoHours
        if (hours <= 0) return
        val interval = hours.coerceAtLeast(1)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (cfg.wifiOnlyAuto) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            UNIQUE_AUTO,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
