@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nasmusic.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.nasmusic.player.core.Playlist
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.formatDuration
import com.nasmusic.player.data.Lyrics
import com.nasmusic.player.playback.PlaybackUiState
import kotlin.math.cos
import kotlin.math.sin

private val DiscoverRed = Color(0xFFF0334F)
private val DiscoverInk = Color(0xFF23252B)

/** 车机双栏右栏里可内嵌的二级页面：保持在发现页内，左侧播放卡片不随导航消失。 */
private sealed interface RightPane {
    data object None : RightPane
    // 搜索内嵌在右栏：左侧播放卡片常驻，不随导航消失
    data object Search : RightPane
    data class FnosLogin(val sourceId: String) : RightPane
    data class OnlineLogin(val sourceId: String) : RightPane
    data class NasBrowser(val sourceId: String, val path: String) : RightPane
    data class Playlist(val playlistId: String) : RightPane
    data object Settings : RightPane
    data object SoundEffect : RightPane
}

/**
 * 车机双栏首页：左侧常驻播放卡片（黑胶 + 播控），右侧发现 / 本地 / 网络 / 我的。
 * 版式参考 QQ 音乐车机版，但所有内容都来自本应用自己的曲库与音乐源，不含任何广告或推广位。
 */
@Composable
fun DiscoverScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    nav: NavController,
    // 横屏下从播放页/设置页点「音效」进入时，右栏直接落在音效页，左侧播放卡片常驻；
    // soundExit 为音效页的返回动作（一般为返回上一路由）。默认行为与发现页快捷入口一致。
    startInSound: Boolean = false,
    soundExit: (() -> Unit)? = null
) {
    val state by playerVm.state.collectAsStateWithLifecycle()
    val coverOverrides by playerVm.coverOverrides.collectAsStateWithLifecycle()
    val lyrics by playerVm.lyrics.collectAsStateWithLifecycle()
    val allSongs by vm.songs.collectAsStateWithLifecycle()
    val allFavorites by vm.favorites.collectAsStateWithLifecycle()
    val connectionStatus by vm.nasConnectionStatus.collectAsStateWithLifecycle()
    // 随机漫游 / 收藏 / 最近添加与歌曲 Tab 共用同一过滤口径，断连音源不会被建进播放队列
    val songs = remember(allSongs, connectionStatus) { allSongs.filterConnected(connectionStatus) }
    val favorites = remember(allFavorites, connectionStatus) {
        allFavorites.filterConnected(connectionStatus)
    }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val nasSources by vm.nasSources.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshNasConnections() }
    // 搜索词提升到发现页：搜索作为右栏二级页面，输入即在这里过滤
    val query by vm.query.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    // 右栏二级页面（FN 登录 / NAS 浏览 / 歌单详情）内嵌在发现页里，左侧播放卡片常驻不受影响。
    // startInSound=true 时（横屏独立音效入口）初始就落在音效页，同样保留左侧播放卡片。
    var rightPane by remember {
        mutableStateOf<RightPane>(if (startInSound) RightPane.SoundEffect else RightPane.None)
    }

    // 分屏后窗口可能只有半屏甚至 1/3 屏：按「窗口」尺寸整体缩小第一张图的双栏，
    // 不再切换到另一种布局——任何宽度下都保持左侧黑胶播放卡片 + 右侧标签页。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val uiScale = minOf(maxWidth / 900.dp, maxHeight / 700.dp).coerceIn(0.6f, 1f)
        val paneWidth = (maxWidth * 0.30f).coerceAtLeast(200.dp).coerceAtMost(340.dp)
        val artwork = state.currentSong?.let { coverOverrides[it.id] ?: it.artworkUri }
        // 点顶部标签时先退出二级页面，保证右侧切标签总能回到对应主内容
        val onTab: (Int) -> Unit = { tab = it; rightPane = RightPane.None }
        // 不再 nav.navigate("library")：那会把整个 NavHost 切成单页，
        // 左侧播放卡片消失、搜索结果又铺满全屏。改为右栏内嵌搜索页。
        val onSearch = {
            vm.setQuery("")
            rightPane = RightPane.Search
        }
        val backToTabs = { rightPane = RightPane.None; vm.setQuery("") }
        val tabs: @Composable ColumnScope.() -> Unit = {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val pane = rightPane) {
                    is RightPane.FnosLogin -> FnosLoginScreen(
                        vm = vm,
                        sourceId = pane.sourceId,
                        onBack = backToTabs,
                        onDone = backToTabs
                    )
                    is RightPane.OnlineLogin -> OnlineSourceLoginScreen(
                        vm = vm,
                        sourceId = pane.sourceId,
                        onBack = backToTabs,
                        onDone = backToTabs
                    )
                    is RightPane.NasBrowser -> NasBrowserScreen(
                        vm = vm,
                        playerVm = playerVm,
                        sourceId = pane.sourceId,
                        path = pane.path,
                        onBack = backToTabs,
                        onOpenFolder = { newPath ->
                            rightPane = RightPane.NasBrowser(pane.sourceId, newPath)
                        }
                    )
                    is RightPane.Playlist -> PlaylistDetailScreen(
                        vm = vm,
                        playerVm = playerVm,
                        playlistId = pane.playlistId,
                        onBack = backToTabs
                    )
                    RightPane.Search -> {
                        // 搜索只在右栏内展开，左侧 NowPlayingPane 始终不动；
                        // 返回按钮与切标签都回到原标签页，并清空关键词。
                        Column(Modifier.fillMaxSize()) {
                            SimpleTopBar(title = "搜索", onBack = backToTabs)
                            AppSearchBar(
                                query = query,
                                onQueryChange = { vm.setQuery(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp * uiScale, vertical = 8.dp * uiScale)
                            )
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (query.isBlank()) {
                                    EmptyState("输入歌名、歌手或专辑搜索")
                                } else {
                                    SearchResultsPanel(vm, playerVm, onPicked = backToTabs)
                                }
                            }
                        }
                    }
                    RightPane.Settings -> {
                        Column(Modifier.fillMaxSize()) {
                            SimpleTopBar(title = "设置", onBack = backToTabs)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                // 设置里的音效入口也内嵌右栏，避免跳到整页让左侧播放卡片消失
                                SettingsScreen(
                                    vm, playerVm, nav,
                                    onOpenSound = { rightPane = RightPane.SoundEffect }
                                )
                            }
                        }
                    }
                    RightPane.SoundEffect -> {
                        // 独立音效入口（横屏）返回时退出到上一路由；发现页内嵌入口则回到标签页。
                        val exitSound = soundExit ?: backToTabs
                        Column(Modifier.fillMaxSize()) {
                            SimpleTopBar(title = "音效设置", onBack = exitSound)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                SoundEffectScreen(vm, exitSound, embedded = true)
                            }
                        }
                    }
                    RightPane.None -> when (tab) {
                        0 -> DiscoverFeed(
                            playerVm = playerVm,
                            state = state,
                            songs = songs,
                            favorites = favorites,
                            playlists = playlists,
                            nasSourceCount = nasSources.size,
                            onOpenLocal = { tab = 1 },
                            onOpenSources = { tab = 2 },
                            onOpenPlaylist = { id -> rightPane = RightPane.Playlist(id) },
                            onOpenSound = { rightPane = RightPane.SoundEffect },
                            scale = uiScale
                        )
                        1 -> LibraryScreen(vm, playerVm, nav)
                        2 -> NasSourcesSection(
                            vm = vm,
                            nav = nav,
                            onFnosLogin = { id -> rightPane = RightPane.FnosLogin(id) },
                            onOnlineLogin = { id -> rightPane = RightPane.OnlineLogin(id) },
                            onBrowse = { id, path -> rightPane = RightPane.NasBrowser(id, path) }
                        )
                        else -> PlaylistScreen(
                            vm = vm,
                            playerVm = playerVm,
                            nav = nav,
                            onOpenPlaylist = { id -> rightPane = RightPane.Playlist(id) }
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                .padding(horizontal = 14.dp * uiScale, vertical = 12.dp * uiScale),
            horizontalArrangement = Arrangement.spacedBy(14.dp * uiScale)
        ) {
            NowPlayingPane(
                state = state,
                artwork = artwork,
                lyrics = lyrics,
                playerVm = playerVm,
                modifier = Modifier.width(paneWidth).fillMaxHeight()
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                DiscoverTopBar(
                    active = tab,
                    onTab = onTab,
                    onSearch = onSearch,
                    onOpenSettings = { rightPane = RightPane.Settings },
                    scale = uiScale
                )
                tabs()
            }
        }
    }
}

