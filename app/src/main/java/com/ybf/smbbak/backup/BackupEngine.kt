package com.ybf.smbbak.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ybf.smbbak.R
import com.ybf.smbbak.data.AppConfig
import com.ybf.smbbak.data.FolderSource
import com.ybf.smbbak.data.Prefs
import com.ybf.smbbak.util.Errors
import com.ybf.smbbak.util.Fmt
import com.ybf.smbbak.util.LogStore
import com.ybf.smbbak.util.NetScan
import com.ybf.smbbak.util.UsbNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar

/**
 * 备份引擎 v2：
 * - 支持两种目标：Wi-Fi SMB、USB 网络共享直连电脑（同一套共享/目录设置）
 * - 登记表/内容索引按目标隔离，切换目标不会误跳过文件
 * - 增量 + 内容哈希去重：同一内容在远端只保留一份
 * - 文件级暂停/继续：暂停时把剩余队列落盘，可稍后继续或换目标续传
 */
object BackupEngine {

    // 结果类型
    const val RESULT_OK = "ok"
    const val RESULT_PARTIAL = "partial"
    const val RESULT_FAILED = "failed"
    const val RESULT_CANCELLED = "cancelled"
    const val RESULT_PAUSED = "paused"

    data class Summary(
        val result: String,
        val uploaded: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val bytes: Long = 0L,
        val error: String = ""
    )

    private class Plan(
        val items: List<PlanItem>,
        val pruneSrcIds: Set<String>   // 允许清理“已删除本地文件”登记的来源（仅全量跑）
    )

    /** 引擎入口：自动判断是全新备份还是继续上次暂停的备份。内部切到 IO 线程。 */
    suspend fun run(context: Context, cfg: AppConfig, reason: String): Summary {
        return withContext(Dispatchers.IO) {
            doRun(context.applicationContext, cfg, reason)
        }
    }

    // ---------- 主流程 ----------

    private fun doRun(app: Context, cfg: AppConfig, reason: String): Summary {
        val log = LogStore.get(app)
        val startedAt = System.currentTimeMillis()

        if (!BackupEvents.begin()) {
            log.append("忽略重复的备份请求（已有任务在运行）")
            return Summary(RESULT_OK, error = "")
        }
        val wakelock = acquireWakelock(app)
        try {
            if (!cfg.hasTarget) {
                val err = app.getString(R.string.err_no_smb_config)
                log.append("失败：$err")
                return persist(app, Summary(RESULT_FAILED, error = err))
            }
            if (!cfg.imagesEnabled && !cfg.videosEnabled && cfg.folders.isEmpty()) {
                val err = app.getString(R.string.err_no_source)
                log.append("失败：$err")
                return persist(app, Summary(RESULT_FAILED, error = err))
            }

            // 有暂停待办则进入续传
            val resumeMode = Prefs.isPaused(app) && BackupQueue.exists(app)
            if (resumeMode) Prefs.clearPaused(app)
            log.append(
                if (resumeMode) "==== 继续上次备份 (${reason}) ===="
                else "==== 备份开始 (${reason}) ===="
            )

            val items: List<PlanItem>
            val pruneSrcIds: Set<String>
            if (resumeMode) {
                val loaded = BackupQueue.load(app)
                if (loaded == null) {
                    // 队列损坏：回退到全量
                    val plan = buildPlan(app, cfg, log)
                    items = plan.items
                    pruneSrcIds = plan.pruneSrcIds
                } else {
                    items = loaded
                    pruneSrcIds = emptySet()
                    if (items.isEmpty()) {
                        log.append("待办队列为空")
                        return persist(app, Summary(RESULT_OK))
                    }
                }
            } else {
                val plan = buildPlan(app, cfg, log)
                items = plan.items
                pruneSrcIds = plan.pruneSrcIds
                BackupQueue.save(app, items)
            }

            val summary = execute(app, cfg, items, pruneSrcIds, log, startedAt)
            if (summary.result != RESULT_PAUSED) {
                BackupQueue.delete(app)
                if (resumeMode && summary.result == RESULT_FAILED) {
                    // 续传时连不上目标：保留暂停状态，让用户可换目标/重试
                    Prefs.setPaused(app, items.size)
                    log.append("继续备份失败，已保留待办队列（${items.size} 个），可重试或换目标")
                }
            }
            return persist(app, summary)
        } finally {
            releaseWakelock(wakelock)
            BackupEvents.finish()
        }
    }

