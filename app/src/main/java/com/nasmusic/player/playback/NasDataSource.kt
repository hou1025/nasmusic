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
 * 一首歌前几秒就要几十次网络往返（中继场景尤其慢）。这里一次性预读一大块到内存缓冲，
 * 后续 read 直接从缓冲拷贝，把请求数降低一个数量级。
 *
 * 块大小是渐进放大的：16KB → 32KB → 64KB → 128KB → 256KB → 512KB。
 * 原因是"一次必须凑满 512KB/96KB 才返回"在飞牛 FN Connect 中继、弱 WiFi 下要等好几秒，
 * 而这段时间全部落在「点歌到出声」上；播放器只要 250ms 数据（约 8~16KB）就能出声，
 * 所以首块压到 16KB，且短读立即交给播放器，不再死等凑满。
 * 之后逐步放大，请求数几乎不变（一首 5MB 的曲子 10~12 次请求），但起播快得多。
 */
class NasDataSource(private val nasRepository: NasRepository) : BaseDataSource(true) {

    companion object {
        /** 首次读取块：播放器起播阈值已降至 250ms，16KB 足够立即出声。 */
        private const val FIRST_CHUNK_BYTES = 16 * 1024
        /** 预读块上限：一次 512KB 把 HTTP Range 请求数压低一个数量级。 */
        private const val READ_AHEAD_BYTES = 512 * 1024
    }

    private var browser: NasBrowser? = null
    private var access: NasRandomAccess? = null
    private var dataSpec: DataSpec? = null
    private var currentPosition = 0L
    private var bytesRemaining = 0L
    private var totalLength = 0L
    private var opened = false
    /** 当前预读块大小：每次成功读取翻倍，直到 READ_AHEAD_BYTES。拖动后重新从最小块开始。 */
    private var chunkSize = FIRST_CHUNK_BYTES
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
        // 扫描入库时已把文件大小写进 URI，直接复用可省掉飞牛 Range 0-0 长度探测（中继要 2~5 秒）
        val sizeHint = spec.uri.getQueryParameter("sz")?.toLongOrNull() ?: 0L

        var b = nasRepository.browserFor(sourceId)
        val a = try {
            b.open(path)
        } catch (e: IOException) {
            // 连接已失效：让仓库销毁缓存的浏览器，重建一次再试
            nasRepository.invalidateBrowser(sourceId)
            b = nasRepository.browserFor(sourceId)
            b.open(path)
        }
        browser = b
        access = a

        // 有 URI 带过来的大小就用，不再触发 a.length（飞牛会为此发一次 Range 0-0 往返）
        val total = if (sizeHint > 0) sizeHint else a.length
        totalLength = total
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
        chunkSize = FIRST_CHUNK_BYTES
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
            // 非顺序前进（用户往回/往前拖进度）时回到最小块：拖动同样要尽快出声，
            // 不能因为整首已经进入大块模式就按 512KB 等一次。
            if (aheadLength > 0 && currentPosition != aheadStart + aheadLength) {
                chunkSize = FIRST_CHUNK_BYTES
            }
            // 块大小渐进放大：首块小、起播快，之后逐步变大以减少 Range 请求次数
            val chunk = min(chunkSize.toLong(), bytesRemaining).toInt()
            // 首次读取（起播关键路径）：拿到一小段可用数据就立刻交给播放器出声，
            // 不为了凑满整块反复发 Range。播放器有 250ms 缓冲就会开始解码。
            // 后续块仍走补齐循环，保证稳定播放时请求数不膨胀。
            val firstFill = aheadLength <= 0 && !reachedEof
            var filled = 0
            if (firstFill) {
                val n = source.readAt(currentPosition, ahead, 0, chunk)
                if (n > 0) filled = n
            } else {
                // 短读补齐：HTTP Range 到文件尾、FN Connect 中继限速都可能一次只返回部分字节，
                // 绝不能把短读直接当 EOF，否则播到一半就自动切下一曲。
                while (filled < chunk) {
                    val n = source.readAt(currentPosition + filled, ahead, filled, chunk - filled)
                    if (n <= 0) break
                    filled += n
                }
            }
            if (filled <= 0) {
                reachedEof = true
                return C.RESULT_END_OF_INPUT
            }
            val reachedPhysicalEnd = totalLength > 0 && currentPosition + filled >= totalLength
            // 只有真到文件尾才把剩余量修正为实际读到的字节数。长度未知时（bytesRemaining
            // 为 MAX_VALUE），这样读完这批即归零，Media3 收到 EOF 才会自动切下一曲。
            // 中继短读但没到文件尾时不要抛错：已读到的字节是有效音频，接受后下次从新位置
            // 继续请求即可；旧实现在这里抛 IOException，弱网下会直接把播放搞停。
            if (filled < chunk && reachedPhysicalEnd) bytesRemaining = filled.toLong()
            aheadStart = currentPosition
            aheadLength = filled
            aheadPos = 0
            // 只有在整块读满时才放大块尺寸：网络不稳出现短读时保持小块，保住流畅度
            if (filled >= chunk) {
                chunkSize = (chunkSize * 2).coerceAtMost(READ_AHEAD_BYTES)
            }
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
