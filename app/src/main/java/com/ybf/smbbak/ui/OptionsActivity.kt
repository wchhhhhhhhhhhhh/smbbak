package com.ybf.smbbak.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ybf.smbbak.R
import com.ybf.smbbak.backup.Scheduler
import com.ybf.smbbak.data.AppConfig
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.util.BatteryKeepAlive
import com.ybf.smbbak.util.LogStore
import com.ybf.smbbak.util.WorkReset

/** 备份选项：自动频率 / Wi-Fi 限制 / 保留时间 / 后台保活 */
class OptionsActivity : AppCompatActivity() {

    private var cfg: AppConfig = AppConfig()
    private var loading = true

    private lateinit var rgAuto: RadioGroup
    private lateinit var swWifiOnly: SwitchMaterial
    private lateinit var swKeepDates: SwitchMaterial

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论是否授予通知权限，都继续保存并调度自动备份
            onAutoConfirmed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkReset.apply(this)
        setContentView(R.layout.activity_options)

        findViewById<MaterialToolbar>(R.id.toolbar).let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        rgAuto = findViewById(R.id.rgAuto)
        swWifiOnly = findViewById(R.id.swWifiOnly)
        swKeepDates = findViewById(R.id.swKeepDates)

        cfg = Prefs.loadConfig(this)
        bindValues()

        rgAuto.setOnCheckedChangeListener { _, checkedId ->
            if (loading) return@setOnCheckedChangeListener
            val hours = when (checkedId) {
                R.id.rbAutoDaily -> 24L
                R.id.rbAuto3Days -> 72L
                R.id.rbAutoWeekly -> 168L
                else -> 0L
            }
            setAuto(hours)
        }

        swWifiOnly.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            cfg.wifiOnlyAuto = checked
            save()
        }
        swKeepDates.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            cfg.keepDates = checked
            save()
        }

        findViewById<TextView>(R.id.tvNotifHint).setOnClickListener {
            if (needNotifPermission()) {
                runCatching { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else {
                Toast.makeText(this, "✔ ${getString(R.string.sources_media_perm_ok)}", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            startActivity(BatteryKeepAlive.requestIgnoreIntent(this))
        }
        refreshBatteryStatus()

        loading = false
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindValues() {
        loading = true
        when (cfg.autoHours) {
            24L -> rgAuto.check(R.id.rbAutoDaily)
            72L -> rgAuto.check(R.id.rbAuto3Days)
            168L -> rgAuto.check(R.id.rbAutoWeekly)
            else -> rgAuto.check(R.id.rbAutoOff)
        }
        swWifiOnly.isChecked = cfg.wifiOnlyAuto
        swKeepDates.isChecked = cfg.keepDates
        loading = false
    }

    /** 用户选择/修改自动频率 */
    private fun setAuto(hours: Long) {
        cfg.autoHours = hours
        persist()
        if (hours > 0) {
            // 自动备份会用到前台通知：先要通知权限（拿不到也照常调度，仅看不到通知）
            if (needNotifPermission()) {
                runCatching {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }.onFailure {
                    onAutoConfirmed()
                }
            } else {
                onAutoConfirmed()
            }
        } else {
            // 关闭自动备份
            runCatching { Scheduler.cancel(this) }
                .onFailure { reportSchedulerError(it) }
        }
    }

    /** 保存后真正执行调度（有通知权限结果后回调） */
    private fun onAutoConfirmed() {
        if (cfg.autoHours <= 0) {
            runCatching { Scheduler.cancel(this) }
                .onFailure { reportSchedulerError(it) }
            return
        }
        persist()
        runCatching { Scheduler.apply(this, Prefs.loadConfig(this)) }
            .onSuccess {
                Prefs.clearScheduleCrash(this)
                Toast.makeText(this, R.string.options_saved, Toast.LENGTH_SHORT).show()
            }
            .onFailure { reportSchedulerError(it) }
    }

    private fun persist() {
        val c = Prefs.loadConfig(this)
        c.autoHours = cfg.autoHours
        c.wifiOnlyAuto = cfg.wifiOnlyAuto
        c.keepDates = cfg.keepDates
        Prefs.saveConfig(this, c)
        cfg = c
    }

    private fun save() {
        persist()
    }

    /** 调度异常：不闪退，写日志 + 提示（下次启动会自动重置 WorkManager） */
    private fun reportSchedulerError(t: Throwable) {
        Prefs.setScheduleCrash(this)
        LogStore.get(this).append("自动备份调度失败：${t.javaClass.simpleName}: ${t.message}")
        Toast.makeText(
            this,
            "自动备份设置失败：${t.javaClass.simpleName}: ${t.message}",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshBatteryStatus() {
        val status = findViewById<TextView>(R.id.tvBatteryStatus)
        if (BatteryKeepAlive.isIgnoringOptimizations(this)) {
            status.setTextColor(getColor(R.color.success))
            status.text = getString(R.string.keepalive_status_on)
        } else {
            status.setTextColor(getColor(R.color.warning))
            status.text = getString(R.string.keepalive_status_off)
        }
    }

    private fun needNotifPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        return !(granted == PackageManager.PERMISSION_GRANTED)
    }
}
