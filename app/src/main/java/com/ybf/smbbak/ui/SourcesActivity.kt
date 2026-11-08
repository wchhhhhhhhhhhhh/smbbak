package com.ybf.smbbak.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.ybf.smbbak.R
import com.ybf.smbbak.backup.FolderScanner
import com.ybf.smbbak.backup.MediaScanner
import com.ybf.smbbak.data.AppConfig
import com.ybf.smbbak.data.FolderSource
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.util.Fmt
import java.io.File

/** 备份内容设置：照片/视频开关、SAF 自定义文件夹、路径文件夹（可选） */
class SourcesActivity : AppCompatActivity() {

    private var cfg: AppConfig = AppConfig()
    private var loading = true

    private lateinit var swImages: SwitchMaterial
    private lateinit var swVideos: SwitchMaterial
    private lateinit var tvMediaPerm: TextView
    private lateinit var tvAlbumScope: TextView
    private lateinit var tvFolderEmpty: TextView
    private lateinit var containerFolders: LinearLayout
    private lateinit var containerPaths: LinearLayout
    private lateinit var tvPathPerm: TextView

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addSafFolder(uri)
        }

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)

        findViewById<MaterialToolbar>(R.id.toolbar).let {
            setSupportActionBar(it)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }

        swImages = findViewById(R.id.swImages)
        swVideos = findViewById(R.id.swVideos)
        tvMediaPerm = findViewById(R.id.tvMediaPerm)
        tvAlbumScope = findViewById(R.id.tvAlbumScope)
        tvFolderEmpty = findViewById(R.id.tvFolderEmpty)
        containerFolders = findViewById(R.id.containerFolders)
        containerPaths = findViewById(R.id.containerPaths)
        tvPathPerm = findViewById(R.id.tvPathPerm)

        cfg = Prefs.loadConfig(this)

        swImages.isChecked = cfg.imagesEnabled
        swVideos.isChecked = cfg.videosEnabled

        swImages.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            cfg.imagesEnabled = checked
            Prefs.saveConfig(this, cfg)
            if (checked && !MediaScanner.hasReadPermission(this, MediaScanner.KIND_IMAGE)) {
                requestMediaPerm()
            }
            refreshMediaPerm()
        }
        swVideos.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            cfg.videosEnabled = checked
            Prefs.saveConfig(this, cfg)
            if (checked && !MediaScanner.hasReadPermission(this, MediaScanner.KIND_VIDEO)) {
                requestMediaPerm()
            }
            refreshMediaPerm()
        }

        tvMediaPerm.setOnClickListener { requestMediaPerm() }
        tvAlbumScope.setOnClickListener {
            startActivity(Intent(this, AlbumsActivity::class.java))
        }
        tvPathPerm.setOnClickListener { openAllFilesSetting() }
        findViewById<MaterialButton>(R.id.btnAddFolder).setOnClickListener { pickTree.launch(null) }
        findViewById<MaterialButton>(R.id.btnAddPath).setOnClickListener { onAddPath() }

        loading = false
        refreshAll()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        cfg = Prefs.loadConfig(this)
        refreshAll()
    }

    // ---------- 渲染 ----------

    private fun refreshAll() {
        loading = true
        swImages.isChecked = cfg.imagesEnabled
        swVideos.isChecked = cfg.videosEnabled
        loading = false
        refreshMediaPerm()
        refreshAlbumScope()
        refreshFolders()
        refreshPaths()
    }

    /** “图集范围”入口：仅在任一媒体备份开启时显示 */
    private fun refreshAlbumScope() {
        val anyMedia = cfg.imagesEnabled || cfg.videosEnabled
        tvAlbumScope.visibility = if (anyMedia) View.VISIBLE else View.GONE
        if (!anyMedia) return
        val imgN = cfg.albumCountFor(MediaScanner.KIND_IMAGE)
        val vidN = cfg.albumCountFor(MediaScanner.KIND_VIDEO)
        val summary = when {
            imgN == 0 && vidN == 0 ->
                getString(R.string.sources_album_scope_all)
            imgN > 0 && vidN > 0 ->
                getString(R.string.sources_album_scope_images, imgN) +
                    "\n" + getString(R.string.sources_album_scope_videos, vidN)
            imgN > 0 ->
                getString(R.string.sources_album_scope_images, imgN)
            else ->
                getString(R.string.sources_album_scope_videos, vidN)
        }
        tvAlbumScope.text = "▸ ${getString(R.string.sources_album_scope)}\n$summary"
    }

    private fun refreshMediaPerm() {
        val missing = ArrayList<String>()
        if (cfg.imagesEnabled && !MediaScanner.hasReadPermission(this, MediaScanner.KIND_IMAGE)) {
            missing.add(getString(R.string.src_images_label))
        }
        if (cfg.videosEnabled && !MediaScanner.hasReadPermission(this, MediaScanner.KIND_VIDEO)) {
            missing.add(getString(R.string.src_videos_label))
        }
        if (missing.isEmpty()) {
            tvMediaPerm.text = "✔ ${getString(R.string.sources_media_perm_ok)}"
            tvMediaPerm.setTextColor(getColor(R.color.success))
        } else {
            tvMediaPerm.text = "⚠ ${getString(R.string.sources_media_perm_missing)}（${missing.joinToString("、")}）"
            tvMediaPerm.setTextColor(getColor(R.color.error))
        }
    }

    private fun refreshFolders() {
        containerFolders.removeAllViews()
        tvFolderEmpty.visibility =
            if (cfg.folders.isEmpty()) View.VISIBLE else View.GONE
        val saf = cfg.folders.filter { it.kind == FolderSource.KIND_SAF }
        saf.forEach { f -> containerFolders.addView(makeFolderRow(f)) }
    }

    private fun refreshPaths() {
        containerPaths.removeAllViews()
        val canPath = isPathModeAllowed()
        tvPathPerm.text = if (canPath) {
            "✔ ${getString(R.string.sources_path_granted)}"
        } else {
            "⚠ ${getString(R.string.sources_path_need_perm)}（点击授予）"
        }
        tvPathPerm.setTextColor(getColor(if (canPath) R.color.success else R.color.warning))
        findViewById<MaterialButton>(R.id.btnAddPath).isEnabled = canPath
        val paths = cfg.folders.filter { it.kind == FolderSource.KIND_PATH }
        paths.forEach { f -> containerPaths.addView(makeFolderRow(f)) }
    }

    private fun makeFolderRow(f: FolderSource): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_folder_row, containerFolders, false)
        row.findViewById<TextView>(R.id.tvRowTitle).text = f.label
        val sub = if (f.kind == FolderSource.KIND_SAF) {
            "SAF · " + Uri.decode(f.id)
        } else {
            "路径 · ${f.id}"
        }
        row.findViewById<TextView>(R.id.tvRowSub).text = sub
        row.findViewById<View>(R.id.btnRowRename).setOnClickListener { renameDialog(f) }
        row.findViewById<View>(R.id.btnRowRemove).setOnClickListener { removeDialog(f) }
        return row
    }

    // ---------- 动作 ----------

    private fun requestMediaPerm() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (cfg.imagesEnabled) perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (cfg.videosEnabled) perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (perms.isEmpty()) return
        mediaPermLauncher.launch(perms.toTypedArray())
    }

    private fun addSafFolder(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val label = uniqueLabel(FolderScanner.labelOfTreeUri(uri))
        cfg.folders.add(FolderSource(id = uri.toString(), label = label, kind = FolderSource.KIND_SAF))
        Prefs.saveConfig(this, cfg)
        refreshFolders()
        Toast.makeText(this, "已添加“$label”", Toast.LENGTH_SHORT).show()
    }

    private fun onAddPath() {
        if (!isPathModeAllowed()) {
            Toast.makeText(this, R.string.sources_path_need_perm, Toast.LENGTH_LONG).show()
            openAllFilesSetting()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.sources_path_input_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sources_path_add)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val path = input.text?.toString()?.trim().orEmpty()
                if (path.isEmpty() || !File(path).isDirectory) {
                    Toast.makeText(this, R.string.sources_path_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val defaultLabel = File(path).name.ifEmpty { "folder" }
                cfg.folders.add(
                    FolderSource(
                        id = path,
                        label = uniqueLabel(defaultLabel),
                        kind = FolderSource.KIND_PATH
                    )
                )
                Prefs.saveConfig(this, cfg)
                refreshPaths()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun renameDialog(f: FolderSource) {
        val input = EditText(this).apply {
            setText(f.label)
            hint = getString(R.string.sources_label_dialog_title)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sources_rename)
            .setView(input)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty()
                if (label.isEmpty()) {
                    Toast.makeText(this, R.string.sources_label_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val safe = Fmt.sanitizeFileName(label)
                val idx = cfg.folders.indexOfFirst { it.id == f.id && it.kind == f.kind }
                if (idx >= 0) {
                    cfg.folders[idx] = cfg.folders[idx].copy(label = safe)
                    Prefs.saveConfig(this, cfg)
                    refreshAll()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun removeDialog(f: FolderSource) {
        AlertDialog.Builder(this)
            .setTitle(R.string.sources_remove)
            .setMessage(getString(R.string.sources_remove_confirm, f.label))
            .setPositiveButton(R.string.action_yes) { _, _ ->
                cfg.folders.removeAll { it.id == f.id && it.kind == f.kind }
                Prefs.saveConfig(this, cfg)
                refreshAll()
            }
            .setNegativeButton(R.string.action_no, null)
            .show()
    }

    private fun openAllFilesSetting() {
        if (isPathModeAllowed()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun isPathModeAllowed(): Boolean {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    }

    private fun uniqueLabel(base: String): String {
        val safe = Fmt.sanitizeFileName(base)
        val taken = cfg.folders.map { it.label }.toSet()
        if (safe !in taken) return safe
        var i = 2
        while ("$safe ($i)" in taken) i++
        return "$safe ($i)"
    }
}
