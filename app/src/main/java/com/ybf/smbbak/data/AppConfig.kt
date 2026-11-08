package com.ybf.smbbak.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份来源：文件夹（SAF 树授权）或路径（需“所有文件访问”权限）。
 */
data class FolderSource(
    val id: String,      // SAF: tree Uri 字符串；PATH: 绝对路径
    val label: String,   // 在远端显示的名称
    val kind: String     // "saf" 或 "path"
) {
    companion object {
        const val KIND_SAF = "saf"
        const val KIND_PATH = "path"
    }
}

/**
 * 图集（相册）选择：限制某类媒体只备份指定文件夹，避免把各 App 的缓存/缩略目录一起备份。
 * bucketId 为该相册在 MediaStore 中的标识，name 仅用于展示。
 */
data class AlbumChoice(
    val kind: String,       // "image" | "video"
    val bucketId: String,
    val name: String
)

/**
 * 应用配置（序列化为 JSON 存于 SharedPreferences）。
 */
data class AppConfig(
    var host: String = "",
    var port: Int = 445,
    var share: String = "",
    var user: String = "",
    var password: String = "",
    var domain: String = "",
    var remoteRoot: String = "",
    var imagesEnabled: Boolean = false,
    var videosEnabled: Boolean = false,
    var folders: MutableList<FolderSource> = mutableListOf(),
    var albums: MutableList<AlbumChoice> = mutableListOf(),
    var mode: String = MODE_WIFI,     // 传输方式：Wi-Fi SMB / USB 直连电脑
    var usbHostManual: String = "",   // USB 模式手动指定电脑 IP（可选，留空=自动检测）
    var autoHours: Long = 0,          // 0=关闭, 24/72/168
    var wifiOnlyAuto: Boolean = true,
    var keepDates: Boolean = true
) {

    val hasTarget: Boolean get() = host.isNotBlank() && share.isNotBlank()

    val isUsbMode: Boolean get() = mode == MODE_USB

    val isHotspotMode: Boolean get() = mode == MODE_HOTSPOT

    /** 规范化远端根目录：去掉首尾斜杠 */
    val rootNorm: String
        get() = remoteRoot.trim().trim('/')

    /**
     * 目标唯一键：登记表/内容索引按它隔离。
     * 同一份“共享+目录”在不同传输方式（Wi-Fi/电脑）下视为不同目标，互不串用记录。
     */
    fun targetKey(): String = "${mode}|${share.trim()}|$rootNorm"

    /** 某类媒体选中的图集 bucketId 集合；空 = 备份全部图集 */
    fun albumIdsFor(kind: String): Set<String> =
        albums.filter { it.kind == kind }.map { it.bucketId }.toSet()

    /** 覆盖某类媒体的图集选择 */
    fun setAlbumsFor(kind: String, list: List<AlbumChoice>) {
        albums.removeAll { it.kind == kind }
        albums.addAll(list)
    }

    fun albumCountFor(kind: String): Int = albums.count { it.kind == kind }

    fun toJson(): String {
        val foldersArr = JSONArray()
        folders.forEach { f ->
            foldersArr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("label", f.label)
                    .put("kind", f.kind)
            )
        }
        val albumsArr = JSONArray()
        albums.forEach { a ->
            albumsArr.put(
                JSONObject()
                    .put("kind", a.kind)
                    .put("bucketId", a.bucketId)
                    .put("name", a.name)
            )
        }
        return JSONObject()
            .put("host", host)
            .put("port", port)
            .put("share", share)
            .put("user", user)
            .put("password", password)
            .put("domain", domain)
            .put("remoteRoot", remoteRoot)
            .put("imagesEnabled", imagesEnabled)
            .put("videosEnabled", videosEnabled)
            .put("folders", foldersArr)
            .put("albums", albumsArr)
            .put("mode", mode)
            .put("usbHostManual", usbHostManual)
            .put("autoHours", autoHours)
            .put("wifiOnlyAuto", wifiOnlyAuto)
            .put("keepDates", keepDates)
            .toString()
    }

    companion object {
        const val MODE_WIFI = "wifi"
        const val MODE_USB = "usb"
        const val MODE_HOTSPOT = "hotspot"

        fun fromJson(s: String?): AppConfig {
            val cfg = AppConfig()
            if (s.isNullOrBlank()) return cfg
            return runCatching {
                val o = JSONObject(s)
                cfg.host = o.optString("host")
                cfg.port = o.optInt("port", 445).coerceIn(1, 65535)
                cfg.share = o.optString("share")
                cfg.user = o.optString("user")
                cfg.password = o.optString("password")
                cfg.domain = o.optString("domain")
                cfg.remoteRoot = o.optString("remoteRoot")
                cfg.imagesEnabled = o.optBoolean("imagesEnabled")
                cfg.videosEnabled = o.optBoolean("videosEnabled")
                val arr = o.optJSONArray("folders")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val fo = arr.optJSONObject(i) ?: continue
                        cfg.folders.add(
                            FolderSource(
                                id = fo.optString("id"),
                                label = fo.optString("label"),
                                kind = fo.optString("kind", FolderSource.KIND_SAF)
                            )
                        )
                    }
                }
                val alb = o.optJSONArray("albums")
                if (alb != null) {
                    for (i in 0 until alb.length()) {
                        val ao = alb.optJSONObject(i) ?: continue
                        cfg.albums.add(
                            AlbumChoice(
                                kind = ao.optString("kind", "image"),
                                bucketId = ao.optString("bucketId"),
                                name = ao.optString("name")
                            )
                        )
                    }
                }
                cfg.mode = o.optString("mode", MODE_WIFI)
                cfg.usbHostManual = o.optString("usbHostManual")
                cfg.autoHours = o.optLong("autoHours", 0)
                cfg.wifiOnlyAuto = o.optBoolean("wifiOnlyAuto", true)
                cfg.keepDates = o.optBoolean("keepDates", true)
                cfg
            }.getOrDefault(AppConfig())
        }
    }
}
