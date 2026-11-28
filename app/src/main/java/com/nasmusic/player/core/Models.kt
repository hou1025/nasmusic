@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nasmusic.player.core

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** FNOS = 飞牛音乐（fnOS /music/api/v1）私有网关；ONLINE = Subsonic 兼容在线音乐源。 */
enum class SourceType { LOCAL, SMB, WEBDAV, FNOS, ONLINE }

/**
 * 本地音乐与 NAS 音乐在 UI 层统一为同一个模型，只通过 sourceType/sourceId 区分管理。
 */
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
    val sourceLabel: String
        get() = when (sourceType) {
            SourceType.LOCAL -> "本地"
            SourceType.SMB -> "NAS · SMB"
            SourceType.WEBDAV -> "NAS · WebDAV"
            SourceType.FNOS -> "飞牛音乐"
            SourceType.ONLINE -> "在线音源"
        }
}

/** 转为 Media3 播放项；副标题携带来源标识，供通知栏/锁屏展示。 */
fun Song.toMediaItem(playbackUri: String = uri): MediaItem {
    val extras = Bundle().apply {
        putString("sourceLabel", sourceLabel)
        putString("sourceType", sourceType.name)
        putString("songId", id)
    }
    // 通知栏/锁屏默认展示 title + artist，因此把来源标识一并列进 artist，确保外部控制器可见
    val displayArtist = if (sourceType == SourceType.LOCAL) artist.ifBlank { "未知歌手" }
    else "${artist.ifBlank { "未知歌手" }} · $sourceLabel"
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(displayArtist)
        .setAlbumTitle(album)
        .setSubtitle(sourceLabel)
        .setArtworkUri(artworkUri?.let { Uri.parse(it) })
        .setExtras(extras)
        .build()

    val builder = MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse(playbackUri))
        .setMediaMetadata(metadata)
    if (mimeType.isNotBlank()) builder.setMimeType(mimeType)
    return builder.build()
}

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

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

data class ScanProgress(
    val running: Boolean = false,
    val sourceId: String = "",
    val scanned: Int = 0,
    val found: Int = 0,
    val current: String = "",
    val message: String = "",
    val error: String? = null
)
