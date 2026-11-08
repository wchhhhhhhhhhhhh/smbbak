package com.ybf.smbbak.backup

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.ybf.smbbak.data.AppConfig
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * 基于 smbj(SMB2/3) 的一次性 SMB 会话封装。
 * 所有远端路径均为“相对共享根”的路径（如 a/b/c.txt），由本类统一处理根目录前缀。
 */
class SmbSession private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    val share: DiskShare,
    val root: String          // 规范化后的远端根目录（相对共享，可为空）
) : AutoCloseable {

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    /** rel(相对 root) -> 相对共享根的绝对路径 */
    fun abs(rel: String): String = when {
        root.isEmpty() -> rel
        rel.isEmpty() -> root
        else -> "$root/$rel"
    }

    /**
     * 确保目录链存在。memo 用于同一运行内缓存已确认过的目录，避免对每个文件都发请求。
     */
    fun ensureDirs(relDir: String, memo: MutableSet<String>) {
        var cur = ""
        for (seg in relDir.split('/').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) seg else "$cur/$seg"
            if (!memo.add(cur)) continue
            if (!share.folderExists(cur)) share.mkdir(cur)
        }
    }

    /**
     * 递归列出现有远端文件索引：相对 root 的路径 -> 大小。
     */
    fun indexRemote(): Map<String, Long> {
        val out = HashMap<String, Long>()
        if (root.isNotEmpty()) ensureDirs(root, HashSet())
        walkDir(abs(""), out)
        return out
    }

    private fun walkDir(absDir: String, out: MutableMap<String, Long>) {
        for (e in share.list(absDir)) {
            val name = e.fileName ?: continue
            if (name == "." || name == "..") continue
            val isDir = (e.fileAttributes and 0x10L) != 0L
            val childAbs = if (absDir.isEmpty()) name else "$absDir/$name"
            if (isDir) {
                walkDir(childAbs, out)
            } else {
                val rel = if (root.isEmpty()) childAbs
                else childAbs.removePrefix(root).removePrefix("/")
                out[rel] = e.endOfFile
            }
        }
    }

    /**
     * 上传文件到 relPath（相对 root）。已存在同名文件会被覆盖。
     * keepDates=true 时尽量把远端修改时间设为与源文件一致。
     * expectedSize>=0 时校验写入字节数与之一致，不一致按失败处理（避免把截断的文件记为成功）。
     * digest 非空时在传输中同步计算内容哈希（用于去重）。
     * abort 非空且返回 true 时抛 PauseSignal 中断当前文件（文件级断点，暂停用）。
     * 返回实际写入字节数。
     */
    fun upload(
        relPath: String,
        input: InputStream,
        mtimeMs: Long,
        expectedSize: Long,
        keepDates: Boolean,
        digest: MessageDigest? = null,
        abort: (() -> Boolean)? = null,
        onBytes: ((Long) -> Unit)? = null
    ): Long {
        val target = abs(relPath)
        val file = share.openFile(
            target,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            null
        )
        var total = 0L
        try {
            val out = file.getOutputStream()
            val buf = ByteArray(DEFAULT_BUFFER)
            while (true) {
                if (abort != null && abort()) throw PauseSignal()
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    digest?.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                    onBytes?.invoke(total)
                }
            }
            out.flush()
            out.close()
            if (expectedSize >= 0 && total != expectedSize) {
                throw IOException("写入字节数不符：期望 $expectedSize，实际 $total")
            }
            if (keepDates) {
                runCatching {
                    file.setFileInformation(
                        FileBasicInformation(
                            FileBasicInformation.DONT_SET,
                            FileBasicInformation.DONT_SET,
                            FileTime.ofEpochMillis(mtimeMs),
                            FileBasicInformation.DONT_SET,
                            FileAttributes.FILE_ATTRIBUTE_NORMAL.value
                        )
                    )
                }
            }
            return total
        } finally {
            runCatching { file.close() }
        }
    }

    /** 删除远端文件（rel 相对 root）。用于清除刚上传后发现是重复内容的那份。 */
    fun deleteRemote(relPath: String) {
        val target = abs(relPath)
        runCatching { share.rm(target) }
    }

    /** 文件级暂停：由上传循环抛出，中断当前文件传输（远端残留半截文件由下次覆盖修复）。 */
    class PauseSignal : Exception("pause")

    companion object {
        private const val DEFAULT_BUFFER = 256 * 1024

        /** 用配置里的主机（Wi-Fi 模式）连接 */
        fun connect(cfg: AppConfig): SmbSession = connect(cfg, cfg.host.trim())

        /** 用显式主机连接（USB/热点模式传入自动/手动探测到的电脑 IP） */
        fun connect(cfg: AppConfig, host: String): SmbSession {
            // 超时收紧：避免对无响应主机的 SMB 协商/读取长时间“卡连接中”
            val smbConfig = SmbConfig.builder()
                .withReadTimeout(45, TimeUnit.SECONDS)
                .withWriteTimeout(120, TimeUnit.SECONDS)
                .withTransactTimeout(60, TimeUnit.SECONDS)
                .withSoTimeout(120, TimeUnit.SECONDS)
                .build()
            val client = SMBClient(smbConfig)
            val connection = client.connect(host, cfg.port)
            val auth = if (cfg.user.trim().isEmpty()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(cfg.user.trim(), cfg.password.toCharArray(), cfg.domain.trim())
            }
            val session = connection.authenticate(auth)
            val share = session.connectShare(cfg.share.trim()) as DiskShare
            return SmbSession(client, connection, session, share, cfg.rootNorm)
        }
    }
}
