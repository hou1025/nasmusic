package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import com.nasmusic.player.core.extensionOf
import com.nasmusic.player.core.isAudioFile
import com.nasmusic.player.core.suspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class LyricsSource(val key: String, val label: String) {
    AUTO("AUTO", "自动（双源）"),
    LRCLIB("LRCLIB", "LRCLIB"),
    NETEASE("NETEASE", "网易云音乐");

    companion object {
        fun fromKey(key: String): LyricsSource = values().firstOrNull { it.key == key } ?: AUTO
    }
}

data class LyricCandidate(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val source: LyricsSource,
    val timed: Boolean,
    /** LRCLIB 直接随搜索结果返回歌词；网易云需要二次请求，命中预取后也会写入这里。 */
    val lrc: String? = null,
    val remoteId: String = "",
    /** 网易云搜索结果自带的专辑封面地址：下载歌词时可直接用它取封面，省掉一次搜索。 */
    val coverUrl: String = ""
) {
    val summary: String
        get() = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { source.label } + if (timed) "" else " · 纯文本"
}

private data class CachedSearch(val at: Long, val list: List<LyricCandidate>)

/**
 * 在线歌词源：LRCLIB（开源、同步 LRC 为主）与网易云音乐（中文曲库更全）。
 *
 * 速度策略：
 * 1. 双源并行，AUTO 下慢源不再拖累快源；自动下载走「竞速」，拿到足够匹配立即返回。
 * 2. 连接/读取超时收紧到 3/5 秒，连接复用，失败源熔断 3 分钟。
 * 3. 搜索结果内存缓存 10 分钟，同一首歌切回来零网络请求。
 * 4. 网易云候选的歌词在搜索后预取，自动下载省掉一次额外往返。
 */
class LyricsFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    private val searchCache = ConcurrentHashMap<String, CachedSearch>()
    private val lrcCache = ConcurrentHashMap<String, String>()
    private val failedUntil = ConcurrentHashMap<LyricsSource, Long>()

    /** 手动搜索：并行取全部源，结果更全；命中缓存时瞬时返回。 */
    suspend fun search(
        title: String,
        artist: String,
        durationSec: Int,
        preferred: LyricsSource
    ): List<LyricCandidate> = withContext(Dispatchers.IO) {
        val query = cleanTitle(title)
        if (query.isBlank()) return@withContext emptyList()
        val sources = sourcesFor(preferred)
        val key = cacheKey(sources, query, artist)
        searchCache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL }
            ?.let { return@withContext it.list }

        val found = coroutineScope {
            sources.map { source ->
                async { searchOne(source, query, artist, allowBreaker = sources.size > 1) }
            }.awaitAll().flatten()
        }
        val result = normalize(found, query, artist, durationSec)
        if (result.isNotEmpty()) {
            searchCache[key] = CachedSearch(System.currentTimeMillis(), result)
            trimCache()
        }
        result
    }

    /**
     * 自动下载专用：谁先返回足够匹配的结果就用谁，不再等另一个源。
     * 单个源无结果或全部不匹配时，最多再等 6 秒收集剩余结果。
     */
    suspend fun searchFast(
        title: String,
        artist: String,
        durationSec: Int,
        preferred: LyricsSource
    ): List<LyricCandidate> = withContext(Dispatchers.IO) {
        val query = cleanTitle(title)
        if (query.isBlank()) return@withContext emptyList()
        val sources = sourcesFor(preferred)
        val key = cacheKey(sources, query, artist)
        searchCache[key]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL }
            ?.let { return@withContext it.list }

        val channel = Channel<List<LyricCandidate>>(sources.size)
        val jobs = sources.map { source ->
            launch {
                val list = searchOne(source, query, artist, allowBreaker = sources.size > 1)
                try {
                    channel.send(list)
                } catch (_: CancellationException) {
                    // 超时取消是正常控制流，不再冒泡
                }
            }
        }
        // acc 提到超时块外：超时只应停止"继续等待剩余源"，不能丢掉已经收到的候选，
        // 否则慢源超时时快源已返回的结果会被 ?: emptyList() 全部抹掉。
        var acc = emptyList<LyricCandidate>()
        withTimeoutOrNull(FAST_TIMEOUT_MS) {
            for (i in sources.indices) {
                val list = channel.receive()
                if (list.isNotEmpty()) {
                    acc = (acc + list).distinctBy { it.key }
                    if (acc.any { score(it, query, artist, durationSec) >= MATCH_THRESHOLD }) break
                }
            }
        }
        val collected = acc
        // 先取消生产者再关闭 channel：避免仍在 send 的协程抛 ClosedSendChannelException 冒泡
        jobs.forEach { it.cancel() }
        channel.close()

        val result = normalize(collected, query, artist, durationSec)
        if (result.isNotEmpty()) {
            searchCache[key] = CachedSearch(System.currentTimeMillis(), result)
            trimCache()
        }
        result
    }

    /** 自动下载时只有当匹配度达标才采用，避免给歌曲配上错误歌词。 */
    fun bestMatch(
        candidates: List<LyricCandidate>,
        title: String,
        artist: String,
        durationSec: Int
    ): LyricCandidate? {
        val query = cleanTitle(title)
        return candidates.map { it to score(it, query, artist, durationSec) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    /** 预取首个网易云候选的歌词，写回 lrc 字段，让后续 fetch 零网络。 */
    suspend fun prefetch(candidates: List<LyricCandidate>): List<LyricCandidate> =
        withContext(Dispatchers.IO) {
            val target = candidates.firstOrNull {
                it.source == LyricsSource.NETEASE && it.lrc.isNullOrBlank()
            } ?: return@withContext candidates
            val text = suspendCatching { fetch(target) }.getOrNull()
                ?: return@withContext candidates
            candidates.map { if (it.key == target.key) it.copy(lrc = text) else it }
        }

    suspend fun fetch(candidate: LyricCandidate): String? = withContext(Dispatchers.IO) {
        candidate.lrc?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        lrcCache[candidate.key]?.let { return@withContext it }
        if (candidate.source == LyricsSource.NETEASE && candidate.remoteId.isNotBlank()) {
            val lrc = fetchLyricById(candidate.remoteId)
            if (lrc.isNotBlank()) {
                lrcCache[candidate.key] = lrc
                return@withContext lrc
            }
        }
        null
    }

    private fun fetchLyricById(id: String): String {
        val url = "https://music.163.com/api/song/lyric?id=${enc(id)}&lv=1&kv=1&tv=-1"
        return JSONObject(get(url, "https://music.163.com"))
            .optJSONObject("lrc")?.optString("lyric").orEmpty()
    }

    private fun sourcesFor(preferred: LyricsSource): List<LyricsSource> =
        if (preferred == LyricsSource.AUTO) listOf(LyricsSource.LRCLIB, LyricsSource.NETEASE)
        else listOf(preferred)

    /** 单源查询：熔断期内直接跳过，失败登记冷却，避免每次切歌都白等超时。 */
    private fun searchOne(
        source: LyricsSource,
        query: String,
        artist: String,
        allowBreaker: Boolean
    ): List<LyricCandidate> {
        if (allowBreaker && (failedUntil[source] ?: 0L) > System.currentTimeMillis()) return emptyList()
        return try {
            val list = if (source == LyricsSource.LRCLIB) searchLrclib(query, artist)
            else searchNetease(query, artist)
            failedUntil.remove(source)
            list
        } catch (e: CancellationException) {
            // 取消绝不能被计入失败熔断，否则切歌取消会误伤后续搜索
            throw e
        } catch (_: Exception) {
            if (allowBreaker) failedUntil[source] = System.currentTimeMillis() + BREAKER_MS
            emptyList()
        }
    }

    private fun normalize(
        found: List<LyricCandidate>,
        query: String,
        artist: String,
        durationSec: Int
    ): List<LyricCandidate> = found.distinctBy { it.key }
        .sortedByDescending { score(it, query, artist, durationSec) }

    private fun trimCache() {
        if (searchCache.size <= 64) return
        val now = System.currentTimeMillis()
        searchCache.entries.removeIf { now - it.value.at > CACHE_TTL }
    }

    private fun cacheKey(sources: List<LyricsSource>, query: String, artist: String): String =
        sources.joinToString("+") { it.key } + "|" + norm(query) + "|" + norm(artist)

    private fun searchLrclib(query: String, artist: String): List<LyricCandidate> {
        var url = "https://lrclib.net/api/search?track_name=${enc(query)}"
        if (artist.isNotBlank()) url += "&artist_name=${enc(artist)}"
        val array = JSONArray(get(url))
        val result = mutableListOf<LyricCandidate>()
        for (i in 0 until minOf(array.length(), 10)) {
            val o = array.optJSONObject(i) ?: continue
            val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() }
            val text = synced ?: plain ?: continue
            result += LyricCandidate(
                key = "lrclib:${o.optLong("id")}",
                title = o.optString("trackName").ifBlank { query },
                artist = o.optString("artistName"),
                album = o.optString("albumName"),
                durationSec = o.optDouble("duration", 0.0).toInt(),
                source = LyricsSource.LRCLIB,
                timed = synced != null,
                lrc = text
            )
        }
        return result
    }

    private fun searchNetease(query: String, artist: String): List<LyricCandidate> {
        val keyword = if (artist.isBlank()) query else "$query $artist"
        val url = "https://music.163.com/api/search/get?type=1&s=${enc(keyword)}&limit=8&offset=0"
        val root = JSONObject(get(url, "https://music.163.com"))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        val result = mutableListOf<LyricCandidate>()
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (id <= 0) continue
            val artists = o.optJSONArray("artists")
            val artistName = if (artists != null && artists.length() > 0)
                artists.optJSONObject(0)?.optString("name").orEmpty() else ""
            val album = o.optJSONObject("album")
            result += LyricCandidate(
                key = "netease:$id",
                title = o.optString("name"),
                artist = artistName,
                album = album?.optString("name").orEmpty(),
                durationSec = (o.optLong("duration", 0L) / 1000L).toInt(),
                source = LyricsSource.NETEASE,
                timed = true,
                remoteId = id.toString(),
                // 同一条搜索结果里的专辑图：下载歌词时顺带落盘，不额外发一次搜索
                coverUrl = album?.optString("picUrl")?.takeIf { it.startsWith("http") }.orEmpty()
            )
        }
        // 与搜索在同一个后台任务里顺带取首个候选的歌词：
        // 自动下载因此只需要一轮并行网络，不再额外多一次往返。
        val first = result.firstOrNull()
        if (first != null) {
            val text = runCatching { fetchLyricById(first.remoteId) }.getOrNull()
            if (!text.isNullOrBlank()) {
                result[0] = first.copy(lrc = text)
                lrcCache[first.key] = text
            }
        }
        return result
    }

    private fun get(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "NasMusic/1.7 (Android local music player)")
            .header("Accept", "application/json, text/plain, */*")
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("歌词服务返回 HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun score(c: LyricCandidate, title: String, artist: String, durationSec: Int): Int {
        val t = norm(c.title)
        val q = norm(title)
        val a = norm(artist)
        var value = 0
        when {
            t == q -> value += 60
            t.contains(q) || q.contains(t) -> value += 35
            else -> value -= 15
        }
        if (a.isNotBlank() && (norm(c.artist).contains(a) || a.contains(norm(c.artist)))) value += 25
        if (durationSec > 0 && c.durationSec > 0) {
            val diff = abs(durationSec - c.durationSec)
            if (diff <= 5) value += 15 else if (diff <= 15) value += 8 else if (diff > 45) value -= 12
        }
        if (c.timed) value += 10 else value -= 8
        return value
    }

    private fun norm(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[()\\[\\]【】（）,，.。'\"`!！?？\\-_/]"), "")
            .replace(Regex("\\s+"), "")

    private fun cleanTitle(value: String): String {
        val base = if (isAudioFile(value) && extensionOf(value).isNotBlank())
            value.substringBeforeLast('.') else value
        return base
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]|【[^】]*】|（[^）]*）"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val CACHE_TTL = 10 * 60 * 1000L
        const val BREAKER_MS = 3 * 60 * 1000L
        const val FAST_TIMEOUT_MS = 6_000L
        const val MATCH_THRESHOLD = 45
    }
}

/** 下载的歌词只写入应用私有目录，不污染系统媒体库；卸载即清除。 */
class LyricsStore(context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }

    private fun fileFor(songId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(songId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)
        return File(dir, "$name.lrc")
    }

    fun save(songId: String, text: String): String {
        val file = fileFor(songId)
        file.writeText(text, Charsets.UTF_8)
        return Uri.fromFile(file).toString()
    }

    fun delete(songId: String) { fileFor(songId).delete() }

    fun count(): Int = dir.listFiles()?.count { it.extension == "lrc" } ?: 0

    fun clear() { dir.listFiles()?.forEach { runCatching { it.delete() } } }
}
