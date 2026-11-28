package com.nasmusic.desktop.data

import com.nasmusic.desktop.model.Song
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面解析：把一首歌映射成可解码的图片字节。
 *
 * 查找顺序（命中即返回，代价从低到高）：
 * 1. Song.artworkUri —— NAS / Subsonic 音源已经给出封面地址（http），直接下载；
 * 2. 音频文件内嵌封面 —— MP3 的 ID3v2 APIC、FLAC 的 PICTURE block、M4A 的 covr atom；
 * 3. 歌曲同目录的同名图片（歌名.jpg）；
 * 4. 歌曲同目录的通用封面（cover/folder/front/album）。
 *
 * 都找不到就返回 null，UI 侧回退到默认黑胶盘面。
 *
 * 注意：全程只读文件头与元数据区，不解码音频；解析结果按 songId 做内存缓存，
 * 避免每次重组或切页都重新读盘。
 */
object CoverArt {

    /** 最近解析过的封面（值为 null 表示"确认没有封面"，避免反复读盘）。 */
    private val cache = ConcurrentHashMap<String, OptionalBytes>()

    /** 同目录通用封面文件名，按优先级排列。 */
    private val GENERAL_COVER_NAMES = listOf(
        "cover", "folder", "front", "album", "AlbumArt"
    )
    private val IMAGE_EXTS = listOf("jpg", "jpeg", "png", "webp")

    /** 包装一层以便把"没有封面"也缓存起来（ConcurrentHashMap 不允许 null value）。 */
    private class OptionalBytes(val bytes: ByteArray?)

    /**
     * 取封面字节。可能产生 IO，调用方应在 IO 线程执行。
     */
    fun load(song: Song): ByteArray? {
        val key = song.id.ifBlank { song.uri }
        cache[key]?.let { return it.bytes }
        val result = runCatching { resolve(song) }.getOrNull()
        cache[key] = OptionalBytes(result)
        return result
    }

    /** 清空缓存（音源重扫后可调用）。 */
    fun invalidate() = cache.clear()

    private fun resolve(song: Song): ByteArray? {
        // 1. 音源直接给出的封面地址
        song.artworkUri?.takeIf { it.isNotBlank() }?.let { uri ->
            readUri(uri)?.let { return it }
        }
        // 2~4. 本地文件：内嵌封面 → 同名图片 → 目录通用封面
        val file = localFile(song.uri) ?: return null
        if (!file.exists()) return null
        embeddedCover(file)?.let { return it }
        sameNameImage(file)?.let { return readFile(it) }
        generalFolderCover(file)?.let { return readFile(it) }
        return null
    }

