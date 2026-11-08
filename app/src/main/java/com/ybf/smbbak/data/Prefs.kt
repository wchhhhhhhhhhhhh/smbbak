package com.ybf.smbbak.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置与最近一次备份结果的持久化。
 */
object Prefs {

    private const val NAME = "smbbak"

    private const val KEY_CONFIG = "config_json"

    // 最近一次备份结果
    const val KEY_RESULT = "last_result"      // ok | partial | failed | cancelled | error
    const val KEY_AT = "last_at"
    const val KEY_UPLOADED = "last_uploaded"
    const val KEY_SKIPPED = "last_skipped"
    const val KEY_FAILED = "last_failed"
    const val KEY_BYTES = "last_bytes"
    const val KEY_ERROR = "last_error"
    const val KEY_DETAIL = "last_detail"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun loadConfig(ctx: Context): AppConfig =
        AppConfig.fromJson(sp(ctx).getString(KEY_CONFIG, null))

    fun saveConfig(ctx: Context, cfg: AppConfig) {
        sp(ctx).edit().putString(KEY_CONFIG, cfg.toJson()).apply()
    }

    fun saveLastRun(
        ctx: Context, result: String, at: Long,
        uploaded: Int, skipped: Int, failed: Int, bytes: Long,
        error: String = "", detail: String = ""
    ) {
        sp(ctx).edit()
            .putString(KEY_RESULT, result)
            .putLong(KEY_AT, at)
            .putInt(KEY_UPLOADED, uploaded)
            .putInt(KEY_SKIPPED, skipped)
            .putInt(KEY_FAILED, failed)
            .putLong(KEY_BYTES, bytes)
            .putString(KEY_ERROR, error)
            .putString(KEY_DETAIL, detail)
            .apply()
    }

    fun lastResult(ctx: Context): String? = sp(ctx).getString(KEY_RESULT, null)
    fun lastAt(ctx: Context): Long = sp(ctx).getLong(KEY_AT, 0L)
    fun lastUploaded(ctx: Context): Int = sp(ctx).getInt(KEY_UPLOADED, 0)
    fun lastSkipped(ctx: Context): Int = sp(ctx).getInt(KEY_SKIPPED, 0)
    fun lastFailed(ctx: Context): Int = sp(ctx).getInt(KEY_FAILED, 0)
    fun lastBytes(ctx: Context): Long = sp(ctx).getLong(KEY_BYTES, 0L)
    fun lastError(ctx: Context): String? = sp(ctx).getString(KEY_ERROR, null)
    fun lastDetail(ctx: Context): String? = sp(ctx).getString(KEY_DETAIL, null)

    // 暂停状态：暂停时置位，继续/放弃/全新备份开始时清除
    private const val KEY_PAUSED = "paused"
    private const val KEY_PAUSED_AT = "paused_at"
    private const val KEY_PAUSED_REMAINING = "paused_remaining"

    fun setPaused(ctx: Context, remaining: Int) {
        sp(ctx).edit()
            .putBoolean(KEY_PAUSED, true)
            .putLong(KEY_PAUSED_AT, System.currentTimeMillis())
            .putInt(KEY_PAUSED_REMAINING, remaining)
            .apply()
    }

    fun isPaused(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PAUSED, false)
    fun pausedAt(ctx: Context): Long = sp(ctx).getLong(KEY_PAUSED_AT, 0L)
    fun pausedRemaining(ctx: Context): Int = sp(ctx).getInt(KEY_PAUSED_REMAINING, 0)

    fun clearPaused(ctx: Context) {
        sp(ctx).edit()
            .remove(KEY_PAUSED)
            .remove(KEY_PAUSED_AT)
            .remove(KEY_PAUSED_REMAINING)
            .apply()
    }

    // 自动备份调度上次曾异常：下次启动时自愈（重置 WorkManager 库）
    private const val KEY_SCHEDULE_CRASH = "schedule_crash"

    fun isScheduleCrash(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SCHEDULE_CRASH, false)

    fun setScheduleCrash(ctx: Context) {
        sp(ctx).edit().putBoolean(KEY_SCHEDULE_CRASH, true).apply()
    }

    fun clearScheduleCrash(ctx: Context) {
        sp(ctx).edit().remove(KEY_SCHEDULE_CRASH).apply()
    }
}
