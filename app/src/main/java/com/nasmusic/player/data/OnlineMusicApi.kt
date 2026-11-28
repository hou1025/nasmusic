package com.nasmusic.player.data

import android.net.Uri
import com.nasmusic.player.core.HttpStatusException
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.SourceType
import com.nasmusic.player.core.readLimitedBytes
import com.nasmusic.player.core.suspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Subsonic 兼容协议客户端（Navidrome / Gonic / Airsonic / Subsonic 通用）。
 *
 * 契约（UI 组按这些签名调用）：
 *  - testConnection(baseUrl, username, password): Result<String>  成功返回服务器版本
 *  - search(query, source): List<Song>
 *  - listRoot(source): List<Song>
 *  - streamUrl(source, songId): String     可直接交给 Media3 的 https 直链
 *  - coverUrl(source, coverId): String
 *
 * 所有网络请求跑在 Dispatchers.IO，复用全局 OkHttp 连接池与超时设置。
 */
class OnlineMusicApi(private val crypto: NasCredentialsCrypto) {

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_NAME = "NasMusic"
        private const val DEFAULT_SONG_COUNT = 500
        // 分页拉取全库的上限：异常服务端即使返回错误 total 也不会无限循环 / 打爆内存
        private const val MAX_SONGS = 5000
    }

    /**
     * 去尾斜杠、自动补 scheme。
     * 保留用户显式填写的协议：Subsonic 大量部署在 http（DDNS + 端口映射的公网 NAS
     * 往往只开放 http），强制把公网 http 改写为 https 会让这类地址必然连接失败。
     * 仅在用户完全没写 scheme 时，才默认补 https 作为安全默认值。
     */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isEmpty()) throw IOException("请输入在线音源服务地址")
        if (!s.contains("://")) s = "https://$s"
        val uri = Uri.parse(s)
        if (uri.scheme != "http" && uri.scheme != "https") {
            throw IOException("在线音源地址格式不正确：$s")
        }
        return s.trimEnd('/')
    }

    /**
     * 连通性测试：GET /rest/ping.view。成功返回服务器版本，失败 Result.failure 带明确文案。
     * 取消信号会被 suspendCatching 原样抛出，不会被当成普通失败。
     */
    suspend fun testConnection(baseUrl: String, username: String, password: String): Result<String> =
        suspendCatching {
            withContext(Dispatchers.IO) {
                if (username.isBlank()) throw IOException("请输入在线音源用户名")
                if (password.isEmpty()) throw IOException("请输入在线音源密码")
                val base = normalizeBaseUrl(baseUrl)
                val url = buildUrl(base, "ping", username, password)
                val request = Request.Builder().url(url).get().build()
                NasHttp.client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw HttpStatusException(
                            response.code,
                            "连接失败（${Uri.parse(url).path.orEmpty()}）：HTTP ${response.code}"
                        )
                    }
                    val resp = responseObject(body, "ping")
                    if (!resp.optString("status").equals("ok", true)) {
                        val err = resp.optJSONObject("error")
                        val msg = err?.optString("message").orEmpty().ifBlank { "未知错误" }
                        throw IOException("服务器拒绝连接：$msg（code=${err?.optInt("code") ?: -1}）")
                    }
                    resp.optString("version").ifBlank { "Subsonic 服务可用" }
                }
            }
        }

    /** 关键字搜索：/rest/search3.view?query=...&songCount=500。 */
    suspend fun search(query: String, source: NasSource): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        querySongs(
            source, "search3",
            mapOf("query" to query.trim(), "songCount" to DEFAULT_SONG_COUNT.toString())
        )
    }

    /**
     * 根目录曲目：优先空 query 拉全库；部分服务端（如 Airsonic）空 query 不返回结果，
     * 再退回通配符查询。两条路径都按 offset 分页拉到服务端 total（见 queryAllSongs），
     * 不再只取前 500 首，避免曲库 >500 首时第 501 首之后永久不同步。
     */
    suspend fun listRoot(source: NasSource): List<Song> = withContext(Dispatchers.IO) {
        val all = queryAllSongs(source, "")
        if (all.isNotEmpty()) all else queryAllSongs(source, "*")
    }

    /** 可直接交给 Media3 DefaultDataSource 播放的完整 URL（凭据作为查询参数）。 */
    fun streamUrl(source: NasSource, songId: String): String {
        if (songId.isBlank()) throw IOException("缺少在线曲目 ID")
        return buildUrl(
            normalizeBaseUrl(source.baseUrl), "stream", source.username, source.plainPassword(),
            mapOf("id" to songId)
        )
    }

    fun coverUrl(source: NasSource, coverId: String, size: Int = 300): String {
        if (coverId.isBlank()) throw IOException("缺少在线封面 ID")
        return buildUrl(
            normalizeBaseUrl(source.baseUrl), "getCoverArt", source.username, source.plainPassword(),
            mapOf("id" to coverId, "size" to size.toString())
        )
    }

    /** 封面限流下载：读满 2MB+1 即中断丢弃，避免异常大响应打爆内存。 */
    suspend fun coverBytes(source: NasSource, coverId: String, size: Int = 600): ByteArray? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(coverUrl(source, coverId, size)).get().build()
            NasHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val stream = response.body?.byteStream() ?: return@withContext null
                readLimitedBytes(stream)
            }
        }

    /** 把保存音源时的明文密码（来自 NasConfig）包装成可复用 NasSource。 */
    fun sourceOf(config: NasConfig): NasSource = NasSource(
        id = config.id,
        name = "在线音源",
        type = SourceType.ONLINE,
        baseUrl = normalizeBaseUrl(config.baseUrl),
        username = config.username,
        passwordEnc = crypto.encrypt(config.password)
    )

    /** 供 NasBrowser.open / 缓存逻辑使用的随机访问读取。 */
    fun randomAccess(source: NasSource, songId: String): NasRandomAccess = object : NasRandomAccess {
        override val length: Long by lazy { probeLength(source, songId) }
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            readRange(source, songId, position, buffer, offset, length)
        override fun close() = Unit
    }

    // ---------- 内部实现 ----------

    /** 用 Android Uri 拼 URL，自动处理参数编码；OkHttp Request.url(String) 直接接受结果。 */
    private fun buildUrl(
        base: String,
        endpoint: String,
        username: String,
        password: String,
        extra: Map<String, String> = emptyMap()
    ): String {
        val builder = Uri.parse(base).buildUpon()
            .appendPath("rest")
            .appendPath("$endpoint.view")
            .appendQueryParameter("u", username)
            .appendQueryParameter("p", password)
            .appendQueryParameter("v", API_VERSION)
            .appendQueryParameter("c", CLIENT_NAME)
            .appendQueryParameter("f", "json")
        extra.forEach { (k, v) -> builder.appendQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun NasSource.plainPassword(): String = crypto.decrypt(passwordEnc)

    /** 单页结果：songs 已按 id 去重，total 为服务端报告的命中总数（-1 表示未提供）。 */
    private data class SongPage(val songs: List<Song>, val total: Int)

    /** 兼容原有单页调用（关键字搜索仍只需首页）。 */
    private fun querySongs(
        source: NasSource,
        endpoint: String,
        extra: Map<String, String>
    ): List<Song> = querySongsPage(source, endpoint, extra).songs

    /**
     * search3 的单次请求：在原有解析基础上额外读取 searchResult3.total，供分页判断是否取完。
     */
    private fun querySongsPage(
        source: NasSource,
        endpoint: String,
        extra: Map<String, String>
    ): SongPage {
        val url = buildUrl(
            normalizeBaseUrl(source.baseUrl), endpoint, source.username, source.plainPassword(), extra
        )
        val request = Request.Builder().url(url).get().build()
        NasHttp.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    response.code,
                    "在线音源请求失败（${Uri.parse(url).path.orEmpty()}）：HTTP ${response.code}"
                )
            }
            val resp = responseObject(body, endpoint)
            if (!resp.optString("status").equals("ok", true)) {
                val err = resp.optJSONObject("error")
                val msg = err?.optString("message").orEmpty().ifBlank { "未知错误" }
                throw IOException("在线音源返回错误（$endpoint）：$msg（code=${err?.optInt("code") ?: -1}）")
            }
            val searchRoot = resp.optJSONObject("searchResult3")
            val array = searchRoot?.optJSONArray("song")
                ?: resp.optJSONArray("song")
                ?: return SongPage(emptyList(), -1)
            val total = searchRoot?.optInt("total", -1) ?: -1
            val result = ArrayList<Song>(array.length())
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val song = parseSong(o, source) ?: continue
                if (seen.add(song.id)) result += song
            }
            return SongPage(result, total)
        }
    }

    /**
     * 按服务端 search3 的 offset / songCount / total 分页拉取某个 query 的全部曲目。
     * 停止条件（任一满足即止）：
     *  1) 本页为空；2) offset 达到/超过服务端 total；3) 服务端未给 total 且本页不足一页；
     *  4) 连续两页没有新增曲目（服务端未按 offset 推进，防死循环）；5) 达到 MAX_SONGS 上限。
     * 用 LinkedHashMap 按 id 去重并保持入库顺序。
     */
    private fun queryAllSongs(source: NasSource, query: String): List<Song> {
        val result = LinkedHashMap<String, Song>()
        var offset = 0
        var total = -1
        var stagnantPages = 0
        while (result.size < MAX_SONGS) {
            val page = querySongsPage(
                source, "search3",
                mapOf(
                    "query" to query,
                    "songCount" to DEFAULT_SONG_COUNT.toString(),
                    "offset" to offset.toString()
                )
            )
            if (page.songs.isEmpty()) break
            if (page.total >= 0) total = page.total
            var added = 0
            for (song in page.songs) {
                if (result.size >= MAX_SONGS) break
                if (!result.containsKey(song.id)) {
                    result[song.id] = song
                    added++
                }
            }
            offset += page.songs.size
            if (added == 0) {
                // 服务端忽略了 offset、持续返回同一批数据：再试一页即退出，避免死循环
                if (++stagnantPages >= 2) break
            } else {
                stagnantPages = 0
            }
            if (total >= 0 && offset >= total) break
            // 服务端未报告 total 时，用「短页」判断是否已到最后一页
            if (total < 0 && page.songs.size < DEFAULT_SONG_COUNT) break
        }
        return result.values.toList()
    }

    private fun responseObject(body: String, endpoint: String): JSONObject {
        if (body.isBlank()) throw IOException("在线音源 $endpoint 返回了空响应")
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("在线音源 $endpoint 返回了无法解析的内容：${body.take(120)}")
        }
        return root.optJSONObject("subsonic-response")
            ?: throw IOException("在线音源 $endpoint 响应缺少 subsonic-response 节点")
    }

    private fun parseSong(o: JSONObject, source: NasSource): Song? {
        val songId = o.optString("id").ifBlank { o.optString("mid") }
        if (songId.isBlank()) return null
        val coverId = o.optString("coverArt").ifBlank { o.optString("parent") }
        val path = o.optString("path")
        val ext = path.substringAfterLast('.', "").lowercase()
        val title = o.optString("title").ifBlank {
            path.substringAfterLast('/').substringBeforeLast('.')
        }.ifBlank { songId }
        val album = o.optString("album")
        val created = o.optLong("created", 0L)
        return Song(
            id = "nas:${source.id}:$songId",
            title = title,
            uri = streamUrl(source, songId),
            sourceType = SourceType.ONLINE,
            artist = o.optString("artist").ifBlank { "未知歌手" },
            album = album.ifBlank { "未知专辑" },
            durationMs = o.optLong("duration", 0L) * 1000L,
            sourceId = source.id,
            folder = album.ifBlank { source.name },
            artworkUri = coverId.takeIf { it.isNotBlank() }?.let { coverUrl(source, it) },
            sizeBytes = o.optLong("size", 0L),
            lastModified = if (created in 1..99_999_999_999L) created * 1000L else created,
            mimeType = when (ext) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "m4a", "mp4", "aac" -> "audio/mp4"
                "ogg", "opus" -> "audio/ogg"
                "ape" -> "audio/ape"
                else -> ""
            },
            sampleRate = o.optInt("samplingRate", 0),
            bitrate = o.optInt("bitRate", 0),
            channels = o.optInt("channels", 0)
        )
    }

    private fun probeLength(source: NasSource, songId: String): Long {
        val url = buildUrl(
            normalizeBaseUrl(source.baseUrl), "stream", source.username, source.plainPassword(),
            mapOf("id" to songId)
        )
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
        NasHttp.client.newCall(request).execute().use { response ->
            requirePartialContent(response, 0L)
            return response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    private fun readRange(
        source: NasSource,
        songId: String,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        if (length <= 0) return 0
        val url = buildUrl(
            normalizeBaseUrl(source.baseUrl), "stream", source.username, source.plainPassword(),
            mapOf("id" to songId)
        )
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-${position + length - 1}")
            .get()
            .build()
        NasHttp.client.newCall(request).execute().use { response ->
            requirePartialContent(response, position)
            val stream = response.body?.byteStream() ?: return -1
            var total = 0
            // 补齐短读：只有流真正结束（n < 0）才认为这次 Range 读取完成
            while (total < length) {
                val n = stream.read(buffer, offset + total, length - total)
                if (n < 0) break
                total += n
            }
            return if (total == 0) -1 else total
        }
    }
}
