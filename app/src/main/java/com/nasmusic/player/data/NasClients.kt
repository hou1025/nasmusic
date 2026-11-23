package com.nasmusic.player.data

import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Xml
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.nasmusic.player.core.SourceType
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** 解密后的连接参数，仅存在于内存中，不落库。 */
data class NasConfig(
    val id: String,
    val type: SourceType,
    val host: String = "",
    val port: Int = 0,
    val share: String = "",
    val rootPath: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val anonymous: Boolean = false
)

data class NasEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L
)

/** 支持按偏移量读取：ExoPlayer 拖动进度时无需完整下载文件。 */
interface NasRandomAccess {
    val length: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

interface NasBrowser {
    fun listDir(path: String): List<NasEntry>
    fun open(path: String): NasRandomAccess
    fun close()
}

fun NasRandomAccess.asInputStream(): InputStream = object : InputStream() {
    private var cursor = 0L
    override fun read(): Int {
        val one = ByteArray(1)
        val n = readAt(cursor, one, 0, 1)
        if (n <= 0) return -1
        cursor += n
        return one[0].toInt() and 0xFF
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = readAt(cursor, b, off, len)
        if (n <= 0) return -1
        cursor += n
        return n
    }
    override fun skip(n: Long): Long {
        val target = (cursor + n).coerceAtMost(length)
        val skipped = target - cursor
        cursor = target
        return skipped
    }
    override fun available(): Int = (length - cursor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

private const val KEY_ALIAS = "nasmusic_nas_credentials"

/** 登录信息使用 Android Keystore 中的 AES-GCM 密钥加密后持久化。 */
class NasCredentialsCrypto {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun secretKey() = (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey) ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = combined.copyOfRange(0, 12)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(combined, 12, combined.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }

}

object NasClients {
    fun create(config: NasConfig): NasBrowser = when (config.type) {
        SourceType.SMB -> SmbBrowser(config)
        SourceType.WEBDAV -> WebDavBrowser(config)
        SourceType.FNOS -> FnosBrowser(config)
        SourceType.LOCAL -> throw IllegalArgumentException("本地音乐不需要 NAS 客户端")
    }
}

/**
 * 飞牛音乐：曲库以 track guid 标识，播放走带签名的 /track/stream Range 请求。
 * 目录浏览映射为「曲目列表」，复用现有 NasBrowserScreen 的点击播放链路。
 */
class FnosBrowser(private val config: NasConfig) : NasBrowser {
    private val api = FnosApi(config.baseUrl, config.username, config.password, config.id)

    override fun listDir(path: String): List<NasEntry> {
        if (path.trim('/').isNotEmpty()) return emptyList()
        // 按 total 翻页拉完整个飞牛曲库：只取第一页会漏掉 200 首之后的歌曲，
        // 导致目录浏览页看不到、也点播不了这些曲目（扫描器已采用同样的翻页策略）。
        val allTracks = mutableListOf<FnosTrack>()
        var page = 1
        var total = -1
        while (page <= 100) {
            val (tracks, reportedTotal) = api.tracks(page, 200)
            if (reportedTotal > 0) total = reportedTotal
            allTracks.addAll(tracks)
            if (tracks.isEmpty()) break
            if (total > 0 && page * 200 >= total) break
            page++
        }
        return allTracks.map { track ->
            val name = track.fileName.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: "${track.title}.mp3"
            NasEntry(
                name = name,
                path = track.guid,
                isDirectory = false,
                sizeBytes = track.sizeBytes,
                lastModified = 0L
            )
        }
    }

    override fun open(path: String): NasRandomAccess {
        val guid = path.trim('/')
        if (guid.isEmpty()) throw IOException("无效的飞牛音乐曲目")
        return object : NasRandomAccess {
            override val length: Long by lazy {
                // 曲库元数据里已有文件大小时直接复用，省掉一次 Range 0-0 网络往返
                api.knownLength(guid).takeIf { it > 0 } ?: api.streamLength(guid)
            }
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                api.streamRead(guid, position, buffer, offset, length)
            override fun close() {}
        }
    }

    override fun close() = api.close()
}

/** SMB/CIFS：SMBJ，支持匿名访问、目录浏览、按偏移读取。 */
class SmbBrowser(private val config: NasConfig) : NasBrowser {
    private val client = SMBClient()
    private val connection: Connection = client.connect(
        config.host, if (config.port > 0) config.port else 445
    )
    private val session: Session = connection.authenticate(
        if (config.anonymous) AuthenticationContext.anonymous()
        else AuthenticationContext(config.username, config.password.toCharArray(), "")
    )
    private val share: DiskShare = session.connectShare(config.share) as DiskShare

    override fun listDir(path: String): List<NasEntry> {
        val clean = path.trim().trim('/').replace('\\', '/')
        val result = mutableListOf<NasEntry>()
        for (info in share.list(clean)) {
            val name = info.fileName
            if (name == "." || name == ".." || name.isBlank()) continue
            val full = if (clean.isEmpty()) name else "$clean/$name"
            val isDir = (info.fileAttributes and 0x10L) != 0L // FILE_ATTRIBUTE_DIRECTORY
            result += NasEntry(
                name = name,
                path = full,
                isDirectory = isDir,
                sizeBytes = info.endOfFile,
                lastModified = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L)
            )
        }
        return result
    }

    override fun open(path: String): NasRandomAccess {
        val file = share.openFile(
            path.trim().trimStart('/').replace('\\', '/'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        )
        return object : NasRandomAccess {
            override val length: Long by lazy {
                file.getFileInformation().getStandardInformation().getEndOfFile()
            }
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                file.read(buffer, position, offset, length)
            override fun close() = file.close()
        }
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

/** WebDAV：PROPFIND 浏览目录 + HTTP Range 分段读取，支持 HTTP/HTTPS。 */
class WebDavBrowser(private val config: NasConfig) : NasBrowser {
    // 复用全局连接池：避免每次切歌/扫描都重新握手，远程 WebDAV 提速明显
    private val client = NasHttp.client

    private val basePath: String = runCatching { URI(config.baseUrl).path.trimEnd('/') }.getOrDefault("")

    private fun urlOf(path: String): String {
        val base = config.baseUrl.trimEnd('/')
        val encoded = path.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { Uri.encode(it) }
        return if (encoded.isEmpty()) "$base/" else "$base/$encoded"
    }

    private fun Request.Builder.auth() = apply {
        if (!config.anonymous && config.username.isNotEmpty()) {
            header("Authorization", Credentials.basic(config.username, config.password))
        }
    }

    override fun listDir(path: String): List<NasEntry> {
        val xml = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\">" +
            "<d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"
        val request = Request.Builder()
            .url(urlOf(path))
            .header("Depth", "1")
            .method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .auth()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV PROPFIND 失败: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parsePropfind(body, path)
        }
    }

    private fun parsePropfind(body: String, requestPath: String): List<NasEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(body))
        val result = mutableListOf<NasEntry>()
        var href = ""
        var collection = false
        var size = 0L
        var modified = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = parser.name?.substringAfterLast(':')?.lowercase(Locale.ROOT).orEmpty()
            if (event == XmlPullParser.START_TAG) {
                when (tag) {
                    "response" -> { href = ""; collection = false; size = 0L; modified = 0L }
                    "href" -> href = parser.nextText()
                    "collection" -> collection = true
                    "getcontentlength" -> size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> modified = parseHttpDate(parser.nextText())
                }
            } else if (event == XmlPullParser.END_TAG && tag == "response") {
                val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
                var relative = decoded.trim().trimStart('/')
                if (basePath.isNotEmpty() && relative.startsWith(basePath.trimStart('/'))) {
                    relative = relative.removePrefix(basePath.trimStart('/')).trimStart('/')
                }
                relative = relative.trimEnd('/')
                if (relative.isNotEmpty() && relative != requestPath.trim('/')) {
                    result += NasEntry(
                        name = relative.substringAfterLast('/'),
                        path = relative,
                        isDirectory = collection || decoded.endsWith("/"),
                        sizeBytes = size,
                        lastModified = modified
                    )
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseHttpDate(value: String): Long = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value.trim())?.time ?: 0L
    }.getOrDefault(0L)

    override fun open(path: String): NasRandomAccess {
        val totalLength by lazy { resolveLength(path) }
        return object : NasRandomAccess {
            override val length: Long get() = totalLength
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (length <= 0) return 0
                val end = position + length - 1
                val request = Request.Builder()
                    .url(urlOf(path))
                    .header("Range", "bytes=$position-$end")
                    .auth()
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 206 && !response.isSuccessful) {
                        throw IOException("WebDAV 范围读取失败: HTTP ${response.code}")
                    }
                    val stream = response.body?.byteStream() ?: return -1
                    var total = 0
                    while (total < length) {
                        val n = stream.read(buffer, offset + total, length - total)
                        if (n < 0) break
                        total += n
                    }
                    return if (total == 0) -1 else total
                }
            }
            override fun close() {}
        }
    }

    private fun resolveLength(path: String): Long {
        val request = Request.Builder()
            .url(urlOf(path))
            .header("Range", "bytes=0-0")
            .auth()
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val range = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            return range ?: response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    /** 共享客户端由全局管理，此处不关闭。 */
    override fun close() = Unit
}
