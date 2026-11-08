package com.ybf.smbbak.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ybf.smbbak.Notifier
import com.ybf.smbbak.R
import com.ybf.smbbak.data.Prefs

/**
 * 自动备份任务：由 WorkManager 周期调度。
 */
class BackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (BackupEvents.isBusy) {
            // 已有任务在运行（如手动备份），本次自动任务放弃
            return Result.success()
        }
        // 有暂停中的手动任务：自动备份不覆盖，等用户决定继续/放弃
        if (Prefs.isPaused(app)) return Result.success()

        Notifier.ensureChannels(app)
        setForeground(createForegroundInfo())
        val cfg = Prefs.loadConfig(app)
        if (!cfg.hasTarget) return Result.success()
        if (!cfg.imagesEnabled && !cfg.videosEnabled && cfg.folders.isEmpty()) return Result.success()

        val summary = BackupEngine.run(app, cfg, app.getString(R.string.options_auto))
        Notifier.showResult(app, summary)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val app = applicationContext
        Notifier.ensureChannels(app)
        val notification = Notifier.progress(
            app,
            app.getString(R.string.notif_running_title, app.getString(R.string.options_auto)),
            app.getString(R.string.phase_connect),
            0, 0
        )
        // 必须显式声明 dataSync：Android 14+ / 鸿蒙 API36 下 ForegroundInfo 不带类型会以
        // “type none”起前台服务直接崩溃
        return ForegroundInfo(
            Notifier.NOTIF_RUNNING,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
