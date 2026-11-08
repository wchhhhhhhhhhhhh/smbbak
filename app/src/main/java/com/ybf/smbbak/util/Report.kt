package com.ybf.smbbak.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.ybf.smbbak.App
import com.ybf.smbbak.R
import com.ybf.smbbak.data.Prefs

/**
 * 导出错误报告：把版本/设备/配置摘要/最近日志/崩溃堆栈打包成文本，
 * 通过系统“另存为”(SAF) 保存后可直接分享。避免鸿蒙剪贴板对长文本的截断。
 */
object Report {

    fun newFileName(): String = "易备份错误报告_${Fmt.fileStamp()}.txt"

    fun build(context: Context): String {
        val sb = StringBuilder()
        sb.append("===== 易备份 错误报告 =====").append('\n')
        sb.append("时间：").append(Fmt.timestamp(System.currentTimeMillis())).append('\n')

        // 应用与设备
        val app = context.applicationContext
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            sb.append("应用版本：v").append(info.versionName)
                .append(" (code ").append(info.versionCode).append(")\n")
        }
        sb.append("设备：").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append('\n')
        sb.append("系统：Android API ").append(Build.VERSION.SDK_INT)
            .append(" / ").append(Build.VERSION.RELEASE).append('\n')
        sb.append("指纹：").append(Build.FINGERPRINT).append('\n')

        // 配置摘要（不含密码）
        val cfg = Prefs.loadConfig(app)
        sb.append('\n').append("----- 传输配置 -----").append('\n')
        sb.append("连接方式：").append(
            when {
                cfg.isHotspotMode -> "热点(电脑连手机)"
                cfg.isUsbMode -> "USB 直连"
                else -> "Wi-Fi SMB"
            }
        ).append('\n')
        sb.append("服务器/主机：").append(cfg.host.ifBlank { "(未填)" }).append('\n')
        if (cfg.isUsbMode || cfg.isHotspotMode) {
            sb.append("电脑IP(手动)：").append(cfg.usbHostManual.ifBlank { "(留空=自动)" }).append('\n')
        }
        sb.append("共享名：").append(cfg.share.ifBlank { "(未填)" }).append('\n')
        sb.append("远端根目录：").append(cfg.rootNorm.ifBlank { "(共享根)" }).append('\n')
        sb.append("用户名：").append(if (cfg.user.isBlank()) "(匿名)" else "(已设置)").append('\n')
        sb.append("照片备份：").append(if (cfg.imagesEnabled) "开" else "关")
            .append("； 视频备份：").append(if (cfg.videosEnabled) "开" else "关").append('\n')
        sb.append("图集筛选(图/视频)：")
            .append(cfg.albumCountFor("image")).append(" / ")
            .append(cfg.albumCountFor("video")).append(" 个\n")
        sb.append("自定义文件夹：").append(cfg.folders.size).append(" 个\n")
        sb.append("自动备份：").append(
            when (cfg.autoHours) {
                24L -> "每天"
                72L -> "每3天"
                168L -> "每周"
                else -> "关闭"
            }
        ).append("； 仅WiFi：").append(if (cfg.wifiOnlyAuto) "是" else "否")
            .append("； 保留时间：").append(if (cfg.keepDates) "是" else "否").append('\n')

        // 最近结果
        sb.append('\n').append("----- 最近一次运行 -----").append('\n')
        val res = Prefs.lastResult(app)
        sb.append("结果：").append(res ?: "无").append('\n')
        if (res != null) {
            sb.append("时间：").append(Fmt.timestamp(Prefs.lastAt(app))).append('\n')
            sb.append("上传/跳过/失败：").append(Prefs.lastUploaded(app)).append(" / ")
                .append(Prefs.lastSkipped(app)).append(" / ")
                .append(Prefs.lastFailed(app)).append('\n')
            sb.append("字节：").append(Prefs.lastBytes(app)).append('\n')
            Prefs.lastError(app)?.let { if (it.isNotBlank()) sb.append("错误：").append(it).append('\n') }
        }
        if (Prefs.isPaused(app)) {
            sb.append("暂停中：剩余 ").append(Prefs.pausedRemaining(app)).append(" 个\n")
        }

        // 崩溃堆栈
        val crash = App.crashText()
        if (crash.isNotBlank()) {
            sb.append('\n').append("----- 崩溃堆栈 -----").append('\n')
            sb.append(crash.take(120_000)).append('\n')
        }

        // 备份日志尾部
        sb.append('\n').append("----- 备份日志(尾部) -----").append('\n')
        sb.append(LogStore.get(app).readTail(60_000)).append('\n')

        sb.append('\n').append("===== 报告结束 =====").append('\n')
        return sb.toString()
    }

    /** 写入 SAF 提供的 Uri；成功返回 null，失败返回错误文本 */
    fun save(context: Context, uri: Uri): String? {
        return try {
            val os = context.contentResolver.openOutputStream(uri)
                ?: return "无法创建文件（输出流为空）"
            os.use { out ->
                out.write(build(context).toByteArray(Charsets.UTF_8))
            }
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    /** 保存成功后调起系统分享（发送报告给开发者） */
    fun share(activity: Activity, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.report_share_title))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.report_share_title)))
        }
    }
}
