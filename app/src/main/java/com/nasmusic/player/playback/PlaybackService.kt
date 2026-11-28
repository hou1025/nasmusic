@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nasmusic.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
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
    private var noisyRegistered = false
    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val effectChain by lazy { AudioEffectChain(this) }
    private var soundSettingsJob: kotlinx.coroutines.Job? = null
    /** 音效配置缓存：Player.Listener 回调是同步的，不能在里面挂起读 DataStore。 */
    @Volatile
    private var currentSoundEffect = com.nasmusic.player.core.SoundEffectState()

    /**
     * 播放状态写盘节流：Player.Listener 的状态回调非常频繁（切歌/跳转/播放暂停等），
     * 每次都同步拼接队列并 SP.apply 会在大队列低端机上造成卡顿。
     * 这里把 1 秒内的多次变化合并成一次写；onDestroy 中无条件立即写一次兜底。
     * Runnable 持有 Service 引用，onDestroy 必须 removeCallbacks 避免泄漏。
     */
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveState() }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
        }
    }

    private val stateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = scheduleSaveState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = scheduleSaveState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = scheduleSaveState()
        override fun onRepeatModeChanged(repeatMode: Int) = scheduleSaveState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSaveState()
        /** ExoPlayer 的 audioSessionId 在渲染器建立后才分配，此时才能附加音效链。 */
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                effectChain.attach(audioSessionId, currentSoundEffect)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.get(this)

        // 自定义 NAS 数据源交给 DefaultDataSource 兜底处理 nas:// scheme
        val upstream = NasDataSourceFactory(container.nasRepository)
        val dataSourceFactory = DefaultDataSource.Factory(this, upstream)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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

        // 音效配置变化实时生效（换预设 / 调 EQ / 开关模块无需重启播放）
        soundSettingsJob = scope.launch {
            container.settings.settings.collect { settings ->
                val effect = settings.soundEffect
                currentSoundEffect = effect
                exoPlayer.audioSessionId
                    .takeIf { it != C.AUDIO_SESSION_ID_UNSET }
                    ?.let { effectChain.attach(it, effect) }
                    ?: effectChain.apply(effect)
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

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true

        restoreState(exoPlayer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        // 兜底：撤销尚未执行的合并写，并立即同步写入最后一次状态，保证最终一定落盘。
        saveHandler.removeCallbacks(saveRunnable)
        saveState()
        if (noisyRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
        soundSettingsJob?.cancel()
        effectChain.release()
        scope.cancel()
        mediaSession?.release()
        player?.release()
        mediaSession = null
        player = null
        super.onDestroy()
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
        val ids = (0 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        container.playbackState.save(
            SavedPlayback(
                ids = ids,
                index = p.currentMediaItemIndex.coerceIn(0, ids.lastIndex),
                positionMs = p.currentPosition,
                repeatMode = p.repeatMode,
                shuffle = p.shuffleModeEnabled,
                currentId = p.currentMediaItem?.mediaId.orEmpty()
                    .ifBlank { ids.getOrNull(p.currentMediaItemIndex).orEmpty() }
            )
        )
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
        /** 状态变化合并写盘的延迟（毫秒）。 */
        private const val SAVE_DEBOUNCE_MS = 1_000L
    }
}
