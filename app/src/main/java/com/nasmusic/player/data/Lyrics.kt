package com.nasmusic.player.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.nasUri
import com.nasmusic.player.core.parseNasUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val offsetMs: Long = 0L,
    val synced: Boolean = true
) {
    /** 当前播放位置对应的歌词行下标，供滚动高亮使用。 */
    fun indexAt(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val p = positionMs - offsetMs
        var result = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= p) result = i else break
        }
        return result
    }
}

object LrcParser {
    private val TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    fun parse(text: String): Lyrics? {
        val lines = mutableListOf<LyricLine>()
        var offsetMs = 0L
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("[offset:", true)) {
                // startsWith 忽略大小写，但 removePrefix 大小写敏感：用 substringAfter
                // 按 ':' 取值，兼容 [OFFSET:+500] / [Offset:500] 等大小写写法。
                offsetMs = line.substringAfter(':').removeSuffix("]").trim().toLongOrNull() ?: 0L
                continue
            }
            val matches = TIME.findAll(line).toList()
            if (matches.isEmpty()) continue
            val content = TIME.replace(line, "").trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: 0L
                val seconds = m.groupValues[2].toLongOrNull() ?: 0L
                val fraction = m.groupValues[3]
                val millis = when {
                    fraction.isEmpty() -> 0L
                    fraction.length == 1 -> fraction.toLong() * 100L
                    fraction.length == 2 -> fraction.toLong() * 10L
                    else -> fraction.take(3).toLongOrNull() ?: 0L
                }
                lines += LyricLine(minutes * 60_000L + seconds * 1000L + millis, content)
            }
        }
        if (lines.isEmpty()) return null
        return Lyrics(lines.sortedBy { it.timeMs }, offsetMs, true)
    }
}

/** 优先读取同名 LRC 文件，缺失时回退到标签中的内嵌歌词。 */
class LyricsLoader(
    private val context: Context,
    private val nasRepository: NasRepository
) {
    suspend fun load(song: Song, embeddedLyrics: String?, offsetMs: Long): Lyrics? =
        withContext(Dispatchers.IO) {
            val candidates = mutableListOf<String>()
            song.lyricsUri?.let { candidates += it }
            val base = song.title.substringBeforeLast('.')

            if (!song.isNas) {
                findLocalLrc(base)?.let { candidates += it }
            } else {
                parseNasUri(song.uri)?.let { (sourceId, path) ->
                    candidates += nasUri(sourceId, path.substringBeforeLast('.') + ".lrc")
                }
            }

            var plainText: String? = null
            for (candidate in candidates.distinct()) {
                val text = runCatching { nasRepository.readText(candidate) }.getOrNull() ?: continue
                LrcParser.parse(text)?.let { return@withContext it.copy(offsetMs = offsetMs) }
                if (plainText == null && text.isNotBlank()) plainText = text.trim()
            }

            if (!embeddedLyrics.isNullOrBlank()) {
                return@withContext LrcParser.parse(embeddedLyrics)?.copy(offsetMs = offsetMs)
                    ?: Lyrics(listOf(LyricLine(0L, embeddedLyrics)), offsetMs, false)
            }
            // 在线源返回的纯文本歌词（无时间轴）作为静态歌词展示
            plainText?.let { Lyrics(listOf(LyricLine(0L, it)), offsetMs, false) }
        }

    private fun findLocalLrc(baseName: String): String? {
        val uri = MediaStore.Files.getContentUri("external")
        val cursor = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$baseName%.lrc"),
                null
            )
        }.getOrNull() ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                return ContentUris.withAppendedId(uri, id).toString()
            }
        }
        return null
    }
}
