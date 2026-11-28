package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import com.nasmusic.player.core.SettingsStore
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.parseNasUri
import com.nasmusic.player.core.suspendCatching
import com.nasmusic.player.core.nasUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val FIRST_CACHE_CHUNK = 96 * 1024
private const val MAX_CACHE_CHUNK = 512 * 1024

/** NAS 歌曲缓存：默认在线播放，按需缓存；LRU 自动清理，缓存文件不写入系统媒体库。 */
class NasCacheManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val nasRepository: NasRepository
) {
    private val dir: File = File(context.cacheDir, "nas_audio").apply { mkdirs() }

    // 用后台常驻收集器维护容量配置，替代 enforceLimit 里的 runBlocking（无法被取消）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var cachedLimitMb = 512

    init {
        scope.launch {
            settings.settings.collect { cachedLimitMb = it.cacheLimitMb }
        }
    }

    private fun fileFor(songId: String): File =
        File(dir, Integer.toHexString(songId.hashCode()) + ".cache")

    fun cachedFile(songId: String): File? {
        val file = fileFor(songId)
        if (!file.exists() || file.length() == 0L) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** 已缓存歌曲改用本地文件播放，断网仍可播；未缓存走 nas:// 在线数据源。 */
    fun playbackUri(song: Song): String {
        if (!song.isNas) return song.uri
        cachedFile(song.id)?.let { return Uri.fromFile(it).toString() }
        // 曲库里已有的文件大小直接带进 URI，数据源无需再发 Range 0-0 探测长度
        if (song.sizeBytes > 0) {
            parseNasUri(song.uri)?.let { (sourceId, path) ->
                return nasUri(sourceId, path, song.sizeBytes)
            }
        }
        return song.uri
    }

    fun isCached(song: Song): Boolean = song.isNas && cachedFile(song.id) != null

    /**
     * 缓存整首歌到本地。
     *
     * 速度优化：块大小从 96KB 起渐进放大到 512KB。旧实现用固定 512KB，
     * 飞牛 FN Connect 中继/弱网下每个 Range 请求要 2~5 秒才返回，
     * 首块迟迟不出数据，整首歌要等十几到几十秒；渐进块让开头立刻开始落盘，
     * 稳定后仍用 512KB 压低请求数。
     *
     * 短读补齐：中继可能一次只返回部分字节，绝不能把短读当 EOF，否则缓存文件不完整。
     *
     * 带宽让步：shouldYield 返回 false 时暂停下载（例如当前播放缓冲不足），
     * 避免后台缓存把正在播放的歌抢卡。连续让步超过 30 秒则放弃本次缓存。
     */
    suspend fun cacheSong(
        song: Song,
        shouldYield: () -> Boolean = { true }
    ): Boolean = withContext(Dispatchers.IO) {
        if (!song.isNas) return@withContext false
        val (sourceId, path) = parseNasUri(song.uri) ?: return@withContext false
        val browser = nasRepository.browserFor(sourceId)
        val tmp = File(dir, Integer.toHexString(song.id.hashCode()) + ".tmp")
        try {
            val access = browser.open(path)
            var aborted = false
            try {
                val total = runCatching { access.length }.getOrDefault(0L)
                val buf = ByteArray(MAX_CACHE_CHUNK)
                var pos = 0L
                var chunk = FIRST_CACHE_CHUNK
                var yieldRounds = 0
                FileOutputStream(tmp).use { out ->
                    while (pos < total || total <= 0) {
                        if (!shouldYield()) {
                            yieldRounds++
                            if (yieldRounds > 120) { aborted = true; break }
                            delay(250)
                            continue
                        }
                        val want = if (total > 0) minOf(chunk.toLong(), total - pos)
                                   else chunk.toLong()
                        if (want <= 0) break
                        var filled = 0
                        while (filled < want) {
                            val n = access.readAt(pos + filled, buf, filled, (want - filled).toInt())
                            if (n <= 0) break
                            filled += n
                        }
                        if (filled <= 0) break
                        out.write(buf, 0, filled)
                        pos += filled
                        // 整块读满才放大：短读时保持小块，避免在抖动链路上反复等大块
                        if (filled >= want) chunk = (chunk * 2).coerceAtMost(MAX_CACHE_CHUNK)
                        else if (total <= 0) break // 长度未知时短读视为 EOF
                    }
                }
                if (aborted) {
                    tmp.delete()
                    return@withContext false
                }
            } finally {
                suspendCatching { access.close() }
            }
            val target = fileFor(song.id)
            if (tmp.exists() && tmp.length() > 0) {
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                enforceLimit()
                true
            } else {
                tmp.delete()
                false
            }
        } finally {
            suspendCatching { browser.close() }
        }
    }

    /** 批量缓存改为小并发（3 路）：串行缓存多首歌时等待时间过长。 */
    suspend fun cachePlaylist(songs: List<Song>): Int = withContext(Dispatchers.IO) {
        val pending = songs.filter { it.isNas && cachedFile(it.id) == null }
        coroutineScope {
            pending.chunked(3).sumOf { batch ->
                batch.map { song ->
                    async(Dispatchers.IO) {
                        suspendCatching { if (cacheSong(song)) 1 else 0 }.getOrDefault(0)
                    }
                }.awaitAll().sum()
            }
        }
    }

    fun cachedSizeBytes(): Long = (dir.listFiles()?.sumOf { it.length() } ?: 0L)

    fun cachedCount(): Int = dir.listFiles()?.count { it.name.endsWith(".cache") } ?: 0

    /** 按 LRU 删除最久未访问的缓存文件，直到不超过设置的上限。 */
    fun enforceLimit() {
        val limit = cachedLimitMb.coerceAtLeast(16) * 1024L * 1024L
        val files = dir.listFiles()?.filter { it.name.endsWith(".cache") }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            total -= file.length()
            runCatching { file.delete() }
        }
    }

    /**
     * 清空缓存。[keepSongId] 指向的歌曲缓存文件会保留。
     *
     * 正在播放的 NAS 歌曲用的是 `file://` 缓存文件，若一并删掉，播放器下一秒就读不到数据，
     * 表现为「清缓存把正在放的歌搞停了」。UI 会传入当前曲目 id 以跳过它。
     */
    fun clear(keepSongId: String? = null) {
        val keepPath = keepSongId?.let { fileFor(it).absolutePath }
        dir.listFiles()?.forEach { file ->
            if (keepPath != null && file.absolutePath == keepPath) return@forEach
            runCatching { file.delete() }
        }
    }
}
