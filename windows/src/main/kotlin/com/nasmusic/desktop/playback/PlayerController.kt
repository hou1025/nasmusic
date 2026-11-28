package com.nasmusic.desktop.playback

import com.nasmusic.desktop.model.Song
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 播放器对外暴露的状态，Compose 用 collectAsState 订阅。 */
data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Double = 0.0,
    val durationMs: Double = 0.0,
    val volume: Double = 1.0,
    val error: String? = null,
    /** 当前播放队列（含正在播放的这首），供「最近播放」等界面展示。 */
    val queue: List<Song> = emptyList(),
    /** 当前曲目在 [queue] 中的下标，-1 表示没有队列。 */
    val queueIndex: Int = -1
)

/**
 * 基于 JavaFX Media 的播放器封装。
 *
 * 关键说明：
 * - MediaPlayer 依赖 JavaFX 应用线程，所有调用经 Platform.runLater 派发。
 * - 支持 mp3/wav/aac/m4a，flac 支持有限（取决于系统解码器），详见 README。
 * - 进度与时长通过属性监听写入 StateFlow，UI 无需轮询。
 * - 网络音源若需自定义请求头（如飞牛网关），需先经本地代理，见各客户端注释。
 */
class PlayerController : AutoCloseable {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var index: Int = -1

    init {
        // 初始化 JavaFX 工具包；若已被初始化会抛异常，忽略即可。
        runCatching { Platform.startup { } }
    }

    /** 设置播放队列并从指定位置开始。 */
    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        queue = songs
        index = if (songs.isEmpty()) -1 else startIndex.coerceIn(0, songs.lastIndex)
        if (songs.isEmpty()) stop() else start(songs[index])
    }

    /** 播放单曲（同时把队列重置为该曲）。 */
    fun play(song: Song) {
        queue = listOf(song)
        index = 0
        start(song)
    }

    fun play() {
        runOnFx {
            player?.play()
            _state.value = _state.value.copy(isPlaying = true, error = null)
        }
    }

    fun pause() {
        runOnFx {
            player?.pause()
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    fun toggle() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun next() = step(1)

    fun prev() = step(-1)

    private fun step(delta: Int) {
        if (queue.isEmpty()) return
        val target = index + delta
        if (target !in queue.indices) {
            // 到队尾/队首时停住，不循环（与 Android 端默认行为一致）。
            pause()
            return
        }
        index = target
        start(queue[index])
    }

    /** 跳转到指定毫秒位置。 */
    fun seek(positionMs: Long) {
        runOnFx {
            player?.seek(Duration.millis(positionMs.toDouble()))
            _state.value = _state.value.copy(positionMs = positionMs.toDouble())
        }
    }

    /** 音量范围 0.0 ~ 1.0。 */
    fun setVolume(volume: Double) {
        val value = volume.coerceIn(0.0, 1.0)
        runOnFx {
            player?.volume = value
            _state.value = _state.value.copy(volume = value)
        }
    }

    /**
     * 恢复上次播放：装好队列与进度但不自动出声（用户可能只是打开应用看一眼）。
     * 音量一并恢复，避免"上次调小、这次又炸响"。
     */
    fun restore(songs: List<Song>, index: Int, positionMs: Long, volume: Double) {
        if (songs.isEmpty()) return
        queue = songs
        this.index = index.coerceIn(0, songs.lastIndex)
        val safeVolume = volume.coerceIn(0.0, 1.0)
        _state.value = _state.value.copy(volume = safeVolume)
        start(queue[this.index], autoPlay = false, startPositionMs = positionMs)
    }

    private fun start(song: Song, autoPlay: Boolean = true, startPositionMs: Long = 0L) {
        runOnFx {
            try {
                player?.dispose()
                val media = Media(song.uri)
                val newPlayer = MediaPlayer(media)
                newPlayer.volume = _state.value.volume

                newPlayer.currentTimeProperty().addListener { _, _, time ->
                    _state.value = _state.value.copy(positionMs = time.toMillis())
                }
                // JavaFX 没有 durationProperty()：可观测的总时长是 totalDurationProperty()
                newPlayer.totalDurationProperty().addListener { _, _, duration ->
                    if (duration != null && !duration.isUnknown) {
                        _state.value = _state.value.copy(durationMs = duration.toMillis())
                    }
                }
                newPlayer.setOnReady {
                    // MediaPlayer 没有 getDuration()，总时长要读 totalDuration（就绪后才有效）
                    val total = newPlayer.totalDuration
                    if (total != null && !total.isUnknown) {
                        _state.value = _state.value.copy(durationMs = total.toMillis())
                    }
                    if (startPositionMs > 0L) {
                        newPlayer.seek(Duration.millis(startPositionMs.toDouble()))
                    }
                    if (autoPlay) {
                        newPlayer.play()
                        _state.value = _state.value.copy(isPlaying = true, error = null)
                    } else {
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            positionMs = startPositionMs.toDouble(),
                            error = null
                        )
                    }
                }
                newPlayer.setOnEndOfMedia {
                    if (index < queue.lastIndex) {
                        index++
                        start(queue[index])
                    } else {
                        _state.value = _state.value.copy(isPlaying = false)
                    }
                }
                newPlayer.setOnError {
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        error = "播放失败：${newPlayer.error?.message ?: "未知错误"}（格式不受支持或地址不可达）"
                    )
                }

                player = newPlayer
                _state.value = PlaybackState(
                    currentSong = song,
                    isPlaying = false,
                    volume = _state.value.volume,
                    queue = queue,
                    queueIndex = index
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    currentSong = song,
                    isPlaying = false,
                    error = "无法播放「${song.title}」：${e.message ?: "地址或格式无效"}"
                )
            }
        }
    }

    private fun stop() {
        runOnFx {
            player?.stop()
            player?.dispose()
            player = null
            _state.value = PlaybackState(volume = _state.value.volume)
        }
    }

    /** Compose 线程不在 FX 线程上，统一在此派发。 */
    private fun runOnFx(block: () -> Unit) {
        try {
            Platform.runLater { block() }
        } catch (e: IllegalStateException) {
            // 工具包尚未启动时降级为直接执行，避免界面卡死。
            block()
        }
    }

    override fun close() {
        runOnFx {
            player?.stop()
            player?.dispose()
            player = null
        }
        // 不调用 Platform.exit()，由宿主应用生命周期负责退出。
    }
}
