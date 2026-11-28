package com.nasmusic.desktop.model

import kotlinx.serialization.Serializable

/**
 * 音源类型。
 *
 * 前四种与 Android 端 `core/Models.kt` 完全对齐（顺序、名称一致），
 * 桌面端额外增加 ONLINE（Subsonic 兼容在线音源，安卓内核组正在做）。
 * 后续抽公共模块时，公共枚举只保留四者，ONLINE 可由两端各自扩展。
 */
@Serializable
enum class SourceType { LOCAL, SMB, WEBDAV, FNOS, ONLINE }

/**
 * 统一歌曲模型：字段顺序与名称与 Android 端 `Song` 对齐，便于未来直接迁移到共享模块。
 * 桌面端不依赖 Android 的 Uri/Media3，uri 一律用普通字符串（file:/http(s):/smb: 等）。
 */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val uri: String,
    val sourceType: SourceType,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sourceId: String = "",
    val folder: String = "",
    val artworkUri: String? = null,
    val lyricsUri: String? = null,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val mimeType: String = "",
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val channels: Int = 0,
    val isFavorite: Boolean = false
) {
    val isNas: Boolean get() = sourceType != SourceType.LOCAL

    /** 供 UI 展示的来源标签；与 Android 端保持一致并补充 ONLINE。 */
    val sourceLabel: String
        get() = when (sourceType) {
            SourceType.LOCAL -> "本地"
            SourceType.SMB -> "NAS · SMB"
            SourceType.WEBDAV -> "NAS · WebDAV"
            SourceType.FNOS -> "飞牛音乐"
            SourceType.ONLINE -> "在线 · Subsonic"
        }
}

/** 歌单（字段与 Android 端 `Playlist` 对齐）。 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * NAS / 在线音源配置（字段与 Android 端 `NasSource` 对齐）。
 * passwordEnc 存密文（见 data/OnlineMusicApi.kt 的 CredentialCipher 接口），
 * 当前骨架落盘为 Base64 混淆，生产应替换为系统凭据库实现。
 */
@Serializable
data class NasSource(
    val id: String,
    val name: String,
    val type: SourceType,
    val host: String = "",
    val port: Int = 0,
    val share: String = "",
    val rootPath: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val passwordEnc: String = "",
    val anonymous: Boolean = false,
    val lastScanAt: Long = 0L
)

/** 扫描进度：与 Android 端字段一致，UI 可据此显示进度与错误。 */
@Serializable
data class ScanProgress(
    val running: Boolean = false,
    val sourceId: String = "",
    val scanned: Int = 0,
    val found: Int = 0,
    val current: String = "",
    val message: String = "",
    val error: String? = null
)
