package com.nasmusic.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.Playlist
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.SourceType

@Entity(
    tableName = "songs",
    indices = [Index("sourceType"), Index("sourceId"), Index("album"), Index("artist")]
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val uri: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sourceType: String = SourceType.LOCAL.name,
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
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("playlistId")]
)
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val position: Int = 0
)

@Entity(tableName = "nas_sources")
data class NasSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
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

fun SongEntity.toSong(): Song = Song(
    id = id,
    title = title,
    uri = uri,
    sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.LOCAL),
    artist = artist,
    album = album,
    durationMs = durationMs,
    sourceId = sourceId,
    folder = folder,
    artworkUri = artworkUri,
    lyricsUri = lyricsUri,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    mimeType = mimeType,
    sampleRate = sampleRate,
    bitrate = bitrate,
    channels = channels,
    isFavorite = isFavorite
)

fun Song.toEntity(): SongEntity = SongEntity(
    id = id,
    title = title,
    uri = uri,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sourceType = sourceType.name,
    sourceId = sourceId,
    folder = folder,
    artworkUri = artworkUri,
    lyricsUri = lyricsUri,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    mimeType = mimeType,
    sampleRate = sampleRate,
    bitrate = bitrate,
    channels = channels,
    isFavorite = isFavorite
)

fun PlaylistEntity.toModel(): Playlist = Playlist(id, name, createdAt)

fun NasSourceEntity.toModel(): NasSource = NasSource(
    id = id,
    name = name,
    type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.SMB),
    host = host,
    port = port,
    share = share,
    rootPath = rootPath,
    baseUrl = baseUrl,
    username = username,
    passwordEnc = passwordEnc,
    anonymous = anonymous,
    lastScanAt = lastScanAt
)

fun NasSource.toEntity(): NasSourceEntity = NasSourceEntity(
    id = id,
    name = name,
    type = type.name,
    host = host,
    port = port,
    share = share,
    rootPath = rootPath,
    baseUrl = baseUrl,
    username = username,
    passwordEnc = passwordEnc,
    anonymous = anonymous,
    lastScanAt = lastScanAt
)
