@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nasmusic.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.formatDuration
import com.nasmusic.player.core.formatSize
import com.nasmusic.player.data.LyricCandidate
import com.nasmusic.player.data.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** 顶部标签：封面 / 歌词两个视图，第三个位置留给「音乐列表」入口。 */
private enum class PlayerTab(val label: String) {
    COVER("封面"), LYRICS("歌词")
}

@Composable
private fun TabLabel(label: String, active: Boolean, onClick: () -> Unit) {
    val foreground = MaterialTheme.colorScheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = if (active) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) foreground else foreground.copy(alpha = 0.68f)
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(20.dp)
                .height(3.dp)
                .background(
                    if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(2.dp)
                )
        )
    }
}

/** 歌词区上下边缘渐隐，配合逐行渐变形成柔和的淡入淡出。 */
private fun Modifier.lyricsFade(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.14f to Color.Black,
                0.86f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

private fun blendColor(base: Color, other: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = base.red + (other.red - base.red) * t,
        green = base.green + (other.green - base.green) * t,
        blue = base.blue + (other.blue - base.blue) * t
    )
}

/**
 * 圆形播放控制键（第二版）：
 * - 从真实触点扩散的一层柔光渐变，取代原来生硬的描边扩散圆环；
 * - 果冻式回弹 + 按下时提亮，去掉匀速旋转的扫光环（观感太像加载圈）；
 * - 主按钮用「播放进度环」表达状态，播放中带缓慢呼吸的外发光。
 */
@Composable
private fun CircleButton(
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    active: Boolean = false,
    playing: Boolean = false,
    progress: Float = -1f,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val foreground = scheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 记录手指按下的真实位置，柔光从这里扩散
    var pressPoint by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press) pressPoint = event.pressPosition
        }
    }

    val scale by animateFloatAsState(
        if (pressed) 0.90f else 1f,
        spring(dampingRatio = 0.42f, stiffness = 420f)
    )
    val lift by animateFloatAsState(if (pressed) 1f else 0f, tween(160))

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

    // 五个键统一同一套中性色：只靠尺寸与进度环区分主次，不再有紫色实心键
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
    val borderColor = foreground.copy(
        alpha = (if (filled) 0.24f else 0.16f) + 0.10f * lift
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            if (filled) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(foreground.copy(alpha = breath * 0.55f), Color.Transparent)
                    ),
                    radius = radius * 1.36f
                )
                // 进度环：不旋转、不闪烁，直接显示本曲播放进度
                if (progress >= 0f) {
                    val ringRadius = radius - 3.dp.toPx()
                    drawCircle(
                        color = foreground.copy(alpha = 0.18f),
                        radius = ringRadius,
                        style = Stroke(width = 2.2.dp.toPx())
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
                            style = Stroke(width = 2.4.dp.toPx())
                        )
                        val end = Math.toRadians(-90.0 + sweep)
                        drawCircle(
                            color = foreground,
                            radius = 2.4.dp.toPx(),
                            center = Offset(
                                center.x + ringRadius * cos(end).toFloat(),
                                center.y + ringRadius * sin(end).toFloat()
                            )
                        )
                    }
                }
            }
            // 触点柔光：填充式径向渐变，边缘自然化开
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
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(fillBrush, CircleShape)
                .border(1.dp, borderColor, CircleShape)
                .clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null) {
                    taps++
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * 无封面时的动态视觉：缓慢漂移的极光光斑 + 扩散声波 + 随播放律动的均衡条，
 * 颜色跟随当前封面主色/主题色，暂停时自动放缓变淡，不再是静态空块。
 */
@Composable
private fun AnimatedCoverPlaceholder(
    accent: Color,
    primary: Color,
    foreground: Color,
    playing: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition()
    val phase by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart)
    )
    val pulse by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val activity = if (playing) 1f else 0.38f

    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        blendColor(primary, accent, 0.55f).copy(alpha = 0.42f),
                        blendColor(primary, accent, 0.22f).copy(alpha = 0.20f)
                    )
                )
            )
            .border(1.dp, foreground.copy(alpha = 0.10f), shape)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val t = phase * 2f * Math.PI.toFloat()
            val blobs = listOf(
                Triple(0.30f, 0.32f, 0f),
                Triple(0.72f, 0.62f, 2.1f),
                Triple(0.44f, 0.78f, 4.2f)
            )
            blobs.forEachIndexed { index, (bx, by, offset) ->
                val cx = w * (bx + 0.10f * cos(t + offset))
                val cy = h * (by + 0.09f * sin(t * 0.8f + offset))
                val r = w * 0.52f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            (if (index == 1) accent else primary).copy(
                                alpha = (0.22f + 0.07f * pulse) * activity
                            ),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
            }
            for (i in 0..2) {
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    color = foreground.copy(alpha = (1f - p) * 0.10f * activity),
                    radius = w * (0.18f + 0.34f * p),
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            }
            val barW = w * 0.022f
            val gap = w * 0.030f
            val total = 7 * barW + 6 * gap
            val startX = (w - total) / 2f
            for (i in 0 until 7) {
                val wave = sin(t * 2.2f + i * 0.85f).coerceAtLeast(0f)
                val barH = h * (0.030f + 0.075f * wave) * activity
                drawRoundRect(
                    color = foreground.copy(alpha = 0.22f + 0.16f * activity),
                    topLeft = Offset(startX + i * (barW + gap), h * 0.84f - barH / 2f),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f)
                )
            }
        }
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = foreground.copy(alpha = 0.72f),
            modifier = Modifier
                .size(72.dp)
                .align(Alignment.Center)
                .graphicsLayer {
                    val s = 1f + 0.045f * pulse * activity
                    scaleX = s
                    scaleY = s
                }
        )
    }
}

