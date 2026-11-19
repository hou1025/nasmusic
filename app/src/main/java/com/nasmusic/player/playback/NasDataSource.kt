@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nasmusic.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.nasmusic.player.core.parseNasUri
import com.nasmusic.player.data.NasBrowser
import com.nasmusic.player.data.NasRandomAccess
import com.nasmusic.player.data.NasRepository
import java.io.IOException
import kotlin.math.min

/**
 * Media3 自定义数据源：把 nas:// 请求转换成 SMB / WebDAV / 飞牛音乐的按偏移读取。
 *
 * 关键性能点：Media3 每次 read() 只请求几十 KB，如果对每次 read 都发一个 HTTP Range 请求，
 * 一首歌前几秒就要几十次网络往返（中继场景尤其慢）。这里一次性预读 512KB 到内存缓冲，
 * 后续 read 直接从缓冲拷贝，把请求数降低一个数量级，显著缩短「点击到出声」的时间。
 */
class NasDataSource(private val nasRepository: NasRepository) : BaseDataSource(true) {

    companion object {
        private const val READ_AHEAD_BYTES = 512 * 1024
    }

    private var browser: NasBrowser? = null
    private var access: NasRandomAccess? = null
    private var dataSpec: DataSpec? = null
    private var currentPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false
    // 上游返回过真实 EOF 后置位：即使长度未知（bytesRemaining 为 Long.MAX_VALUE），
    // 下一次读取也立即结束，让 Media3 触发 STATE_ENDED 并自动切下一曲。
    private var reachedEof = false

    // 顺序预读缓冲：ahead[aheadPos until aheadLength) 对应文件 [aheadStart, …)
    private val ahead = ByteArray(READ_AHEAD_BYTES)
    private var aheadStart = 0L
    private var aheadLength = 0
    private var aheadPos = 0

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        val uriString = spec.uri.toString()
        val (sourceId, path) = parseNasUri(uriString)
            ?: throw IOException("无效的 NAS 音乐地址：$uriString")

        val b = nasRepository.browserFor(sourceId)
        browser = b
        val a = b.open(path)
        access = a

        val total = a.length
        val unknownLength = total <= 0
        if (!unknownLength && spec.position > total) {
            closeInternal()
            throw IOException("拖动位置超出文件长度")
        }
        val available = if (unknownLength) Long.MAX_VALUE else (total - spec.position).coerceAtLeast(0)
        val unbounded = spec.length == C.LENGTH_UNSET.toLong()
        val requested = if (unbounded) available else min(spec.length, available)

        currentPosition = spec.position
        bytesRemaining = requested
        dataSpec = spec
        aheadLength = 0
        aheadPos = 0
        reachedEof = false
        opened = true
        transferStarted(spec)
        return if (unknownLength && unbounded) C.LENGTH_UNSET.toLong() else requested
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (reachedEof || bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val source = access ?: throw IOException("NAS 数据源尚未打开")
        val wanted = min(length.toLong(), bytesRemaining).toInt()

        // 请求位置不在当前缓冲内（首次读取或拖动进度）时，一次拉取更大的连续块
        if (aheadLength <= 0 || aheadPos >= aheadLength ||
            currentPosition < aheadStart || currentPosition >= aheadStart + aheadLength
        ) {
            val chunk = min(READ_AHEAD_BYTES.toLong(), bytesRemaining).toInt()
            val filled = source.readAt(currentPosition, ahead, 0, chunk)
            if (filled <= 0) {
                reachedEof = true
                return C.RESULT_END_OF_INPUT
            }
            // 上游返回的字节数少于请求量（HTTP Range 到文件尾的正常表现）：
            // 把剩余量修正为实际读到的字节数。长度未知（bytesRemaining 为 MAX_VALUE）时，
            // 这样读完这批 bytesRemaining 归零，Media3 才能收到 EOF、触发 STATE_ENDED
            // 并自动切下一曲；否则会当成无限流永远播不完。
            if (filled < chunk) bytesRemaining = filled.toLong()
            aheadStart = currentPosition
            aheadLength = filled
            aheadPos = 0
        }

        val available = min(aheadLength - aheadPos, wanted)
        if (available <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(ahead, aheadPos, buffer, offset, available)
        aheadPos += available
        currentPosition += available
        bytesRemaining -= available
        bytesTransferred(available)
        return available
    }

    override fun getUri(): Uri? = if (opened) dataSpec?.uri else null

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        dataSpec = null
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { access?.close() }
        access = null
        runCatching { browser?.close() }
        browser = null
        bytesRemaining = 0L
    }
}

class NasDataSourceFactory(private val nasRepository: NasRepository) : DataSource.Factory {
    override fun createDataSource(): DataSource = NasDataSource(nasRepository)
}
