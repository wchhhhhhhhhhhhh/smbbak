package com.ybf.smbbak.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.ybf.smbbak.util.Fmt
import java.io.File

/**
 * 自定义文件夹扫描。
 * - SAF：通过系统目录选择器授权的文件夹（DocumentFile 树）
 * - 路径：需要“所有文件访问”权限的直接路径
 */
object FolderScanner {

    data class Row(
        val relLocal: String,      // 相对所选文件夹的路径（已清理非法字符）
        val size: Long,            // -1 = 未知
        val mtimeMs: Long,
        val contentUri: String?,   // SAF 模式
        val filePath: String?      // 路径模式
    )

    fun scanSaf(context: Context, treeUri: Uri): List<Row> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return emptyList()
        val out = ArrayList<Row>()
        walkDoc(root, "", out)
        out.sortBy { it.relLocal }
        return out
    }

    fun scanPath(root: File): List<Row> {
        val out = ArrayList<Row>()
        walkFile(root, "", out)
        out.sortBy { it.relLocal }
        return out
    }

    private fun walkDoc(doc: DocumentFile, rel: String, out: MutableList<Row>) {
        if (doc.isDirectory) {
            for (child in doc.listFiles()) {
                val raw = child.name ?: continue
                if (raw.startsWith(".")) continue
                val seg = Fmt.sanitizeFileName(raw)
                val childRel = if (rel.isEmpty()) seg else "$rel/$seg"
                walkDoc(child, childRel, out)
            }
        } else {
            val name = Fmt.sanitizeFileName(doc.name ?: "unnamed")
            val relPath = if (rel.isEmpty()) name else "$rel/$name"
            out.add(
                Row(
                    relLocal = relPath,
                    size = doc.length().takeIf { it >= 0 } ?: -1L,
                    mtimeMs = doc.lastModified().takeIf { it > 0 } ?: 0L,
                    contentUri = doc.uri.toString(),
                    filePath = null
                )
            )
        }
    }

    private fun walkFile(dir: File, rel: String, out: MutableList<Row>) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val seg = Fmt.sanitizeFileName(name)
            val childRel = if (rel.isEmpty()) seg else "$rel/$seg"
            if (child.isDirectory) {
                walkFile(child, childRel, out)
            } else {
                out.add(
                    Row(
                        relLocal = childRel,
                        size = child.length().takeIf { it >= 0 } ?: -1L,
                        mtimeMs = child.lastModified().takeIf { it > 0 } ?: 0L,
                        contentUri = null,
                        filePath = child.absolutePath
                    )
                )
            }
        }
    }

    /** SAF 树的显示名称：取 lastPathSegment 解码后的最后一段，如 primary:DCIM -> DCIM */
    fun labelOfTreeUri(uri: Uri): String {
        val seg = uri.lastPathSegment ?: "folder"
        val decoded = Uri.decode(seg)
        val idx = decoded.indexOf(':')
        val leaf = if (idx >= 0) decoded.substring(idx + 1) else decoded
        return if (leaf.isEmpty()) "folder" else leaf
    }
}
