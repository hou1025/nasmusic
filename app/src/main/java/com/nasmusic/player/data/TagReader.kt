package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.min

interface RandomReader {
    val size: Long
    fun readAt(offset: Long, length: Int): ByteArray
    fun close()
}

class NasRandomAccessReader(
    private val access: NasRandomAccess,
    fallbackSize: Long
) : RandomReader {
    override val size: Long = if (access.length > 0) access.length else fallbackSize

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset >= size) return ByteArray(0)
        val wanted = min(length.toLong(), size - offset).toInt()
        val buffer = ByteArray(wanted)
        var filled = 0
        while (filled < wanted) {
            val n = access.readAt(offset + filled, buffer, filled, wanted - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled == wanted) buffer else buffer.copyOf(filled)
    }

    override fun close() = access.close()
}

/** 通过 ContentResolver 读取本地 content:// 音频的头部标签（含内嵌歌词 / 采样率等）。 */
class ContentRandomReader(private val context: Context, private val uri: Uri) : RandomReader {
    override val size: Long by lazy {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }.getOrDefault(0L)
    }

    override fun readAt(offset: Long, length: Int): ByteArray {
        val stream = context.contentResolver.openInputStream(uri) ?: return ByteArray(0)
        stream.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, min(left, buffer.size))
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
            }
            return out.toByteArray()
        }
    }

    override fun close() {}
}

data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val embeddedLyrics: String? = null,
    val cover: ByteArray? = null,
    val coverMime: String = "image/jpeg",
    val durationMs: Long = 0L,
    val sampleRate: Int = 0,
    val bitrate: Int = 0,
    val channels: Int = 0
)

private class MutableTags {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var embeddedLyrics: String? = null
    var cover: ByteArray? = null
    var coverMime: String = "image/jpeg"
    var durationMs: Long = 0L
    var sampleRate: Int = 0
    var bitrate: Int = 0
    var channels: Int = 0
    fun build() = AudioTags(title, artist, album, embeddedLyrics, cover, coverMime, durationMs, sampleRate, bitrate, channels)
}

/**
 * 轻量标签解析：ID3v2(TIT2/TPE1/TALB/USLT/APIC)、ID3v1、FLAC(STREAMINFO/VORBIS_COMMENT/PICTURE)、
 * MPEG 帧头。用于 NAS 扫描时读取元数据与内嵌歌词，不依赖实验性 Media3 API。
 */
object TagReader {
    private const val MAX_HEAD = 512 * 1024

    fun read(reader: RandomReader): AudioTags {
        val out = MutableTags()
        val size = reader.size
        val limit = (if (size > 0) minOf(size, MAX_HEAD.toLong()) else MAX_HEAD.toLong()).toInt()
        val head = reader.readAt(0, limit)
        if (head.isEmpty()) return out.build()

        var audioOffset = 0L
        when {
            head.size >= 10 && head.ascii(0, 3) == "ID3" -> {
                val tagSize = synchsafe(head, 6)
                val total = (10 + tagSize).coerceAtMost(MAX_HEAD)
                val tag = if (total <= head.size) head.copyOfRange(0, total) else reader.readAt(0, total)
                parseId3v2(tag, out)
                audioOffset = total.toLong()
            }
            head.size >= 4 && head.ascii(0, 4) == "fLaC" -> {
                parseFlac(head, out, size)
            }
        }

        if (out.sampleRate == 0 && size > audioOffset) {
            val frame = reader.readAt(audioOffset, 8192)
            parseMpegFrame(frame, out, size, audioOffset)
        }

        if (size >= 128 && out.title == null) {
            val tail = reader.readAt(size - 128, 128)
            if (tail.size >= 128 && tail.ascii(0, 3) == "TAG") {
                out.title = tail.latin(3, 30)
                out.artist = tail.latin(33, 30)
                out.album = tail.latin(63, 30)
            }
        }
        return out.build()
    }