@Composable
private fun DiscoverTopBar(
    active: Int,
    onTab: (Int) -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit = {},
    scale: Float = 1f
) {
    val tabs = listOf("发现", "本地音乐", "网络音源", "我的")
    Row(
        Modifier.fillMaxWidth().height(52.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp * scale)
                .clip(CircleShape)
                .background(DiscoverRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = DiscoverRed,
                modifier = Modifier.size(18.dp * scale)
            )
        }
        Spacer(Modifier.width(12.dp * scale))
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, label ->
                val selected = active == index
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTab(index) }
                        .padding(horizontal = 14.dp * scale, vertical = 4.dp * scale)
                ) {
                    Text(
                        label,
                        color = if (selected) DiscoverRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp * scale)
                    )
                    Spacer(Modifier.height(4.dp * scale))
                    Box(
                        Modifier
                            .width(if (selected) 20.dp * scale else 0.dp)
                            .height(3.dp * scale)
                            .background(DiscoverRed, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
        // 设置键：与竖屏顶栏一致，直接可见的文字入口
        Text(
            "设置",
            color = DiscoverInk.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * scale),
            modifier = Modifier
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 8.dp * scale, vertical = 8.dp)
        )
        IconButton(onClick = onSearch, modifier = Modifier.size(48.dp * scale)) {
            Icon(
                Icons.Default.Search,
                contentDescription = "搜索",
                tint = DiscoverInk.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp * scale)
            )
        }
    }
}

