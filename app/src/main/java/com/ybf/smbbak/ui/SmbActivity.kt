package com.ybf.smbbak.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ybf.smbbak.R
import com.ybf.smbbak.backup.SmbSession
import com.ybf.smbbak.data.AppConfig
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.util.Errors
import com.ybf.smbbak.util.NetScan
import com.ybf.smbbak.util.UsbNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目标设置：Wi-Fi SMB / USB 直连电脑 / 热点（电脑连手机）三种连接方式 + 连接测试。
 */
class SmbActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var mode: String = AppConfig.MODE_WIFI

    private lateinit var btnModeWifi: MaterialButton
    private lateinit var btnModeUsb: MaterialButton
    private lateinit var btnModeHotspot: MaterialButton
    private lateinit var etHost: TextInputEditText
    private lateinit var etPort: TextInputEditText
    private lateinit var etShare: TextInputEditText
    private lateinit var etUser: TextInputEditText
    private lateinit var etPass: TextInputEditText
    private lateinit var etDomain: TextInputEditText
    private lateinit var etRoot: TextInputEditText
    private lateinit var etUsbHost: TextInputEditText
    private lateinit var ilUsbHost: TextInputLayout
    private lateinit var tvUsbHelper: TextView
    private lateinit var tvPreview: TextView
    private lateinit var tvTestResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smb)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        btnModeWifi = findViewById(R.id.btnModeWifi)
        btnModeUsb = findViewById(R.id.btnModeUsb)
        btnModeHotspot = findViewById(R.id.btnModeHotspot)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etShare = findViewById(R.id.etShare)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etDomain = findViewById(R.id.etDomain)
        etRoot = findViewById(R.id.etRoot)
        etUsbHost = findViewById(R.id.etUsbHost)
        ilUsbHost = findViewById(R.id.ilUsbHost)
        tvUsbHelper = findViewById(R.id.tvUsbHelper)
        tvPreview = findViewById(R.id.tvPreview)
        tvTestResult = findViewById(R.id.tvTestResult)

        val cfg = Prefs.loadConfig(this)
        etHost.setText(cfg.host)
        etPort.setText(cfg.port.toString())
        etShare.setText(cfg.share)
        etUser.setText(cfg.user)
        etPass.setText(cfg.password)
        etDomain.setText(cfg.domain)
        etRoot.setText(cfg.remoteRoot)
        etUsbHost.setText(cfg.usbHostManual)
        setMode(cfg.mode)

        btnModeWifi.setOnClickListener { setMode(AppConfig.MODE_WIFI) }
        btnModeUsb.setOnClickListener { setMode(AppConfig.MODE_USB) }
        btnModeHotspot.setOnClickListener { setMode(AppConfig.MODE_HOTSPOT) }

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { testConnection() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveAndExit() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun setMode(m: String) {
        mode = m
        paint(btnModeWifi, m == AppConfig.MODE_WIFI)
        paint(btnModeUsb, m == AppConfig.MODE_USB)
        paint(btnModeHotspot, m == AppConfig.MODE_HOTSPOT)
        val needPcIp = m == AppConfig.MODE_USB || m == AppConfig.MODE_HOTSPOT
        ilUsbHost.visibility = if (needPcIp) View.VISIBLE else View.GONE
        tvUsbHelper.visibility = if (needPcIp) View.VISIBLE else View.GONE
        ilUsbHost.hint = getString(
            if (m == AppConfig.MODE_USB) R.string.usb_host_manual
            else R.string.hotspot_host_manual
        )
        tvUsbHelper.setText(
            if (m == AppConfig.MODE_USB) R.string.usb_helper
            else R.string.hotspot_helper
        )
        updatePreview()
    }

    private fun paint(btn: MaterialButton, active: Boolean) {
        val color = getColor(if (active) R.color.brand else android.R.color.transparent)
        btn.backgroundTintList = ColorStateList.valueOf(color)
        btn.setTextColor(getColor(if (active) R.color.white else R.color.brand))
    }

    private fun collect(): AppConfig {
        val cfg = Prefs.loadConfig(this)
        cfg.mode = mode
        cfg.host = etHost.text?.toString()?.trim().orEmpty()
        cfg.port = etPort.text?.toString()?.trim()?.toIntOrNull() ?: 445
        cfg.share = etShare.text?.toString()?.trim().orEmpty()
        cfg.user = etUser.text?.toString()?.trim().orEmpty()
        cfg.password = etPass.text?.toString().orEmpty()
        cfg.domain = etDomain.text?.toString()?.trim().orEmpty()
        cfg.remoteRoot = etRoot.text?.toString()?.trim().orEmpty()
        cfg.usbHostManual = etUsbHost.text?.toString()?.trim().orEmpty()
        return cfg
    }

    private fun updatePreview() {
        val c = collect()
        val hostForShow = if (c.isUsbMode || c.isHotspotMode) {
            c.usbHostManual.ifBlank { "电脑(自动探测)" }
        } else {
            c.host
        }
        tvPreview.text = getString(R.string.smb_share_path_preview, hostForShow, c.share, c.rootNorm)
    }

    private fun saveAndExit() {
        val cfg = collect()
        if (!cfg.hasTarget) {
            Toast.makeText(this, R.string.smb_unsaved, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.saveConfig(this, cfg)
        Toast.makeText(this, R.string.smb_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** 测试候选主机（与引擎策略一致） */
    private fun hostCandidates(cfg: AppConfig): List<String> {
        if (!cfg.isUsbMode && !cfg.isHotspotMode) {
            return if (cfg.host.isNotBlank()) listOf(cfg.host.trim()) else emptyList()
        }
        val out = ArrayList<String>()
        cfg.usbHostManual.takeIf { it.isNotBlank() }?.let { out.add(it.trim()) }
        if (cfg.isHotspotMode && out.isEmpty()) {
            for (h in NetScan.gateways(this)) out.add(h)
            for (h in NetScan.scanHotspotRange()) if (!out.contains(h)) out.add(h)
        } else if (cfg.isUsbMode) {
            for (h in UsbNet.findPcIpv4Candidates(this)) if (!out.contains(h)) out.add(h)
        }
        return out
    }

    private fun testConnection() {
        val cfg = collect()
        if (!cfg.hasTarget) {
            Toast.makeText(this, R.string.smb_unsaved, Toast.LENGTH_SHORT).show()
            return
        }
        val hosts = hostCandidates(cfg)
        if (hosts.isEmpty()) {
            tvTestResult.setTextColor(getColor(R.color.error))
            tvTestResult.text = getString(
                if (cfg.isHotspotMode) R.string.hotspot_err_not_found else R.string.usb_err_not_found
            )
            return
        }
        val btn = findViewById<MaterialButton>(R.id.btnTest)
        btn.isEnabled = false
        tvTestResult.setTextColor(getColor(R.color.text_secondary))
        tvTestResult.text = getString(R.string.smb_testing)
        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { doTest(cfg, hosts) }
            btn.isEnabled = true
            tvTestResult.text = msg
            tvTestResult.setTextColor(getColor(if (ok) R.color.success else R.color.error))
        }
    }

    /** 在 IO 线程依次尝试候选主机（先快速 TCP 探活，失败立刻返回提示） */
    private fun doTest(cfg: AppConfig, hosts: List<String>): Pair<Boolean, String> {
        var lastErr: Throwable? = null
        var triedAny = false
        for (h in hosts) {
            if (!NetScan.tcpReachable(h, cfg.port)) {
                lastErr = java.net.ConnectException("$h 端口不可达")
                continue
            }
            triedAny = true
            try {
                SmbSession.connect(cfg, h).use { s ->
                    val n = s.share.list("").size
                    val display = if (cfg.isUsbMode || cfg.isHotspotMode) h else "${cfg.host}\\${cfg.share}"
                    return true to getString(R.string.smb_test_ok, "$display（$n 项）")
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        if (!triedAny) {
            return false to getString(
                if (cfg.isHotspotMode) R.string.hotspot_err_not_found else R.string.usb_err_not_found
            )
        }
        val t = lastErr ?: RuntimeException("connect failed")
        return false to Errors.connect(this, t, hosts.first(), cfg.port)
    }
}
