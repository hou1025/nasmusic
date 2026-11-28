package com.nasmusic.player.data

import com.nasmusic.player.core.Song
import com.nasmusic.player.core.suspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 曲库元信息批量预下载：扫描入库后把缺歌词/缺封面的歌曲在后台补齐，
 * 这样播放时直接读本地文件，不再等网络，切歌响应更快。
 *
 * 三条保护，避免预下载本身变成新的卡顿源：
 * 1. 串行处理，每首之间留间隔，不并发轰炸歌词/封面服务；
 * 2. shouldYield 回调：正在播放且缓冲不足时暂停，把带宽让给音频；
 * 3. 已有歌词/封面的歌曲直接跳过，绝不重复下载。
 */
class MetadataPrefetcher(
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val lyricsFetcher: LyricsFetcher,
    private val lyricsStore: LyricsStore,
    private val coverFetcher: CoverFetcher,
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
) {
    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun cancel() {
        job?.cancel()
        job = null
    }

    /**
     * @param shouldYield 返回 false 时暂停下载（例如音频缓冲不足），用于给播放让路
     */
    fun start(
        enableLyrics: Boolean,
        enableCover: Boolean,
        shouldYield: () -> Boolean = { true },
        onSongDone: (Song) -> Unit = {}
    ) {
        if (!enableLyrics && !enableCover) return
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val all = runCatching { musicRepository.allSongs.first() }.getOrDefault(emptyList())
            // 只处理有歌词文件 URI 缺失，或本地封面缓存缺失的歌曲；已有的跳过
            val pending = all.filter { song ->
                (enableLyrics && song.lyricsUri.isNullOrBlank()) ||
                    (enableCover && song.artworkUri.isNullOrBlank() && coverFetcher.cached(song.id) == null)
            }
            val total = pending.size
            if (total == 0) return@launch
            onProgress(0, total)
            for ((index, song) in pending.withIndex()) {
                if (!isActive) break
                // 给播放让路：缓冲不足时等待，不抢音频带宽
                while (isActive && !shouldYield()) delay(500)
                if (!isActive) break

                val wasLyrics = song.lyricsUri.isNullOrBlank()
                val wasCover = song.artworkUri.isNullOrBlank() && coverFetcher.cached(song.id) == null

                if (enableLyrics && wasLyrics) prefetchLyrics(song)
                if (enableCover && wasCover) prefetchCover(song)

                onSongDone(song)
                onProgress(index + 1, total)
                // 显式让出调度，避免整批任务占满单线程调度器
                delay(120)
            }
            onProgress(total, total)
        }
    }

    /** 与播放页自动下载同一套逻辑：竞速搜索 → 最佳匹配 → 落盘 → 写库。失败静默。 */
    private suspend fun prefetchLyrics(song: Song) = withContext(Dispatchers.IO) {
        val candidates = suspendCatching {
            lyricsFetcher.searchFast(
                song.title, song.artist, (song.durationMs / 1000L).toInt(),
                LyricsSource.AUTO
            )
        }.getOrNull().orEmpty()
        val best = lyricsFetcher.bestMatch(
            candidates, song.title, song.artist, (song.durationMs / 1000L).toInt()
        ) ?: return@withContext
        val ready = suspendCatching { lyricsFetcher.prefetch(listOf(best)).firstOrNull() ?: best }
            .getOrDefault(best)
        val text = runCatching { lyricsFetcher.fetch(ready) }.getOrNull()
        if (text.isNullOrBlank()) return@withContext
        val uri = lyricsStore.save(song.id, text)
        musicRepository.setLyricsUri(song.id, uri)
    }

    /** 封面：先查缓存（跳过已有），再双源匹配落盘并回填数据库。失败静默。 */
    private suspend fun prefetchCover(song: Song) = withContext(Dispatchers.IO) {
        if (coverFetcher.cached(song.id) != null) return@withContext
        val uri = suspendCatching { coverFetcher.fetch(song) }.getOrNull() ?: return@withContext
        musicRepository.setArtworkUri(song.id, uri)
    }
}
