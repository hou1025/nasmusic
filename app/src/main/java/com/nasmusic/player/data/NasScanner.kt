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
import com.nasmusic.player.core.readLimitedBytes
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
    private val settings: SettingsStore,
    private val onlineApi: OnlineMusicApi
) {
    private val coverNames = setOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "front.jpg")

    /**
     * 按音源记录封面回填中已尝试过的 key（coverId / 在线封面 URL）。扫描器是单例，
     * 状态可跨多次扫描保留：某批下载失败时下一轮跳过已尝试项，优先回填尚未尝试的封面，
     * 避免曲库超过 300 张封面时永远卡在同一批；全部尝试过一轮后清空，让失败封面再获重试机会。
     */
    private val attemptedCoverKeys = HashMap<String, MutableSet<String>>()

    /** 取本轮要尝试的封面 key：优先未尝试过的，最多 limit 个；若已全部尝试过则清空记录重试。 */
    private fun pickCoverKeysToTry(scopeKey: String, allKeys: Collection<String>, limit: Int): List<String> =
        synchronized(attemptedCoverKeys) {
            val attempted = attemptedCoverKeys.getOrPut(scopeKey) { HashSet<String>() }
            var pending = allKeys.filterNot { it in attempted }
            if (pending.isEmpty()) {
                attempted.clear()
                pending = allKeys.toList()
            }
            val picked = pending.take(limit)
            attempted.addAll(picked)
            picked
        }

    suspend fun scan(source: NasSource, onProgress: (ScanProgress) -> Unit): Int =
        withContext(Dispatchers.IO) {
            // 飞牛音乐 / 在线音源自带完整元数据，直接按 API 分页入库，无需逐文件读 ID3
            when (source.type) {
                SourceType.FNOS -> return@withContext scanFnos(source, onProgress)
                SourceType.ONLINE -> return@withContext scanOnline(source, onProgress)
                else -> Unit
            }
            val filter = settings.snapshot().libraryFilter()
            val browser = nasRepository.browserFor(source.id)
            var hadListError = false
            try {
                val existing = songDao.byNasSource(source.id).associateBy { it.id }
                val finalSongs = mutableListOf<Song>()
                val dirs = ArrayDeque<String>()
                val root = source.rootPath.trim().trim('/')
                if (isExcludedFolder(root, filter.excludedFolders, filter.matchMode)) {
                    onProgress(ScanProgress(false, source.id, 0, 0, root, "根目录已被排除"))
                    return@withContext 0
                }
                dirs.add(root)
                var scanned = 0
                var visitedDirs = 0
                var traversalTruncated = false

                onProgress(
                    ScanProgress(true, source.id, 0, 0, source.rootPath, "正在连接 ${source.name}")
                )

                while (dirs.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (visitedDirs++ > 3000) {
                        // 目录过多：停止遍历，但不能再做整源替换，否则深层未扫到的歌会被误删
                        traversalTruncated = true
                        break
                    }
                    val dir = dirs.removeFirst()
                    val entries = runCatching { browser.listDir(dir) }
                        .getOrElse { error ->
                            hadListError = true
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
                            if (isExcludedFolder(entry.path, filter.excludedFolders, filter.matchMode)) {
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

                // 事务内一次性替换：既移除 NAS 上已删除的文件，又避免先清空后写入导致的
                // 列表空窗闪烁与中途崩溃丢库；收藏状态已在上面保留。
                // 遍历被 3000 目录上限截断、或存在目录读取失败时绝不整源替换，只做增量 upsert，
                // 否则未扫到 / 读取失败目录中的歌曲会被误删。
                if (traversalTruncated || hadListError) {
                    nasRepository.upsertSongs(finalSongs)
                    val message = when {
                        traversalTruncated && hadListError ->
                            "目录数超过 3000 且部分目录读取失败，仅更新已扫描曲目，以避免误删未扫到的歌曲"
                        traversalTruncated ->
                            "目录数超过 3000，已停止遍历并仅更新已扫描曲目，以避免误删未扫到的歌曲"
                        else ->
                            "部分目录读取失败，仅更新已扫描曲目，以避免误删读取失败目录中的歌曲"
                    }
                    onProgress(ScanProgress(false, source.id, scanned, finalSongs.size, "", message))
                } else {
                    nasRepository.replaceNasSongs(source.id, finalSongs)
                    onProgress(
                        ScanProgress(
                            false, source.id, scanned, finalSongs.size, "",
                            "扫描完成，共 ${finalSongs.size} 首"
                        )
                    )
                }
                nasRepository.updateScanTime(source.id)
                finalSongs.size
            } finally {
                runCatching { browser.close() }
                // 扫描期间列表读取失败说明连接可能已失效，统一在结束时失效缓存，下次重建
                if (hadListError) nasRepository.invalidateBrowser(source.id)
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
                // 限流读取：读满 2MB+1 即中断丢弃，避免把整个 cover.jpg 读进内存
                val bytes = access.asInputStream().use { readLimitedBytes(it) }
                bytes?.let { saveCover(songId, it) }
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
            val seenGuids = HashSet<String>()
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
                val before = allTracks.size
                // 按 guid 去重：服务端若忽略 page 参数会重复返回同一批，不能重复入库
                for (track in tracks) if (seenGuids.add(track.guid)) allTracks += track
                if (tracks.isEmpty() || allTracks.size == before) break
                if (total > 0 && page * 200 >= total) break
                page++
            }

            // 先把元数据立即入库：列表无需等待封面网络请求即可显示歌曲。
            // 封面改为入库后批量回填，且只更新 artworkUri 一列，不重写整首歌。
            val songs = mutableListOf<Song>()
            // LinkedHashMap 保留首次出现顺序；回填时由 pickCoverKeysToTry 每轮优先取未尝试过的 key，
            // 避免下载失败时反复卡在同一批 300 张。
            val coverIdToSongIds = LinkedHashMap<String, MutableList<String>>()
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
            val neededCovers = pickCoverKeysToTry("fnos:${source.id}", coverIdToSongIds.keys, 300)
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

    /**
     * Subsonic 在线音源同步：元数据直接入库（封面先用在线 URL 立即可见），
     * 再异步下载封面到本地回填 artworkUri（每轮最多 300 张，优先未尝试过的），失败则保留在线 URL 作为兜底。
     */
    private suspend fun scanOnline(
        source: NasSource,
        onProgress: (ScanProgress) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        onProgress(
            ScanProgress(true, source.id, 0, 0, "", "正在读取在线曲库 ${source.name}")
        )
        val filter = settings.snapshot().libraryFilter()
        val existing = songDao.byNasSource(source.id).associateBy { it.id }
        val remote = onlineApi.listRoot(source)

        val songs = ArrayList<Song>()
        val coverUrlToSongIds = LinkedHashMap<String, MutableList<String>>()
        val seen = HashSet<String>()
        for (apiSong in remote) {
            currentCoroutineContext().ensureActive()
            if (!seen.add(apiSong.id)) continue
            val old = existing[apiSong.id]
            val song = apiSong.copy(
                isFavorite = old?.isFavorite ?: false,
                lyricsUri = old?.lyricsUri,
                artworkUri = old?.artworkUri ?: apiSong.artworkUri
            )
            if (!song.isAllowedBy(filter)) continue
            songs += song
            val coverUrl = apiSong.artworkUri
            if (!coverUrl.isNullOrBlank() && old?.artworkUri.isNullOrBlank()) {
                coverUrlToSongIds.getOrPut(coverUrl) { ArrayList() } += song.id
            }
        }

        // 与飞牛分支一致：事务内整源替换，列表只刷新一次
        nasRepository.replaceNasSongs(source.id, songs)
        nasRepository.updateScanTime(source.id)
        onProgress(
            ScanProgress(
                false, source.id, songs.size, songs.size, "",
                "已载入 ${songs.size} 首，正在补充封面…"
            )
        )

        // 封面异步回填：下载失败不阻断入库，在线 URL 依旧可显示
        val needed = pickCoverKeysToTry("online:${source.id}", coverUrlToSongIds.keys, 300)
        if (needed.isNotEmpty()) {
            val coverCache = HashMap<String, String>()
            coroutineScope {
                needed.chunked(8).forEach { batch ->
                    batch.map { url ->
                        async(Dispatchers.IO) {
                            val coverId = Uri.parse(url).getQueryParameter("id").orEmpty()
                            val uri = runCatching {
                                if (coverId.isBlank()) null
                                else onlineApi.coverBytes(source, coverId)
                                    ?.let { saveCover("online:$coverId", it) }
                            }.getOrNull()
                            url to uri
                        }
                    }.awaitAll().forEach { (url, uri) ->
                        if (uri != null) coverCache[url] = uri
                    }
                    currentCoroutineContext().ensureActive()
                }
            }
            val batches = HashMap<String, List<String>>()
            for ((url, uri) in coverCache) {
                coverUrlToSongIds[url]?.let { batches[uri] = it }
            }
            nasRepository.updateArtworkBatches(batches)
        }

        onProgress(
            ScanProgress(
                false, source.id, songs.size, songs.size, "",
                "在线曲库同步完成，共 ${songs.size} 首"
            )
        )
        songs.size
    }
}
