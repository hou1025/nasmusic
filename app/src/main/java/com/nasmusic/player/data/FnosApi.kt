package com.nasmusic.player.data

import android.net.Uri
import com.nasmusic.player.core.HttpStatusException
import com.nasmusic.player.core.isPrivateOrLocalHost
import com.nasmusic.player.core.readLimitedBytes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 飞牛音乐（fnOS）私有网关客户端。
 *
 * 支持三种接入方式（[resolve] 统一解析）：
 * 1. 局域网：192.168.1.10[:5666]            -> http://…:5666
 * 2. FN ID / FN Connect：mynas.5ddd.com      -> https://…:443，自动带 Cookie: mode=relay 强制中继
 * 3. 自定义域名 / DDNS / 反代：nas.example.com -> https://…:443
 *
 * 接口为飞牛 Web 端自用协议，未公开文档；签名规则与端点来自对 Web 客户端的公开逆向资料。
 */
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

/** FN Connect 官方设备子域名后缀（FN ID 创建时可在这些域名下选择）。 */
private val CONNECT_SUFFIXES = listOf("5ddd.com", "fnos.net", "fnofly.com")

/**
 * 全局共享 OkHttp 客户端：复用连接与 TLS 会话。
 * 每次播放/扫描新建 OkHttpClient 会重复握手，是远程连接「点了很久才出声」的主要原因之一。
 */
internal object NasHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false) // 中继跳到内网地址会泄露凭证，改由调用方受控处理
            .build()
    }
}

/** 解析后的接入地址。 */
data class FnosEndpoint(
    val baseUrl: String,
    val host: String,
    val isConnect: Boolean,
    val isFnId: Boolean,
    val label: String
)

/** token 只在内存中保存：凭据用 Keystore 加密持久化，token 失效可自动重新登录。 */
object FnosSession {
    private val tokens = ConcurrentHashMap<String, String>()
    fun get(sourceId: String): String? = tokens[sourceId]
    fun put(sourceId: String, token: String) { tokens[sourceId] = token }
    fun remove(sourceId: String) { tokens.remove(sourceId) }
}

class FnosAuthException : IOException("飞牛音乐登录已过期，请重新登录")

data class FnosTrack(
    val guid: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverId: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val fileName: String
)

