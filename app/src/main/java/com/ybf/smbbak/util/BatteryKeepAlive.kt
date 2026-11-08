package com.ybf.smbbak.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活辅助：
 * - 前台服务 + 运行锁(WakeLock)已由服务/引擎保证；
 * - “忽略电池优化”需用户授权（请求界面 + 权限声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）。
 */
object BatteryKeepAlive {

    fun isIgnoringOptimizations(context: Context): Boolean {
        val pm = context.applicationContext
            .getSystemService(Context.POWER_SERVICE) as PowerManager
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /** 跳转到系统“电池优化→允许”请求页（需已在 Manifest 声明对应权限） */
    fun requestIgnoreIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }
}
