package com.nasmusic.player.core

import android.net.Uri
import java.io.IOException
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

/** 统一把底层异常翻译为用户可读提示，覆盖权限错误/路径不存在/断网等场景。 */
fun friendlyError(t: Throwable, fallback: String = "操作失败"): String {
    val msg = t.message.orEmpty()
    return when {
        t is SecurityException -> "没有访问权限，请检查账号或文件夹权限"
        t is UnknownHostException -> "网络不可用或 NAS 地址无法解析"
        t is SocketTimeoutException -> "连接 NAS 超时，请检查网络与地址"
        t is IOException && msg.contains("refused", true) -> "NAS 拒绝连接，请检查端口与服务"
        msg.contains("STATUS_ACCESS_DENIED", true) || msg.contains("Access denied", true) -> "账号或密码错误，或无访问权限"
        msg.contains("STATUS_OBJECT_NAME_NOT_FOUND", true) || msg.contains("404", true) -> "远程路径不存在"
        msg.contains("STATUS_LOGON_FAILURE", true) || msg.contains("401", true) -> "登录失败，请检查用户名与密码"
        msg.contains("ENETUNREACH", true) || msg.contains("Failed to connect", true) -> "网络不可用，已缓存歌曲仍可播放"
        msg.isNotBlank() -> msg
        else -> fallback
    }
}
