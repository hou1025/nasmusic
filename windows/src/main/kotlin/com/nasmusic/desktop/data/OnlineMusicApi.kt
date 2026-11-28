package com.nasmusic.desktop.data

import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.model.SourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * 凭据加密封装接口。
 *
 * 生产实现应替换为操作系统凭据库（Windows Credential Manager / DPAPI）。
 * [Base64CredentialCipher] 只是避免明文落盘的混淆，不是真正的加密。
 */
interface CredentialCipher {
    fun encrypt(plain: String): String
    fun decrypt(cipher: String): String
}

/** Base64 混淆封装：仅用于骨架，切勿用于生产环境的密码保护。 */
class Base64CredentialCipher : CredentialCipher {
    override fun encrypt(plain: String): String =
        Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))

    override fun decrypt(cipher: String): String =
        String(Base64.getDecoder().decode(cipher), Charsets.UTF_8)
}

/**
 * Subsonic 兼容在线音源客户端（Java 11 HttpClient，无第三方网络库）。
 *
 * 认证采用 Subsonic 1.13+ 的 enc token：p=enc:hex(md5(password+salt))，
 * 这样凭据可以安全放进 URL，JavaFX Media 也能直接拉取音频流（无需自定义请求头）。
 */
class OnlineMusicApi(
    rawBaseUrl: String,
    private val username: String,
    private val password: String,
    private val sourceId: String = ""
) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** 每次请求生成随机 salt + token，Subsonic 服务端据此校验。 */
    private fun authQuery(): String {
        val salt = java.util.UUID.randomUUID().toString().replace("-", "")
        val token = md5(password + salt)
        return listOf(
            "u=${enc(username)}",
            "p=enc:$token",
            "s=$salt",
            "v=1.16.1",
            "c=NasMusicDesktop",
            "f=json"
        ).joinToString("&")
    }

    private fun endpoint(subPath: String): String = "$baseUrl/rest/$subPath"

    private fun get(subPath: String, query: String): String {
        if (baseUrl.isEmpty()) throw IOException("未填写 Subsonic 服务器地址")
        val uri = URI.create("${endpoint(subPath)}?${authQuery()}&$query")
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IOException("Subsonic 请求失败：HTTP ${response.statusCode()}")
        }
        return response.body()
    }

    /** 连通性探测：/rest/ping.view 的 status 必须为 ok。 */
    fun testConnection(): Result<String> = runCatching {
        val resp = json.parseToJsonElement(get("ping.view", "")).jsonObject["subsonic-response"]?.jsonObject
            ?: throw IOException("响应中缺少 subsonic-response 字段")
        val status = resp.str("status")
        if (status != "ok") {
            val msg = (resp["error"] as? JsonObject)?.str("message").orEmpty().ifBlank { "未知错误" }
            throw IOException("Subsonic 返回 $status：$msg")
        }
        "Subsonic 连接成功（服务端 v${resp.str("version").ifBlank { "?" }}）"
    }

    /** 搜索歌曲：search3.view，返回 song 数组。offset 用于分页拉全库。 */
    fun search(query: String, limit: Int = 100, offset: Int = 0): Result<List<Song>> = runCatching {
        val body = get(
            "search3.view",
            "query=${enc(query)}&songCount=$limit&albumCount=0&artistCount=0&offset=$offset"
        )
        val resp = json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
            ?: throw IOException("响应中缺少 subsonic-response 字段")
        if (resp.str("status") != "ok") {
            throw IOException(resp["error"]?.jsonObject?.str("message").orEmpty().ifBlank { "搜索失败" })
        }
        val result = resp["searchResult3"] as? JsonObject ?: return@runCatching emptyList()
        val array = result["song"] as? JsonArray ?: JsonArray(emptyList())
        array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            songFromJson(o)
        }
    }

    private fun songFromJson(o: JsonObject): Song {
        val rawId = o.str("id")
        if (rawId.isEmpty()) return Song(id = "", title = "未知曲目", uri = "", sourceType = SourceType.ONLINE)
        val path = o.str("path")
        val coverId = o.str("coverArt").ifBlank { null }
        return Song(
            id = "subsonic:$sourceId:$rawId",
            title = o.str("title").ifBlank { "未知曲目" },
            uri = streamUrl(rawId),
            sourceType = SourceType.ONLINE,
            artist = o.str("artist").ifBlank { "未知歌手" },
            album = o.str("album"),
            durationMs = o.str("duration").toLongOrNull()?.times(1000) ?: 0L,
            sourceId = sourceId,
            folder = path.substringBeforeLast('/', ""),
            artworkUri = coverId?.let { coverUrl(it) },
            sizeBytes = o.str("size").toLongOrNull() ?: 0L,
            mimeType = o.str("contentType")
        )
    }

    /** 音频流地址：凭据已包含在 query 中，可直接交给播放器。 */
    fun streamUrl(songId: String): String =
        "${endpoint("stream.view")}?${authQuery()}&id=${enc(songId)}"

    /** 封面地址。 */
    fun coverUrl(coverId: String, size: Int = 300): String =
        "${endpoint("getCoverArt.view")}?${authQuery()}&id=${enc(coverId)}&size=$size"

    companion object {
        fun normalizeBase(raw: String): String = raw.trim().trimEnd('/')
    }
}

private fun JsonObject.str(key: String): String =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

private fun md5(input: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v < 16) append('0')
            append(Integer.toHexString(v))
        }
    }
}
