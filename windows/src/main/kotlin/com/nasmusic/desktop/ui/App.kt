package com.nasmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nasmusic.desktop.data.Base64CredentialCipher
import com.nasmusic.desktop.data.Lyrics
import com.nasmusic.desktop.data.LyricsLoader
import com.nasmusic.desktop.data.MusicLibrary
import com.nasmusic.desktop.data.PlaybackMemory
import com.nasmusic.desktop.data.PlaybackSnapshot
import com.nasmusic.desktop.model.NasSource
import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.playback.PlayerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 右栏叠加的二级页面：设置 / 音效 / 搜索 / 歌单详情。 */
private sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay
    data object Sound : Overlay
    data object Search : Overlay
    data class PlaylistDetail(val id: String) : Overlay
}

/**
 * 桌面端主界面：与 Android 2.3.9 横屏版同构的双栏布局。
 *
 * 左侧是常驻播放卡片（黑胶 + 歌词 + 播控），右侧是顶栏（发现/本地音乐/网络音源/我的 + 设置/搜索）
 * 加内容区；二级页面（设置、音效、搜索、歌单详情）在右栏内切换，左侧播放卡片始终不消失。
 */
@Composable
fun App() {
    val library = remember { MusicLibrary() }
    val player = remember { PlayerController() }
    DisposableEffect(player) { onDispose { player.close() } }
    LaunchedEffect(Unit) { library.load() }

    val songs by library.songs.collectAsState()
    val sources by library.sources.collectAsState()
    val playlists by library.playlists.collectAsState()
    val localRoots by library.localRoots.collectAsState()
    val scan by library.scan.collectAsState()
    val playback by player.state.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(HomeTab.DISCOVER) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var query by remember { mutableStateOf("") }

    val memory = remember { PlaybackMemory() }
    val latestPlayback by rememberUpdatedState(playback)

    // 上次播放恢复：等曲库就绪再装队列，且只在用户还没开始操作时执行
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(songs.size) {
        if (restored || songs.isEmpty() || playback.currentSong != null) return@LaunchedEffect
        restored = true
        val snapshot = memory.load() ?: return@LaunchedEffect
        val byId = songs.associateBy { it.id }
        val queue = snapshot.songIds.mapNotNull { byId[it] }
        if (queue.isNotEmpty()) {
            player.restore(queue, snapshot.index, snapshot.positionMs, snapshot.volume)
        }
    }

    // 定时落盘：播放进度变化频繁，节流到 5 秒一次；关窗时再补写一次
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            val p = latestPlayback
            if (p.queue.isNotEmpty()) {
                memory.save(
                    PlaybackSnapshot(
                        songIds = p.queue.map { it.id },
                        index = p.queueIndex.coerceAtLeast(0),
                        positionMs = p.positionMs.toLong(),
                        volume = p.volume
                    )
                )
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val p = latestPlayback
            if (p.queue.isNotEmpty()) {
                memory.save(
                    PlaybackSnapshot(
                        songIds = p.queue.map { it.id },
                        index = p.queueIndex.coerceAtLeast(0),
                        positionMs = p.positionMs.toLong(),
                        volume = p.volume
                    )
                )
            }
        }
    }

    // 睡眠定时器：Android 端是"到点只暂停，绝不自动开始播放"，这里语义一致
    var sleepEndsAt by remember { mutableStateOf(0L) }
    var sleepRemainingMs by remember { mutableStateOf(0L) }
    LaunchedEffect(sleepEndsAt) {
        if (sleepEndsAt <= 0L) {
            sleepRemainingMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            val left = sleepEndsAt - System.currentTimeMillis()
            sleepRemainingMs = left.coerceAtLeast(0L)
            if (left <= 0L) {
                if (latestPlayback.isPlaying) player.pause()
                sleepEndsAt = 0L
                sleepRemainingMs = 0L
                break
            }
            delay(1_000L)
        }
    }

    val favorites = remember(songs) { songs.filter { it.isFavorite } }
    val currentSongId = playback.currentSong?.id
    val currentIsFavorite = remember(songs, currentSongId) {
        currentSongId != null && songs.firstOrNull { it.id == currentSongId }?.isFavorite == true
    }

    // 歌词随当前歌曲切换重新加载（本地同名 .lrc），失败时静默显示占位
    var lyrics by remember { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(currentSongId) {
        lyrics = LyricsLoader.load(playback.currentSong)
    }

    /** 从某个列表的某首歌开始播放：把整张列表作为播放队列，切歌才连贯。 */
    val playFrom: (List<Song>, Song) -> Unit = { list, song ->
        if (list.isNotEmpty()) {
            val index = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            player.setQueue(list, index)
        }
    }

    // 切标签时退出二级页面并清空搜索词，避免下次进来还留着上次的关键词
    val goTab: (HomeTab) -> Unit = { next ->
        tab = next
        overlay = Overlay.None
        query = ""
    }

    NasTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // 窗口可自由拉伸：整体按较小边缩放，窄窗口也不至于挤成一团
            val scale = minOf(maxWidth / 1240.dp, maxHeight / 780.dp).coerceIn(0.72f, 1.18f)
            val paneWidth = (maxWidth * 0.28f).coerceIn(250.dp, 360.dp)

            Row(
                Modifier.fillMaxSize().padding(14.dp * scale),
                horizontalArrangement = Arrangement.spacedBy(14.dp * scale)
            ) {
                NowPlayingPane(
                    playback = playback,
                    lyrics = lyrics,
                    isFavorite = currentIsFavorite,
                    onToggleFavorite = { currentSongId?.let { library.toggleFavorite(it) } },
                    onPlayPause = { player.toggle() },
                    onNext = { player.next() },
                    onPrev = { player.prev() },
                    onSeek = { player.seek(it) },
                    onVolume = { player.setVolume(it.toDouble()) },
                    modifier = Modifier.width(paneWidth).fillMaxHeight(),
                    scale = scale
                )

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    DashboardTopBar(
                        active = tab,
                        onTab = goTab,
                        onSearch = { overlay = Overlay.Search; query = "" },
                        onSettings = { overlay = Overlay.Settings },
                        scale = scale
                    )

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (val current = overlay) {
                            Overlay.None -> when (tab) {
                                HomeTab.DISCOVER -> DiscoverFeed(
                                    songs = songs,
                                    favorites = favorites,
                                    playlists = playlists,
                                    queue = playback.queue,
                                    sourceCount = sources.size,
                                    statusMessage = scan.message.ifBlank { scan.error.orEmpty() },
                                    onPlayList = { list -> player.setQueue(list) },
                                    onOpenTab = goTab,
                                    onOpenPlaylist = { id -> overlay = Overlay.PlaylistDetail(id) },
                                    onOpenSound = { overlay = Overlay.Sound },
                                    onPlayRecent = { index ->
                                        val queue = playback.queue
                                        if (index in queue.indices) player.setQueue(queue, index)
                                    },
                                    scale = scale
                                )

                                HomeTab.LOCAL -> LibraryPane(
                                    songs = songs,
                                    currentSongId = currentSongId,
                                    query = query,
                                    onQuery = { query = it },
                                    onPlay = playFrom,
                                    onFavorite = { library.toggleFavorite(it) },
                                    onScan = { scope.launch { library.scanLocal() } },
                                    scanning = scan.running,
                                    scanMessage = scan.message,
                                    scale = scale
                                )

                                HomeTab.SOURCES -> SourceSettings(
                                    sources = sources,
                                    onSave = { draft ->
                                        library.addSource(
                                            name = draft.name,
                                            type = draft.type,
                                            baseUrl = draft.baseUrl,
                                            host = draft.host,
                                            port = draft.port,
                                            rootPath = draft.rootPath,
                                            username = draft.username,
                                            password = draft.password
                                        ).map { "已保存：${it.name}" }
                                    },
                                    onRemove = { library.removeSource(it) },
                                    onTestDraft = { draft ->
                                        library.testSource(
                                            NasSource(
                                                id = "draft",
                                                name = draft.name.ifBlank { "测试音源" },
                                                type = draft.type,
                                                baseUrl = draft.baseUrl,
                                                host = draft.host,
                                                port = draft.port,
                                                rootPath = draft.rootPath,
                                                username = draft.username,
                                                passwordEnc = Base64CredentialCipher()
                                                    .encrypt(draft.password)
                                            )
                                        )
                                    },
                                    onTestSaved = { library.testSource(it) },
                                    onRefresh = { library.refreshSource(it) }
                                )

                                HomeTab.MINE -> MinePane(
                                    playlists = playlists,
                                    favorites = favorites,
                                    songsInPlaylist = { library.songsInPlaylist(it) },
                                    currentSongId = currentSongId,
                                    onCreate = { name ->
                                        library.createPlaylist(name).onFailure {
                                            // 失败原因（空名/重名）由 UI 输入校验兜住，这里静默
                                        }
                                    },
                                    onOpenPlaylist = { id -> overlay = Overlay.PlaylistDetail(id) },
                                    onPlayList = { list -> player.setQueue(list) },
                                    onPlay = { song -> playFrom(favorites, song) },
                                    onFavorite = { library.toggleFavorite(it) },
                                    scale = scale
                                )
                            }

                            Overlay.Settings -> Column(Modifier.fillMaxSize()) {
                                PaneHeader("设置", onBack = { overlay = Overlay.None }, scale = scale)
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    SettingsPane(
                                        roots = localRoots,
                                        songCount = songs.size,
                                        scanMessage = scan.message,
                                        scanError = scan.error,
                                        scanning = scan.running,
                                        sleepRemainingMs = sleepRemainingMs,
                                        onSetSleepTimer = { minutes ->
                                            sleepEndsAt = if (minutes <= 0) 0L
                                            else System.currentTimeMillis() + minutes * 60_000L
                                        },
                                        onAddRoot = { path ->
                                            library.addLocalRoot(path).onFailure {
                                                // 目录不存在等错误由 scan.error 通道展示
                                            }
                                        },
                                        onRemoveRoot = { library.removeLocalRoot(it) },
                                        onScan = { scope.launch { library.scanLocal() } },
                                        scale = scale
                                    )
                                }
                            }

                            Overlay.Sound -> Column(Modifier.fillMaxSize()) {
                                PaneHeader("音效设置", onBack = { overlay = Overlay.None }, scale = scale)
                                Box(Modifier.weight(1f).fillMaxWidth()) { SoundPane(scale = scale) }
                            }

                            Overlay.Search -> SearchPane(
                                query = query,
                                onQuery = { query = it },
                                songs = songs,
                                currentSongId = currentSongId,
                                onPlay = playFrom,
                                onFavorite = { library.toggleFavorite(it) },
                                onBack = { overlay = Overlay.None },
                                scale = scale
                            )

                            is Overlay.PlaylistDetail -> {
                                val playlist = playlists.firstOrNull { it.id == current.id }
                                PlaylistDetailPane(
                                    title = playlist?.name ?: "歌单",
                                    songs = library.songsInPlaylist(current.id),
                                    currentSongId = currentSongId,
                                    onBack = { overlay = Overlay.None },
                                    onPlayList = { list -> player.setQueue(list) },
                                    onPlay = { song ->
                                        playFrom(library.songsInPlaylist(current.id), song)
                                    },
                                    onFavorite = { library.toggleFavorite(it) },
                                    scale = scale
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
