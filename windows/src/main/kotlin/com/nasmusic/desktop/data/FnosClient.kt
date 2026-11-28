package com.nasmusic.desktop.data

import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.model.SourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import java.util.UUID

// 以下协议常量与 Android 端 data/FnosApi.kt 保持一致，方便后续共享签名实现。
private const val API_PREFIX = "/music/api/v1"
private const val SIGN_SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
private val CLIENT_KEY: String = run {
    val encoded = intArrayOf(
        143, 253, 140, 143, 137, 139, 253, 141, 148, 248, 138, 141, 139,
        148, 141, 142, 128, 128, 148, 248, 137, 255, 137, 148, 251, 251,
        142, 128, 140, 252, 142, 136, 143, 142, 253, 137
    )
    buildString(encoded.size) { for (v in encoded) append((v xor 185).toChar()) }
}
private val CONNECT_SUFFIXES = listOf("5ddd.com", "fnos.net", "fnofly.com")

/** 解析后的接入地址：局域网 / FN Connect 中继 / 自定义域名。 */
data class FnosEndpoint(
    val baseUrl: String,
    val host: String,
    val isConnect: Boolean,
    val label: String
)

class FnosAuthException : IOException("飞牛音乐登录已过期，请重新登录")

/**
 * 飞牛音乐（fnOS）私有网关客户端骨架。
 *
 * 关键协议（与 Android 端一致）：
 * - 所有请求带 authx 头：nonce/timestamp/sign，sign=md5(salt_path_nonce_ts_payloadHash_clientKey)
 * - GET 的 payloadHash 是规范化 query 的 md5；POST 是 body 的 md5
 * - 登录返回 userToken，后续请求用 Cookie: music-token=...；中继额外带 mode=relay
 * - 签名头里的客户端标识不能用 X-Music-API 版本头，登录握手例外
 *
 * 接口未公开文档，字段兼容 Web 端多个候选名；端点来自公开逆向资料。
 */
