@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nasmusic.player.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player?.pause()
        }
    }

    private val stateListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = saveState()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saveState()
        override fun onIsPlayingChanged(isPlaying: Boolean) = saveState()
        override fun onRepeatModeChanged(repeatMode: Int) = saveState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveState()
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
        saveState()
        if (noisyRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
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
                val index = saved.index.coerceIn(0, items.lastIndex.coerceAtLeast(0))
                exoPlayer.setMediaItems(items, index, saved.positionMs)
                exoPlayer.repeatMode = saved.repeatMode
                exoPlayer.shuffleModeEnabled = saved.shuffle
                exoPlayer.prepare()
            }
        }
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
                shuffle = p.shuffleModeEnabled
            )
        )
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
