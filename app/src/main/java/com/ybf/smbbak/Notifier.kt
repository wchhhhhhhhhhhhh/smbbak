package com.ybf.smbbak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

import com.ybf.smbbak.backup.BackupEngine
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.R

/** 进度与结果通知 */
object Notifier {

    const val CH_PROGRESS = "backup_progress"
    const val CH_RESULT = "backup_result"
    const val NOTIF_RUNNING = 1001
    const val NOTIF_DONE = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_PROGRESS,
                    context.getString(R.string.notif_channel_progress),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_RESULT,
                    context.getString(R.string.notif_channel_result),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    fun progress(context: Context, title: String, text: String, done: Int, total: Int): Notification {
        val b = base(context, CH_PROGRESS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            b.setProgress(total, done, false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    fun done(context: Context, title: String, text: String): Notification {
        return base(context, CH_RESULT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
    }

    /** 依据引擎结果弹出结束通知 */
    fun showResult(context: Context, s: BackupEngine.Summary) {
        val res = context.resources
        if (s.result == BackupEngine.RESULT_PAUSED) {
            val remain = Prefs.pausedRemaining(context)
            show(
                context, NOTIF_DONE,
                done(
                    context,
                    res.getString(R.string.notif_paused_title),
                    res.getString(R.string.notif_paused_text, remain)
                )
            )
            return
        }
        val title = when (s.result) {
            BackupEngine.RESULT_OK, BackupEngine.RESULT_PARTIAL ->
                res.getString(R.string.notif_done_title)
            BackupEngine.RESULT_CANCELLED ->
                res.getString(R.string.notif_cancel_title)
            else ->
                res.getString(R.string.notif_fail_title)
        }
        val text = when {
            s.error.isNotEmpty() ->
                res.getString(R.string.notif_fail_text, s.error)
            s.result == BackupEngine.RESULT_CANCELLED ->
                res.getString(R.string.notif_cancel_text, s.uploaded)
            else ->
                res.getString(R.string.notif_done_text, s.uploaded, s.skipped, s.failed)
        }
        show(context, NOTIF_DONE, done(context, title, text))
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder {
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun canPost(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return NotificationManagerCompat.from(app).areNotificationsEnabled()
    }

    fun show(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