    private fun persist(app: Context, s: Summary): Summary {
        Prefs.saveLastRun(
            app, s.result, System.currentTimeMillis(),
            s.uploaded, s.skipped, s.failed, s.bytes, s.error, detail = ""
        )
        return s
    }

    // ---------- 上传执行 ----------

    private fun execute(
        app: Context, cfg: AppConfig,
        items: List<PlanItem>, pruneSrcIds: Set<String>,
        log: LogStore, startedAt: Long
    ): Summary {
        var uploaded = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        var topError = ""
        val meter = TransferMeter()

        BackupEvents.setPhase(app.getString(R.string.phase_connect))
        val hosts = resolveHosts(app, cfg)
        if (hosts.isEmpty()) {
            val err = app.getString(
                if (cfg.isHotspotMode) R.string.hotspot_err_not_found
                else R.string.usb_err_not_found
            )
            log.append("失败：$err")
            return Summary(RESULT_FAILED, error = err)
        }

        // 逐个候选先做快速 TCP 探活，避免 smbj 在不可达主机上长时间“卡连接中”
        val reachable = ArrayList<String>()
        for (h in hosts) {
            val ok = NetScan.tcpReachable(h, cfg.port)
            log.append(if (ok) "TCP 可达：$h:${cfg.port}" else "TCP 不可达：$h:${cfg.port}（已跳过）")
            if (ok) reachable.add(h)
        }
        if (reachable.isEmpty() && hosts.isNotEmpty()) {
            val err = app.getString(
                R.string.err_generic,
                "候选主机 445 端口均不可达（${hosts.size} 个）：请检查网络/共享服务/防火墙，或手动填写电脑 IP"
            )
            log.append("失败：$err")
            return Summary(RESULT_FAILED, error = err)
        }

        var session: SmbSession? = null
        var lastConnectError: Throwable? = null
        var tried = 0
        for (host in reachable) {
            tried++
            BackupEvents.setPhase("${app.getString(R.string.phase_connect)} ($host ${tried}/${reachable.size})")
            log.append("连接 $host（${tried}/${reachable.size}）…")
            try {
                session = SmbSession.connect(cfg, host)
                break
            } catch (t: Throwable) {
                lastConnectError = t
                log.append("连接 $host 失败：${Errors.generic(t)}")
            }
        }
        if (session == null) {
            val t = lastConnectError ?: IOException("connect failed")
            val probeHost = reachable.firstOrNull() ?: hosts.first()
            val err = Errors.connect(app, t, probeHost, cfg.port)
            topError = when {
                cfg.isUsbMode -> "$err\n${app.getString(R.string.usb_err_not_found)}"
                cfg.isHotspotMode -> "$err\n${app.getString(R.string.hotspot_err_not_found)}"
                else -> err
            }
            log.append("SMB 连接失败")
            return Summary(RESULT_FAILED, error = topError)
        }

        try {
            val smb = session
            BackupEvents.setPhase(app.getString(R.string.phase_index))
            val remoteIndex = try {
                smb.indexRemote()
            } catch (t: Throwable) {
                topError = app.getString(R.string.err_generic, "读取远端目录失败：" + Errors.generic(t))
                log.append(topError)
                return Summary(RESULT_FAILED, error = topError)
            }
            val currentIndex = HashMap(remoteIndex)
            val contentIndex = ContentIndex.open(app, cfg.targetKey())
            val dirMemo = HashSet<String>()
            val regCache = HashMap<String, FileRegistry>()
            val seenKeys = HashMap<String, MutableSet<String>>()
            val groupFailed = HashMap<String, Int>()
            val dedupeCount = HashMap<String, Int>()

            log.append("目标：${cfg.modeLabel(app)} · 远端已有文件 ${remoteIndex.size} 个 · 待处理 ${items.size} 个")
            BackupEvents.setPhase(app.getString(R.string.phase_upload, items.size.toString()))

            val total = items.size
            var idx = 0
            var paused = false
            var cancelled = false
            while (idx < total) {
                if (BackupEvents.isCancelled) { cancelled = true; break }
                if (BackupEvents.isPauseRequested) { paused = true; break }
                val item = items[idx]
                BackupEvents.sourceProgress(item.label, idx, total)
                BackupEvents.setCurrent(item.relPath)

                val reg = regCache.getOrPut(item.srcId) {
                    FileRegistry.open(app, item.srcId, cfg.targetKey())
                }
                if (pruneSrcIds.contains(item.srcId)) {
                    seenKeys.getOrPut(item.srcId) { HashSet() }.add(item.regKey)
                }

                if (shouldSkip(item, reg, currentIndex)) {
                    skipped++
                    BackupEvents.addSkipped()
                    idx++
                    continue
                }
                var input: java.io.InputStream? = null
                var itemPaused = false
                var isDup = false
                try {
                    input = openItemStream(app, item)
                    val parent = item.relPath.substringBeforeLast('/', "")
                    smb.ensureDirs(parent, dirMemo)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val abort = { BackupEvents.isPauseRequested || BackupEvents.isCancelled }
                    val fileStartNet = meter.netBytes
                    val written = smb.upload(
                        item.relPath, input, item.mtimeMs, item.size, cfg.keepDates,
                        digest = digest, abort = abort,
                        onBytes = { fileTotal -> meter.onProgress(fileStartNet, fileTotal) }
                    )
                    val hex = hexOf(digest)

                    // 内容去重：该内容已存在于远端其它路径 → 删掉刚传的副本
                    if (hex.isNotEmpty()) {
                        val canon = contentIndex.get(hex)
                        if (canon != null && canon != item.relPath && currentIndex.containsKey(canon)) {
                            smb.deleteRemote(item.relPath)
                            currentIndex.remove(item.relPath)
                            reg.put(item.regKey, "D|$hex|$canon|${item.mtimeMs}")
                            skipped++
                            BackupEvents.addSkipped()
                            val d = dedupeCount.getOrDefault(item.srcId, 0) + 1
                            dedupeCount[item.srcId] = d
                            isDup = true
                        } else {
                            contentIndex.put(hex, item.relPath)
                            reg.put(
                                item.regKey,
                                "U|${item.relPath}|${item.mtimeMs}|$written|$hex"
                            )
                        }
                    } else {
                        reg.put(item.regKey, "U|${item.relPath}|${item.mtimeMs}|$written")
                    }

                    if (!isDup) {
                        uploaded++
                        bytes += written
                        BackupEvents.addUploaded()
                        currentIndex[item.relPath] = written
                    }
                    meter.onProgress(fileStartNet, written)
                } catch (e: SmbSession.PauseSignal) {
                    // 传输中被要求中断：区分“停止”与“暂停”
                    if (BackupEvents.isCancelled) cancelled = true else itemPaused = true
                } catch (e: Exception) {
                    failed++
                    BackupEvents.addFailed()
                    groupFailed[item.srcId] = groupFailed.getOrDefault(item.srcId, 0) + 1
                    log.append("  失败：${item.relPath} → ${Errors.generic(e)}")
                } finally {
                    runCatching { input?.close() }
                }

                if (cancelled) break
                if (itemPaused) { paused = true; break }
                idx++
            }

            // 收尾：登记表、内容索引落盘
            if (paused) {
                val remaining = items.drop(idx)
                if (remaining.isNotEmpty()) {
                    BackupQueue.save(app, remaining)
                    Prefs.setPaused(app, remaining.size)
                    saveAll(regCache)
                    contentIndex.save()
                    log.append("已暂停：已上传 $uploaded / 跳过 $skipped / 失败 $failed，剩余 ${remaining.size} 个")
                    return Summary(RESULT_PAUSED, uploaded, skipped, failed, bytes, error = "")
                }
                // 剩余为空：按正常完成处理
            }

            // 全量跑才清理登记表；有失败或按图集筛选的来源不清理
            for ((srcId, reg) in regCache) {
                if (pruneSrcIds.contains(srcId) &&
                    groupFailed.getOrDefault(srcId, 0) == 0
                ) {
                    reg.prune(seenKeys[srcId] ?: emptySet())
                }
                reg.save()
            }
            contentIndex.save()
            if (cancelled) log.append("任务被用户停止")

            val result = when {
                cancelled -> RESULT_CANCELLED
                failed > 0 -> RESULT_PARTIAL
                else -> RESULT_OK
            }
            if (dedupeCount.isNotEmpty()) {
                log.append(app.getString(R.string.dedupe_hint, dedupeCount.values.sum()))
            }
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            log.append(
                "结束：上传 $uploaded / 跳过 $skipped / 失败 $failed，" +
                    "共 ${Fmt.bytes(bytes)}，耗时 ${secs}s"
            )
            log.append("==== 备份结束 ====")
            return Summary(result, uploaded, skipped, failed, bytes, topError)
        } catch (t: Throwable) {
            failed++
            topError = Errors.generic(t)
            log.append("备份异常：$topError")
            return Summary(RESULT_FAILED, uploaded, skipped, failed, bytes, topError)
        } finally {
            session.close()
        }
    }

