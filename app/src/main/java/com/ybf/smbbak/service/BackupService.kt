package com.ybf.smbbak.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.ybf.smbbak.Notifier
import com.ybf.smbbak.R
import com.ybf.smbbak.backup.BackupEngine
import com.ybf.smbbak.backup.BackupEvents
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 手动备份的前台服务：显示进度通知（含实时速度），任务完成后自停。
 * START_STICKY：备份途中若被系统杀掉进程，会重启服务并自动继续（断点续传或全量自愈）。
 */
class BackupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notifierJob: Job? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_CANCEL, false) == true) {
            BackupEvents.requestCancel()
            return START_NOT_STICKY
        }
        if (BackupEvents.isBusy) return START_STICKY
        startRun()
        return START_STICKY
    }

    /** 启动一次备份（手动点击 / 换目标 / 系统杀进程后的自动恢复共用） */
    private fun startRun() {
        val app = applicationContext
        Notifier.ensureChannels(app)
        val title = app.getString(R.string.notif_running_title, shareLabel(app))
        val notification = Notifier.progress(
            app, title, app.getString(R.string.phase_connect), 0, 0
        )
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                Notifier.NOTIF_RUNNING, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Notifier.NOTIF_RUNNING, notification)
        }

        notifierJob = scope.launch {
            BackupEvents.state.collect { s ->
                if (!s.running) return@collect
                val now = System.currentTimeMillis()
                if (now - lastNotifyAt < 400) return@collect
                lastNotifyAt = now
                val text = buildString {
                    append(s.phase)
                    if (s.currentName.isNotEmpty()) append("\n").append(s.currentName)
                    if (s.total > 0) append("\n").append(s.done).append(" / ").append(s.total)
                    if (s.bytes > 0) append(" · 已传 ").append(Fmt.bytes(s.bytes))
                    if (s.speedBps > 0) append("\n速度 ").append(Fmt.speed(s.speedBps))
                }
                val notification = Notifier.progress(app, title, text, s.done, s.total)
                Notifier.show(app, Notifier.NOTIF_RUNNING, notification)
            }
        }

        scope.launch {
            val cfg = Prefs.loadConfig(app)
            val summary = BackupEngine.run(app, cfg, app.getString(R.string.main_btn_backup_now))
            notifierJob?.cancel()
            Notifier.showResult(app, summary)
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun shareLabel(context: Context): String {
        val cfg = Prefs.loadConfig(context)
        return if (cfg.hasTarget) "${cfg.host}/${cfg.share}" else context.getString(R.string.not_configured)
    }

    companion object {
        const val EXTRA_CANCEL = "cancel"

        fun start(context: Context) {
            val i = Intent(context, BackupService::class.java)
            context.startForegroundService(i)
        }

        fun requestStop(context: Context) {
            val i = Intent(context, BackupService::class.java).putExtra(EXTRA_CANCEL, true)
            context.startService(i)
        }
    }
}