class FnosClient(
    rawBaseUrl: String,
    private val username: String,
    private val password: String,
    private val sourceId: String
) {
    private val endpoint: FnosEndpoint = resolve(rawBaseUrl)
    private val relay = endpoint.isConnect
    private val deviceId: String =
        UUID.nameUUIDFromBytes("${endpoint.baseUrl}|$username".toByteArray(Charsets.UTF_8)).toString()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER) // 中继跳内网会泄露 Cookie，禁止自动跟随
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** token 仅存内存；持久化密码请用 CredentialCipher（见 OnlineMusicApi.kt）。 */
    private var token: String? = null

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun cookieHeader(): String? {
        val parts = mutableListOf<String>()
        if (relay) parts += "mode=relay"
        token?.let { parts += "music-token=$it" }
        return parts.joinToString("; ").ifBlank { null }
    }

    /** 构造带签名的请求；range 用于音频流随机读取。 */
    private fun signedRequest(
        method: String,
        subPath: String,
        query: String,
        body: String?,
        withToken: Boolean = true,
        versionHeader: Boolean = true,
        range: String? = null
    ): HttpRequest.Builder {
        val path = API_PREFIX + subPath
        val url = endpoint.baseUrl + path + if (query.isEmpty()) "" else "?$query"
        val builder = HttpRequest.newBuilder(URI.create(url))
            .header("authx", signHeader(method, path, query, body))
            .timeout(Duration.ofSeconds(45))
        if (versionHeader) builder.header("X-Music-API", "v1")
        if (withToken) cookieHeader()?.let { builder.header("Cookie", it) }
        range?.let { builder.header("Range", it) }
        builder.method(
            method,
            if (body != null) HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            else HttpRequest.BodyPublishers.noBody()
        )
        return builder
    }

    /** 受控执行：拦截跨主机跳转，防止 Cookie 中的 music-token 泄露到内网地址。 */
    private fun send(builder: HttpRequest.Builder): HttpResponse<String> {
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val location = response.headers().firstValue("Location").orElse(null)
        if (response.statusCode() in 300..399 && !location.isNullOrEmpty()) {
            val nextHost = runCatching { URI.create(location).host }.getOrNull()
            response.body() // 消费响应体，避免连接泄漏
            throw IOException(
                if (nextHost == null || !nextHost.equals(endpoint.host, ignoreCase = true)) {
                    "服务器跳转到其他主机（${nextHost ?: "未知"}），已停止以保护登录凭证。" +
                        "请确认已开启 FN Connect 中继。"
                } else {
                    "服务器发生跳转（$location），请检查地址配置或改用中继地址"
                }
            )
        }
        return response
    }

    private fun ensureToken(): String = token ?: login().getOrThrow()

    private fun <T> withToken(block: (String) -> T): T {
        val current = ensureToken()
        return try {
            block(current)
        } catch (e: FnosAuthException) {
            token = null
            block(login().getOrThrow())
        }
    }

    /** 连通性探测：不需要登录态，401/403 也说明服务可达。 */
    fun testConnection(): Result<String> = runCatching {
        val response = send(signedRequest("GET", "/initialization/state", "", null, withToken = false))
        when (response.statusCode()) {
            401, 403 -> "飞牛音乐服务可达（需要登录）"
            !in 200..299 -> throw IOException("服务器不可达：HTTP ${response.statusCode()}")
            else -> {
                if (isChallengePage(response.body())) throw challengeError()
                if (relay) "FN Connect 中继可达" else "飞牛音乐服务可达"
            }
        }
    }

    /** 密码登录；密码用 SHA-256 传输，成功返回 userToken。 */
    fun login(): Result<String> = runCatching {
        if (username.isBlank()) throw IOException("飞牛音乐账号为空，请重新登录")
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", sha256(password))
            put("deviceId", deviceId)
        }.toString()
        val response = send(
            signedRequest("POST", "/user/password-login", "", body, withToken = false, versionHeader = false)
        )
        if (response.statusCode() !in 200..299) throw IOException("登录失败：HTTP ${response.statusCode()}")
        val env = parseEnvelope(response.body())
        val code = env.num("code")
        if (code != 0L && code != 200L) {
            throw IOException(env.str("msg").ifBlank { "账号或密码错误（code=$code）" })
        }
        val data = env["data"] as? JsonObject
        val newToken = data?.str("userToken").orEmpty().ifBlank { data?.str("token").orEmpty() }
        if (newToken.isEmpty()) throw IOException("服务器没有返回登录凭证")
        token = newToken
        newToken
    }

    private fun callJson(subPath: String, query: String, currentToken: String): JsonObject {
        val response = send(signedRequest("GET", subPath, query, null, withToken = true))
        if (response.statusCode() == 401 || response.statusCode() == 403) throw FnosAuthException()
        if (response.statusCode() !in 200..299) throw IOException("请求失败：HTTP ${response.statusCode()}")
        val env = parseEnvelope(response.body())
        val code = env.num("code")
        if (code == 120001L || code == 401L || code == 403L) throw FnosAuthException()
        if (code != 0L && code != 200L) {
            throw IOException(env.str("msg").ifBlank { "曲库请求失败（$subPath，code=$code）" })
        }
        return env["data"] as? JsonObject ?: buildJsonObject { }
    }

    /** 分页拉取曲库：data.list + data.total，映射为统一 Song 模型。 */
    fun tracks(page: Int = 1, size: Int = 200): Result<Pair<List<Song>, Int>> = runCatching {
        withToken { current ->
            val data = callJson("/track/list", "page=$page&size=$size", current)
            val array = data["list"] as? JsonArray ?: JsonArray(emptyList())
            val total = data.num("total").takeIf { it > 0 } ?: data.num("totalCount")
            val songs = array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val guid = o.str("guid").ifBlank { o.str("trackGuid") }
                if (guid.isEmpty()) return@mapNotNull null
                val albumObj = o["album"] as? JsonObject
                val fileName = o.str("fileName").ifBlank { o.str("path") }
                val title = o.str("title").ifBlank { o.str("name") }.ifBlank { "未知曲目" }
                val artist = artistNames(o).ifBlank { o.str("artist") }.ifBlank { "未知歌手" }
                val album = albumObj?.str("name").orEmpty().ifBlank { o.str("album") }
                val coverId = o.str("coverId").ifBlank { albumObj?.str("coverId").orEmpty() }
                val durationRaw = firstNumber(o, "duration", "durationMs", "length", "time")
                val durationMs = when {
                    durationRaw <= 0L -> 0L
                    durationRaw < 10_000L -> durationRaw * 1000L
                    else -> durationRaw
                }
                Song(
                    id = "fnos:$sourceId:$guid",
                    title = title,
                    uri = streamUrl(guid),
                    sourceType = SourceType.FNOS,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    sourceId = sourceId,
                    artworkUri = coverId.ifBlank { null }?.let { coverUrl(it) },
                    sizeBytes = firstNumber(o, "size", "fileSize", "byteSize"),
                    mimeType = mimeForName(fileName)
                )
            }
            songs to total.toInt()
        }
    }

    private fun artistNames(o: JsonObject): String {
        val array = o["artists"] as? JsonArray ?: return ""
        return array.mapNotNull { element ->
            when (element) {
                is kotlinx.serialization.json.JsonPrimitive -> element.content
                is JsonObject -> element.str("name").ifBlank { element.str("title") }
                else -> ""
            }.ifBlank { null }
        }.joinToString(" / ")
    }

    private fun firstNumber(o: JsonObject, vararg keys: String): Long {
        for (key in keys) {
            val value = o.str(key).toLongOrNull() ?: continue
            if (value > 0) return value
        }
        return 0L
    }

    /**
     * 音频流地址。注意：飞牛网关要求 authx + Cookie 认证，JavaFX Media 无法附加请求头；
     * 这里先返回端点 URL，真实播放需加本地带认证代理（Android 端用 ExoPlayer DataSource 解决）。
     */
    fun streamUrl(guid: String): String =
        "${endpoint.baseUrl}$API_PREFIX/track/stream?guid=${enc(guid)}"

    /** 封面地址（同样需要认证头，UI 当前用占位图）。 */
    fun coverUrl(coverId: String, size: Int = 300): String =
        "${endpoint.baseUrl}$API_PREFIX/static/cover?coverId=${enc(coverId)}&size=$size"

    private fun isChallengePage(text: String): Boolean {
        val head = text.take(600).lowercase(Locale.ROOT)
        return head.contains("<!doctype html") || head.contains("<html") || head.contains("<head")
    }

    private fun challengeError(): IOException = IOException(
        if (relay) {
            "FN Connect 返回了网页校验页而非接口数据。请先在浏览器用同一 FN ID 登录一次完成设备校验；" +
                "若 NAS 开启了「外网访问校验码」，请先关闭或完成校验后重试。"
        } else {
            "服务器返回了网页而非接口数据，请确认地址是飞牛 NAS 的 5666 端口"
        }
    )

    private fun parseEnvelope(text: String): JsonObject {
        if (isChallengePage(text)) throw challengeError()
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IOException("飞牛音乐服务返回了无法解析的内容：${text.take(80)}") }
    }

    companion object {
        /** 兼容旧调用：只取规范化后的 URL。 */
        fun normalizeHost(raw: String): String = resolve(raw).baseUrl

        fun isConnectUrl(url: String): Boolean = runCatching {
            val host = URI.create(url).host.orEmpty().lowercase(Locale.ROOT)
            CONNECT_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)

        /** 局域网 IP / FN ID / 自定义域名统一解析为 baseUrl。 */
        fun resolve(raw: String): FnosEndpoint {
            val s = raw.trim().trimEnd('/')
            if (s.isEmpty()) throw IOException("请输入飞牛 NAS 地址或 FN ID")
            if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
                val url = s.removeSuffix("/music").trimEnd('/')
                val host = runCatching { URI.create(url).host }.getOrNull().orEmpty()
                if (host.isEmpty()) throw IOException("服务器地址格式不正确")
                val connect = isConnectUrl(url)
                return FnosEndpoint(
                    url, host, connect,
                    if (connect) "FN Connect 中继" else "自定义地址"
                )
            }
            val hasPort = Regex("^.*:[0-9]{1,5}$").matches(s)
            val isIp = s.matches(Regex("^[0-9.]+$")) ||
                s.matches(Regex("^\\[[0-9a-fA-F:.]+\\](:[0-9]{1,5})?$"))
            if (isIp || hasPort) {
                val base = "http://$s" + if (hasPort) "" else ":5666"
                val host = s.removePrefix("[").substringBefore(']')
                return FnosEndpoint(base, host, false, "局域网地址")
            }
            if (!s.contains('.') && s.matches(Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,31}$"))) {
                val host = "$s.5ddd.com"
                return FnosEndpoint("https://$host", host, true, "FN ID · $s")
            }
            val lower = s.lowercase(Locale.ROOT)
            val connect = CONNECT_SUFFIXES.any { lower == it || lower.endsWith(".$it") }
            return FnosEndpoint(
                "https://$s", s, connect,
                if (connect) "FN Connect 中继" else "自定义域名"
            )
        }
    }
}

