package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.ScanProgress
import com.nasmusic.player.core.SettingsStore
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.SourceType
import com.nasmusic.player.core.extensionOf
import com.nasmusic.player.core.isAllowedBy
import com.nasmusic.player.core.isAudioFile
import com.nasmusic.player.core.isExcludedFolder
import com.nasmusic.player.core.libraryFilter
import com.nasmusic.player.core.mimeForName
import com.nasmusic.player.core.nasUri
import com.nasmusic.player.data.db.SongDao
import com.nasmusic.player.data.db.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 异步扫描 NAS 目录：递归浏览、读取标签元数据/封面/内嵌歌词、增量更新（大小+修改时间未变则跳过），
 * 扫描结果写 Room，支持进度回调与协程取消。
 */
class NasScanner(
    private val context: Context,
    private val nasRepository: NasRepository,
    private val songDao: SongDao,
    private val settings: SettingsStore
) {
    private val coverNames = setOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "front.jpg")

    suspend fun scan(source: NasSource, onProgress: (ScanProgress) -> Unit): Int =
        withContext(Dispatchers.IO) {
            // 飞牛音乐自带完整元数据，直接按 API 分页入库，无需逐文件读 ID3
            if (source.type == SourceType.FNOS) return@withContext scanFnos(source, onProgress)
            val filter = settings.snapshot().libraryFilter()
            val browser = nasRepository.browserFor(source.id)
            try {
                val existing = songDao.byNasSource(source.id).associateBy { it.id }
                val finalSongs = mutableListOf<Song>()
                val dirs = ArrayDeque<String>()
                val root = source.rootPath.trim().trim('/')
                if (isExcludedFolder(root, filter.excludedFolders)) {
                    onProgress(ScanProgress(false, source.id, 0, 0, root, "根目录已被排除"))
                    return@withContext 0
                }
                dirs.add(root)
                var scanned = 0
                var visitedDirs = 0

                onProgress(
                    ScanProgress(true, source.id, 0, 0, source.rootPath, "正在连接 ${source.name}")
                )

                while (dirs.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (visitedDirs++ > 3000) break
                    val dir = dirs.removeFirst()
                    val entries = runCatching { browser.listDir(dir) }
                        .getOrElse { error ->
                            onProgress(
                                ScanProgress(
                                    true, source.id, scanned, finalSongs.size, dir,
                                    "目录读取失败：${error.message.orEmpty()}"
                                )
                            )
                            emptyList()
                        }

                    val lrcBases = entries.asSequence()
                        .filter { !it.isDirectory && extensionOf(it.name) == "lrc" }
                        .map { it.name.substringBeforeLast('.') }
                        .toSet()
                    val coverEntry = entries.firstOrNull {
                        !it.isDirectory && it.name.lowercase(Locale.ROOT) in coverNames
                    }

                    for (entry in entries) {
                        currentCoroutineContext().ensureActive()
                        if (entry.isDirectory) {
                            if (isExcludedFolder(entry.path, filter.excludedFolders)) {
                                onProgress(
                                    ScanProgress(
                                        true, source.id, scanned, finalSongs.size,
                                        entry.path, "已跳过排除的目录"
                                    )
                                )
                            } else {
                                dirs.add(entry.path)
                            }
                            continue
                        }
                        if (!isAudioFile(entry.name)) continue
                        scanned++
                        if (scanned % 5 == 0 || finalSongs.size < 5) {
                            onProgress(
                                ScanProgress(
                                    true, source.id, scanned, finalSongs.size,
                                    entry.name, "正在扫描 ${entry.name}"
                                )
                            )
                        }

                        val id = "nas:${source.id}:${entry.path}"
                        val old = existing[id]
                        val folder = entry.path.substringBeforeLast('/', "")
                            .ifBlank { source.name }

                        val song = if (old != null && old.sizeBytes == entry.sizeBytes &&
                            old.lastModified == entry.lastModified && old.title.isNotBlank()
                        ) {
                            old.toSong()
                        } else {
                            val tags = readTags(browser, entry.path, entry.sizeBytes)
                            val cover = tags.cover?.let { saveCover(id, it) }
                                ?: coverEntry?.let { downloadCover(browser, it.path, id) }
                                ?: old?.artworkUri
                            val baseName = entry.name.substringBeforeLast('.')
                            Song(
                                id = id,
                                title = tags.title?.takeIf { it.isNotBlank() } ?: baseName,
                                uri = nasUri(source.id, entry.path),
                                sourceType = source.type,
                                artist = tags.artist?.takeIf { it.isNotBlank() } ?: "未知歌手",
                                album = tags.album?.takeIf { it.isNotBlank() } ?: folder,
                                durationMs = tags.durationMs,
                                sourceId = source.id,
                                folder = folder,
                                artworkUri = cover,
                                lyricsUri = if (baseName in lrcBases) {
                                    nasUri(source.id, entry.path.substringBeforeLast('.') + ".lrc")
                                } else null,
                                sizeBytes = entry.sizeBytes,
                                lastModified = entry.lastModified,
                                mimeType = mimeForName(entry.name),
                                sampleRate = tags.sampleRate,
                                bitrate = tags.bitrate,
                                channels = tags.channels,
                                isFavorite = old?.isFavorite ?: false
                            )
                        }
                        if (song.isAllowedBy(filter)) finalSongs += song
                    }
                }

                // 先删除旧索引再整体写回，可正确移除 NAS 上已删除的文件；收藏状态已在上面保留
                songDao.deleteByNasSource(source.id)
                nasRepository.upsertSongs(finalSongs)
                nasRepository.updateScanTime(source.id)
                onProgress(
                    ScanProgress(
                        false, source.id, scanned, finalSongs.size, "",
                        "扫描完成，共 ${finalSongs.size} 首"
                    )
                )
                finalSongs.size
            } finally {
                runCatching { browser.close() }
            }
        }

    private fun readTags(browser: NasBrowser, path: String, size: Long): AudioTags =
        runCatching {
            val access = browser.open(path)
            try {
                TagReader.read(NasRandomAccessReader(access, size))
            } finally {
                runCatching { access.close() }
            }
        }.getOrDefault(AudioTags())

    private fun saveCover(songId: String, bytes: ByteArray): String? {
        val coverDir = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File(coverDir, Integer.toHexString(songId.hashCode()) + ".img")
        return runCatching {
            file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        }.getOrNull()
    }

    private fun downloadCover(browser: NasBrowser, path: String, songId: String): String? =
        runCatching {
            val access = browser.open(path)
            try {
                val bytes = access.asInputStream().use { it.readBytes() }
                if (bytes.size > 2 * 1024 * 1024) null else saveCover(songId, bytes)
            } finally {
                runCatching { access.close() }
            }
        }.getOrNull()

    /**
     * 飞牛音乐曲库同步：分页拉取 /track/list，用服务端元数据直接构造 Song，
     * 封面按 coverId 去重下载到本机（同一张专辑只下一次），保留收藏与歌词状态。
     */
    private suspend fun scanFnos(
        source: NasSource,
        onProgress: (ScanProgress) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val config = nasRepository.configFor(source.id)
            ?: throw IOException("飞牛音乐源不存在或已被删除")
        val api = FnosApi(config.baseUrl, config.username, config.password, config.id)
        try {
            onProgress(
                ScanProgress(true, source.id, 0, 0, "", "正在登录 ${source.name}")
            )
            api.login()
            val filter = settings.snapshot().libraryFilter()
            val existing = songDao.byNasSource(source.id).associateBy { it.id }
            // 先翻完分页元数据，不边读边串行下封面（这是上次同步慢的主要原因）
            val allTracks = ArrayList<FnosTrack>()
            var page = 1
            var total = -1
            while (page <= 100) {
                currentCoroutineContext().ensureActive()
                onProgress(
                    ScanProgress(
                        true, source.id, allTracks.size, allTracks.size, "",
                        "正在读取飞牛曲库 · 第 $page 页"
                    )
                )
                val (tracks, reportedTotal) = api.tracks(page, 200)
                if (reportedTotal > 0) total = reportedTotal
                allTracks.addAll(tracks)
                if (tracks.isEmpty()) break
                if (total > 0 && page * 200 >= total) break
                page++
            }

            // 先把元数据立即入库：列表无需等待封面网络请求即可显示歌曲。
            // 封面改为入库后批量回填，且只更新 artworkUri 一列，不重写整首歌。
            val songs = mutableListOf<Song>()
            val coverIdToSongIds = HashMap<String, MutableList<String>>()
            for (track in allTracks) {
                currentCoroutineContext().ensureActive()
                val id = "nas:${source.id}:${track.guid}"
                val old = existing[id]
                val song = Song(
                    id = id,
                    title = track.title,
                    uri = nasUri(source.id, track.guid),
                    sourceType = SourceType.FNOS,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    sourceId = source.id,
                    folder = track.album.ifBlank { source.name },
                    artworkUri = old?.artworkUri,
                    lyricsUri = old?.lyricsUri,
                    sizeBytes = track.sizeBytes,
                    lastModified = old?.lastModified ?: 0L,
                    mimeType = track.mimeType,
                    isFavorite = old?.isFavorite ?: false
                )
                if (song.isAllowedBy(filter)) {
                    songs += song
                    // 记录需要补封面的歌曲（本地还没有封面），按 coverId 归组
                    if (track.coverId.isNotBlank() && old?.artworkUri.isNullOrBlank()) {
                        coverIdToSongIds.getOrPut(track.coverId) { ArrayList() } += id
                    }
                }
            }

            // 事务内一次性替换曲库：列表只刷新一次，歌曲立刻可见。
            nasRepository.replaceNasSongs(source.id, songs)
            nasRepository.updateScanTime(source.id)
            onProgress(
                ScanProgress(
                    false, source.id, songs.size, songs.size, "",
                    "已载入 ${songs.size} 首，正在补充封面…"
                )
            )

            // 封面下载放到歌曲入库之后：即使中继慢/失败，也不影响列表已可见。
            val neededCovers = coverIdToSongIds.keys.take(300)
            if (neededCovers.isNotEmpty()) {
                val coverCache = HashMap<String, String>() // 仅存成功结果，避免 null value 触发 NPE
                coroutineScope {
                    neededCovers.chunked(8).forEach { batch ->
                        batch.map { coverId ->
                            async(Dispatchers.IO) {
                                coverId to runCatching {
                                    api.coverBytes(coverId)?.let { saveCover("fnos:$coverId", it) }
                                }.getOrNull()
                            }
                        }.awaitAll().forEach { (coverId, uri) ->
                            if (uri != null) coverCache[coverId] = uri
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
                // 把 coverId -> 本地 uri 转成 本地 uri -> 歌曲 ids，逐批更新
                val batches = HashMap<String, List<String>>()
                for ((coverId, uri) in coverCache) {
                    coverIdToSongIds[coverId]?.let { batches[uri] = it }
                }
                nasRepository.updateArtworkBatches(batches)
            }

            onProgress(
                ScanProgress(
                    false, source.id, songs.size, songs.size, "",
                    "飞牛曲库同步完成，共 ${songs.size} 首"
                )
            )
            songs.size
        } finally {
            api.close()
        }
    }
}