@Composable
private fun NowPlayingPane(
    state: PlaybackUiState,
    artwork: String?,
    lyrics: Lyrics?,
    playerVm: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val song = state.currentSong
    val duration = (state.durationMs.takeIf { it > 0 } ?: song?.durationMs ?: 0L)
        .coerceAtLeast(1L).toFloat()
    val position = state.positionMs.coerceIn(0L, duration.toLong()).toFloat()

    BoxWithConstraints(modifier = modifier) {
        // 底部播控按钮区固定预留高度（主按钮约 64dp + 上下间距），
        // 上部内容只能用它之外的高度，避免矮窗口时把按钮挤出卡片。
        val controlsReserve = 92.dp
        val contentHeight = (maxHeight - controlsReserve).coerceAtLeast(150.dp)
        // 按「扣除按钮后的可用高度」缩放上部内容，下限放宽以适配更矮的横屏/分屏窗口
        val scale = minOf(maxWidth / 300.dp, contentHeight / 460.dp).coerceIn(0.42f, 1f)
        fun sp(value: Dp): Dp = value * scale
        // 歌词字号跟随窗口：同时受宽度与「可用高度」约束。
        // 横屏是宽而矮——只按宽度会把歌词放到 1.35 倍独占高度、挤掉按钮，
        // 因此取宽高两者较小值，宽大屏仍能放大，矮窗口则自动收小。
        val lyricScale = minOf(maxWidth / 300.dp, contentHeight / 460.dp).coerceIn(0.55f, 1.35f)
        Card(
            shape = RoundedCornerShape(sp(24.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                Modifier.fillMaxSize().padding(sp(16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 上部内容贴顶对齐：标题与黑胶紧靠卡片顶部，进度条/时间紧贴播控按钮。
                // 中间的弹性空白用 weight Spacer 放在歌词与歌名之间，两头都不留白。
                // 缩放已按「扣除按钮预留」计算，矮窗口也不会把按钮挤出卡片。
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Text(
                        "正在播放",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp * scale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = sp(6.dp))
                    )
                    Spacer(Modifier.height(sp(4.dp)))

                    VinylDisc(artwork = artwork, song = song, discSize = sp(138.dp), playing = state.isPlaying)

                    Spacer(Modifier.height(sp(4.dp)))
                    // 黑胶下方的三行滚动歌词（上一句 / 当前句 / 下一句），随播放进度切换并带动画
                    DiscLyrics(lyrics = lyrics, positionMs = state.positionMs, scale = lyricScale)

                    // 弹性空白落在这里：顶部贴标题、底部贴进度条，中间自然过渡
                    Spacer(Modifier.weight(1f).height(sp(2.dp)))
                    Text(
                        song?.title ?: "暂无播放歌曲",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp * scale),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(sp(2.dp)))
                    Text(
                        song?.artist?.ifBlank { "未知歌手" } ?: "从音乐列表选一首开始",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp * scale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )

                    Spacer(Modifier.height(sp(6.dp)))
                    Slider(
                        value = position,
                        onValueChange = { playerVm.seekTo(it.toLong()) },
                        valueRange = 0f..duration,
                        colors = SliderDefaults.colors(
                            thumbColor = DiscoverRed,
                            activeTrackColor = DiscoverRed,
                            inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth().height(sp(18.dp))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = sp(4.dp)),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatDuration(state.positionMs), fontSize = 11.sp * scale, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(state.durationMs.takeIf { it > 0 } ?: song?.durationMs ?: 0L),
                            fontSize = 11.sp * scale, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 底部播控按钮：紧贴上方的进度条/时间行，不留多余空隙
                val playProgress = (position / duration).coerceIn(0f, 1f)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DiscCircleButton(
                        onClick = { song?.let { playerVm.toggleFavorite(it) } },
                        size = sp(48.dp),
                        active = song?.isFavorite == true,
                        enabled = song != null,
                        scale = scale
                    ) {
                        Icon(
                            if (song?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (song?.isFavorite == true) DiscoverRed else DiscoverInk.copy(alpha = 0.75f),
                            modifier = Modifier.size(sp(26.dp))
                        )
                    }
                    DiscCircleButton(
                        onClick = { playerVm.playPause() },
                        size = sp(64.dp),
                        filled = true,
                        playing = state.isPlaying,
                        progress = playProgress,
                        scale = scale
                    ) {
                        DiscPlayPauseGlyph(playing = state.isPlaying, size = sp(34.dp), color = DiscoverInk)
                    }
                    DiscCircleButton(
                        onClick = { playerVm.next() },
                        size = sp(48.dp),
                        enabled = song != null,
                        scale = scale
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            tint = DiscoverInk.copy(alpha = 0.85f),
                            modifier = Modifier.size(sp(28.dp))
                        )
                    }
                }
            }
        }
    }
}

/**
 * 横屏播放卡片圆形按钮：与竖屏 CircleButton 同一套动画——
 * 按下 spring 回弹、触点柔光扩散、播放时呼吸光晕、主按钮进度环。
 */
@Composable
private fun DiscCircleButton(
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    active: Boolean = false,
    playing: Boolean = false,
    progress: Float = -1f,
    enabled: Boolean = true,
    scale: Float = 1f,
    content: @Composable () -> Unit
) {
    val foreground = DiscoverInk
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    var pressPoint by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press) pressPoint = event.pressPosition
        }
    }

    val pressScale by animateFloatAsState(
        if (pressed && enabled) 0.90f else 1f,
        spring(dampingRatio = 0.42f, stiffness = 420f)
    )
    val lift by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(160))

    var taps by remember { mutableStateOf(0) }
    val ripple = remember { Animatable(1f) }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        ripple.snapTo(0f)
        ripple.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    val rippleProgress by ripple.asState()

    val infinite = rememberInfiniteTransition()
    val breathe by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val breath = if (playing) 0.13f + 0.11f * breathe else 0f

    val fillBrush = Brush.radialGradient(
        listOf(
            foreground.copy(
                alpha = when {
                    filled -> 0.16f
                    active -> 0.13f
                    else -> 0.10f
                } + 0.05f * lift
            ),
            foreground.copy(alpha = 0.045f)
        )
    )
    val borderColor = foreground.copy(alpha = (if (filled) 0.24f else 0.16f) + 0.10f * lift)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            if (filled) {
                // 与竖屏 CircleButton 完全一致：呼吸光晕与进度环均用中性前景色
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(foreground.copy(alpha = breath * 0.55f), Color.Transparent)
                    ),
                    radius = radius * 1.36f
                )
                // 进度环：直接显示本曲播放进度，不旋转不闪烁
                if (progress >= 0f) {
                    val ringRadius = radius - 3.dp.toPx() * scale
                    drawCircle(
                        color = foreground.copy(alpha = 0.18f),
                        radius = ringRadius,
                        style = Stroke(width = 2.2.dp.toPx() * scale)
                    )
                    val sweep = 360f * progress.coerceIn(0f, 1f)
                    if (sweep > 0.5f) {
                        drawArc(
                            color = foreground.copy(alpha = 0.88f),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                            size = Size(ringRadius * 2f, ringRadius * 2f),
                            style = Stroke(width = 2.4.dp.toPx() * scale)
                        )
                        val end = Math.toRadians(-90.0 + sweep)
                        drawCircle(
                            color = foreground,
                            radius = 2.4.dp.toPx() * scale,
                            center = Offset(
                                center.x + ringRadius * cos(end).toFloat(),
                                center.y + ringRadius * sin(end).toFloat()
                            )
                        )
                    }
                }
            }
            // 触点柔光：从按下处扩散
            if (rippleProgress < 0.999f) {
                val p = rippleProgress
                val start = pressPoint ?: center
                val reach = radius * 1.12f * (0.35f + 0.65f * p)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            foreground.copy(alpha = (1f - p) * 0.20f),
                            Color.Transparent
                        ),
                        center = start,
                        radius = reach
                    ),
                    radius = reach,
                    center = start
                )
            }
        }
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .background(fillBrush, CircleShape)
                .border(1.dp * scale, borderColor, CircleShape)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                    taps++
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** 播放 / 暂停 morph：自绘三角形与双条，交叉淡入 + 轻微位移缩放，与竖屏一致。 */
@Composable
private fun DiscPlayPauseGlyph(playing: Boolean, size: Dp, color: Color) {
    val morph by animateFloatAsState(
        if (playing) 1f else 0f,
        spring(dampingRatio = 0.7f, stiffness = 520f)
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(size)
                .graphicsLayer {
                    alpha = 1f - morph
                    scaleX = 0.86f + 0.14f * (1f - morph)
                    scaleY = scaleX
                    translationY = -3f * morph
                }
        ) {
            val w = this.size.width
            val h = this.size.height
            drawPath(
                Path().apply {
                    moveTo(w * 0.34f, h * 0.20f)
                    lineTo(w * 0.82f, (h * 0.20f + h * 0.80f) / 2f)
                    lineTo(w * 0.34f, h * 0.80f)
                    close()
                },
                color = color
            )
        }
        Canvas(
            Modifier
                .size(size)
                .graphicsLayer {
                    alpha = morph
                    scaleX = 0.86f + 0.14f * morph
                    scaleY = scaleX
                    translationY = 3f * (1f - morph)
                }
        ) {
            val w = this.size.width
            val h = this.size.height
            val bar = w * 0.22f
            val gap = w * 0.16f
            val left = (w - bar * 2f - gap) / 2f
            val top = h * 0.20f
            val barHeight = h * 0.60f
            val radius = CornerRadius(bar * 0.30f)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(bar, barHeight),
                cornerRadius = radius
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(left + bar + gap, top),
                size = Size(bar, barHeight),
                cornerRadius = radius
            )
        }
    }
}