    private fun parseId3v2(tag: ByteArray, out: MutableTags) {
        val version = tag[3].toInt() and 0xFF
        var pos = 10
        while (pos + 10 <= tag.size) {
            val id = tag.ascii(pos, 4)
            if (id.isBlank() || !id.all { it.isLetterOrDigit() }) break
            val rawSize = beLong(tag, pos + 4, 4).toInt()
            val frameSize = if (version == 4) synchsafe(tag, pos + 4) else rawSize
            if (frameSize <= 0 || pos + 10 + frameSize > tag.size) break
            val data = tag.copyOfRange(pos + 10, pos + 10 + frameSize)
            when (id) {
                "TIT2" -> out.title = decodeTextFrame(data)
                "TPE1" -> out.artist = decodeTextFrame(data)
                "TALB" -> out.album = decodeTextFrame(data)
                "USLT" -> out.embeddedLyrics = parseUsIt(data)
                "APIC" -> parseApic(data)?.let { out.cover = it.first; out.coverMime = it.second }
            }
            pos += 10 + frameSize
        }
    }

    private fun decodeTextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return decode(data, 1, data.size - 1, data[0].toInt() and 0xFF).trim('\u0000', ' ').ifBlank { null }
    }

    private fun parseUsIt(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        var p = 4
        while (p < data.size && data[p] != 0.toByte()) p++
        if (encoding == 1 || encoding == 2) p++
        p++
        if (p >= data.size) return null
        return decode(data, p, data.size - p, encoding).trim('\u0000').ifBlank { null }
    }

    private fun parseApic(data: ByteArray): Pair<ByteArray, String>? {
        if (data.size < 4) return null
        val encoding = data[0].toInt() and 0xFF
        var mimeEnd = 1
        while (mimeEnd < data.size && data[mimeEnd] != 0.toByte()) mimeEnd++
        val mime = data.latin(1, mimeEnd - 1).ifBlank { "image/jpeg" }
        var p = mimeEnd + 1 + 1 // MIME 终止符 + 图片类型
        while (p < data.size && data[p] != 0.toByte()) p++
        if (encoding == 1 || encoding == 2) p++
        p++
        if (p >= data.size) return null
        return data.copyOfRange(p, data.size) to mime
    }

    private fun parseFlac(head: ByteArray, out: MutableTags, fileSize: Long) {
        var pos = 4
        while (pos + 4 <= head.size) {
            val first = head[pos].toInt() and 0xFF
            val last = (first and 0x80) != 0
            val type = first and 0x7F
            val blockLen = beLong(head, pos + 1, 3).toInt()
            if (blockLen < 0 || pos + 4 + blockLen > head.size) break
            val block = head.copyOfRange(pos + 4, pos + 4 + blockLen)
            when (type) {
                0 -> parseStreamInfo(block, out)
                4 -> parseVorbisComment(block, out)
                6 -> parsePicture(block, out)
            }
            pos += 4 + blockLen
            if (last) break
        }
        if (out.durationMs == 0L && out.bitrate == 0 && fileSize > 0 && out.sampleRate > 0) {
            // 无 STREAMINFO 时长时留给播放器上报，不做猜测
        }
    }

    private fun parseStreamInfo(b: ByteArray, out: MutableTags) {
        if (b.size < 18) return
        val packed = beLong(b, 10, 8)
        val sampleRate = (packed ushr 44).toInt() and 0xFFFFF
        val channels = ((packed ushr 41) and 0x7L).toInt() + 1
        val totalSamples = packed and 0xFFFFFFFFFL
        out.sampleRate = sampleRate
        out.channels = channels
        if (sampleRate > 0 && totalSamples > 0) {
            out.durationMs = totalSamples * 1000L / sampleRate
        }
    }

    private fun parseVorbisComment(b: ByteArray, out: MutableTags) {
        runCatching {
            var p = 0
            val vendorLen = leInt(b, p)
            p += 4 + vendorLen
            val count = leInt(b, p).coerceAtMost(512)
            p += 4
            repeat(count) {
                if (p + 4 > b.size) return
                val len = leInt(b, p)
                p += 4
                if (len < 0 || p + len > b.size) return
                val entry = String(b, p, len, Charsets.UTF_8)
                p += len
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    when (entry.substring(0, eq).uppercase()) {
                        "TITLE" -> out.title = entry.substring(eq + 1).ifBlank { null }
                        "ARTIST" -> out.artist = entry.substring(eq + 1).ifBlank { null }
                        "ALBUM" -> out.album = entry.substring(eq + 1).ifBlank { null }
                        "LYRICS", "UNSYNCEDLYRICS" -> out.embeddedLyrics = entry.substring(eq + 1).ifBlank { null }
                    }
                }
            }
        }
    }

    private fun parsePicture(b: ByteArray, out: MutableTags) {
        runCatching {
            var q = 4
            val mimeLen = beLong(b, q, 4).toInt()
            q += 4
            val mime = b.latin(q, mimeLen).ifBlank { "image/jpeg" }
            q += mimeLen
            val descLen = beLong(b, q, 4).toInt()
            q += 4 + descLen + 16 // 描述 + 宽/高/色深/色数
            val dataLen = beLong(b, q, 4).toInt()
            q += 4
            if (dataLen > 0 && q + dataLen <= b.size) {
                out.cover = b.copyOfRange(q, q + dataLen)
                out.coverMime = mime
            }
        }
    }

    private fun parseMpegFrame(frame: ByteArray, out: MutableTags, fileSize: Long, offset: Long) {
        val mpeg1Rates = intArrayOf(44100, 48000, 32000)
        val mpeg2Rates = intArrayOf(22050, 24000, 16000)
        val mpeg25Rates = intArrayOf(11025, 12000, 8000)
        val mpeg1Layer3Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val mpeg2Layer3Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

        for (i in 0 until frame.size - 4) {
            if ((frame[i].toInt() and 0xFF) != 0xFF || (frame[i + 1].toInt() and 0xE0) != 0xE0) continue
            val h = beLong(frame, i, 4)
            val version = (h ushr 19) and 3L
            val layer = (h ushr 17) and 3L
            if (version == 1L || layer != 1L) continue // 仅解析 MPEG Layer III
            val bitrateIndex = ((h ushr 12) and 0xFL).toInt()
            val rateIndex = ((h ushr 10) and 3L).toInt()
            val mode = (h ushr 6) and 3L
            if (rateIndex >= 3 || bitrateIndex !in 1..14) continue
            val rates = when (version) {
                3L -> mpeg1Rates
                2L -> mpeg2Rates
                else -> mpeg25Rates
            }
            val bitrateKbps = if (version == 3L) mpeg1Layer3Bitrates[bitrateIndex]
            else mpeg2Layer3Bitrates[bitrateIndex]
            out.sampleRate = rates[rateIndex]
            out.bitrate = bitrateKbps * 1000
            out.channels = if (mode == 3L) 1 else 2
            if (bitrateKbps > 0 && fileSize > offset) {
                out.durationMs = (fileSize - offset) * 8L / (bitrateKbps.toLong() * 1000L) * 1000L
            }
            return
        }
    }

    private fun decode(b: ByteArray, off: Int, len: Int, encoding: Int): String {
        if (len <= 0 || off + len > b.size) return ""
        val charset: Charset = when (encoding) {
            1 -> Charset.forName("UTF-16")
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return runCatching { String(b, off, len, charset) }.getOrDefault("")
    }

    private fun synchsafe(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)
    }

    private fun beLong(b: ByteArray, off: Int, len: Int): Long {
        if (off + len > b.size || len <= 0) return 0L
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun leInt(b: ByteArray, off: Int): Int {
        if (off + 4 > b.size) return 0
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.ascii(off: Int, len: Int): String =
        if (off + len > size) "" else String(this, off, len, Charsets.ISO_8859_1)

    private fun ByteArray.latin(off: Int, len: Int): String =
        ascii(off, len.coerceAtLeast(0)).trim('\u0000', ' ')
}
