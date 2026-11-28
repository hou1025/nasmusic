package com.nasmusic.desktop.data

import com.nasmusic.desktop.model.NasSource
import com.nasmusic.desktop.model.Playlist
import com.nasmusic.desktop.model.ScanProgress
import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/** 落盘快照：收藏与歌单用 JSON 持久化，与 Android 端 Room 表结构保持语义一致。 */
@Serializable
private data class LibrarySnapshot(
    val localRoots: List<String> = emptyList(),
    val sources: List<NasSource> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val favoriteIds: List<String> = emptyList(),
    val playlistSongIds: Map<String, List<String>> = emptyMap()
)

private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "ogg", "wav")

/** 远程曲库分页上限：防止服务端 total 异常时无限翻页。 */
private const val MAX_REMOTE_SONGS = 5000
private const val MAX_PAGES = 50

/**
 * 曲库中心：本地递归扫描 + 远程音源聚合 + JSON 持久化。
 *
 * 所有状态以 StateFlow 暴露给 Compose UI；IO 操作在 Dispatchers.IO 上执行。
 */
class MusicLibrary {
    private val dataDir = File(System.getProperty("user.home"), ".nasmusic-desktop")
    private val storeFile = File(dataDir, "library.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val cipher = Base64CredentialCipher()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _sources = MutableStateFlow<List<NasSource>>(emptyList())
    val sources: StateFlow<List<NasSource>> = _sources.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _localRoots = MutableStateFlow<List<String>>(emptyList())
    val localRoots: StateFlow<List<String>> = _localRoots.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    private val _playlistSongIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    private val _scan = MutableStateFlow(ScanProgress())
    val scan: StateFlow<ScanProgress> = _scan.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 启动时读取配置；找不到文件则静默使用空库。 */
    fun load() {
        if (!storeFile.exists()) return
        val text = runCatching { storeFile.readText(Charsets.UTF_8) }
            .getOrElse { e -> _lastError.value = "读取曲库配置失败：${e.message}"; return }
        val snapshot = runCatching { json.decodeFromString<LibrarySnapshot>(text) }
            .getOrElse { e -> _lastError.value = "曲库配置格式错误：${e.message}"; return }
        _localRoots.value = snapshot.localRoots
        _sources.value = snapshot.sources
        _playlists.value = snapshot.playlists
        _favoriteIds.value = snapshot.favoriteIds.toSet()
        _playlistSongIds.value = snapshot.playlistSongIds
        _songs.value = _songs.value.map { it.copy(isFavorite = it.id in _favoriteIds.value) }
    }

    private fun persist() {
        try {
            if (!dataDir.exists() && !dataDir.mkdirs()) throw IOException("无法创建配置目录：$dataDir")
            val snapshot = LibrarySnapshot(
                localRoots = _localRoots.value,
                sources = _sources.value,
                playlists = _playlists.value,
                favoriteIds = _favoriteIds.value.toList(),
                playlistSongIds = _playlistSongIds.value
            )
            storeFile.writeText(json.encodeToString(snapshot), Charsets.UTF_8)
            _lastError.value = null
        } catch (e: IOException) {
            _lastError.value = "保存曲库配置失败：${e.message}"
        }
    }

    fun addLocalRoot(path: String): Result<Unit> = runCatching {
        val p = Paths.get(path.trim())
        require(Files.isDirectory(p)) { "目录不存在：$path" }
        val absolute = p.toAbsolutePath().normalize().toString()
        if (absolute !in _localRoots.value) {
            _localRoots.value = _localRoots.value + absolute
            persist()
        }
    }

    fun removeLocalRoot(path: String) {
        _localRoots.value = _localRoots.value.filter { it != path }
        persist()
    }

    fun toggleFavorite(songId: String) {
        val current = _favoriteIds.value
        _favoriteIds.value = if (songId in current) current - songId else current + songId
        _songs.value = _songs.value.map {
            if (it.id == songId) it.copy(isFavorite = songId in _favoriteIds.value) else it
        }
        persist()
    }

    fun createPlaylist(name: String): Result<Playlist> = runCatching {
        require(name.isNotBlank()) { "歌单名不能为空" }
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim())
        _playlists.value = _playlists.value + playlist
        persist()
        playlist
    }

    fun addToPlaylist(playlistId: String, songIds: List<String>) {
        val current = _playlistSongIds.value[playlistId].orEmpty()
        _playlistSongIds.value = _playlistSongIds.value + (playlistId to (current + songIds).distinct())
        persist()
    }