/**
 * 老式唱机黑胶：
 * - 唱片持续旋转（约 4 秒一圈），带高光扇区、径向亮纹与细密纹路，转动清晰可见；
 * - 播放时唱臂从右上角停泊位摆落到盘面，暂停时抬起归位；
 * - 唱针落点用红色高亮模拟唱头，整体呈现经典唱片机视觉。
 */
@Composable
private fun VinylDisc(
    artwork: String?,
    song: Song?,
    discSize: androidx.compose.ui.unit.Dp,
    playing: Boolean
) {
    val rotation = remember { Animatable(0f) }
    // 0f = 落针播放，1f = 抬针停泊
    val armSwing = remember { Animatable(1f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(4200, easing = LinearEasing)
                )
            }
        }
    }
    LaunchedEffect(playing) {
        armSwing.animateTo(
            targetValue = if (playing) 0f else 1f,
            animationSpec = spring(dampingRatio = 0.52f, stiffness = 110f)
        )
    }
    val angle by rotation.asState()
    val swing by armSwing.asState()

    // 容器比唱片大一圈，给唱臂支点留出空间
    val stageSize = discSize * 1.46f
    Box(Modifier.size(stageSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(discSize)
                .clip(CircleShape)
                .background(Color(0xFF131316))
                .graphicsLayer { rotationZ = angle },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.matchParentSize()) {
                val radius = discSize.toPx() / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                // 细密纹路：深浅交替，旋转时更容易看出盘在动
                for (i in 0 until 12) {
                    drawCircle(
                        color = Color.White.copy(alpha = if (i % 3 == 0) 0.09f else 0.045f),
                        radius = radius * (0.56f + i * 0.036f),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                // 随盘转动的反光扇区
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    ),
                    startAngle = -70f,
                    sweepAngle = 62f,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f)
                )
                // 径向亮纹：一个明显的旋转参照点
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(center.x + radius * 0.62f, center.y),
                    end = Offset(center.x + radius * 0.96f, center.y),
                    strokeWidth = 2.dp.toPx()
                )
            }
            Box(
                Modifier
                    .size(discSize * 0.76f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (artwork != null) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = song?.title ?: "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(discSize * 0.76f).clip(CircleShape)
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(discSize * 0.30f)
                    )
                }
            }
            Box(
                Modifier
                    .size(discSize * 0.09f)
                    .clip(CircleShape)
                    .background(Color(0xFFF6F6F8))
            )
        }

        // 唱臂：不随盘旋转，只在落针 / 抬针之间摆动
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val pivot = Offset(w * 0.94f, h * 0.10f)
            val armLen = discSize.toPx() * 0.42f
            // 落针时朝左下 135°；抬起时向右旋 32°，针尖离开盘面
            val dir = Math.toRadians(135.0 + 32.0 * swing).toFloat()
            val stylus = Offset(
                pivot.x + armLen * cos(dir),
                pivot.y + armLen * sin(dir)
            )
            // 支点底座
            drawCircle(Color(0xFF22242A), radius = 10.dp.toPx(), center = pivot)
            drawCircle(Color(0xFF7A8090), radius = 5.dp.toPx(), center = pivot)
            // 金属臂杆
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF7C8290), Color(0xFFE2E6EE), Color(0xFF727887)),
                    start = pivot,
                    end = stylus
                ),
                start = pivot,
                end = stylus,
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            // 唱头
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFC6CBD5), Color(0xFF656B78)),
                    start = stylus,
                    end = Offset(stylus.x + 8.dp.toPx(), stylus.y + 8.dp.toPx())
                ),
                topLeft = Offset(stylus.x - 6.dp.toPx(), stylus.y - 4.dp.toPx()),
                size = Size(13.dp.toPx(), 8.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            // 针尖
            drawCircle(DiscoverRed, radius = 2.dp.toPx(), center = stylus)
        }
    }
}

