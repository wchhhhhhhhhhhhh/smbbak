package com.ybf.smbbak.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.ybf.smbbak.R
import com.ybf.smbbak.backup.MediaScanner
import com.ybf.smbbak.data.AlbumChoice
import com.ybf.smbbak.data.AppConfig
import com.ybf.smbbak.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图集选择：图片 / 视频各自勾选要备份的相册。
 * 未勾选任何图集 = 备份全部（保持旧行为）。
 */
class AlbumsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var cfg: AppConfig = AppConfig()
    private var currentKind: String = MediaScanner.KIND_IMAGE

    private lateinit var btnKindImages: MaterialButton
    private lateinit var btnKindVideos: MaterialButton
    private lateinit var pbAlbums: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var listAlbums: LinearLayout

    // kind -> 设备上的图集
    private val albumsByKind = HashMap<String, List<MediaScanner.Album>>()
    // kind -> 当前勾选的 bucketId
    private val checks = HashMap<String, MutableSet<String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_albums)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        btnKindImages = findViewById(R.id.btnKindImages)
        btnKindVideos = findViewById(R.id.btnKindVideos)
        pbAlbums = findViewById(R.id.pbAlbums)
        tvEmpty = findViewById(R.id.tvEmpty)
        listAlbums = findViewById(R.id.listAlbums)

        cfg = Prefs.loadConfig(this)

        btnKindImages.setOnClickListener { selectKind(MediaScanner.KIND_IMAGE) }
        btnKindVideos.setOnClickListener { selectKind(MediaScanner.KIND_VIDEO) }
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            checks[currentKind] = HashSet()
            persistCurrent()
            renderCurrent()
        }

        scope.launch {
            load(MediaScanner.KIND_IMAGE)
            load(MediaScanner.KIND_VIDEO)
        }
        selectKind(MediaScanner.KIND_IMAGE)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 加载 ----------

    private suspend fun load(kind: String) {
        val app = applicationContext
        val list = withContext(Dispatchers.IO) {
            runCatching { MediaScanner.listAlbums(app, kind) }.getOrDefault(emptyList())
        }
        albumsByKind[kind] = list
        if (kind == currentKind) renderCurrent()
    }

    // ---------- 渲染 ----------

    private fun selectKind(kind: String) {
        currentKind = kind
        if (!checks.containsKey(kind)) {
            checks[kind] = cfg.albumIdsFor(kind).toMutableSet()
        }
        paintButtons()
        renderCurrent()
    }

    private fun paintButtons() {
        paint(btnKindImages, currentKind == MediaScanner.KIND_IMAGE)
        paint(btnKindVideos, currentKind == MediaScanner.KIND_VIDEO)
    }

    private fun paint(btn: MaterialButton, active: Boolean) {
        val color = getColor(if (active) R.color.brand else android.R.color.transparent)
        btn.backgroundTintList = ColorStateList.valueOf(color)
        btn.setTextColor(getColor(if (active) R.color.white else R.color.brand))
    }

    private fun renderCurrent() {
        val kind = currentKind
        val list = albumsByKind[kind]
        pbAlbums.visibility = if (list == null) View.VISIBLE else View.GONE
        listAlbums.removeAllViews()
        if (list == null) return

        if (list.isEmpty()) {
            tvEmpty.isVisible = true
            return
        }
        tvEmpty.isVisible = false

        val selected = checks.getOrPut(kind) { cfg.albumIdsFor(kind).toMutableSet() }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        for (album in list) {
            val cb = CheckBox(this)
            val countText = getString(R.string.albums_file_count, album.count)
            cb.text = "${album.name}（$countText）"
            cb.isChecked = album.bucketId in selected
            cb.textSize = 15f
            cb.setTextColor(getColor(R.color.text_primary))
            lp.topMargin = 4
            cb.layoutParams = lp
            listAlbums.addView(cb)
            cb.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(album.bucketId) else selected.remove(album.bucketId)
                persistCurrent()
            }
        }
    }

    // ---------- 保存 ----------

    private fun persistCurrent() {
        val kind = currentKind
        val current = albumsByKind[kind] ?: return
        val selectedIds = checks[kind] ?: return
        val picked = current
            .filter { it.bucketId in selectedIds }
            .map { AlbumChoice(kind = kind, bucketId = it.bucketId, name = it.name) }
        cfg.setAlbumsFor(kind, picked)
        Prefs.saveConfig(this, cfg)
    }
}