    fun songsInPlaylist(playlistId: String): List<Song> {
        val ids = _playlistSongIds.value[playlistId].orEmpty().toSet()
        return _songs.value.filter { it.id in ids }
    }

    /** 添加音源；密码用 CredentialCipher 封装后再落盘。 */
    fun addSource(
        name: String,
        type: SourceType,
        baseUrl: String,
        host: String,
        port: Int,
        rootPath: String,
        username: String,
        password: String
    ): Result<NasSource> = runCatching {
        require(name.isNotBlank()) { "音源名称不能为空" }
        val source = NasSource(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            type = type,
            baseUrl = baseUrl.trim(),
            host = host.trim(),
            port = port,
            rootPath = rootPath.trim(),
            username = username.trim(),
            passwordEnc = cipher.encrypt(password)
        )
        _sources.value = _sources.value + source
        persist()
        source
    }

    fun removeSource(id: String) {
        _sources.value = _sources.value.filter { it.id != id }
        _songs.value = _songs.value.filter { it.sourceId != id }
        persist()
    }

    fun decryptedPassword(source: NasSource): String =
        runCatching { cipher.decrypt(source.passwordEnc) }.getOrDefault("")

    /** 按音源类型分发连通性测试，UI 直接展示返回文案。 */
    suspend fun testSource(source: NasSource): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.type) {
                SourceType.ONLINE -> OnlineMusicApi(source.baseUrl, source.username, decryptedPassword(source), source.id)
                    .testConnection().getOrThrow()
                SourceType.FNOS -> FnosClient(
                    source.baseUrl.ifBlank { source.host }, source.username, decryptedPassword(source), source.id
                ).testConnection().getOrThrow()
                SourceType.WEBDAV -> WebDavClient(source.baseUrl, source.username, decryptedPassword(source))
                    .testConnection().getOrThrow()
                SourceType.SMB -> "SMB 配置已保存；扫描需接入 smbj 库（当前骨架未实现，见 README）"
                SourceType.LOCAL -> if (Files.isDirectory(Paths.get(source.rootPath))) {
                    "本地目录有效"
                } else {
                    throw IOException("本地目录不存在：${source.rootPath}")
                }
            }
        }
    }

    /**
     * 刷新单个音源；SMB 暂不支持，返回可读错误。
     *
     * 分页拉全库：以前飞牛只拉第 1 页 200 首、在线音源只拉 100 首，
     * 曲库大一点的用户会以为"歌丢了"。这里按 total / 空页收敛，并设 5000 首上限防死循环。
     */
    suspend fun refreshSource(source: NasSource): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.type) {
                SourceType.FNOS -> {
                    val client = FnosClient(
                        source.baseUrl.ifBlank { source.host }, source.username, decryptedPassword(source), source.id
                    )
                    val collected = mutableListOf<Song>()
                    var page = 1
                    val pageSize = 200
                    var total = Int.MAX_VALUE
                    while (collected.size < MAX_REMOTE_SONGS && page <= MAX_PAGES) {
                        val (songs, serverTotal) = client.tracks(page, pageSize).getOrThrow()
                        if (songs.isEmpty()) break
                        collected += songs
                        if (serverTotal > 0) total = serverTotal
                        if (collected.size >= total) break
                        if (songs.size < pageSize) break
                        page++
                    }
                    val distinct = collected.distinctBy { it.id }
                    addRemoteSongs(source.id, distinct)
                    distinct.size
                }

                SourceType.WEBDAV -> WebDavClient(source.baseUrl, source.username, decryptedPassword(source))
                    .listAudio(source.rootPath, source.id).map { songs ->
                        addRemoteSongs(source.id, songs)
                        songs.size
                    }.getOrThrow()

                SourceType.ONLINE -> {
                    val client = OnlineMusicApi(source.baseUrl, source.username, decryptedPassword(source), source.id)
                    val collected = mutableListOf<Song>()
                    val pageSize = 500
                    var offset = 0
                    while (collected.size < MAX_REMOTE_SONGS) {
                        val songs = client.search("*", pageSize, offset).getOrThrow()
                        if (songs.isEmpty()) break
                        collected += songs
                        if (songs.size < pageSize) break
                        offset += pageSize
                    }
                    val distinct = collected.distinctBy { it.id }
                    addRemoteSongs(source.id, distinct)
                    distinct.size
                }

                SourceType.LOCAL -> {
                    scanLocal()
                    _songs.value.count { it.sourceType == SourceType.LOCAL }
                }

                SourceType.SMB -> throw UnsupportedOperationException("SMB 扫描需接入 smbj 库，当前骨架未实现")
            }
        }
    }

    /** 递归扫描所有本地根目录，更新进度并合并结果。 */
    suspend fun scanLocal() = withContext(Dispatchers.IO) {
        _scan.value = ScanProgress(running = true, message = "准备扫描本地音乐…")
        var scanned = 0
        val found = mutableListOf<Song>()
        try {
            for (root in _localRoots.value) {
                val rootPath = Paths.get(root)
                if (!Files.exists(rootPath)) {
                    _scan.value = _scan.value.copy(message = "目录不存在，已跳过：$root")
                    continue
                }
                val stream = Files.walk(rootPath)
                try {
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        val path = iterator.next()
                        if (!Files.isRegularFile(path)) continue
                        scanned++
                        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
                        if (ext !in AUDIO_EXT) continue
                        val meta = TagReader.read(path)
                        val id = stableId(path.toAbsolutePath().normalize().toString())
                        found += Song(
                            id = id,
                            title = meta.title,
                            uri = path.toAbsolutePath().normalize().toUri().toString(),
                            sourceType = SourceType.LOCAL,
                            artist = meta.artist,
                            album = meta.album,
                            folder = path.parent?.toString().orEmpty(),
                            sizeBytes = runCatching { Files.size(path) }.getOrDefault(0L),
                            lastModified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L),
                            mimeType = mimeFor(ext),
                            isFavorite = id in _favoriteIds.value
                        )
                        if (found.size % 20 == 0) {
                            _scan.value = _scan.value.copy(
                                scanned = scanned,
                                found = found.size,
                                current = path.fileName.toString()
                            )
                        }
                    }
                } finally {
                    stream.close()
                }
            }
            val remote = _songs.value.filter { it.sourceType != SourceType.LOCAL }
            _songs.value = (remote + found).distinctBy { it.id }
            _scan.value = ScanProgress(
                running = false, scanned = scanned, found = found.size,
                message = "扫描完成，共 ${found.size} 首本地歌曲"
            )
        } catch (e: Exception) {
            _scan.value = _scan.value.copy(running = false, error = "扫描失败：${e.message}")
        }
    }

    private fun addRemoteSongs(sourceId: String, songs: List<Song>) {
        val others = _songs.value.filter { it.sourceId != sourceId }
        val favorites = _favoriteIds.value
        _songs.value = (others + songs.map { it.copy(isFavorite = it.id in favorites) })
            .distinctBy { it.id }
    }

    private fun stableId(path: String): String =
        UUID.nameUUIDFromBytes(path.toByteArray(Charsets.UTF_8)).toString()

    private fun mimeFor(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> ""
    }

    /** 轻量元数据读取：优先 ID3v1，退化为「歌手 - 标题」文件名。完整标签解析留待共享模块。 */
    private object TagReader {
        fun read(path: Path): Meta {
            readId3v1(path)?.let { return it }
            val fileName = path.fileName.toString()
            val base = fileName.substringBeforeLast('.', fileName)
            val parts = base.split(" - ", limit = 2)
            return Meta(
                title = (if (parts.size == 2) parts[1] else base).trim().ifBlank { "未知曲目" },
                artist = if (parts.size == 2) parts[0].trim() else "",
                album = ""
            )
        }

        private fun readId3v1(path: Path): Meta? {
            val file = try {
                RandomAccessFile(path.toFile(), "r")
            } catch (e: IOException) {
                return null
            }
            try {
                if (file.length() < 128) return null
                file.seek(file.length() - 128)
                val tag = ByteArray(128)
                if (file.read(tag) != 128) return null
                if (String(tag, 0, 3, Charsets.US_ASCII) != "TAG") return null
                fun field(offset: Int, length: Int): String =
                    String(tag, offset, length, Charsets.ISO_8859_1).trim('\u0000', ' ').trim()
                val title = field(3, 30)
                val artist = field(33, 30)
                val album = field(63, 30)
                if (title.isEmpty() && artist.isEmpty()) return null
                return Meta(title.ifBlank { "未知曲目" }, artist, album)
            } finally {
                file.close()
            }
        }
    }

    private data class Meta(val title: String, val artist: String, val album: String)
}
