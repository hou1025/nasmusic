package com.nasmusic.player.core

import android.net.Uri
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.roundToInt

val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "aac", "ogg", "m4a", "ape", "wma",
    "aiff", "aif", "opus", "dsf", "dff", "mp4", "m4b", "3gp", "mka", "wv", "tta"
)

fun extensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun isAudioFile(name: String): Boolean = extensionOf(name) in AUDIO_EXTENSIONS

fun mimeForName(name: String): String = when (extensionOf(name)) {
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
    "3gp" -> "audio/3gpp"
    else -> ""
}

/** nas://<sourceId>/<远程路径> —— 自定义 scheme 交给 NasDataSource 处理。 */
fun nasUri(sourceId: String, remotePath: String): String =
    Uri.Builder().scheme("nas").authority(sourceId).path(remotePath).build().toString()

fun parseNasUri(uriString: String): Pair<String, String>? {
    val uri = Uri.parse(uriString)
    if (uri.scheme != "nas") return null
    val sourceId = uri.authority ?: return null
    return sourceId to (uri.path ?: "")
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = (ms / 1000).coerceAtMost(359999)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val kb = bytes / 1024.0
    return if (kb < 1024) "${kb.roundToInt()} KB"
    else String.format(Locale.ROOT, "%.1f MB", kb / 1024.0)
}

fun normalizeRemotePath(root: String, child: String): String {
    val base = root.trim().trim('/').replace('\\', '/')
    val name = child.trim().trim('/').replace('\\', '/')
    return listOf(base, name).filter { it.isNotEmpty() }.joinToString("/")
}

/**
 * 统一的「可取消」结果包装：取消是协程的正常控制流，绝不能被 when/else 当成普通失败吞掉。
 * 数据层所有原先裸写的 runCatching 都改用它，UI 层的 ViewModels 由 UI 组自行处理。
 */
suspend fun <T> suspendCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * 携带真实 HTTP 状态码的异常：替代过去用 msg.contains("404"/"401") 猜状态的做法，
 * 避免端口号/字节数/文件名里恰好出现这些数字时误判。
 */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/** 统一把底层异常翻译为用户可读提示，覆盖权限错误/路径不存在/断网等场景。 */
fun friendlyError(t: Throwable, fallback: String = "操作失败"): String {
    val msg = t.message.orEmpty()
    return when {
        t is SecurityException -> "没有访问权限，请检查账号或文件夹权限"
        t is UnknownHostException -> "网络不可用或 NAS 地址无法解析"
        t is SocketTimeoutException -> "连接 NAS 超时，请检查网络与地址"
        t is HttpStatusException -> when (t.code) {
            404 -> "远程路径不存在"
            401, 403 -> "登录失败，请检查用户名与密码"
            else -> "服务器返回错误：HTTP ${t.code}"
        }
        t is IOException && msg.contains("refused", true) -> "NAS 拒绝连接，请检查端口与服务"
        msg.contains("STATUS_ACCESS_DENIED", true) || msg.contains("Access denied", true) -> "账号或密码错误，或无访问权限"
        msg.contains("STATUS_OBJECT_NAME_NOT_FOUND", true) -> "远程路径不存在"
        msg.contains("STATUS_LOGON_FAILURE", true) -> "登录失败，请检查用户名与密码"
        msg.contains("ENETUNREACH", true) || msg.contains("Failed to connect", true) -> "网络不可用，已缓存歌曲仍可播放"
        msg.isNotBlank() -> msg
        else -> fallback
    }
}

/**
 * 限流读取：封面等二进制响应读满 limitBytes + 1 就立即中断并丢弃，
 * 避免 NAS 目录里一个异常大的 cover.jpg 把整个进程读 OOM。
 */
fun readLimitedBytes(input: InputStream, limitBytes: Int = 2 * 1024 * 1024): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limitBytes) return null
        out.write(buf, 0, n)
    }
    return if (out.size() == 0) null else out.toByteArray()
}

/**
 * 判断主机是否属于局域网/本机。用于程序层面收窄明文 HTTP：
 * 仅私网地址放行 http，公网地址强制升级为 https。
 */
fun isPrivateOrLocalHost(host: String): Boolean {
    val h = host.lowercase().removePrefix("[").substringBefore(']').trim()
    if (h.isEmpty()) return false
    if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
    if (h.contains(':')) {
        // IPv6：回环 / 唯一本地 / 链路本地
        return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
            h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
    }
    val parts = h.split('.').map { it.toIntOrNull() ?: return !h.contains('.') }
    if (parts.size == 4) {
        val a = parts[0]
        val b = parts[1]
        return a == 10 || a == 127 || a == 0 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }
    // 无点的裸主机名（nas、docker 容器名等）按局域网名处理
    return !h.contains('.')
}