    private fun saveAll(regCache: Map<String, FileRegistry>) {
        for (reg in regCache.values) reg.save()
    }

    /** 依据登记表 + 远端索引判断是否已备份过（增量跳过） */
    private fun shouldSkip(item: PlanItem, reg: FileRegistry, remote: Map<String, Long>): Boolean {
        val e = reg.get(item.regKey) ?: return false
        val parts = e.split('|', limit = 5)
        if (parts.size < 4) return false
        return when (parts[0]) {
            "U" -> {
                val p = parts[1]
                val m = parts[2].toLongOrNull()
                val s = parts[3].toLongOrNull()
                if (p != item.relPath) return false
                if (m != item.mtimeMs) return false
                if (item.size >= 0) {
                    (s == item.size && remote[p] == item.size)
                } else {
                    remote.containsKey(p)
                }
            }
            "D" -> {
                val m = parts[3].toLongOrNull()
                if (m != item.mtimeMs) return false
                // 内容相同，参照的远端文件仍在 → 跳过
                remote.containsKey(parts[2])
            }
            else -> false
        }
    }

    // ---------- 打开源文件流 ----------

    private fun openItemStream(app: Context, item: PlanItem): java.io.InputStream {
        val resolver = app.contentResolver
        return when (item.kind) {
            MediaScanner.KIND_IMAGE -> openMedia(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, item)
            MediaScanner.KIND_VIDEO -> openMedia(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item)
            FolderSource.KIND_SAF -> {
                val tree = Uri.parse(item.folderRoot)
                val treeDoc = DocumentsContract.getTreeDocumentId(tree)
                val docId = if (item.relLocal.isEmpty()) treeDoc else "$treeDoc/${item.relLocal}"
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                resolver.openInputStream(uri)
                    ?: throw IOException("无法打开文件 ${item.relLocal}")
            }
            else -> FileInputStream(File(item.folderRoot, item.relLocal))
        }
    }

