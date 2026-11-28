@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nasmusic.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.toMediaItem
import com.nasmusic.player.data.MusicRepository
import com.nasmusic.player.data.NasCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentSong: Song? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val queue: List<Song> = emptyList()
)

/** UI 侧统一通过 MediaController 控制 Service 中的播放器，UI 状态由播放器事件驱动刷新。 */
class PlaybackController(
    context: Context,
    private val musicRepository: MusicRepository,
    private val nasCache: NasCacheManager
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionToken = SessionToken(
        appContext, ComponentName(appContext, PlaybackService::class.java)
    )
    private val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()

    private var controller: MediaController? = null
    private var currentSongId: String? = null
    // 冷启动时 Controller 仍在异步连接，期间的用户操作先暂存，连接就绪后按序补发，
    // 避免"点了没反应"。补发只在主线程执行，与 Controller 回调线程一致。
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingActions += action
    }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            refreshCurrentSong()
            refreshQueue()
            publish()
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            refreshQueue()
            publish()
        }
        override fun onRepeatModeChanged(repeatMode: Int) = publish()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) = publish()
        override fun onPlayerError(error: PlaybackException) {
            val reason = error.message.orEmpty()
            _state.value = _state.value.copy(
                buffering = false,
                error = when {
                    reason.contains("Source error", true) || reason.contains("DataSource", true) ->
                        "播放失败：网络不可用、路径失效或没有访问权限，请检查后重试"
                    reason.isNotBlank() -> "播放失败：$reason"
                    else -> "播放失败，请重试"
                }
            )
        }
    }

    init {
        controllerFuture.addListener(
            {
                val c = runCatching { controllerFuture.get() }.getOrNull()
                if (c != null) {
                    controller = c
                    c.addListener(listener)
                    // 补发连接前暂存的用户操作
                    pendingActions.forEach { runCatching { it(c) } }
                    pendingActions.clear()
                    refreshCurrentSong()
                    refreshQueue()
                    publish()
                    startTicker()
                }
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                publish(fromTicker = true)
                delay(250L)
            }
        }
    }

    /**
     * 发布 UI 状态。250ms 心跳只在位置按 1s 粒度取整后才更新，
     * 且整体按值去抖（new == old 不发布），避免无变化也触发全页重组。
     */
    private fun publish(fromTicker: Boolean = false) {
        val c = controller
        val duration = c?.duration ?: C.TIME_UNSET
        val position = c?.currentPosition ?: 0L
        val publishedPosition =
            if (fromTicker && c?.isPlaying == true) (position / 1000L) * 1000L else position
        val old = _state.value
        val new = old.copy(
            connected = c != null,
            isPlaying = c?.isPlaying ?: false,
            positionMs = publishedPosition,
            durationMs = if (duration == C.TIME_UNSET || duration < 0) {
                old.currentSong?.durationMs ?: 0L
            } else duration,
            repeatMode = c?.repeatMode ?: Player.REPEAT_MODE_OFF,
            shuffle = c?.shuffleModeEnabled ?: false,
            buffering = c?.playbackState == Player.STATE_BUFFERING
        )
        if (new == old) return
        _state.value = new
    }

    private fun refreshCurrentSong() {
        val id = controller?.currentMediaItem?.mediaId
        if (id == currentSongId) return
        currentSongId = id
        scope.launch {
            val song = id?.let { runCatching { musicRepository.getById(it) }.getOrNull() }
            // 异步查库期间可能已连续切歌，只有结果仍对应当前曲目才写入，
            // 否则后到的旧查询会把 currentSong 覆盖成过期歌曲。
            if (controller?.currentMediaItem?.mediaId != id) return@launch
            _state.value = _state.value.copy(currentSong = song)
            publish()
        }
    }

    /**
     * 缓存被清理后重建队列 URI。
     *
     * 队列里的 NAS 曲目若命中过缓存，MediaItem 存的是 `file://…/xxx.cache`；缓存一删，
     * 播到那首就报「无法播放」。这里用最新 URI（缓存没了自然回落到在线地址）重建队列，
     * 并保持当前曲目、播放进度与播放状态。
     */
    fun refreshQueueUris() {
        withController { c ->
            val songs = _state.value.queue
            if (songs.isEmpty() || songs.size != c.mediaItemCount) return@withController
            val items = songs.map { it.toMediaItem(nasCache.playbackUri(it)) }
            val unchanged = (0 until c.mediaItemCount).all { i ->
                c.getMediaItemAt(i).localConfiguration?.uri == items[i].localConfiguration?.uri
            }
            if (unchanged) return@withController
            val index = c.currentMediaItemIndex
            val position = c.currentPosition
            val wasPlaying = c.isPlaying
            c.setMediaItems(items, index, position)
            c.prepare()
            if (wasPlaying) c.play()
        }
    }

    private var refreshQueueJob: Job? = null

    /**
     * 收藏 / 标签修改后强制重读当前曲目，让播放页的心形、歌名等信息立即刷新。
     *
     * [refreshCurrentSong] 有「mediaId 未变就直接返回」的短路逻辑，正常切歌够用，
     * 但同一首歌的数据被外部改动时不会被触发，所以这里单独留一个不求值 id 差异的入口。
     * 队列也一并重读，保证队列列表里的收藏标记同步更新。
     */
    fun reloadCurrentSong() {
        val id = controller?.currentMediaItem?.mediaId ?: currentSongId
        if (id == null) {
            refreshQueue()
            return
        }
        currentSongId = id
        scope.launch {
            val song = runCatching { musicRepository.getById(id) }.getOrNull()
            if (controller?.currentMediaItem?.mediaId != id) return@launch
            _state.value = _state.value.copy(currentSong = song)
            publish()
        }
        refreshQueue()
    }

    private fun refreshQueue() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        // 取消上一次未完成的队列查询：快速切歌时旧结果后到会把 queue 覆盖成过期列表。
        refreshQueueJob?.cancel()
        refreshQueueJob = scope.launch {
            val songs = runCatching { musicRepository.getByIds(ids) }.getOrDefault(emptyList())
            if (!isActive) return@launch
            _state.value = _state.value.copy(queue = songs)
        }
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        clearError()
        withController { c ->
            val items = songs.map { it.toMediaItem(nasCache.playbackUri(it)) }
            c.setMediaItems(items, startIndex.coerceIn(0, songs.lastIndex), 0L)
            c.prepare()
            c.play()
        }
    }

    fun playSong(song: Song) = playSongs(listOf(song), 0)

    fun addToQueue(songs: List<Song>) {
        withController { c ->
            val items = songs.map { it.toMediaItem(nasCache.playbackUri(it)) }
            c.addMediaItems(items)
        }
    }

    /** 「下一首播放」：插在当前播放项之后，而不是追加到队尾（代码审查报告 #11）。 */
    fun playNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        clearError()
        withController { c ->
            val items = songs.map { it.toMediaItem(nasCache.playbackUri(it)) }
            val insertAt = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
            c.addMediaItems(insertAt.coerceAtMost(c.mediaItemCount), items)
        }
    }

    fun playPause() {
        clearError()
        withController { c ->
            if (c.isPlaying) c.pause() else {
                if (c.mediaItemCount == 0) return@withController
                c.play()
            }
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0L)
        withController { c ->
            c.seekTo(pos)
            publish()
        }
    }

    fun seekBy(deltaMs: Long) {
        withController { c -> seekTo((c.currentPosition) + deltaMs) }
    }

    fun cycleRepeatMode() {
        withController { c ->
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            publish()
        }
    }

    fun toggleShuffle() {
        withController { c ->
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            publish()
        }
    }

    fun retry() {
        clearError()
        withController { c ->
            c.prepare()
            c.play()
        }
    }

    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    fun stop() {
        withController { c ->
            c.stop()
            publish()
        }
    }

    /**
     * 删除网络音乐源后调用：移除队列中属于该源（mediaId 以 nas:<sourceId>: 开头）的曲目，
     * 若正在播放的歌也被移除则停止播放，避免继续读取已失效的源。
     *
     * 关键点：删的若不是当前播放项，必须**原地移除**而不是重建整个队列。
     * 原实现无条件 `setMediaItems(keep, 0, ...)`，会把正在播放的歌拽回队列第一首并重新
     * prepare，表现为「删了一个没在用的音源，当前播放却被打断/跳到别的歌」。
     */
    fun removeBySourceId(sourceId: String) {
        withController { c ->
            val prefix = "nas:$sourceId:"
            val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
            val removed = items.indices.filter { items[it].mediaId?.startsWith(prefix) == true }
            if (removed.isEmpty()) {
                // 该音源压根不在队列里，播放器完全不用动。
                refreshQueue()
                publish()
                return@withController
            }
            val currentId = c.currentMediaItem?.mediaId
            val removedCurrent = currentId != null && currentId.startsWith(prefix)
            // 从后往前按连续区间删除：区间高位先删，低位的索引才不会失效；
            // Media3 会保持当前曲目与播放进度不变，不打断无关的播放。
            var rangeStart = -1
            var rangeEnd = -1
            for (i in removed.asReversed()) {
                if (rangeEnd == -1) {
                    rangeStart = i
                    rangeEnd = i
                } else if (i == rangeStart - 1) {
                    rangeStart = i
                } else {
                    c.removeMediaItems(rangeStart, rangeEnd + 1)
                    rangeStart = i
                    rangeEnd = i
                }
            }
            if (rangeEnd != -1) c.removeMediaItems(rangeStart, rangeEnd + 1)

            if (removedCurrent || c.mediaItemCount == 0) {
                c.stop()
                currentSongId = null
                refreshCurrentSong()
            }
            refreshQueue()
            publish()
        }
    }
}
