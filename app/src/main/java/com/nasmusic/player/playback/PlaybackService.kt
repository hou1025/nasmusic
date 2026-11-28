@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nasmusic.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nasmusic.player.core.AppContainer
import com.nasmusic.player.core.SavedPlayback
import com.nasmusic.player.core.toMediaItem
import com.nasmusic.player.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放逻辑全部放在 Service 中：Media3 MediaSessionService + ExoPlayer。
 * 音频焦点由 ExoPlayer(handleAudioFocus=true) 统一处理（来电/导航/其他应用），
 * 耳机拔出与蓝牙断开通过 ACTION_AUDIO_BECOMING_NOISY 暂停。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effectChain by lazy { AudioEffectChain(this) }
    private var soundSettingsJob: kotlinx.coroutines.Job? = null

    /**
     * 部分唤醒锁：Manifest 里申请了 WAKE_LOCK 却从未真正持有，是「息屏后台播放卡顿/断音」
     * 的系统级根因。前台服务只保进程，不保 CPU；屏幕关闭后 CPU 进入 suspend，
     * ExoPlayer 的音频缓冲区填不进去，表现为周期性断音（本地/网络歌都会）。
     * 只在播放时持有，暂停/出错/销毁立即释放，不设超时（由状态回调精确控制）。
     */
    private var wakeLock: PowerManager.WakeLock? = null
    private fun acquireWakeLock() {
        val lock = wakeLock ?: runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NasMusic:Playback")
                .apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return
        if (!lock.isHeld) runCatching { lock.acquire() }
    }
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
    }

    /** 音效配置缓存：Player.Listener 回调是同步的，不能在里面挂起读 DataStore。 */
    @Volatile
    private var currentSoundEffect = com.nasmusic.player.core.SoundEffectState()
    /** 当前 ExoPlayer 的 audio session，供设置变更时在 IO 线程重新附加音效链。 */
    @Volatile
    private var effectSessionId = C.AUDIO_SESSION_ID_UNSET

    /**
     * 播放状态写盘节流：Player.Listener 的状态回调非常频繁（切歌/跳转/播放暂停等），
     * 每次都同步拼接队列并 SP.apply 会在大队列低端机上造成卡顿。
     * 这里把 1 秒内的多次变化合并成一次写；onDestroy 中无条件立即写一次兜底。
     * Runnable 持有 Service 引用，onDestroy 必须 removeCallbacks 避免泄漏。
     */
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveState() }

    /** 下一首预载任务：同一时刻只允许一个，避免连续切歌堆出一串并发下载互相抢带宽。 */
    private var preloadJob: kotlinx.coroutines.Job? = null
    private val preloadMutex = kotlinx.coroutines.sync.Mutex()
    /** 正在预载的曲目 id：同一首重复触发时直接跳过，避免把已下载的部分打断重来。 */
    private var preloadingSongId: String? = null

    private val stateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = scheduleSaveState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scheduleSaveState()
            // 切歌即开始后台预载下一首，把「点下一首才开始拉数据」的等待提前消化掉
            preloadNext()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            scheduleSaveState()
            // 播放时持有唤醒锁、暂停时释放：息屏后台不再被系统挂起 CPU 导致断音
            if (isPlaying) { acquireWakeLock(); preloadNext() } else releaseWakeLock()
        }
        override fun onRepeatModeChanged(repeatMode: Int) = scheduleSaveState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSaveState()
        /** ExoPlayer 的 audioSessionId 在渲染器建立后才分配，此时才能附加音效链。 */
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            // 关键：AudioEffect 的构造/释放是 Binder 同步调用，实测在车机/低端机上单个
            // 就要十几到几十毫秒，6 个连建会阻塞主线程上百毫秒 → 表现为切歌卡顿/掉帧。
            // 这里切到 IO 线程做，并且「原生直通」模式下完全不创建效果器。
            effectSessionId = audioSessionId
            scope.launch(Dispatchers.IO) { effectChain.attach(audioSessionId, currentSoundEffect) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)

        // 自定义 NAS 数据源交给 DefaultDataSource 兜底处理 nas:// scheme
        val upstream = NasDataSourceFactory(container.nasRepository)
        val dataSourceFactory = DefaultDataSource.Factory(this, upstream)

        // 缓冲策略：起播速度优先。
        // 旧值 minBuffer=30s / bufferForPlayback=1.2s / afterRebuffer=4s，
        // 意味着点下播放后 ExoPlayer 最少要攒够 1.2 秒、重新缓冲后要攒 4 秒才出声，
        // 本地文件明明瞬间可读，也要等这几个阈值 → 用户感知「过几秒才开始」。
        // 新值：起播 250ms、重缓冲 750ms，本地/已缓存文件一点就响；
        // 网络曲目的抗抖动交给 NasDataSource 的预读和大 maxBuffer，不靠起播阈值。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,   // minBufferMs：保留足够解码余量，但不再拖慢起播
                120_000,  // maxBufferMs：弱网/中继下仍可缓冲到 120 秒抗抖动
                250,      // bufferForPlaybackMs：起播阈值（旧 1200）
                750       // bufferForPlaybackAfterRebufferMs（旧 4000）
            )
            .setBackBuffer(45_000, true)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            // 耳机拔出/蓝牙断开交给 ExoPlayer 自己处理（ AudioAttributes 第二参 true 已在
            // handleAudioFocus 中涵盖音频焦点；这里显式开启 noisy 处理，删掉自注册的
            // BroadcastReceiver，避免两个监听重复 pause 产生状态抖动）。
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()
        exoPlayer.addListener(stateListener)
        player = exoPlayer
        // 服务重建/恢复播放时 onIsPlayingChanged 可能不会立即回调，这里按当前状态兜底申请
        if (exoPlayer.isPlaying) acquireWakeLock()

        // 音效配置变化实时生效（换预设 / 调 EQ / 开关模块无需重启播放）
        soundSettingsJob = scope.launch {
            container.settings.settings.collect { settings ->
                val effect = settings.soundEffect
                currentSoundEffect = effect
                // 同上：设置变化也要在 IO 线程重建效果器，避免阻塞主线程
                val sid = effectSessionId
                if (sid != C.AUDIO_SESSION_ID_UNSET) {
                    scope.launch(Dispatchers.IO) { effectChain.attach(sid, effect) }
                } else {
                    scope.launch(Dispatchers.IO) { effectChain.apply(effect) }
                }
            }
        }

        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()

        restoreState(exoPlayer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        // 兜底：撤销尚未执行的合并写，并立即同步写入最后一次状态，保证最终一定落盘。
        saveHandler.removeCallbacks(saveRunnable)
        saveState()
        releaseWakeLock()
        soundSettingsJob?.cancel()
        effectChain.release()
        scope.cancel()
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }

    /**
     * 预载下一首：网络音源的切歌卡顿，绝大多数来自「点了下一首才开始建连接、拉数据」。
     *
     * 当前曲目稳定播放后，后台把下一首完整缓存到本地（复用 NasCache 的 LRU 缓存），
     * 缓存成功后只把「队列中的非当前项」URI 换成本地文件，切过去直接读磁盘，做到零等待。
     *
     * 三条保护，避免优化本身变成新的卡顿源：
     * 1. 先等 3 秒，且当前曲目已缓冲 15 秒以上才动手，绝不和正在播放的曲目抢带宽；
     * 2. 用 Mutex.tryLock 串行化，上一个预载还没完成时新请求直接放弃，不堆积并发下载；
     * 3. 只替换非当前项，正在播放的曲目 URI 永远不动。
     */
    private fun preloadNext() {
        val snapshot = player ?: return
        val index = snapshot.currentMediaItemIndex
        if (index < 0 || index + 1 >= snapshot.mediaItemCount) return
        val next = snapshot.getMediaItemAt(index + 1)
        val songId = next.mediaId
        if (songId.isBlank()) return
        // 已经是 file:// 说明本就落在本地（本地音乐或之前缓存过），无需预载
        if (next.localConfiguration?.uri?.scheme == "file") return
        // 同一首歌的预载正在跑就别重启：onIsPlayingChanged / onMediaItemTransition
        // 会连续触发，重启会把已经下载的部分作废，反而更慢更占带宽。
        if (songId == preloadingSongId && preloadJob?.isActive == true) return
        preloadJob?.cancel()
        preloadingSongId = songId

        preloadJob = scope.launch {
            delay(2_000)
            val p = player ?: return@launch
            // 当前曲目缓冲不足时不预载：中继带宽有限，抢带宽反而会把正在放的歌搞卡
            val bufferedAhead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
            if (bufferedAhead < 25_000L) return@launch
            if (!preloadMutex.tryLock()) return@launch
            try {
                val song = withContext(Dispatchers.IO) {
                    container.musicRepository.getById(songId)
                } ?: return@launch
                if (!song.isNas) return@launch

                val cached = withContext(Dispatchers.IO) { container.nasCache.isCached(song) }
                if (!cached) {
                    // 下载过程中持续给播放让路：当前曲目缓冲掉到 12 秒以下就暂停，
                    // 等 ExoPlayer 把缓冲喂饱再继续。这是"预载不把正在播的歌搞卡"的关键。
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            container.nasCache.cacheSong(song) {
                                val cur = player
                                cur == null || (cur.bufferedPosition - cur.currentPosition) >= 12_000L
                            }
                        }.getOrDefault(false)
                    }
                    if (!ok) return@launch
                }
                swapToLocal(songId)
            } finally {
                preloadMutex.unlock()
                preloadingSongId = null
            }
        }
    }

    /** 把队列里已缓存歌曲的 URI 换成本地文件；跳过当前播放项，避免打断播放。 */
    private fun swapToLocal(songId: String) {
        val p = player ?: return
        val file = container.nasCache.cachedFile(songId) ?: return
        val localUri = android.net.Uri.fromFile(file).toString()
        for (i in 0 until p.mediaItemCount) {
            if (i == p.currentMediaItemIndex) continue
            val item = p.getMediaItemAt(i)
            if (item.mediaId != songId) continue
            if (item.localConfiguration?.uri?.scheme == "file") continue
            runCatching { p.replaceMediaItem(i, item.buildUpon().setUri(localUri).build()) }
        }
    }

    /** 播放记忆：恢复上次队列、播放位置、循环与随机模式。 */
    private fun restoreState(exoPlayer: ExoPlayer) {
        scope.launch(Dispatchers.IO) {
            // 全部磁盘 IO 放到 IO 线程池挂起执行，不在 Main 上 runBlocking 阻塞启动。
            val settings = container.settings.settings.first()
            if (!settings.restorePlayback) return@launch
            val saved = container.playbackState.restore() ?: return@launch
            val songs = container.musicRepository.getByIds(saved.ids)
            if (songs.isEmpty()) return@launch
            val items = songs.map { it.toMediaItem(container.nasCache.playbackUri(it)) }
            withContext(Dispatchers.Main) {
                // 恢复是异步的：若期间用户已通过控制器点歌（队列已被填充），
                // 此时再 setMediaItems 会覆盖用户刚发起的播放。跳过恢复，尊重用户操作。
                if (exoPlayer.mediaItemCount > 0) return@withContext
                if (items.isEmpty()) return@withContext

                // getByIds 会按过滤规则丢弃部分曲目，saved.index 在过滤后的队列里整体前移。
                // 用 currentId 反查真实位置：先定位原下标，再数它之前有多少首 survived。
                val originalIndex = saved.ids.indexOf(saved.currentId)
                    .takeIf { it >= 0 } ?: saved.index
                var index = items.indexOfFirst { it.mediaId == saved.currentId }
                if (index < 0) {
                    val keptBefore = saved.ids.take(originalIndex).count { id ->
                        items.any { it.mediaId == id }
                    }
                    index = keptBefore.coerceIn(0, items.lastIndex)
                }
                exoPlayer.setMediaItems(items, index.coerceIn(0, items.lastIndex), saved.positionMs)
                exoPlayer.repeatMode = saved.repeatMode
                exoPlayer.shuffleModeEnabled = saved.shuffle
                exoPlayer.prepare()
            }
        }
    }

    /** 合并写：取消上一次未执行的写盘，1 秒内无新变化时才真正落盘。 */
    private fun scheduleSaveState() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    private fun saveState() {
        val p = player ?: return
        // 在主线程只做 O(1) 的标量读取：队列 id 拼接 + SharedPreferences 写全部挪到 IO 线程。
        // 大队列（几千首）时旧实现每秒在主线程拼几万字符，是周期性的掉帧/音频抖动来源。
        val ids = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        val snapshot = SavedPlayback(
            ids = ids,
            index = p.currentMediaItemIndex.coerceIn(0, ids.lastIndex),
            positionMs = p.currentPosition,
            repeatMode = p.repeatMode,
            shuffle = p.shuffleModeEnabled,
            currentId = p.currentMediaItem?.mediaId.orEmpty()
                .ifBlank { ids.getOrNull(p.currentMediaItemIndex).orEmpty() }
        )
        scope.launch(Dispatchers.IO) { container.playbackState.save(snapshot) }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        /** 状态变化合并写盘的延迟（毫秒）。 */
        private const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
