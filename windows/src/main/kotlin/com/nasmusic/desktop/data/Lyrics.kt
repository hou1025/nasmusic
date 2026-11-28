package com.nasmusic.desktop.data

import com.nasmusic.desktop.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.ByteBuffer

/** 一行歌词：时间戳（毫秒）+ 文本。 */
data class LyricLine(val timeMs: Long, val text: String)

/** 解析后的歌词，按时间升序存放，便于二分查找当前行。 */
class Lyrics(val lines: List<LyricLine>) {

    /** 返回 positionMs 时应高亮的行下标；还没到第一句时返回 -1。 */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var answer = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                answer = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return answer
    }
}

/**
 * 歌词加载：与 Android 端「本地同名 .lrc」的约定保持一致。
 *
 * 桌面端只处理本地文件（NAS/在线音源的同名歌词需要额外请求，暂不在此实现），
 * 找不到 .lrc 时返回 null，界面显示占位提示而不是报错。
 */
object LyricsLoader {

    private val TAG = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    suspend fun load(song: Song?): Lyrics? = withContext(Dispatchers.IO) {
        val s = song ?: return@withContext null
        val file = localFileOf(s.uri) ?: return@withContext null
        val lrc = File(file.parentFile, file.nameWithoutExtension + ".lrc")
        if (!lrc.isFile) return@withContext null
        val text = runCatching { decode(lrc.readBytes()) }.getOrNull() ?: return@withContext null
        parse(text)
    }

    private fun localFileOf(uri: String): File? = runCatching {
        if (!uri.startsWith("file:")) return null
        File(URI(uri))
    }.getOrNull()

    /** 中文歌词常见 UTF-8 / GBK 两种编码：先严格按 UTF-8 解码，失败再退回 GBK。 */
    private fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, charset("GBK")) }
    }

    fun parse(text: String): Lyrics? {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lineSequence()) {
            val matches = TAG.findAll(raw).toList()
            if (matches.isEmpty()) continue
            // 一行可能带多个时间标签（[00:12.00][01:30.00]副歌），取最后一个标签之后为内容
            val content = raw.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val minute = m.groupValues[1].toLongOrNull() ?: continue
                val second = m.groupValues[2].toLongOrNull() ?: continue
                val frac = m.groupValues[3]
                val millis = if (frac.isEmpty()) {
                    0L
                } else {
                    val value = frac.toLongOrNull() ?: 0L
                    when (frac.length) {
                        1 -> value * 100
                        2 -> value * 10
                        else -> value
                    }
                }
                out += LyricLine(minute * 60_000L + second * 1000L + millis, content)
            }
        }
        if (out.isEmpty()) return null
        return Lyrics(out.sortedBy { it.timeMs })
    }
}