/**
 * 黑胶下方的三行歌词：上一句 / 当前句 / 下一句。
 * 当前句高亮放大并带呼吸式缩放动画；切句时用 Crossfade + 竖向位移平滑过渡。
 * 无歌词时显示三行占位提示，保持布局稳定。
 */
@Composable
private fun DiscLyrics(
    lyrics: Lyrics?,
    positionMs: Long,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val lines = lyrics?.lines.orEmpty()
    val current = lyrics?.indexAt(positionMs) ?: -1
    val previousText = if (current > 0) lines.getOrNull(current - 1)?.text.orEmpty() else ""
    val currentText = if (current >= 0) lines.getOrNull(current)?.text.orEmpty() else ""
    val nextText = if (current >= 0) lines.getOrNull(current + 1)?.text.orEmpty()
    else lines.firstOrNull()?.text.orEmpty()

    // 字号固定不缩放：持续的呼吸缩放会让文字一直在跳，长时间看很伤眼。
    // 当前句只靠颜色和字重区分，静态字号看着稳。
    // 行高留足：当前句 18sp，行高偏小会裁掉字体上下缘。
    val rowHeight = 30.dp * scale
    Column(
        modifier.fillMaxWidth().height(rowHeight * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LyricRow(
            text = previousText,
            active = false,
            scale = scale,
            height = rowHeight,
            fontSize = 14.5f.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        LyricRow(
            text = currentText.ifBlank { if (lines.isEmpty()) "♪ 暂无歌词 ♪" else "♪" },
            active = true,
            scale = scale,
            height = rowHeight,
            fontSize = 18f.sp,
            color = DiscoverRed
        )
        LyricRow(
            text = nextText,
            active = false,
            scale = scale,
            height = rowHeight,
            fontSize = 14.5f.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}

/** 单行歌词：文字变化时 Crossfade + 竖向滑入；当前行用 spring 放大高亮。 */
@Composable
private fun LyricRow(
    text: String,
    active: Boolean,
    scale: Float,
    height: Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color
) {
    val textColor by animateColorAsState(color, tween(320))
    Box(
        Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(targetState = text, animationSpec = tween(420)) { shown ->
            Text(
                text = shown,
                fontSize = fontSize * scale,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DiscoverFeed(
    playerVm: PlayerViewModel,
    state: PlaybackUiState,
    songs: List<Song>,
    favorites: List<Song>,
    playlists: List<Playlist>,
    nasSourceCount: Int,
    onOpenLocal: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenSound: () -> Unit,
    scale: Float = 1f
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp * scale, vertical = 4.dp * scale)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp * scale)) {
            BannerCard(
                title = "本地音乐",
                subtitle = "${songs.count { it.sourceType == com.nasmusic.player.core.SourceType.LOCAL }} 首歌曲",
                colors = listOf(Color(0xFFB9BEC7), Color(0xFF8E96A3)),
                modifier = Modifier.weight(1f),
                onClick = onOpenLocal,
                scale = scale
            )
            BannerCard(
                title = if (nasSourceCount > 0) "网络音乐源" else "连接飞牛 NAS",
                subtitle = if (nasSourceCount > 0) "$nasSourceCount 个已连接" else "局域网 / FN ID / 域名",
                colors = listOf(Color(0xFF233B6E), Color(0xFF111C38)),
                modifier = Modifier.weight(1f),
                onClick = onOpenSources,
                scale = scale
            )
        }

        Spacer(Modifier.height(16.dp * scale))
        SectionTitle("快捷入口", scale = scale)
        Spacer(Modifier.height(8.dp * scale))
        // 四个快捷入口：竖屏/窄窗口两行两列，宽屏车机一行四列，避免文字被挤压
        val quickCards = listOf<@Composable () -> Unit>(
            {
                QuickCard("随机漫游", listOf(Color(0xFFA7ABB4), Color(0xFF8B9099)),
                    scale = scale, onClick = {
                    if (songs.isNotEmpty()) playerVm.playSongs(songs.shuffled())
                })
            },
            {
                QuickCard("我的收藏", listOf(Color(0xFF1D3A70), Color(0xFF0E214A)),
                    scale = scale, onClick = {
                    if (favorites.isNotEmpty()) playerVm.playSongs(favorites)
                })
            },
            {
                QuickCard("最近添加", listOf(Color(0xFFA68C71), Color(0xFF7C664F)),
                    scale = scale, onClick = {
                    val recent = songs.sortedByDescending { it.lastModified }.take(50)
                    if (recent.isNotEmpty()) playerVm.playSongs(recent)
                })
            },
            {
                QuickCard(
                    "音效设置",
                    listOf(Color(0xFF6A4FB3), Color(0xFF3E2C73)),
                    scale = scale,
                    icon = Icons.Default.GraphicEq,
                    onClick = onOpenSound
                )
            }
        )
        // 与上方本地音乐/网络音乐源按键同尺寸：固定两列，4 张卡片分两排，每个占一半宽
        val quickPerRow = 2
        Column(verticalArrangement = Arrangement.spacedBy(12.dp * scale)) {
            quickCards.chunked(quickPerRow).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp * scale)) {
                    rowCards.forEach { card ->
                        Box(Modifier.weight(1f)) { card() }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp * scale))
        SectionTitle("我的歌单", onMore = onOpenLocal, scale = scale)
        Spacer(Modifier.height(8.dp * scale))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (playlists.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(width = 148.dp * scale, height = 132.dp * scale)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("还没有歌单\n到「我的」创建", textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                playlists.take(8).forEachIndexed { index, playlist ->
                    PlaylistCard(playlist, index, onClick = { onOpenPlaylist(playlist.id) }, scale = scale)
                }
            }
        }

        Spacer(Modifier.height(20.dp * scale))
        SectionTitle("最近播放", onMore = onOpenLocal, scale = scale)
        Spacer(Modifier.height(8.dp * scale))
        val recent = state.queue.take(6)
        if (recent.isEmpty()) {
            Text("播放歌曲后，这里会显示最近队列",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                recent.forEachIndexed { index, song ->
                    RecentCard(song, index, scale = scale) {
                        val i = state.queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        playerVm.playSongs(state.queue, i)
                    }
                }
            }
        }

        // 底部「设置」卡片已移除：顶栏已有「设置」文字按钮，避免重复入口
        Spacer(Modifier.height(24.dp * scale))
    }
}

@Composable
private fun BannerCard(
    title: String,
    subtitle: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    scale: Float = 1f
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Unspecified,
        modifier = modifier.height(124.dp * scale).clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(colors))
                .padding(18.dp * scale),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(title, color = Color.White, fontSize = 21.sp * scale, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp * scale))
                Text(subtitle, color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp * scale)
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp * scale)
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, onMore: (() -> Unit)? = null, scale: Float = 1f) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp * scale), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp * scale)) {
                Icon(Icons.Default.ChevronRight, contentDescription = "查看全部")
            }
        }
    }
}

