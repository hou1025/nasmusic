package com.nasmusic.desktop.data

import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.model.SourceType
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** WebDAV 目录项：href 保留原始编码，name 为解码后的展示名。 */
data class WebDavEntry(
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modified: String
)

/**
 * WebDAV 客户端（PROPFIND 列目录），仅使用 JDK 自带的 HttpClient + DOM XML 解析。
 *
 * 注意：JavaFX Media 不支持自定义请求头，因此 [streamUrl] 返回的 URL 不含 Basic 认证头，
 * 直接播放需要服务端允许匿名读取，或后续加一层本地带认证代理。
 */
class WebDavClient(
    baseUrl: String,
    username: String,
    password: String
) {
    private val root = baseUrl.trim().trimEnd('/')
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val authHeader = "Basic " + Base64.getEncoder()
        .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    /** 发起 PROPFIND，Depth=1 列一层，Depth=0 仅探测根。 */
    private fun propfind(path: String, depth: String): HttpResponse<String> {
        if (root.isEmpty()) throw IOException("未填写 WebDAV 服务器地址")
        val target = if (path.isBlank() || path == "/") "$root/" else "$root/${path.trimStart('/')}"
        val request = HttpRequest.newBuilder(URI.create(target))
            .timeout(Duration.ofSeconds(30))
            .header("Depth", depth)
            .header("Authorization", authHeader)
            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        if (response.statusCode() !in 200..299) {
            throw IOException("WebDAV 请求失败：HTTP ${response.statusCode()}（${response.body().take(80)}）")
        }
        return response
    }

    /** 连通性探测。 */
    fun testConnection(): Result<String> = runCatching {
        val response = propfind("", "0")
        "WebDAV 连接成功（HTTP ${response.statusCode()}）"
    }

    /** 列出目录下的全部条目。 */
    fun list(folder: String = ""): Result<List<WebDavEntry>> = runCatching {
        parseMultistatus(propfind(folder, "1").body())
    }

    /** 列出目录下的音频文件并映射为统一 [Song] 模型。 */
    fun listAudio(folder: String = "", sourceId: String = ""): Result<List<Song>> = runCatching {
        parseMultistatus(propfind(folder, "1").body())
            .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXT }
            .map { entry ->
                val url = streamUrl(entry)
                val base = entry.name.substringBeforeLast('.', entry.name)
                val parts = base.split(" - ", limit = 2)
                Song(
                    id = "webdav:$sourceId:${UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8))}",
                    title = if (parts.size == 2) parts[1].trim() else base,
                    uri = url,
                    sourceType = SourceType.WEBDAV,
                    artist = if (parts.size == 2) parts[0].trim() else "",
                    sizeBytes = entry.sizeBytes,
                    sourceId = sourceId
                )
            }
    }

    /** 把目录项解析为可播放 URL（href 可能是绝对或相对路径）。 */
    fun streamUrl(entry: WebDavEntry): String = URI.create(root).resolve(entry.href).toString()

    /** 解析 WebDAV multistatus XML；按 localName 匹配，兼容不同命名空间前缀。 */
    private fun parseMultistatus(xml: String): List<WebDavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        val all = doc.getElementsByTagName("*")
        val entries = mutableListOf<WebDavEntry>()
        for (i in 0 until all.length) {
            val node = all.item(i)
            if (node !is Element || node.localName != "response") continue
            val href = firstLocal(node, "href") ?: continue
            // href 保留原始百分号编码供 URI.resolve 使用；name 单独解码展示
            val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
            val isDir = descend(node, "resourcetype")?.let { firstLocal(it, "collection") != null } ?: false
            entries += WebDavEntry(
                href = href,
                name = decoded.trimEnd('/').substringAfterLast('/'),
                isDirectory = isDir,
                sizeBytes = firstLocal(node, "getcontentlength")?.toLongOrNull() ?: 0L,
                modified = firstLocal(node, "getlastmodified").orEmpty()
            )
        }
        return entries
    }

    private fun firstLocal(root: Node, local: String): String? =
        descend(root, local)?.textContent?.trim()?.ifBlank { null }

    /** 深度优先查找指定 localName 的子元素。 */
    private fun descend(root: Node, local: String): Element? {
        if (root is Element && root.localName == local) return root
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child !is Element) continue
            if (child.localName == local) return child
            descend(child, local)?.let { return it }
        }
        return null
    }

    companion object {
        private val AUDIO_EXT = setOf("mp3", "flac", "m4a", "ogg", "wav")
    }
}