class FnosApi(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val sourceId: String,
    private val relay: Boolean = isConnectUrl(baseUrl)
) {
    private val deviceId: String =
        UUID.nameUUIDFromBytes("$baseUrl|$username".toByteArray(Charsets.UTF_8)).toString()

    private val client: OkHttpClient = NasHttp.client
    private val trackSizes = ConcurrentHashMap<String, Long>()

    companion object {
        /** 兼容旧调用：只取规范化后的 URL。 */
        fun normalizeHost(raw: String): String = resolve(raw).baseUrl

        fun isConnectUrl(url: String): Boolean = runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT)
            CONNECT_SUFFIXES.any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)

        /**
         * 统一地址解析：
         * - 完整 http(s) URL 原样使用（去掉尾部 /music）
         * - 裸 FN ID（mynas）-> https://mynas.5ddd.com，按 Connect 中继处理
         * - Connect 子域名 / 普通域名 -> https://域名:443
         * - IP / IP:端口 -> http://IP:端口（默认 5666）
         */
        fun resolve(raw: String): FnosEndpoint {
            val s = raw.trim().trimEnd('/')
            if (s.isEmpty()) throw IOException("请输入飞牛 NAS 地址或 FN ID")
            if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
                var url = s.removeSuffix("/music").trimEnd('/')
                val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
                if (host.isEmpty()) throw IOException("服务器地址格式不正确")
                // 公网地址不允许明文 HTTP，程序层面升级为 HTTPS（局域网保留 http）
                if (url.startsWith("http://", true) && !isPrivateOrLocalHost(host)) {
                    url = "https://" + url.removePrefix("http://")
                }
                val connect = isConnectUrl(url)
                return FnosEndpoint(
                    url, host, connect, false,
                    if (connect) "FN Connect 中继" else "自定义地址"
                )
            }
            val hasPort = Regex("^.*:[0-9]{1,5}$").matches(s)
            val isIp = s.matches(Regex("^[0-9.]+$")) ||
                s.matches(Regex("^\\[[0-9a-fA-F:.]+\\](:[0-9]{1,5})?$"))
            if (isIp || hasPort) {
                val base = "http://$s" + if (hasPort) "" else ":5666"
                val host = s.removePrefix("[").substringBefore(']')
                return FnosEndpoint(base, host, false, false, "局域网地址")
            }
            // 裸 FN ID：无点、纯字母数字/连字符
            if (!s.contains('.') && s.matches(Regex("^[A-Za-z0-9][A-Za-z0-9-]{1,31}$"))) {
                val host = "$s.5ddd.com"
                return FnosEndpoint("https://$host", host, true, true, "FN ID · $s")
            }
            // 普通域名（DDNS / 反向代理 / FN Connect 子域名）
            val lower = s.lowercase(Locale.ROOT)
            val connect = CONNECT_SUFFIXES.any { lower == it || lower.endsWith(".$it") }
            return FnosEndpoint(
                "https://$s", s, connect, false,
                if (connect) "FN Connect 中继" else "自定义域名"
            )
        }
    }

    /** Cookie：中继标记与登录凭证合并为同一个 Cookie 头。 */
    private fun cookieHeader(token: String?): String? {
        val parts = ArrayList<String>(2)
        if (relay) parts += "mode=relay"
        if (!token.isNullOrEmpty()) parts += "music-token=$token"
        return parts.joinToString("; ").ifBlank { null }
    }

    private fun signedRequest(
        method: String,
        subPath: String,
        query: String,
        body: String?,
        token: String?,
        versionHeader: Boolean = true,
        range: String? = null
    ): Request {
        val path = API_PREFIX + subPath
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val builder = Request.Builder()
            .url(url)
            .header("authx", signHeader(method, path, query, body))
        if (versionHeader) builder.header("X-Music-API", "v1") // 登录握手不能带版本头
        cookieHeader(token)?.let { builder.header("Cookie", it) }
        if (!range.isNullOrEmpty()) builder.header("Range", range)
        if (body != null) {
            builder.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else {
            builder.method(method, null)
        }
        return builder.build()
    }

    /**
     * 受控执行：禁止跨主机跳转。FN Connect 在局域网 / P2P 场景可能 302 到内网地址，
     * 跟随会泄露 Cookie 中的 music-token；同主机跳转最多跟随 3 次。
     */
    private fun executeChecked(request: Request): Response {
        var current = request
        val origin = request.url.host
        repeat(4) {
            val response = client.newCall(current).execute()
            val location = response.header("Location")
            if (response.isRedirect && !location.isNullOrEmpty()) {
                val next = runCatching { current.url.resolve(location) }.getOrNull()
                response.close()
                if (next == null || !next.host.equals(origin, ignoreCase = true)) {
                    throw IOException(
                        "服务器跳转到其他主机（${next?.host ?: "未知"}），已停止以保护登录凭证。" +
                            "请确认已开启 FN Connect 中继。"
                    )
                }
                current = current.newBuilder().url(next).build()
                return@repeat
            }
            return response
        }
        throw IOException("服务器重定向次数过多")
    }

    private fun ensureToken(): String = FnosSession.get(sourceId) ?: login()

    private fun <T> withToken(block: (String) -> T): T {
        val token = ensureToken()
        return try {
            block(token)
        } catch (e: FnosAuthException) {
            FnosSession.remove(sourceId)
            block(login())
        }
    }

    /** 连通性探测：登录页用来判断地址是否可达，不要求登录态。 */
    fun probe(): String {
        val request = signedRequest("GET", "/initialization/state", "", null, null)
        executeChecked(request).use { response ->
            if (response.code == 401 || response.code == 403) return "飞牛音乐服务可达（需要登录）"
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "服务器不可达：HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            if (isChallengePage(text)) throw challengeError()
            return if (relay) "FN Connect 中继可达" else "飞牛音乐服务可达"
        }
    }

    /** 密码登录；成功返回 userToken 并写入内存会话。 */
    fun login(): String {
        if (username.isBlank()) throw IOException("飞牛音乐账号为空，请重新登录")
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", sha256(password))
            .put("deviceId", deviceId)
            .toString()
        val request = signedRequest(
            "POST", "/user/password-login", "", body, null, versionHeader = false
        )
        executeChecked(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "登录失败：HTTP ${response.code}")
            }
            val env = parseEnvelope(text)
            val code = env.optInt("code", -1)
            if (code != 0 && code != 200) {
                // 中继网关有时返回 code 但 msg 为空；空消息会让界面只显示「扫描失败」
                val msg = env.optString("msg").ifBlank { "账号或密码错误（code=$code）" }
                throw IOException(msg)
            }
            val data = env.optJSONObject("data")
            val token = data?.optString("userToken").orEmpty()
                .ifBlank { data?.optString("token").orEmpty() }
            if (token.isEmpty()) throw IOException("服务器没有返回登录凭证")
            FnosSession.put(sourceId, token)
            return token
        }
    }

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

    private fun parseEnvelope(text: String): JSONObject {
        if (isChallengePage(text)) throw challengeError()
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("飞牛音乐服务返回了无法解析的内容：${text.take(80)}")
        }
    }

    private fun callJson(subPath: String, query: String, token: String): JSONObject {
        val request = signedRequest("GET", subPath, query, null, token)
        executeChecked(request).use { response ->
            if (response.code == 401 || response.code == 403) throw FnosAuthException()
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "请求失败（$subPath）：HTTP ${response.code}")
            }
            val env = parseEnvelope(response.body?.string().orEmpty())
            val code = env.optInt("code", -1)
            if (code == 120001 || code == 401 || code == 403) throw FnosAuthException()
            if (code != 0 && code != 200) {
                // 必须保证错误消息非空，否则界面只能显示兜底文案，无法判断中继返回了什么
                val msg = env.optString("msg").ifBlank { "曲库请求失败（$subPath，code=$code）" }
                throw IOException(msg)
            }
            return env.optJSONObject("data") ?: JSONObject()
        }
    }

    /** 曲目分页：data.list + data.total。字段名按飞牛 Web 端返回体兼容多个候选。 */
    fun tracks(page: Int, size: Int): Pair<List<FnosTrack>, Int> = withToken { token ->
        val data = callJson("/track/list", "page=$page&size=$size", token)
        val array: JSONArray = data.optJSONArray("list") ?: JSONArray()
        val total = data.optInt("total", data.optInt("totalCount", -1))
        val list = ArrayList<FnosTrack>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val guid = o.optString("guid").ifBlank { o.optString("trackGuid") }
            if (guid.isEmpty()) continue
            val albumObj = o.optJSONObject("album")
            val title = o.optString("title").ifBlank { o.optString("name") }
                .ifBlank { "未知曲目" }
            val artist = artistNames(o.optJSONArray("artists"))
                .ifBlank { o.optString("artist") }
                .ifBlank { "未知歌手" }
            val album = albumObj?.optString("name").orEmpty().ifBlank { o.optString("album") }
            val coverId = o.optString("coverId").ifBlank { albumObj?.optString("coverId").orEmpty() }
            val durationRaw = firstNumber(o, "duration", "durationMs", "length", "time")
            val durationMs = when {
                durationRaw <= 0L -> 0L
                durationRaw < 10_000L -> durationRaw * 1000L
                else -> durationRaw
            }
            val sizeBytes = firstNumber(o, "size", "fileSize", "byteSize")
            trackSizes[guid] = sizeBytes
            val fileName = o.optString("fileName").ifBlank { o.optString("path") }
            list += FnosTrack(
                guid = guid, title = title, artist = artist, album = album,
                coverId = coverId, durationMs = durationMs, sizeBytes = sizeBytes,
                mimeType = if (fileName.isNotBlank()) mimeForName(fileName) else "",
                fileName = fileName
            )
        }
        list to total
    }

    /** 曲库里的已知文件大小；命中时播放无需再发 Range 0-0 探测长度。 */
    fun knownLength(guid: String): Long = trackSizes[guid] ?: 0L

    private fun firstNumber(o: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = o.opt(key) ?: continue
            val number = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull() ?: continue
                else -> continue
            }
            if (number > 0) return number
        }
        return 0L
    }

    private fun artistNames(array: JSONArray?): String {
        if (array == null) return ""
        val names = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.opt(i) ?: continue
            val name = when (item) {
                is String -> item
                is JSONObject -> item.optString("name").ifBlank { item.optString("title") }
                else -> ""
            }
            if (name.isNotBlank()) names += name
        }
        return names.joinToString(" / ")
    }

    private fun mimeForName(name: String): String = when (
        name.substringAfterLast('.', "").lowercase()
    ) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "ape" -> "audio/ape"
        "wma" -> "audio/x-ms-wma"
        "aiff", "aif" -> "audio/aiff"
        "dsf", "dff" -> "audio/dsd"
        else -> ""
    }

    /** 封面是二进制响应，需带签名；下载后由扫描器写入本地文件。 */
    fun coverBytes(coverId: String, size: Int = 300): ByteArray? = withToken { token ->
        val query = "coverId=${Uri.encode(coverId)}&size=$size"
        val request = signedRequest("GET", "/static/cover", query, null, token)
        executeChecked(request).use { response ->
            if (response.code == 401 || response.code == 403) throw FnosAuthException()
            if (!response.isSuccessful) return@withToken null
            // 限流读取：读满 2MB+1 立即中断丢弃， never 全量读进内存
            val stream = response.body?.byteStream() ?: return@withToken null
            readLimitedBytes(stream)
        }
    }

    /** 音频流总长度：Range 0-0 探测 Content-Range。 */
    fun streamLength(guid: String): Long = withToken { token ->
        val query = "guid=${Uri.encode(guid)}"
        val request = signedRequest("GET", "/track/stream", query, null, token, range = "bytes=0-0")
        executeChecked(request).use { response ->
            if (response.code == 401 || response.code == 403) throw FnosAuthException()
            requirePartialContent(response, 0L)
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    /** 按偏移读取音频流，供 ExoPlayer 拖动进度时随机访问。 */
    fun streamRead(
        guid: String,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int = withToken { token ->
        if (length <= 0) return@withToken 0
        val query = "guid=${Uri.encode(guid)}"
        val request = signedRequest(
            "GET", "/track/stream", query, null, token,
            range = "bytes=$position-${position + length - 1}"
        )
        executeChecked(request).use { response ->
            if (response.code == 401 || response.code == 403) throw FnosAuthException()
            // 必须拿到真实 206 且 Content-Range 起始与 position 一致，否则数据会整体错位
            requirePartialContent(response, position)
            val stream = response.body?.byteStream() ?: return@withToken -1
            var total = 0
            while (total < length) {
                val n = stream.read(buffer, offset + total, length - total)
                if (n < 0) break
                total += n
            }
            if (total == 0) -1 else total
        }
    }

    /** 共享客户端不做单例关闭，保留方法仅为兼容既有调用点。 */
    fun close() = Unit
}

private fun md5(input: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        if (v < 16) sb.append('0')
        sb.append(Integer.toHexString(v))
    }
    return sb.toString()
}

private fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xff
        if (v < 16) sb.append('0')
        sb.append(Integer.toHexString(v))
    }
    return sb.toString()
}