@Composable
private fun QuickCard(
    label: String,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.PlayArrow,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Unspecified,
        // 撑满 Box(weight(1f)) 分配的宽度，与上方本地音乐/网络音乐源按键同尺寸
        modifier = modifier.fillMaxWidth().height(124.dp * scale).clickable(onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(colors)).padding(18.dp * scale),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(label, color = Color.White, fontSize = 20.sp * scale, fontWeight = FontWeight.Medium)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(32.dp * scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(18.dp * scale))
            }
        }
    }
}

private val PlaylistGradients = listOf(
    listOf(Color(0xFFFF3D9A), Color(0xFFC2185B)),
    listOf(Color(0xFF37474F), Color(0xFF12181B)),
    listOf(Color(0xFFD9A441), Color(0xFF8B5E1B)),
    listOf(Color(0xFF8E5BD0), Color(0xFF4A2480)),
    listOf(Color(0xFF3E7CB1), Color(0xFF1B4B73))
)

@Composable
private fun PlaylistCard(playlist: Playlist, index: Int, onClick: () -> Unit, scale: Float = 1f) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Unspecified,
        modifier = Modifier.size(width = 148.dp * scale, height = 132.dp * scale).clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(PlaylistGradients[index % PlaylistGradients.size]))
                .padding(12.dp * scale)
        ) {
            Text(
                playlist.name,
                color = Color.White,
                fontSize = 15.sp * scale,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                modifier = Modifier.padding(end = 24.dp * scale)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(30.dp * scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(16.dp * scale))
            }
        }
    }
}

@Composable
private fun RecentCard(song: Song, index: Int, scale: Float = 1f, onClick: () -> Unit) {
    Column(
        Modifier
            .size(width = 118.dp * scale, height = 128.dp * scale)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(width = 118.dp * scale, height = 96.dp * scale)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (song.artworkUri != null) {
                AsyncImage(
                    model = song.artworkUri,
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp * scale))
            }
        }
        Spacer(Modifier.height(6.dp * scale))
        Text(song.title, fontSize = 12.sp * scale, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface)
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    listOf(DiscoverRed, Color(0xFF43B7C2), Color(0xFFF4A742), Color(0xFF7E8BD6), Color(0xFF62B67C))[index % 5],
                    RoundedCornerShape(2.dp)
                )
        )
    }
}
