package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.extensionOf
import com.nasmusic.player.core.isAudioFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 在线专辑封面：网易云（中文曲库）与 iTunes（国际曲库）双源并行，
 * 命中后把图片缓存到应用私有目录，之后离线也能显示。
 * 所有请求短超时、结果按歌名校验、失败源短时间内不再重试。
 */
class CoverFetcher(context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
        .build()

    private val dir = File(context.filesDir, "covers").apply { mkdirs() }
    private val failed = ConcurrentHashMap<String, Long>()

    private fun fileFor(songId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(songId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)
        return File(dir, "$name.img")
    }

    /** 已缓存的封面，供切歌时立即显示，不发起网络请求。 */
    fun cached(songId: String): String? = fileFor(songId)
        .takeIf { it.exists() && it.length() > 1024L }
        ?.let { Uri.fromFile(it).toString() }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        failed.clear()
    }

    fun forget(songId: String) {
        runCatching { fileFor(songId).delete() }
        failed.remove(songId)
    }

    /**
     * 返回本地 file:// URI；已缓存直接返回，未命中则并行查询两个源并下载。
     * 失败返回 null，由上层回退到动态占位，不影响播放。
     */
    suspend fun fetch(song: Song, force: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!force) cached(song.id)?.let { return@withContext it }
        val lastFail = failed[song.id] ?: 0L
        if (!force && System.currentTimeMillis() - lastFail < FAIL_COOLDOWN_MS) return@withContext null

        val query = cleanTitle(song.title)
        if (query.isBlank()) return@withContext null
        val artist = song.artist.trim()

        val urls = coroutineScope {
            listOf(
                async { runCatching { neteaseUrl(query, artist) }.getOrNull() },
                async { runCatching { itunesUrl(query, artist) }.getOrNull() }
            ).awaitAll().filterNotNull()
        }
        // 网易云结果优先：中文曲库封面匹配更准
        for (entry in urls) {
            val bytes = runCatching { download(entry.first) }.getOrNull() ?: continue
            if (bytes.size < 1024) continue
            runCatching {
                val file = fileFor(song.id)
                file.writeBytes(bytes)
                failed.remove(song.id)
                Uri.fromFile(file).toString()
            }.getOrNull()?.let { return@withContext it }
        }
        failed[song.id] = System.currentTimeMillis()
        null
    }

    private fun neteaseUrl(query: String, artist: String): Pair<String, String>? {
        val keyword = if (artist.isBlank()) query else "$query $artist"
        val url = "https://music.163.com/api/search/get?type=1&s=${enc(keyword)}&limit=8&offset=0"
        val songs = JSONObject(get(url, "https://music.163.com"))
            .optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (!matches(query, name)) continue
            val pic = o.optJSONObject("album")?.optString("picUrl").orEmpty()
                .ifBlank { o.optJSONArray("artists")?.optJSONObject(0)?.optString("picUrl").orEmpty() }
            if (pic.startsWith("http")) return pic to name
        }
        return null
    }

    private fun itunesUrl(query: String, artist: String): Pair<String, String>? {
        val keyword = if (artist.isBlank()) query else "$query $artist"
        val url = "https://itunes.apple.com/search?term=${enc(keyword)}&entity=song&limit=8"
        val results = JSONObject(get(url)).optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val name = o.optString("trackName")
            if (!matches(query, name)) continue
            val art = o.optString("artworkUrl100")
            if (art.startsWith("http")) {
                return art.replace("100x100bb", "600x600bb").replace("100x100", "600x600") to name
            }
        }
        return null
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) NasMusic/1.7")
            .header("Accept", "image/*,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val type = response.header("Content-Type").orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image") && !type.contains("octet-stream")) {
                throw IllegalStateException("not an image: $type")
            }
            return response.body?.bytes() ?: throw IllegalStateException("empty body")
        }
    }

    private fun get(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) NasMusic/1.7")
            .header("Accept", "application/json, text/plain, */*")
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun matches(query: String, result: String): Boolean {
        val q = norm(query)
        val r = norm(result)
        if (q.isBlank() || r.isBlank()) return false
        return r == q || r.contains(q) || q.contains(r)
    }

    private fun norm(value: String): String = value.lowercase(Locale.ROOT)
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
        const val FAIL_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
