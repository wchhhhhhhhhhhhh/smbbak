package com.ybf.smbbak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.ybf.smbbak.backup.BackupEngine
import com.ybf.smbbak.backup.BackupEvents
import com.ybf.smbbak.backup.BackupEvents.Snapshot
import com.ybf.smbbak.backup.BackupQueue
import com.ybf.smbbak.backup.MediaScanner
import com.ybf.smbbak.data.AppConfig
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.service.BackupService
import com.ybf.smbbak.ui.LogsActivity
import com.ybf.smbbak.ui.OptionsActivity
import com.ybf.smbbak.ui.SmbActivity
import com.ybf.smbbak.ui.SourcesActivity
import com.ybf.smbbak.util.BatteryKeepAlive
import com.ybf.smbbak.util.Fmt
import com.ybf.smbbak.util.Report
import com.ybf.smbbak.util.WorkReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private enum class UiState { IDLE, RUNNING, PAUSED }

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var cardProgress: MaterialCardView
    private lateinit var tvPhase: TextView
    private lateinit var pbProgress: ProgressBar
    private lateinit var tvCount: TextView
    private lateinit var tvBytes: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var btnBackup: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnPause: MaterialButton
    private lateinit var btnSwitchTarget: MaterialButton
    private lateinit var tvSmbSummary: TextView
    private lateinit var tvSourcesSummary: TextView
    private lateinit var tvOptionsSummary: TextView

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val exportReportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val err = Report.save(this, uri)
            if (err == null) {
                App.clearCrash()
                Toast.makeText(this, R.string.report_exported, Toast.LENGTH_SHORT).show()
                Report.share(this, uri)
            } else {
                Toast.makeText(this, "导出失败：$err", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkReset.apply(this)
        setContentView(R.layout.activity_main)

        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        cardProgress = findViewById(R.id.cardProgress)
        tvPhase = findViewById(R.id.tvPhase)
        pbProgress = findViewById(R.id.pbProgress)
        tvCount = findViewById(R.id.tvCount)
        tvBytes = findViewById(R.id.tvBytes)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvCurrentFile = findViewById(R.id.tvCurrentFile)
        btnBackup = findViewById(R.id.btnBackup)
        btnStop = findViewById(R.id.btnStop)
        btnPause = findViewById(R.id.btnPause)
        btnSwitchTarget = findViewById(R.id.btnSwitchTarget)
        tvSmbSummary = findViewById(R.id.tvSmbSummary)
        tvSourcesSummary = findViewById(R.id.tvSourcesSummary)
        tvOptionsSummary = findViewById(R.id.tvOptionsSummary)

        findViewById<MaterialCardView>(R.id.cardSmb).setOnClickListener {
            startActivity(Intent(this, SmbActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardSources).setOnClickListener {
            startActivity(Intent(this, SourcesActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardOptions).setOnClickListener {
            startActivity(Intent(this, OptionsActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.cardLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        btnBackup.setOnClickListener { onPrimaryClick() }
        btnStop.setOnClickListener { onStopDiscardClick() }
        btnPause.setOnClickListener {
            BackupEvents.requestPause()
            btnPause.isEnabled = false
            Toast.makeText(this, R.string.pause_requested, Toast.LENGTH_SHORT).show()
        }
        btnSwitchTarget.setOnClickListener { onSwitchTarget() }

        maybeAskNotificationPermission()
        showCrashReportIfAny()
    }

    /** 上次崩溃过：弹出堆栈便于用户复制反馈 */
    private fun showCrashReportIfAny() {
        val text = App.crashText()
        if (text.isBlank()) return
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        sv.isVerticalScrollBarEnabled = true
        val pad = (12 * resources.displayMetrics.density).toInt()
        sv.setPadding(pad, pad, pad, pad)
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(R.string.crash_dialog_message)
            .setView(sv)
            .setPositiveButton(R.string.crash_dialog_export) { _, _ ->
                exportReportLauncher.launch(Report.newFileName())
            }
            .setNeutralButton(R.string.crash_dialog_copy) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        collectJob?.cancel()
        collectJob = scope.launch {
            BackupEvents.state.collect { s ->
                if (s.running) {
                    renderRunning(s)
                } else {
                    refreshAll()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 状态渲染 ----------

    private fun uiState(): UiState = when {
        BackupEvents.isBusy || BackupEvents.state.value.running -> UiState.RUNNING
        Prefs.isPaused(this) -> UiState.PAUSED
        else -> UiState.IDLE
    }

    private fun refreshAll() {
        val cfg = Prefs.loadConfig(this)
        tvSmbSummary.text = smbSummary(cfg)
        tvSourcesSummary.text = sourcesSummary(cfg)
        tvOptionsSummary.text = optionsSummary(cfg)
        applyUi(uiState())
    }

    private fun applyUi(state: UiState) {
        cardProgress.visibility =
            if (state == UiState.RUNNING) View.VISIBLE else View.GONE

        when (state) {
            UiState.RUNNING -> {
                btnBackup.visibility = View.GONE
                btnStop.visibility = View.VISIBLE
                btnStop.setText(R.string.main_btn_stop)
                btnPause.visibility = View.VISIBLE
                btnPause.isEnabled = true
                btnSwitchTarget.visibility = View.GONE
                renderStatusTitle(getString(R.string.main_status_running), "", useDetail = false)
            }
            UiState.PAUSED -> {
                btnBackup.visibility = View.VISIBLE
                btnBackup.setText(R.string.main_btn_continue)
                btnStop.visibility = View.VISIBLE
                btnStop.setText(R.string.main_btn_discard)
                btnPause.visibility = View.GONE
                btnSwitchTarget.visibility = View.VISIBLE
                renderPausedStatus()
            }
            UiState.IDLE -> {
                btnBackup.visibility = View.VISIBLE
                btnBackup.setText(R.string.main_btn_backup_now)
                btnStop.visibility = View.GONE
                btnPause.visibility = View.GONE
                btnSwitchTarget.visibility = View.GONE
                renderLastRun()
            }
        }
    }

    private fun renderRunning(s: Snapshot) {
        applyUi(UiState.RUNNING)
        tvPhase.text = buildString {
            append(s.phase)
            if (s.sourceLabel.isNotEmpty()) append("  ·  ").append(s.sourceLabel)
        }
        pbProgress.progress = s.percent
        tvCount.text = getString(R.string.main_progress_count, s.done, s.total)
        tvBytes.text = getString(R.string.main_bytes, Fmt.bytes(s.bytes))
        tvSpeed.text = getString(R.string.main_speed, Fmt.speed(s.speedBps))
        tvCurrentFile.text =
            if (s.currentName.isEmpty()) "" else getString(R.string.main_current_file, s.currentName)
    }

    private fun renderStatusTitle(title: String, detail: String, useDetail: Boolean) {
        tvStatusTitle.text = title
        tvStatusDetail.visibility = if (useDetail) View.VISIBLE else View.GONE
        if (useDetail) tvStatusDetail.text = detail
    }

    private fun renderPausedStatus() {
        val remain = Prefs.pausedRemaining(this)
        val at = Prefs.pausedAt(this)
        tvStatusTitle.text = "⏸ ${getString(R.string.main_paused_title)}"
        tvStatusDetail.visibility = View.VISIBLE
        tvStatusDetail.text = getString(
            R.string.main_paused_detail,
            remain,
            if (at > 0) Fmt.timestamp(at) else "—"
        )
    }

    private fun renderLastRun() {
        val result = Prefs.lastResult(this)
        if (result == null) {
            tvStatusTitle.text = getString(R.string.main_status_idle)
            tvStatusDetail.visibility = View.VISIBLE
            tvStatusDetail.text = getString(R.string.main_last_run, "—")
            return
        }
        val at = Prefs.lastAt(this)
        val uploaded = Prefs.lastUploaded(this)
        val skipped = Prefs.lastSkipped(this)
        val failed = Prefs.lastFailed(this)
        val bytes = Prefs.lastBytes(this)
        val err = Prefs.lastError(this)

        val title = when (result) {
            BackupEngine.RESULT_OK -> "✔ ${getString(R.string.main_result_ok)}"
            BackupEngine.RESULT_PARTIAL -> "⚠ ${getString(R.string.main_result_partial)}"
            BackupEngine.RESULT_CANCELLED -> "⏹ ${getString(R.string.main_result_cancelled)}"
            else -> "✖ ${getString(R.string.main_result_failed)}"
        }
        tvStatusTitle.text = title
        tvStatusDetail.visibility = View.VISIBLE
        tvStatusDetail.text = when {
            err.isNullOrBlank() -> buildString {
                append(getString(R.string.main_last_run, Fmt.timestamp(at)))
                append("\n")
                append(getString(R.string.main_summary, uploaded, skipped, failed, Fmt.bytes(bytes)))
            }
            else -> buildString {
                append(getString(R.string.main_last_run, Fmt.timestamp(at)))
                append("\n")
                append(getString(R.string.main_error_hint, err))
            }
        }
    }

    // ---------- 摘要文本 ----------

    private fun smbSummary(cfg: AppConfig): String {
        val head = when {
            cfg.isUsbMode -> "${getString(R.string.smb_mode_usb)} · "
            cfg.isHotspotMode -> "${getString(R.string.smb_mode_hotspot)} · "
            else -> ""
        }
        if (!cfg.hasTarget) return getString(R.string.not_configured)
        val root = cfg.rootNorm
        val host = if (cfg.isUsbMode || cfg.isHotspotMode) {
            cfg.usbHostManual.ifBlank { "电脑(自动)" }
        } else {
            cfg.host
        }
        return if (root.isEmpty()) head + "smb://$host/${cfg.share}"
        else head + "smb://$host/${cfg.share}/$root"
    }

    private fun sourcesSummary(cfg: AppConfig): String {
        val parts = ArrayList<String>()
        if (cfg.imagesEnabled) parts.add(getString(R.string.src_images_label))
        if (cfg.videosEnabled) parts.add(getString(R.string.src_videos_label))
        if (cfg.folders.isNotEmpty()) {
            parts.add(getString(R.string.src_folder_label) + " ×" + cfg.folders.size)
        }
        if (parts.isEmpty()) return getString(R.string.not_configured)
        return parts.joinToString("、")
    }

    private fun optionsSummary(cfg: AppConfig): String {
        val auto = when (cfg.autoHours) {
            0L -> getString(R.string.options_auto_off)
            24L -> getString(R.string.options_auto_daily)
            72L -> getString(R.string.options_auto_3days)
            else -> getString(R.string.options_auto_weekly)
        }
        val wifi = if (cfg.wifiOnlyAuto) " · Wi-Fi" else ""
        return "$auto$wifi"
    }

    // ---------- 动作 ----------

    private fun onPrimaryClick() {
        val state = uiState()
        if (state == UiState.PAUSED) {
            BackupService.start(this)
            showBatteryNagIfNeeded()
            return
        }
        if (BackupEvents.isBusy) return
        val cfg = Prefs.loadConfig(this)
        if (!cfg.hasTarget) {
            Toast.makeText(this, R.string.err_no_smb_config, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SmbActivity::class.java))
            return
        }
        val mediaPermOk =
            (!cfg.imagesEnabled || MediaScanner.hasReadPermission(this, MediaScanner.KIND_IMAGE)) &&
                (!cfg.videosEnabled || MediaScanner.hasReadPermission(this, MediaScanner.KIND_VIDEO))
        if (!mediaPermOk) {
            Toast.makeText(this, R.string.sources_media_perm_missing, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SourcesActivity::class.java))
            return
        }
        BackupService.start(this)
        showBatteryNagIfNeeded()
    }

    /** 首次未开“忽略电池优化”时提醒一次（防止长备份被后台杀掉） */
    private fun showBatteryNagIfNeeded() {
        if (BatteryKeepAlive.isIgnoringOptimizations(this)) return
        val prefs = getSharedPreferences("perm", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        Snackbar.make(btnBackup, R.string.battery_nag, Snackbar.LENGTH_LONG)
            .setAction(R.string.battery_nag_action) {
                startActivity(BatteryKeepAlive.requestIgnoreIntent(this@MainActivity))
            }
            .show()
    }

    private fun onStopDiscardClick() {
        if (uiState() == UiState.PAUSED) {
            BackupQueue.delete(applicationContext)
            Prefs.clearPaused(this)
            refreshAll()
        } else {
            BackupEvents.requestCancel()
            Toast.makeText(this, R.string.cancel_requested, Toast.LENGTH_SHORT).show()
        }
    }

    /** 暂停后选择其它目标继续备份（Wi-Fi SMB / USB / 热点） */
    private fun onSwitchTarget() {
        val cfg = Prefs.loadConfig(this)
        val options = arrayOf(
            getString(R.string.smb_mode_wifi),
            getString(R.string.smb_mode_usb),
            getString(R.string.smb_mode_hotspot)
        )
        val checked = when {
            cfg.isUsbMode -> 1
            cfg.isHotspotMode -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.target_switch_title)
            .setSingleChoiceItems(options, checked) { d, which ->
                val newMode = when (which) {
                    1 -> AppConfig.MODE_USB
                    2 -> AppConfig.MODE_HOTSPOT
                    else -> AppConfig.MODE_WIFI
                }
                val updated = Prefs.loadConfig(this).also { it.mode = newMode }
                Prefs.saveConfig(this, updated)
                d.dismiss()
                // 继续到新目标
                BackupService.start(this)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("perm", MODE_PRIVATE)
        if (prefs.getBoolean("notif_asked", false)) return
        prefs.edit().putBoolean("notif_asked", true).apply()
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