/** 服务端按 Go url.Values.Encode 的语义校验：解码后的 query 按 key 排序。 */
private fun canonicalQuery(raw: String): String {
    if (raw.isEmpty()) return ""
    val map = LinkedHashMap<String, String>()
    for (pair in raw.split('&')) {
        if (pair.isEmpty()) continue
        val i = pair.indexOf('=')
        val k = if (i < 0) pair else pair.substring(0, i)
        val v = if (i < 0) "" else pair.substring(i + 1)
        map[decodeQuery(k)] = decodeQuery(v)
    }
    return map.keys.sorted().joinToString("&") { "$it=${map[it]}" }
}

private fun decodeQuery(value: String): String = runCatching {
    URLDecoder.decode(value, "UTF-8")
}.getOrDefault(value)

private fun signHeader(method: String, path: String, query: String, body: String?): String {
    val payloadHash = if (method.equals("GET", true)) md5(canonicalQuery(query))
    else md5(body.orEmpty())
    val nonce = (100000..999999).random().toString()
    val ts = System.currentTimeMillis().toString()
    val sign = md5("${SIGN_SALT}_${path}_${nonce}_${ts}_${payloadHash}_${CLIENT_KEY}")
    return "nonce=$nonce&timestamp=$ts&sign=$sign"
}