    private fun openMedia(
        resolver: android.content.ContentResolver,
        collection: Uri,
        item: PlanItem
    ): java.io.InputStream {
        val uri = ContentUris.withAppendedId(collection, item.mediaId)
        return resolver.openInputStream(uri)
            ?: throw IOException("无法打开媒体文件 ${item.mediaId}")
    }

    // ---------- 计划构建（全量扫描） ----------

    private fun buildPlan(app: Context, cfg: AppConfig, log: LogStore): Plan {
        val items = ArrayList<PlanItem>()
        val prune = HashSet<String>()
        if (cfg.imagesEnabled) {
            addMediaPlan(app, cfg, MediaScanner.KIND_IMAGE, items, prune, log)
        }
        if (cfg.videosEnabled) {
            addMediaPlan(app, cfg, MediaScanner.KIND_VIDEO, items, prune, log)
        }
        for (f in cfg.folders) {
            val label = f.label.ifBlank {
                if (f.kind == FolderSource.KIND_SAF) "saf" else File(f.id).name
            }
            addFolderPlan(app, f, label, items, prune, log)
        }
        return Plan(items, prune)
    }

    private fun addMediaPlan(
        app: Context, cfg: AppConfig, kind: String,
        out: MutableList<PlanItem>, prune: MutableSet<String>, log: LogStore
    ) {
        val label = if (kind == MediaScanner.KIND_IMAGE) {
            app.getString(R.string.src_images_label)
        } else {
            app.getString(R.string.src_videos_label)
        }
        val regId = if (kind == MediaScanner.KIND_IMAGE) "media_images" else "media_videos"
        if (!MediaScanner.hasReadPermission(app, kind)) {
            log.append("$label 被跳过：缺少读取权限")
            return
        }
        val albumFilter = cfg.albumIdsFor(kind)
        if (albumFilter.isNotEmpty()) {
            log.append("$label：已按图集筛选（仅 ${albumFilter.size} 个相册）")
        } else {
            prune.add(regId)
        }
        val rows = try {
            MediaScanner.scan(app, kind, albumFilter.ifEmpty { null })
        } catch (t: Throwable) {
            log.append("$label 扫描失败：${Errors.generic(t)}")
            return
        }
        val nameCounters = HashMap<String, HashMap<String, Int>>()
        for (r in rows) {
            val dir = dateDir(if (r.dateMs > 0) r.dateMs else System.currentTimeMillis())
            val baseName = Fmt.sanitizeFileName(r.displayName)
            val dirKey = "$label/$dir"
            val counters = nameCounters.getOrPut(dirKey) { HashMap() }
            val n = counters.getOrDefault(baseName, 0) + 1
            counters[baseName] = n
            val name = if (n == 1) baseName else Fmt.withSuffix(baseName, n)
            out.add(
                PlanItem(
                    kind = kind,
                    label = label,
                    srcId = regId,
                    regKey = r.id.toString(),
                    relPath = "$dirKey/$name",
                    size = r.size,
                    mtimeMs = r.dateMs,
                    mediaId = r.id
                )
            )
        }
        log.append("$label：扫描到 ${rows.size} 个文件")
    }

