package com.ybf.smbbak.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.ybf.smbbak.R
import com.ybf.smbbak.util.LogStore
import com.ybf.smbbak.util.Report

/** 备份日志查看 + 导出错误报告（日志页底部有常驻按钮） */
class LogsActivity : AppCompatActivity() {

    private val exportReportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val err = Report.save(this, uri)
            if (err == null) {
                Toast.makeText(this, R.string.report_exported, Toast.LENGTH_SHORT).show()
                Report.share(this, uri)
            } else {
                Toast.makeText(this, "导出失败：$err", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 显示版本号，方便确认装的哪个包
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        findViewById<TextView>(R.id.tvVersion).text =
            getString(R.string.app_name) + " v" + version

        findViewById<MaterialButton>(R.id.btnExportReport).setOnClickListener {
            exportReportLauncher.launch(Report.newFileName())
        }
        findViewById<MaterialButton>(R.id.btnClearLogs).setOnClickListener {
            LogStore.get(this).clear()
            refresh()
        }
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val text = LogStore.get(this).readTail()
        findViewById<TextView>(R.id.tvLogs).text = if (text.isEmpty()) {
            getString(R.string.logs_empty)
        } else {
            text
        }
    }
}