private fun JsonObject.str(key: String): String =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

private fun JsonObject.num(key: String): Long = str(key).toLongOrNull() ?: 0L

private fun md5(input: String): String = hex(MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)))

private fun sha256(input: String): String = hex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))

private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xff
        if (v < 16) append('0')
        append(Integer.toHexString(v))
    }
}

/** 服务端按 Go url.Values.Encode 语义校验：解码后的 query 按 key 排序。 */
private fun canonicalQuery(raw: String): String {
    if (raw.isEmpty()) return ""
    val map = LinkedHashMap<String, String>()
    for (pair in raw.split('&')) {
        if (pair.isEmpty()) continue
        val i = pair.indexOf('=')
        val key = if (i < 0) pair else pair.substring(0, i)
        val value = if (i < 0) "" else pair.substring(i + 1)
        map[decodeQuery(key)] = decodeQuery(value)
    }
    return map.keys.sorted().joinToString("&") { "$it=${map[it]}" }
}

private fun decodeQuery(value: String): String = runCatching {
    java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private fun signHeader(method: String, path: String, query: String, body: String?): String {
    val payloadHash = if (method.equals("GET", true)) md5(canonicalQuery(query)) else md5(body.orEmpty())
    val nonce = (100000..999999).random().toString()
    val ts = System.currentTimeMillis().toString()
    val sign = md5("${SIGN_SALT}_${path}_${nonce}_${ts}_${payloadHash}_${CLIENT_KEY}")
    return "nonce=$nonce&timestamp=$ts&sign=$sign"
}

private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "wav" -> "audio/wav"
    "aac" -> "audio/aac"
    "ogg", "opus" -> "audio/ogg"
    "m4a", "m4b", "mp4" -> "audio/mp4"
    else -> ""
}