/** 取封面缩略图的主色：量化直方图 + 跳过近黑近白与低饱和像素，无需额外依赖。 */
private fun dominantOf(bitmap: Bitmap): Color? {
    val buckets = HashMap<Int, IntArray>()
    val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 48)
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if ((pixel ushr 24) > 200) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val high = maxOf(r, g, b)
                val low = minOf(r, g, b)
                if (high in 40..245 && high - low > 22) {
                    val key = ((r / 32) shl 8) or ((g / 32) shl 4) or (b / 32)
                    val acc = buckets.getOrPut(key) { IntArray(4) }
                    acc[0] += r
                    acc[1] += g
                    acc[2] += b
                    acc[3] += 1
                }
            }
            x += step
        }
        y += step
    }
    val best = buckets.values.maxByOrNull { it[3] } ?: return null
    if (best[3] < 4) return null
    return Color(
        red = best[0].toFloat() / best[3] / 255f,
        green = best[1].toFloat() / best[3] / 255f,
        blue = best[2].toFloat() / best[3] / 255f
    )
}

/** 用现有 Coil 加载一张 64px 缩略图取主色，避免引入 Palette 依赖或阻塞主线程。 */
private suspend fun loadArtworkAccent(context: Context, uri: String?): Color? {
    if (uri.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .size(64)
                .build()
            val drawable = (ImageLoader(context).execute(request) as? SuccessResult)?.drawable
            val bitmap = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                null -> null
                else -> {
                    // Coil 也可能返回非 BitmapDrawable（矢量/自定义），统一绘制到小位图再取色
                    val sample = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, 48, 48)
                    drawable.draw(android.graphics.Canvas(sample))
                    sample
                }
            }
            bitmap?.let { dominantOf(it) }
        } catch (_: Exception) {
            null
        }
    }
}

private data class ArtworkAccent(
    val color: Color,
    val fromArtwork: Boolean,
    val enabled: Boolean
)

/** 换歌时主色做 760ms 渐变，背景不会硬切。 */
@Composable
private fun rememberArtworkAccent(uri: String?, enabled: Boolean): ArtworkAccent {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    var extracted by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri, enabled) {
        extracted = null
        if (enabled) extracted = loadArtworkAccent(context, uri)
    }
    val target = extracted ?: primary
    val animated by animateColorAsState(
        target,
        tween(760, easing = FastOutSlowInEasing)
    )
    return ArtworkAccent(animated, extracted != null && enabled, enabled)
}

/**
 * 播放页背景与列表页共用同一组基底色，只在此基础上叠一层封面主色；
 * 这样页面之间切换不会跳色，同时每首歌有自己的视觉个性。
 */
@Composable
private fun playerBackdrop(accent: ArtworkAccent): Brush {
    val base = LocalAppBackdropColors.current
    if (!accent.enabled) return base.brush()
    val dark = LocalIsDarkTheme.current
    val strength = if (accent.fromArtwork) 1f else 0.32f
    return Brush.verticalGradient(
        listOf(
            blendColor(base.top, accent.color, (if (dark) 0.34f else 0.22f) * strength),
            blendColor(base.mid, accent.color, (if (dark) 0.15f else 0.09f) * strength),
            base.bottom
        )
    )
}