    /** 把 file: / 绝对路径 / smb 之外的本地路径统一转成 File。 */
    private fun localFile(uri: String): File? {
        if (uri.isBlank()) return null
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) return null
        if (uri.startsWith("smb:", true)) return null
        if (uri.startsWith("file:", true)) {
            return runCatching { File(URI(uri)) }.getOrNull()
        }
        return File(uri)
    }

    private fun readUri(uri: String): ByteArray? = runCatching {
        when {
            uri.startsWith("http://", true) || uri.startsWith("https://", true) -> {
                val conn = URL(uri).openConnection().apply {
                    connectTimeout = 4000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "NasMusic-Desktop")
                }
                conn.getInputStream().use { it.readBytes() }.takeIf { it.isNotEmpty() }
            }
            else -> localFile(uri)?.takeIf { it.exists() }?.let { readFile(it) }
        }
    }.getOrNull()

    private fun readFile(f: File): ByteArray? =
        runCatching { f.readBytes().takeIf { it.isNotEmpty() } }.getOrNull()

    // ---------------------------------------------------------------- 同目录图片

    private fun sameNameImage(audio: File): File? {
        val base = audio.nameWithoutExtension
        val dir = audio.parentFile ?: return null
        for (ext in IMAGE_EXTS) {
            val f = File(dir, "$base.$ext")
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    private fun generalFolderCover(audio: File): File? {
        val dir = audio.parentFile ?: return null
        for (name in GENERAL_COVER_NAMES) {
            for (ext in IMAGE_EXTS) {
                val f = File(dir, "$name.$ext")
                if (f.isFile && f.length() > 0) return f
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 内嵌封面

    private fun embeddedCover(file: File): ByteArray? {
        if (file.length() < 16) return null
        return when (file.extension.lowercase()) {
            "mp3", "aac", "wav", "aif", "aiff", "ape" -> id3Cover(file)
            "flac" -> flacCover(file)
            "m4a", "mp4", "aac", "alac" -> mp4Cover(file)
            else -> id3Cover(file) ?: flacCover(file) ?: mp4Cover(file)
        }
    }

    /** MP3：扫 ID3v2 帧，取 APIC（v2.2 为 PIC）。 */
    private fun id3Cover(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) != 10) return null
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) return null

            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = syncSafe(header, 6)
            if (tagSize <= 0) return null

            var pos = 10L
            // 扩展头（v2.3/2.4 才有）
            if (flags and 0x40 != 0 && version >= 3) {
                raf.seek(pos)
                val ext = ByteArray(4)
                if (raf.read(ext) != 4) return null
                val extSize = if (version == 4) syncSafe(ext, 0) else beInt(ext, 0)
                pos += extSize.coerceAtLeast(0)
            }

            val end = 10L + tagSize
            val frameHeaderLen = if (version == 2) 6 else 10
            val idLen = if (version == 2) 3 else 4

            while (pos + frameHeaderLen <= end) {
                raf.seek(pos)
                val fh = ByteArray(frameHeaderLen)
                if (raf.read(fh) != frameHeaderLen) break
                if (fh[0] == 0.toByte()) break // 填充区，后面没有帧了
                val id = String(fh, 0, idLen, Charsets.ISO_8859_1)
                if (!id.all { it.isLetterOrDigit() }) break

                val frameSize = when (version) {
                    2 -> ((fh[3].toInt() and 0xFF) shl 16) or
                        ((fh[4].toInt() and 0xFF) shl 8) or (fh[5].toInt() and 0xFF)
                    4 -> syncSafe(fh, 4)
                    else -> beInt(fh, 4)
                }
                if (frameSize <= 0 || pos + frameHeaderLen + frameSize > end + 1) break

                if (id == "APIC" || id == "PIC") {
                    val body = ByteArray(frameSize)
                    raf.read(body)
                    return parseApic(body, version == 2)
                }
                pos += frameHeaderLen + frameSize
            }
            null
        }
    }.getOrNull()

    /** APIC/PIC 帧体解析：encoding + mime + pictureType + description + data。 */
    private fun parseApic(body: ByteArray, v22: Boolean): ByteArray? {
        if (body.size < 8) return null
        val encoding = body[0].toInt() and 0xFF
        var p = 1
        if (v22) {
            p += 3 // 3 字节图片格式，如 "JPG"
        } else {
            // MIME 以 0 结尾
            while (p < body.size && body[p] != 0.toByte()) p++
            p++ // 跳过结尾 0
        }
        p++ // pictureType
        // description：UTF-16 用双字节 0 结尾
        if (encoding == 1 || encoding == 2) {
            while (p + 1 < body.size && !(body[p] == 0.toByte() && body[p + 1] == 0.toByte())) p += 2
            p += 2
        } else {
            while (p < body.size && body[p] != 0.toByte()) p++
            p++
        }
        if (p >= body.size) return null
        return body.copyOfRange(p, body.size).takeIf { it.size > 64 }
    }

    /** FLAC：遍历元数据块，取 type=6（PICTURE）。 */
    private fun flacCover(file: File): ByteArray? = runCatching {
        BufferedInputStream(FileInputStream(file)).use { ins ->
            val magic = ByteArray(4)
            if (ins.read(magic) != 4) return@use null
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return@use null

            var last = false
            while (!last) {
                val head = ins.read()
                if (head < 0) return@use null
                last = head and 0x80 != 0
                val type = head and 0x7F
                val len = (ins.read() shl 16) or (ins.read() shl 8) or ins.read()
                if (len < 0) return@use null

                if (type == 6) {
                    val block = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = ins.read(block, read, len - read)
                        if (n <= 0) return@use null
                        read += n
                    }
                    // pictureType(4) + mimeLen(4) + mime + descLen(4) + desc + 16 字节尺寸信息 + dataLen(4) + data
                    var p = 4
                    val mimeLen = beInt(block, p); p += 4 + mimeLen
                    if (p + 4 > block.size) return@use null
                    val descLen = beInt(block, p); p += 4 + descLen + 16
                    if (p + 4 > block.size) return@use null
                    val dataLen = beInt(block, p); p += 4
                    if (dataLen <= 64 || p + dataLen > block.size) return@use null
                    return@use block.copyOfRange(p, p + dataLen)
                }
                var skipped = 0L
                while (skipped < len) {
                    val s = ins.skip(len - skipped)
                    if (s <= 0) return@use null
                    skipped += s
                }
            }
            null
        }
    }.getOrNull()

    /** M4A/MP4：moov → udta → meta → ilst → covr → data。 */
    private fun mp4Cover(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val covr = findCovr(raf, 0L, raf.length(), 0) ?: return@use null
            if (covr.second <= 0 || covr.second > Int.MAX_VALUE) return@use null
            // covr 的载荷里是一个或多个 data atom，取第一个 data 的图片数据
            val buf = ByteArray(covr.second.toInt())
            raf.seek(covr.first)
            raf.readFully(buf)
            extractDataAtom(buf)
        }
    }.getOrNull()

    /** 在给定区间内递归寻找 covr，返回其载荷的 (起始偏移, 长度)。 */
    private fun findCovr(raf: RandomAccessFile, start: Long, end: Long, depth: Int): Pair<Long, Long>? {
        if (depth > 6) return null
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val header = ByteArray(8)
            if (raf.read(header) != 8) return null
            var size = beInt(header, 0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L) {
                val wide = ByteArray(8)
                if (raf.read(wide) != 8) return null
                size = ((wide[0].toLong() and 0xFF) shl 56) or ((wide[1].toLong() and 0xFF) shl 48) or
                    ((wide[2].toLong() and 0xFF) shl 40) or ((wide[3].toLong() and 0xFF) shl 32) or
                    ((wide[4].toLong() and 0xFF) shl 24) or ((wide[5].toLong() and 0xFF) shl 16) or
                    ((wide[6].toLong() and 0xFF) shl 8) or (wide[7].toLong() and 0xFF)
                headerLen = 16L
            }
            if (size < headerLen) return null
            val bodyStart = pos + headerLen
            val bodyEnd = (pos + size).coerceAtMost(end)

            when (type) {
                "covr" -> return bodyStart to (bodyEnd - bodyStart)
                "moov", "udta", "trak", "mdia", "minf", "stbl", "ilst" -> {
                    // meta atom 在 ISO 规范里多 4 字节 version/flags
                    val childStart = if (type == "meta") bodyStart + 4 else bodyStart
                    findCovr(raf, childStart, bodyEnd, depth + 1)?.let { return it }
                }
            }
            pos += size
        }
        return null
    }

    /** 在 covr 载荷里找第一个 data atom，跳过 8 字节类型/区域标志。 */
    private fun extractDataAtom(buf: ByteArray): ByteArray? {
        var p = 0
        while (p + 8 <= buf.size) {
            val size = beInt(buf, p)
            val type = String(buf, p + 4, 4, Charsets.ISO_8859_1)
            if (size <= 8 || p + size > buf.size) return null
            if (type == "data") {
                // data: size(4) + 'data'(4) + typeFlag(4) + locale(4) + 图片数据
                val from = p + 16
                if (from >= p + size) return null
                return buf.copyOfRange(from, p + size).takeIf { it.size > 64 }
            }
            p += size
        }
        return null
    }

    // ---------------------------------------------------------------- 工具

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