    private fun addFolderPlan(
        app: Context, f: FolderSource, labelRaw: String,
        out: MutableList<PlanItem>, prune: MutableSet<String>, log: LogStore
    ) {
        val label = Fmt.sanitizeFileName(labelRaw)
        val regId = "folder_" + com.ybf.smbbak.util.Hash.sha1Hex(f.id)
        val prefix = "${app.getString(R.string.src_folder_label)}/$label"
        val rows: List<FolderScanner.Row> = if (f.kind == FolderSource.KIND_SAF) {
            try {
                FolderScanner.scanSaf(app, Uri.parse(f.id))
            } catch (t: Throwable) {
                log.append("文件夹 $label 扫描失败：${Errors.generic(t)}")
                return
            }
        } else {
            val root = File(f.id)
            if (!root.isDirectory) {
                log.append("文件夹 $label 不存在或不可读：${f.id}")
                return
            }
            try {
                FolderScanner.scanPath(root)
            } catch (t: Throwable) {
                log.append("文件夹 $label 扫描失败：${Errors.generic(t)}")
                return
            }
        }
        prune.add(regId)
        for (r in rows) {
            out.add(
                PlanItem(
                    kind = f.kind,
                    label = label,
                    srcId = regId,
                    regKey = r.relLocal,
                    relPath = "$prefix/${r.relLocal}",
                    size = r.size,
                    mtimeMs = r.mtimeMs,
                    folderRoot = f.id,
                    relLocal = r.relLocal
                )
            )
        }
        log.append("文件夹 $label：扫描到 ${rows.size} 个文件")
    }

