package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import com.nasmusic.player.core.SettingsStore
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.parseNasUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** NAS 歌曲缓存：默认在线播放，按需缓存；LRU 自动清理，缓存文件不写入系统媒体库。 */
class NasCacheManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val nasRepository: NasRepository
) {
    private val dir: File = File(context.cacheDir, "nas_audio").apply { mkdirs() }

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
        return cachedFile(song.id)?.let { Uri.fromFile(it).toString() } ?: song.uri
    }

    fun isCached(song: Song): Boolean = song.isNas && cachedFile(song.id) != null

    suspend fun cacheSong(song: Song): Boolean = withContext(Dispatchers.IO) {
        if (!song.isNas) return@withContext false
        val (sourceId, path) = parseNasUri(song.uri) ?: return@withContext false
        val browser = nasRepository.browserFor(sourceId)
        val tmp = File(dir, Integer.toHexString(song.id.hashCode()) + ".tmp")
        try {
            val access = browser.open(path)
            try {
                FileOutputStream(tmp).use { out ->
                    // 64KB 缓冲会让飞牛/WebDAV 的 Range 请求多 8 倍；512KB 显著降低请求数
                    access.asInputStream().use { input -> input.copyTo(out, 512 * 1024) }
                }
            } finally {
                runCatching { access.close() }
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
            runCatching { browser.close() }
        }
    }

    /** 批量缓存改为小并发（3 路）：串行缓存多首歌时等待时间过长。 */
    suspend fun cachePlaylist(songs: List<Song>): Int = withContext(Dispatchers.IO) {
        val pending = songs.filter { it.isNas && cachedFile(it.id) == null }
        coroutineScope {
            pending.chunked(3).sumOf { batch ->
                batch.map { song ->
                    async(Dispatchers.IO) {
                        runCatching { if (cacheSong(song)) 1 else 0 }.getOrDefault(0)
                    }
                }.awaitAll().sum()
            }
        }
    }

    fun cachedSizeBytes(): Long = (dir.listFiles()?.sumOf { it.length() } ?: 0L)

    fun cachedCount(): Int = dir.listFiles()?.count { it.name.endsWith(".cache") } ?: 0

    /** 按 LRU 删除最久未访问的缓存文件，直到不超过设置的上限。 */
    fun enforceLimit() {
        val limitMb = runBlocking(Dispatchers.IO) { settings.settings.first().cacheLimitMb }
        val limit = limitMb.coerceAtLeast(16) * 1024L * 1024L
        val files = dir.listFiles()?.filter { it.name.endsWith(".cache") }
            ?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            total -= file.length()
            runCatching { file.delete() }
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }
}