/** 播放页内展开的搜索浮层，不额外占屏幕、不新增导航页。 */
@Composable
private fun PlayerSearchOverlay(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onClose: () -> Unit
) {
    val query by vm.query.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭搜索")
                }
                AppSearchBar(
                    query = query,
                    onQueryChange = { vm.setQuery(it) },
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
            }
            if (query.isBlank()) {
                Box(Modifier.weight(1f)) { EmptyState("输入歌名、歌手或专辑搜索") }
            } else {
                Box(Modifier.weight(1f)) {
                    SearchResultsPanel(vm, playerVm, onPicked = onClose)
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // 车机 / 宽屏横屏走左右双栏；手机竖屏保留原版沉浸式单列布局
    if (LocalCarMetrics.current.enabled) {
        CarPlayerScreen(vm, playerVm, onBack, onOpenLibrary, onOpenSettings)
    } else {
        PhonePlayerScreen(vm, playerVm, onBack, onOpenLibrary, onOpenSettings)
    }
}

/** 手机竖屏原版实现，逻辑与 1.7.0 完全一致。 */
@Composable
private fun PhonePlayerScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by playerVm.state.collectAsStateWithLifecycle()
    val lyrics by playerVm.lyrics.collectAsStateWithLifecycle()
    val tags by playerVm.audioTags.collectAsStateWithLifecycle()
    val candidates by playerVm.lyricCandidates.collectAsStateWithLifecycle()
    val lyricsBusy by playerVm.lyricsBusy.collectAsStateWithLifecycle()
    val song = state.currentSong
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val coverOverrides by playerVm.coverOverrides.collectAsStateWithLifecycle()
    // 在线封面下载完成后立即替换，无需等媒体库重新扫描
    val effectiveArtwork = song?.let { coverOverrides[it.id] ?: it.artworkUri }

    // 所有分支共用同一套背景与取色状态，保证组合结构稳定
    val accent = rememberArtworkAccent(effectiveArtwork, settings.artworkTint)
    val backdrop = playerBackdrop(accent)
    var searchMode by remember { mutableStateOf(false) }
    val closeSearch = {
        searchMode = false
        vm.setQuery("")
    }

    var tab by remember { mutableStateOf(PlayerTab.COVER) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var showDetails by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    val lyricsListState = rememberLazyListState()
    val currentLine = lyrics?.indexAt(state.positionMs) ?: -1
    if (tab == PlayerTab.LYRICS) {
        LaunchedEffect(currentLine) {
            if (currentLine >= 0) {
                lyricsListState.animateScrollToItem((currentLine - 1).coerceAtLeast(0))
            }
        }
    }

    if (song == null) {
        val fg = MaterialTheme.colorScheme.onSurface
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(backdrop)
                    .statusBarsPadding()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "收起",
                            tint = fg,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Text("音乐", style = MaterialTheme.typography.titleMedium, color = fg)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { searchMode = true }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = fg.copy(alpha = 0.72f)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMore = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作", tint = fg)
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                },
                                onClick = { showMore = false; onOpenSettings() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("音乐列表") },
                                leadingIcon = {
                                    Icon(Icons.Default.LibraryMusic, contentDescription = null)
                                },
                                onClick = { showMore = false; onOpenLibrary() }
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = fg.copy(alpha = 0.18f),
                        modifier = Modifier.size(116.dp)
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "还没有播放的歌曲",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = fg
                    )
                    Text(
                        "从音乐列表选一首开始，或点右上角搜索",
                        style = MaterialTheme.typography.bodyMedium,
                        color = fg.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Button(onClick = onOpenLibrary, modifier = Modifier.padding(top = 18.dp)) {
                        Icon(
                            Icons.Default.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("打开音乐列表")
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
            if (searchMode) {
                PlayerSearchOverlay(vm, playerVm, onClose = closeSearch)
            }
        }
        return
    }

    val maxDuration = (state.durationMs.takeIf { it > 0 } ?: song.durationMs)
        .coerceAtLeast(1L).toFloat()
    val sliderValue = (if (dragging) dragValue else state.positionMs.toFloat())
        .coerceIn(0f, maxDuration)
    val foreground = MaterialTheme.colorScheme.onSurface
    // 主按钮外圈的进度环直接反映本曲播放进度
    val playProgress = (sliderValue / maxDuration).coerceIn(0f, 1f)
    // 封面区不再吃掉全部剩余高度，让歌名与歌词整体上移
    val coverHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.34f)
        .coerceIn(150.dp, 280.dp)
    val isCoverTab = tab == PlayerTab.COVER
    val modeLabel = when {
        state.shuffle -> "随机播放"
        state.repeatMode == Player.REPEAT_MODE_ONE -> "单曲循环"
        state.repeatMode == Player.REPEAT_MODE_ALL -> "列表循环"
        else -> "顺序播放"
    }
    val formatLabel = song.mimeType
        .substringAfterLast('/')
        .ifBlank { "音频" }
        .uppercase()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(backdrop)
                .statusBarsPadding()
        ) {
                // 顶部：下拉收起 + 文字标签（下划线选中）+ 搜索/信息/更多
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "收起",
                        tint = foreground,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerTab.values().forEach { item ->
                        TabLabel(item.label, tab == item) { tab = item }
                    }
                    // 队列入口取消，第三个位置改为「音乐列表」
                    TabLabel("音乐列表", false) { onOpenLibrary() }
                }
                IconButton(onClick = { searchMode = true }, modifier = Modifier.size(42.dp)) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = foreground.copy(alpha = 0.72f),
                        modifier = Modifier.size(21.dp)
                    )
                }
                Box {
                    IconButton(onClick = { showMore = true }, modifier = Modifier.size(42.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = foreground.copy(alpha = 0.72f)
                        )
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        // 导航
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { showMore = false; onOpenSettings() }
                        )
                        DropdownMenuItem(
                            text = { Text("音乐列表") },
                            leadingIcon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            onClick = { showMore = false; onOpenLibrary() }
                        )
                        DropdownMenuItem(
                            text = { Text("歌曲信息") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { showDetails = true; showMore = false }
                        )
                        HorizontalDivider()
                        // 本曲 / 歌词 / 封面
                        DropdownMenuItem(
                            text = { Text("重新匹配封面") },
                            onClick = { playerVm.refreshCover(song); showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (song.isFavorite) "取消收藏" else "收藏") },
                            leadingIcon = {
                                Icon(
                                    if (song.isFavorite) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    contentDescription = null
                                )
                            },
                            onClick = { playerVm.toggleFavorite(song); showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("下载歌词") },
                            onClick = {
                                tab = PlayerTab.LYRICS
                                showLyrics = true
                                playerVm.searchLyrics(song)
                                showMore = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("歌词 -0.5s") },
                            onClick = { playerVm.adjustLyricsOffset(-500L); showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("歌词 +0.5s") },
                            onClick = { playerVm.adjustLyricsOffset(500L); showMore = false }
                        )
                        HorizontalDivider()
                        // 队列与缓存
                        DropdownMenuItem(
                            text = { Text("播放队列") },
                            onClick = { showQueue = true; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("加入列表") },
                            onClick = { showPlaylist = true; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("缓存本曲") },
                            onClick = { playerVm.cacheCurrentSong(); showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("缓存列表") },
                            onClick = { playerVm.cacheQueue(); showMore = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("睡眠定时") },
                            onClick = { showSleep = true; showMore = false }
                        )
                    }
                }
            }

            // 循环模式只由右下角按钮控制，重复的胶囊已移除

            state.error?.let { message ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = { playerVm.retry() }) { Text("重试") }
                            TextButton(onClick = { playerVm.clearError() }) { Text("关闭") }
                        }
                    }
                }
            }

            // 中间内容区：封面页限制高度把歌名/歌词抬上去，歌词页与队列页仍占满剩余空间
            val contentModifier = if (isCoverTab) {
                Modifier.fillMaxWidth().height(coverHeight).padding(horizontal = 10.dp, vertical = 4.dp)
            } else {
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp, vertical = 4.dp)
            }
            Box(contentModifier, contentAlignment = Alignment.Center) {
                when (tab) {
                    PlayerTab.COVER -> {
                        // 封面主色向外扩散的光晕
                        Box(
                            Modifier
                                .size(coverHeight * 1.16f)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            accent.color.copy(
                                                alpha = if (accent.fromArtwork) 0.34f else 0.22f
                                            ),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        )
                        val artSize = coverHeight - 16.dp
                        val artShape = RoundedCornerShape(28.dp)
                        Box(Modifier.size(artSize), contentAlignment = Alignment.Center) {
                            // 动态光影常驻底层：封面缺失、下载中或加载失败时都不会留白
                            AnimatedCoverPlaceholder(
                                accent = accent.color,
                                primary = MaterialTheme.colorScheme.primary,
                                foreground = foreground,
                                playing = state.isPlaying,
                                shape = artShape,
                                modifier = Modifier.size(artSize)
                            )
                            Crossfade(targetState = effectiveArtwork, label = "coverFade") { uri ->
                                if (uri != null) {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "专辑封面",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(artSize)
                                            .clip(artShape)
                                            .border(1.dp, foreground.copy(alpha = 0.10f), artShape)
                                    )
                                }
                            }
                        }
                    }
                    PlayerTab.LYRICS -> {
                        val current = lyrics
                        when {
                            current == null -> Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Text(
                                    "暂无歌词 · 可在右上角菜单下载歌词",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = foreground.copy(alpha = 0.68f),
                                    modifier = Modifier.padding(top = 20.dp)
                                )
                            }
                            !current.synced -> Column(
                                Modifier
                                    .fillMaxSize()
                                    .lyricsFade()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 8.dp, bottom = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                current.lines.forEach { line ->
                                    Text(
                                        line.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = foreground.copy(alpha = 0.82f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                    )
                                }
                            }
                            else -> LazyColumn(
                                state = lyricsListState,
                                modifier = Modifier.fillMaxSize().lyricsFade(),
                                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                itemsIndexed(current.lines) { index, line ->
                                    val active = index == currentLine
                                    // 当前行颜色、亮度、缩放都走动画，切行时是渐变过渡而非硬切
                                    val lineColor by animateColorAsState(
                                        targetValue = if (active) MaterialTheme.colorScheme.primary
                                        else foreground.copy(alpha = 0.66f),
                                        animationSpec = tween(430)
                                    )
                                    val lineAlpha by animateFloatAsState(
                                        if (active) 1f else 0.88f,
                                        tween(430)
                                    )
                                    val lineScale by animateFloatAsState(
                                        if (active) 1.06f else 1f,
                                        spring(dampingRatio = 0.75f, stiffness = 380f)
                                    )
                                    Text(
                                        line.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = lineColor,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 9.dp)
                                            .graphicsLayer {
                                                alpha = lineAlpha
                                                scaleX = lineScale
                                                scaleY = lineScale
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 歌名与标签：居中，展示我们自己的来源/格式信息
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = foreground,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        song.artist.ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = foreground.copy(alpha = 0.80f),
                        maxLines = 1
                    )
                    Spacer(Modifier.width(8.dp))
                    SourceBadge(song)
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = foreground.copy(alpha = 0.10f),
                        contentColor = foreground.copy(alpha = 0.72f)
                    ) {
                        Text(
                            formatLabel,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 两行歌词：点一下切到完整歌词（从顶部开始）
            if (tab == PlayerTab.COVER) {
                val lines = lyrics?.lines.orEmpty()
                val first = lines.getOrNull(currentLine)?.text
                    ?: "暂无歌词 · 点这里查看"
                val second = lines.getOrNull(currentLine + 1)?.text.orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clickable { tab = PlayerTab.LYRICS }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 换句时旧句淡出、新句淡入，不再瞬间替换
                    Crossfade(targetState = first, animationSpec = tween(460)) { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.titleLarge,
                            color = foreground.copy(alpha = 0.95f),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Crossfade(targetState = second, animationSpec = tween(460)) { text ->
                        if (text.isNotBlank()) {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = foreground.copy(alpha = 0.68f),
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            // 封面页把剩余空间留在歌词与进度条之间，进度条与控制区仍贴底
            if (isCoverTab) Spacer(Modifier.weight(1f))

            Slider(
                value = sliderValue,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    playerVm.seekTo(dragValue.toLong())
                    dragging = false
                },
                valueRange = 0f..maxDuration,
                colors = SliderDefaults.colors(
                    thumbColor = foreground,
                    activeTrackColor = foreground.copy(alpha = 0.88f),
                    inactiveTrackColor = foreground.copy(alpha = 0.22f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .height(18.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatDuration(state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.76f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.buffering) {
                        CircularProgressIndicator(Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        modeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground.copy(alpha = 0.68f)
                    )
                }
                Text(
                    formatDuration(state.durationMs.takeIf { it > 0 } ?: song.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.76f)
                )
            }

            // 控制行：触点柔光 + 果冻回弹 + 主按钮进度环 + 呼吸光晕
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleButton(
                    onClick = { playerVm.toggleShuffle() },
                    size = 46.dp,
                    active = state.shuffle
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "随机播放",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                        else foreground.copy(alpha = 0.72f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                CircleButton(onClick = { playerVm.previous() }, size = 56.dp) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        tint = foreground,
                        modifier = Modifier.size(30.dp)
                    )
                }
                CircleButton(
                    onClick = { playerVm.playPause() },
                    size = 76.dp,
                    filled = true,
                    playing = state.isPlaying,
                    progress = playProgress
                ) {
                    // 自绘播放 / 暂停：几何重心精确落在圆心，交叉淡入 + 轻微位移缩放
                    val morph by animateFloatAsState(
                        if (state.isPlaying) 1f else 0f,
                        spring(dampingRatio = 0.7f, stiffness = 520f)
                    )
                    val glyphColor = foreground
                    Box(
                        Modifier.semantics {
                            contentDescription = if (state.isPlaying) "暂停" else "播放"
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        // 三角形三顶点重心 = 画布中心，修正 Material 图标自带偏右
                        Canvas(
                            Modifier
                                .size(38.dp)
                                .graphicsLayer {
                                    alpha = 1f - morph
                                    scaleX = 0.86f + 0.14f * (1f - morph)
                                    scaleY = scaleX
                                    translationY = -3f * morph
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            val left = w * 0.34f
                            val right = w * 0.82f
                            val top = h * 0.20f
                            val bottom = h * 0.80f
                            drawPath(
                                Path().apply {
                                    moveTo(left, top)
                                    lineTo(right, (top + bottom) / 2f)
                                    lineTo(left, bottom)
                                    close()
                                },
                                color = glyphColor
                            )
                        }
                        // 暂停双条以画布中心对称，圆角更柔和
                        Canvas(
                            Modifier
                                .size(34.dp)
                                .graphicsLayer {
                                    alpha = morph
                                    scaleX = 0.86f + 0.14f * morph
                                    scaleY = scaleX
                                    translationY = 3f * (1f - morph)
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            val bar = w * 0.22f
                            val gap = w * 0.16f
                            val left = (w - bar * 2f - gap) / 2f
                            val top = h * 0.20f
                            val barHeight = h * 0.60f
                            val radius = CornerRadius(bar * 0.30f)
                            drawRoundRect(
                                color = glyphColor,
                                topLeft = Offset(left, top),
                                size = Size(bar, barHeight),
                                cornerRadius = radius
                            )
                            drawRoundRect(
                                color = glyphColor,
                                topLeft = Offset(left + bar + gap, top),
                                size = Size(bar, barHeight),
                                cornerRadius = radius
                            )
                        }
                    }
                }
                CircleButton(onClick = { playerVm.next() }, size = 56.dp) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = foreground,
                        modifier = Modifier.size(30.dp)
                    )
                }
                CircleButton(
                    onClick = { playerVm.cycleRepeat() },
                    size = 46.dp,
                    active = state.repeatMode != Player.REPEAT_MODE_OFF
                ) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                        else Icons.Default.Repeat,
                        contentDescription = "循环模式",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF)
                            foreground.copy(alpha = 0.72f) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        } // Column：播放页主体
        if (searchMode) {
            PlayerSearchOverlay(vm, playerVm, onClose = closeSearch)
        }
    } // Box：播放页 + 搜索浮层

    if (showDetails) {
        val sampleRate = tags?.sampleRate ?: song.sampleRate
        val bitrate = tags?.bitrate ?: song.bitrate
        val channels = tags?.channels ?: song.channels
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("歌曲信息") },
            text = {
                Column {
                    LabeledValue("来源", song.sourceLabel)
                    LabeledValue("格式", song.mimeType.ifBlank { "—" })
                    LabeledValue("采样率", if (sampleRate > 0) "$sampleRate Hz" else "—")
                    LabeledValue("比特率", if (bitrate > 0) "${bitrate / 1000} kbps" else "—")
                    LabeledValue("声道数", if (channels > 0) "$channels" else "—")
                    LabeledValue("文件大小", formatSize(song.sizeBytes))
                    LabeledValue("时长", formatDuration(song.durationMs))
                    LabeledValue("路径", song.folder.ifBlank { "—" })
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("关闭") } }
        )
    }

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            title = { Text("睡眠定时器") },
            text = { Text("到时后自动暂停播放") },
            confirmButton = {
                Row {
                    listOf(15, 30, 60).forEach { minutes ->
                        TextButton(onClick = { playerVm.startSleepTimer(minutes); showSleep = false }) {
                            Text("${minutes}分")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { playerVm.cancelSleepTimer(); showSleep = false }) { Text("取消") }
            }
        )
    }

    if (showQueue) {
        AlertDialog(
            onDismissRequest = { showQueue = false },
            title = { Text("播放队列") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(state.queue, key = { it.id }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playerVm.playSongs(state.queue, state.queue.indexOf(item))
                                    showQueue = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Artwork(item, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    item.artist.ifBlank { "未知歌手" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            if (item.id == song.id) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = "正在播放",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueue = false }) { Text("关闭") } }
        )
    }

    if (showPlaylist) {
        AddToPlaylistDialog(vm, listOf(song.id), onDismiss = { showPlaylist = false })
    }

    if (showLyrics) {
        LyricsPickerDialog(
            song = song,
            candidates = candidates,
            busy = lyricsBusy,
            onRefresh = { playerVm.searchLyrics(song) },
            onAuto = { playerVm.autoDownloadLyrics(song) },
            onSelect = { playerVm.selectLyrics(song, it); showLyrics = false },
            onClear = { playerVm.clearLyrics(song); showLyrics = false },
            onDismiss = { showLyrics = false; playerVm.dismissLyricsCandidates() }
        )
    }
}

@Composable
private fun LyricsPickerDialog(
    song: Song,
    candidates: List<LyricCandidate>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onAuto: () -> Unit,
    onSelect: (LyricCandidate) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载歌词 · ${song.title}") },
        text = {
            Column {
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onAuto) { Text("自动匹配最佳") }
                    TextButton(onClick = onRefresh) { Text("重新搜索") }
                    TextButton(onClick = onClear) { Text("清除本地歌词") }
                }
                InfoText(
                    "按歌名、歌手与时长匹配；选中后保存到本机，下次播放离线可用。",
                    Modifier.padding(bottom = 6.dp)
                )
                if (candidates.isEmpty() && !busy) {
                    InfoText("没有找到候选，可到设置里切换歌词源或检查网络。")
                } else {
                    // 车机横屏可用高度有限，弹窗列表同步收窄避免超出屏幕
                    val listMax = if (LocalCarMetrics.current.enabled) 180.dp else 300.dp
                    LazyColumn(Modifier.heightIn(max = listMax)) {
                        items(candidates, key = { it.key }) { candidate ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelect(candidate) }
                                    .padding(10.dp)
                            ) {
                                Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                InfoText("${candidate.summary} · ${candidate.source.label}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}


/**
 * 车机 / 大屏横屏播放页。
 *
 * 竖屏单列在 720p 级横屏中控（可用高度常仅 320~380dp）会溢出，因此改为左右双栏：
 * 左侧封面按可用高度取正方形并限制最大边长，右侧从上到下依次是歌曲信息、歌词、
 * 进度与播控；全部触控目标 / 字号 / 按键直径来自 CarMetrics 资源限定符。
 * 视觉语言（渐变取色、动态封面占位、歌词渐变、同色圆形按键 + 进度环）与手机版一致。
 */
@Composable
private fun CarPlayerScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val m = LocalCarMetrics.current
    val state by playerVm.state.collectAsStateWithLifecycle()
    val lyrics by playerVm.lyrics.collectAsStateWithLifecycle()
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val coverOverrides by playerVm.coverOverrides.collectAsStateWithLifecycle()
    val candidates by playerVm.lyricCandidates.collectAsStateWithLifecycle()
    val lyricsBusy by playerVm.lyricsBusy.collectAsStateWithLifecycle()
    val tags by playerVm.audioTags.collectAsStateWithLifecycle()
    val song = state.currentSong
    val artwork = song?.let { coverOverrides[it.id] ?: it.artworkUri }
    val accent = rememberArtworkAccent(artwork, settings.artworkTint)
    val backdrop = playerBackdrop(accent)
    val fg = MaterialTheme.colorScheme.onSurface

    var searchMode by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(PlayerTab.COVER) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var showMore by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val lyricsListState = rememberLazyListState()
    val currentLine = lyrics?.indexAt(state.positionMs) ?: -1
    LaunchedEffect(currentLine, tab) {
        if (tab == PlayerTab.LYRICS && currentLine >= 0) {
            lyricsListState.animateScrollToItem((currentLine - 1).coerceAtLeast(0))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(backdrop).statusBarsPadding()) {
            // 顶栏：所有按钮不小于车机触控目标
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(m.touchTarget)) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "收起",
                        tint = fg,
                        modifier = Modifier.size(m.touchTarget * 0.48f)
                    )
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerTab.values().forEach { item ->
                        TabLabel(item.label, tab == item) { tab = item }
                    }
                    TabLabel("音乐列表", false) { onOpenLibrary() }
                }
                IconButton(
                    onClick = { searchMode = true },
                    modifier = Modifier.size(m.touchTarget)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = fg.copy(alpha = 0.85f),
                        modifier = Modifier.size(m.touchTarget * 0.42f)
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMore = true },
                        modifier = Modifier.size(m.touchTarget)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = fg.copy(alpha = 0.85f),
                            modifier = Modifier.size(m.touchTarget * 0.42f)
                        )
                    }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = { showMore = false; onOpenSettings() }
                        )
                        DropdownMenuItem(
                            text = { Text("音乐列表") },
                            leadingIcon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                            onClick = { showMore = false; onOpenLibrary() }
                        )
                        DropdownMenuItem(
                            text = { Text("歌曲信息") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { showDetails = true; showMore = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("重新匹配封面") },
                            onClick = { song?.let { playerVm.refreshCover(it) }; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (song?.isFavorite == true) "取消收藏" else "收藏") },
                            onClick = { song?.let { playerVm.toggleFavorite(it) }; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("下载歌词") },
                            onClick = {
                                song?.let { playerVm.searchLyrics(it) }
                                tab = PlayerTab.LYRICS
                                showLyrics = true
                                showMore = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("播放队列") },
                            onClick = { showQueue = true; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("加入列表") },
                            onClick = { showPlaylist = true; showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("缓存本曲") },
                            onClick = { playerVm.cacheCurrentSong(); showMore = false }
                        )
                        DropdownMenuItem(
                            text = { Text("睡眠定时") },
                            onClick = { showSleep = true; showMore = false }
                        )
                    }
                }
            }

            state.error?.let { message ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { playerVm.retry() }) { Text("重试") }
                        TextButton(onClick = { playerVm.clearError() }) { Text("关闭") }
                    }
                }
            }

            if (song == null) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CarEmptyBody(fg, m, onOpenLibrary)
                }
            } else {
                val duration = (state.durationMs.takeIf { it > 0 } ?: song.durationMs)
                    .coerceAtLeast(1L).toFloat()
                val sliderValue = (if (dragging) dragValue else state.positionMs.toFloat())
                    .coerceIn(0f, duration)
                val progress = (sliderValue / duration).coerceIn(0f, 1f)
                val modeLabel = when {
                    state.shuffle -> "随机播放"
                    state.repeatMode == Player.REPEAT_MODE_ONE -> "单曲循环"
                    state.repeatMode == Player.REPEAT_MODE_ALL -> "列表循环"
                    else -> "顺序播放"
                }
                val formatLabel = song.mimeType
                    .substringAfterLast('/')
                    .ifBlank { "音频" }
                    .uppercase()

                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .widthIn(max = m.contentMaxWidth)
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左栏：封面 + 取色光晕，尺寸跟随可用高度自适应
                        BoxWithConstraints(
                            Modifier.weight(0.42f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val side = (minOf(maxWidth, maxHeight) * 0.90f).coerceAtMost(340.dp)
                            Box(
                                Modifier.size(side * 1.16f).background(
                                    Brush.radialGradient(
                                        listOf(
                                            accent.color.copy(
                                                alpha = if (accent.fromArtwork) 0.34f else 0.22f
                                            ),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                            )
                            val artShape = RoundedCornerShape(30.dp)
                            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                                AnimatedCoverPlaceholder(
                                    accent = accent.color,
                                    primary = MaterialTheme.colorScheme.primary,
                                    foreground = fg,
                                    playing = state.isPlaying,
                                    shape = artShape,
                                    modifier = Modifier.size(side)
                                )
                                Crossfade(targetState = artwork, label = "carCoverFade") { uri ->
                                    if (uri != null) {
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = "专辑封面",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(side)
                                                .clip(artShape)
                                                .border(1.dp, fg.copy(alpha = 0.10f), artShape)
                                        )
                                    }
                                }
                            }
                        }

                        // 右栏：信息 / 歌词 / 进度 / 播控
                        Column(Modifier.weight(0.58f).fillMaxHeight()) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    song.title,
                                    fontSize = m.titleSize,
                                    fontWeight = FontWeight.Bold,
                                    color = fg,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                                Row(
                                    Modifier.padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        song.artist.ifBlank { "未知歌手" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = fg.copy(alpha = 0.90f),
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    SourceBadge(song)
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = fg.copy(alpha = 0.10f),
                                        contentColor = fg.copy(alpha = 0.80f)
                                    ) {
                                        Text(
                                            formatLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .lyricsFade()
                                    .clickable {
                                        tab = if (tab == PlayerTab.COVER) PlayerTab.LYRICS
                                        else PlayerTab.COVER
                                    },
                                contentAlignment = Alignment.TopCenter
                            ) {
                                CarLyricsContent(
                                    lyrics = lyrics,
                                    currentLine = currentLine,
                                    tab = tab,
                                    fg = fg,
                                    m = m,
                                    listState = lyricsListState
                                )
                            }

                            Slider(
                                value = sliderValue,
                                onValueChange = { dragging = true; dragValue = it },
                                onValueChangeFinished = {
                                    playerVm.seekTo(dragValue.toLong())
                                    dragging = false
                                },
                                valueRange = 0f..duration,
                                colors = SliderDefaults.colors(
                                    thumbColor = fg,
                                    activeTrackColor = fg.copy(alpha = 0.90f),
                                    inactiveTrackColor = fg.copy(alpha = 0.24f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .height(28.dp)
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    formatDuration(state.positionMs),
                                    fontSize = 16.sp,
                                    color = fg.copy(alpha = 0.85f)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.buffering) {
                                        CircularProgressIndicator(Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        modeLabel,
                                        fontSize = 16.sp,
                                        color = fg.copy(alpha = 0.78f)
                                    )
                                }
                                Text(
                                    formatDuration(
                                        state.durationMs.takeIf { it > 0 } ?: song.durationMs
                                    ),
                                    fontSize = 16.sp,
                                    color = fg.copy(alpha = 0.85f)
                                )
                            }

                            // 五个同色圆形按键，直径按车机触控目标放大
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircleButton(
                                    onClick = { playerVm.toggleShuffle() },
                                    size = m.buttonSmall,
                                    active = state.shuffle
                                ) {
                                    Icon(
                                        Icons.Default.Shuffle,
                                        contentDescription = "随机播放",
                                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary
                                        else fg.copy(alpha = 0.80f),
                                        modifier = Modifier.size(m.buttonSmall * 0.42f)
                                    )
                                }
                                CircleButton(
                                    onClick = { playerVm.previous() },
                                    size = m.buttonMedium
                                ) {
                                    Icon(
                                        Icons.Default.SkipPrevious,
                                        contentDescription = "上一首",
                                        tint = fg,
                                        modifier = Modifier.size(m.buttonMedium * 0.50f)
                                    )
                                }
                                CircleButton(
                                    onClick = { playerVm.playPause() },
                                    size = m.buttonMain,
                                    filled = true,
                                    playing = state.isPlaying,
                                    progress = progress
                                ) {
                                    val morph by animateFloatAsState(
                                        if (state.isPlaying) 1f else 0f,
                                        spring(dampingRatio = 0.7f, stiffness = 520f)
                                    )
                                    val glyphColor = fg
                                    Box(
                                        Modifier.semantics {
                                            contentDescription = if (state.isPlaying) "暂停" else "播放"
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(
                                            Modifier
                                                .size(m.buttonMain * 0.50f)
                                                .graphicsLayer {
                                                    alpha = 1f - morph
                                                    scaleX = 0.86f + 0.14f * (1f - morph)
                                                    scaleY = scaleX
                                                    translationY = -3f * morph
                                                }
                                        ) {
                                            val w = size.width
                                            val h = size.height
                                            val left = w * 0.34f
                                            val right = w * 0.82f
                                            val top = h * 0.20f
                                            val bottom = h * 0.80f
                                            drawPath(
                                                Path().apply {
                                                    moveTo(left, top)
                                                    lineTo(right, (top + bottom) / 2f)
                                                    lineTo(left, bottom)
                                                    close()
                                                },
                                                color = glyphColor
                                            )
                                        }
                                        Canvas(
                                            Modifier
                                                .size(m.buttonMain * 0.45f)
                                                .graphicsLayer {
                                                    alpha = morph
                                                    scaleX = 0.86f + 0.14f * morph
                                                    scaleY = scaleX
                                                    translationY = 3f * (1f - morph)
                                                }
                                        ) {
                                            val w = size.width
                                            val h = size.height
                                            val bar = w * 0.22f
                                            val gap = w * 0.16f
                                            val left = (w - bar * 2f - gap) / 2f
                                            val top = h * 0.20f
                                            val barHeight = h * 0.60f
                                            val radius = CornerRadius(bar * 0.30f)
                                            drawRoundRect(
                                                color = glyphColor,
                                                topLeft = Offset(left, top),
                                                size = Size(bar, barHeight),
                                                cornerRadius = radius
                                            )
                                            drawRoundRect(
                                                color = glyphColor,
                                                topLeft = Offset(left + bar + gap, top),
                                                size = Size(bar, barHeight),
                                                cornerRadius = radius
                                            )
                                        }
                                    }
                                }
                                CircleButton(
                                    onClick = { playerVm.next() },
                                    size = m.buttonMedium
                                ) {
                                    Icon(
                                        Icons.Default.SkipNext,
                                        contentDescription = "下一首",
                                        tint = fg,
                                        modifier = Modifier.size(m.buttonMedium * 0.50f)
                                    )
                                }
                                CircleButton(
                                    onClick = { playerVm.cycleRepeat() },
                                    size = m.buttonSmall,
                                    active = state.repeatMode != Player.REPEAT_MODE_OFF
                                ) {
                                    Icon(
                                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                                        else Icons.Default.Repeat,
                                        contentDescription = "循环模式",
                                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF)
                                            fg.copy(alpha = 0.80f)
                                        else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(m.buttonSmall * 0.42f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (searchMode) {
            PlayerSearchOverlay(
                vm, playerVm,
                onClose = { searchMode = false; vm.setQuery("") }
            )
        }
    }

    song?.let { current ->
        if (showDetails) {
            AlertDialog(
                onDismissRequest = { showDetails = false },
                title = { Text("歌曲信息") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LabeledValue("来源", current.sourceLabel)
                        LabeledValue("格式", current.mimeType.ifBlank { "—" })
                        LabeledValue(
                            "采样率",
                            if ((tags?.sampleRate ?: current.sampleRate) > 0)
                                "${tags?.sampleRate ?: current.sampleRate} Hz" else "—"
                        )
                        LabeledValue(
                            "比特率",
                            if ((tags?.bitrate ?: current.bitrate) > 0)
                                "${(tags?.bitrate ?: current.bitrate) / 1000} kbps" else "—"
                        )
                        LabeledValue("文件大小", formatSize(current.sizeBytes))
                        LabeledValue("时长", formatDuration(current.durationMs))
                        LabeledValue("路径", current.folder.ifBlank { "—" })
                    }
                },
                confirmButton = { TextButton(onClick = { showDetails = false }) { Text("关闭") } }
            )
        }
        if (showSleep) {
            AlertDialog(
                onDismissRequest = { showSleep = false },
                title = { Text("睡眠定时器") },
                text = { Text("到时后自动暂停播放") },
                confirmButton = {
                    Row {
                        listOf(15, 30, 60).forEach { minutes ->
                            TextButton(
                                onClick = {
                                    playerVm.startSleepTimer(minutes)
                                    showSleep = false
                                }
                            ) { Text("${minutes}分") }
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { playerVm.cancelSleepTimer(); showSleep = false }
                    ) { Text("取消") }
                }
            )
        }
        if (showQueue) {
            AlertDialog(
                onDismissRequest = { showQueue = false },
                title = { Text("播放队列") },
                text = {
                    // 车机横屏可用高度常只有 320~380dp，队列区收窄避免弹窗溢出屏幕
                    LazyColumn(Modifier.heightIn(max = if (m.enabled) 150.dp else 220.dp)) {
                        items(state.queue, key = { it.id }) { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playerVm.playSongs(state.queue, state.queue.indexOf(item))
                                        showQueue = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Artwork(item, m.rowArtwork.coerceAtMost(48.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1
                                    )
                                    Text(
                                        item.artist.ifBlank { "未知歌手" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                if (item.id == current.id) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = "正在播放",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showQueue = false }) { Text("关闭") } }
            )
        }
        if (showPlaylist) {
            AddToPlaylistDialog(vm, listOf(current.id), onDismiss = { showPlaylist = false })
        }
        if (showLyrics) {
            LyricsPickerDialog(
                song = current,
                candidates = candidates,
                busy = lyricsBusy,
                onRefresh = { playerVm.searchLyrics(current) },
                onAuto = { playerVm.autoDownloadLyrics(current) },
                onSelect = { playerVm.selectLyrics(current, it); showLyrics = false },
                onClear = { playerVm.clearLyrics(current); showLyrics = false },
                onDismiss = { showLyrics = false; playerVm.dismissLyricsCandidates() }
            )
        }
    }
}

/** 车机横屏空态：大音符 + 引导按钮，按钮触控目标不小于 64dp。 */
@Composable
private fun CarEmptyBody(fg: Color, m: CarMetrics, onOpenLibrary: () -> Unit) {
    Row(
        Modifier.widthIn(max = m.contentMaxWidth).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = fg.copy(alpha = 0.18f),
            modifier = Modifier.size(140.dp)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "还没有播放的歌曲",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = fg
            )
            Text(
                "从音乐列表选一首开始，或点右上角搜索",
                style = MaterialTheme.typography.bodyLarge,
                color = fg.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = onOpenLibrary,
                modifier = Modifier.padding(top = 20.dp).heightIn(min = m.touchTarget)
            ) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("打开音乐列表")
            }
        }
    }
}

/** 车机歌词区：封面页显示两行，歌词页显示完整逐行渐变高亮并自动滚动。 */
@Composable
private fun CarLyricsContent(
    lyrics: Lyrics?,
    currentLine: Int,
    tab: PlayerTab,
    fg: Color,
    m: CarMetrics,
    listState: LazyListState
) {
    if (lyrics == null) {
        Text(
            "暂无歌词 · 可在右上角菜单下载歌词",
            style = MaterialTheme.typography.bodyLarge,
            color = fg.copy(alpha = 0.70f),
            modifier = Modifier.padding(top = 16.dp)
        )
        return
    }
    if (tab == PlayerTab.COVER) {
        val first = lyrics.lines.getOrNull(currentLine)?.text
            ?: lyrics.lines.firstOrNull()?.text
            ?: "暂无歌词"
        val second = lyrics.lines.getOrNull(currentLine + 1)?.text.orEmpty()
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Crossfade(targetState = first, animationSpec = tween(460)) { text ->
                Text(
                    text,
                    fontSize = m.lyricSize,
                    color = fg.copy(alpha = 0.98f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Crossfade(targetState = second, animationSpec = tween(460)) { text ->
                if (text.isNotBlank()) {
                    Text(
                        text,
                        fontSize = m.lyricSize * 0.78f,
                        color = fg.copy(alpha = 0.70f),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
            }
        }
        return
    }
    if (!lyrics.synced) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            lyrics.lines.forEach { line ->
                Text(
                    line.text,
                    fontSize = m.lyricSize,
                    color = fg.copy(alpha = 0.86f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == currentLine
            val lineColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary
                else fg.copy(alpha = 0.70f),
                animationSpec = tween(430)
            )
            val lineAlpha by animateFloatAsState(if (active) 1f else 0.88f, tween(430))
            val lineScale by animateFloatAsState(
                if (active) 1.06f else 1f,
                spring(dampingRatio = 0.75f, stiffness = 380f)
            )
            Text(
                line.text,
                fontSize = m.lyricSize,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = lineColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .graphicsLayer {
                        alpha = lineAlpha
                        scaleX = lineScale
                        scaleY = lineScale
                    }
            )
        }
    }
}