    // ---------- 目标解析 ----------

    /**
     * 解析目标主机候选：
     * - Wi-Fi：直接用配置的主机；
     * - USB：手动 IP 或 USB 网络共享链路探测；
     * - 热点（手机开热点、电脑连接）：手动 IP → 当前网络网关 → 手机热点网段 445 端口扫描。
     */
    private fun resolveHosts(app: Context, cfg: AppConfig): List<String> {
        if (!cfg.isUsbMode && !cfg.isHotspotMode) {
            return if (cfg.host.isNotBlank()) listOf(cfg.host.trim()) else emptyList()
        }
        val out = ArrayList<String>()
        cfg.usbHostManual.trim().takeIf { it.isNotEmpty() }?.let { out.add(it.trim()) }
        if (cfg.isHotspotMode) {
            // 手动未填时再自动找：网关（电脑开热点/路由器）+ 经典热点网段 445 扫描（电脑连手机热点）
            if (out.isEmpty()) {
                for (h in NetScan.gateways(app)) out.add(h)
                for (h in NetScan.scanHotspotRange()) if (!out.contains(h)) out.add(h)
            }
        } else {
            for (h in UsbNet.findPcIpv4Candidates(app)) if (!out.contains(h)) out.add(h)
        }
        return out
    }

    // ---------- 工具 ----------

    private fun dateDir(dateMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = if (dateMs > 0) dateMs else System.currentTimeMillis()
        return "%04d/%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    private fun hexOf(d: MessageDigest): String {
        val b = d.digest()
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }

    private fun AppConfig.modeLabel(app: Context): String = when {
        isUsbMode -> app.getString(R.string.smb_mode_usb)
        isHotspotMode -> app.getString(R.string.smb_mode_hotspot)
        else -> "${host.trim()}\\${share.trim()}"
    }

    private fun acquireWakelock(app: Context): PowerManager.WakeLock? {
        return runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smbbak:backup")
            wl.setReferenceCounted(false)
            wl.acquire(3 * 60 * 60 * 1000L)
            wl
        }.getOrNull()
    }

    private fun releaseWakelock(wl: PowerManager.WakeLock?) {
        if (wl != null && wl.isHeld) runCatching { wl.release() }
    }
}

/** 上传节拍计速：按节拍把实时字节与平滑速度推给 UI/通知 */
private class TransferMeter {

    var netBytes: Long = 0L
        private set

    private var lastAt = 0L
    private var lastBytes = 0L
    private var smoothBps = 0.0

    fun onProgress(fileStart: Long, fileTotal: Long) {
        netBytes = fileStart + fileTotal
        val now = System.currentTimeMillis()
        if (lastAt == 0L) {
            lastAt = now
            lastBytes = netBytes
            return
        }
        val dt = now - lastAt
        if (dt >= TICK_MS) {
            if (dt > 0 && netBytes >= lastBytes) {
                val inst = (netBytes - lastBytes) * 1000.0 / dt
                smoothBps = if (smoothBps <= 0.0) inst else smoothBps * 0.6 + inst * 0.4
            }
            lastAt = now
            lastBytes = netBytes
            BackupEvents.transfer(netBytes, smoothBps.toLong())
        }
    }

    companion object {
        private const val TICK_MS = 400L
    }
}
