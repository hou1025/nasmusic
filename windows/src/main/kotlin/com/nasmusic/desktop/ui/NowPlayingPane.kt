package com.nasmusic.desktop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.desktop.data.Lyrics
import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.playback.PlaybackState
import kotlin.math.cos
import kotlin.math.sin

/**
 * 左侧常驻播放卡片：黑胶 + 三行歌词 + 曲目信息 + 进度条 + 播控按钮 + 音量。
 * 版式与 Android 横屏发现页的 NowPlayingPane 一致，仅额外补了桌面端必需的音量条。
 */
@Composable
fun NowPlayingPane(
    playback: PlaybackState,
    lyrics: Lyrics?,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    val song = playback.currentSong
    val duration = (playback.durationMs.takeIf { it > 0 } ?: song?.durationMs?.toDouble() ?: 0.0)
        .coerceAtLeast(1.0)
    val position = playback.positionMs.coerceIn(0.0, duration)

    Card(
        shape = RoundedCornerShape(24.dp * scale),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = modifier
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp * scale),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "正在播放",
                    fontSize = 12.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp * scale))

                VinylDisc(
                    song = song,
                    discSize = 144.dp * scale,
                    playing = playback.isPlaying && song != null
                )

                Spacer(Modifier.height(4.dp * scale))
                DiscLyricsStrip(
                    lyrics = lyrics,
                    positionMs = playback.positionMs.toLong(),
                    scale = scale
                )

                Spacer(Modifier.height(8.dp * scale))
                Text(
                    song?.title ?: "暂无播放歌曲",
                    fontSize = 19.sp * scale,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp * scale))
                Text(
                    song?.artist?.ifBlank { "未知歌手" } ?: "从「本地音乐」选一首开始",
                    fontSize = 12.sp * scale,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 进度条与播控之间原本被「上间距 + 时间行 + 下间距」拉得很开，这里整体收紧，
                // 省出的纵向空间全部留给上方歌词，避免大屏下歌词被压成两三行。
                Spacer(Modifier.height(4.dp * scale))
                Slider(
                    value = position.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = DiscoverRed,
                        activeTrackColor = DiscoverRed,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(18.dp * scale)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp * scale),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(playback.positionMs.toLong()), fontSize = 10.5.sp * scale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatTime((playback.durationMs.takeIf { it > 0 }
                            ?: song?.durationMs?.toDouble() ?: 0.0).toLong()),
                        fontSize = 10.5.sp * scale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(2.dp * scale))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleControl(
                    onClick = onToggleFavorite,
                    size = 44.dp,
                    enabled = song != null,
                    scale = scale
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = if (isFavorite) DiscoverRed else DiscoverInk.copy(alpha = 0.75f),
                        modifier = Modifier.size(24.dp * scale)
                    )
                }
                CircleControl(
                    onClick = onPrev,
                    size = 44.dp,
                    enabled = song != null,
                    scale = scale
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        tint = DiscoverInk.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp * scale)
                    )
                }
                CircleControl(
                    onClick = onPlayPause,
                    size = 62.dp,
                    filled = true,
                    enabled = song != null,
                    scale = scale
                ) {
                    Icon(
                        if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playback.isPlaying) "暂停" else "播放",
                        tint = DiscoverInk,
                        modifier = Modifier.size(32.dp * scale)
                    )
                }
                CircleControl(
                    onClick = onNext,
                    size = 44.dp,
                    enabled = song != null,
                    scale = scale
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = DiscoverInk.copy(alpha = 0.85f),
                        modifier = Modifier.size(26.dp * scale)
                    )
                }
            }

            Spacer(Modifier.height(6.dp * scale))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = "音量",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp * scale)
                )
                Spacer(Modifier.width(6.dp * scale))
                Slider(
                    value = playback.volume.toFloat(),
                    onValueChange = onVolume,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        activeTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f).height(16.dp * scale)
                )
            }
        }
    }
}

/** 播放卡片上的圆形按钮：主按钮实心，其余浅色底。 */
@Composable
private fun CircleControl(
    onClick: () -> Unit,
    size: Dp,
    enabled: Boolean,
    scale: Float,
    filled: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (filled) Color.White else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (filled) 2.dp else 0.dp,
        shadowElevation = if (filled) 3.dp else 0.dp,
        modifier = Modifier
            .size(size * scale)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * 黑胶唱片：盘面随播放旋转、唱臂落针/抬针。
 * 盘心优先显示歌曲封面（裁成圆形，随盘一起转），拿不到封面时退回「主色圆片 + 音符」的黑胶默认样式。
 */
@Composable
private fun VinylDisc(
    song: Song?,
    discSize: Dp,
    playing: Boolean
) {
    val cover = rememberSongCover(song)
    val rotation = remember { Animatable(0f) }
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
    val angle = rotation.value
    val swing = armSwing.value
    val stageSize = discSize * 1.40f

    Box(Modifier.size(stageSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(discSize)
                .clip(CircleShape)
                .background(Color(0xFF131316))
                .graphicsLayer { rotationZ = angle },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                for (i in 0 until 12) {
                    drawCircle(
                        color = Color.White.copy(alpha = if (i % 3 == 0) 0.09f else 0.045f),
                        radius = radius * (0.56f + i * 0.036f),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.16f), Color.Transparent)
                    ),
                    startAngle = -70f,
                    sweepAngle = 62f,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f)
                )
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
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .then(
                        // 有封面时加一圈极细的亮边，让圆形封面贴合盘面的层次
                        if (cover != null) {
                            Modifier.border(1.5.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = song?.title ?: "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = song?.title ?: "封面",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(discSize * 0.28f)
                    )
                }
            }
            // 中心轴孔：压在封面之上，保持真实唱片的观感
            Box(
                Modifier
                    .size(discSize * 0.075f)
                    .clip(CircleShape)
                    .background(Color(0xFFF6F6F8).copy(alpha = 0.94f))
                    .border(1.dp, Color(0xFF131316).copy(alpha = 0.30f), CircleShape)
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            val pivot = Offset(size.width * 0.94f, size.height * 0.10f)
            val armLen = discSize.toPx() * 0.42f
            val dir = Math.toRadians(135.0 + 32.0 * swing).toFloat()
            val stylus = Offset(pivot.x + armLen * cos(dir), pivot.y + armLen * sin(dir))
            drawCircle(Color(0xFF22242A), radius = 9.dp.toPx(), center = pivot)
            drawCircle(Color(0xFF7A8090), radius = 4.5.dp.toPx(), center = pivot)
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF7C8290), Color(0xFFE2E6EE), Color(0xFF727887)),
                    start = pivot,
                    end = stylus
                ),
                start = pivot,
                end = stylus,
                strokeWidth = 4.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFC6CBD5), Color(0xFF656B78)),
                    start = stylus,
                    end = Offset(stylus.x + 8.dp.toPx(), stylus.y + 8.dp.toPx())
                ),
                topLeft = Offset(stylus.x - 5.dp.toPx(), stylus.y - 3.5.dp.toPx()),
                size = Size(12.dp.toPx(), 7.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            drawCircle(DiscoverRed, radius = 2.dp.toPx(), center = stylus)
        }
    }
}

/**
 * 五行歌词：前两句 / 上一句 / 当前句 / 下一句 / 后一句。
 * 原先只有三行，桌面窗口高、纵向空余多，扩到五行后歌词上下文更完整；
 * 高度改为按内容自适应（最小 86dp），不再写死 64dp 造成上下大片空白。
 */
@Composable
private fun DiscLyricsStrip(
    lyrics: Lyrics?,
    positionMs: Long,
    scale: Float
) {
    val lines = lyrics?.lines.orEmpty()
    val index = lyrics?.indexAt(positionMs) ?: -1
    val current = if (index >= 0) lines.getOrNull(index)?.text else null

    // 未播放到第一句时 index 可能为 -1，此时把窗口整体下移，保证「即将唱的第一句」出现在当前行位置
    val anchor = if (index < 0) 0 else index
    fun lineAt(offset: Int): String? = lines.getOrNull(anchor + offset)?.text

    Column(
        Modifier.fillMaxWidth().heightIn(min = 86.dp * scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (lines.isEmpty()) {
            Text(
                "暂无歌词",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(3.dp * scale))
            Text(
                "把同名 .lrc 放到歌曲旁即可显示",
                fontSize = 10.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            )
        } else {
            // 远端两行用更小的字号和透明度，避免抢当前行的视觉重心
            listOf(-2, -1).forEach { offset ->
                Text(
                    lineAt(offset) ?: " ",
                    fontSize = 10.5.sp * scale,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                )
                Spacer(Modifier.height(2.dp * scale))
            }
            Text(
                if (index < 0) "♪" else (current ?: "♪"),
                fontSize = 14.sp * scale,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            listOf(1, 2).forEach { offset ->
                Spacer(Modifier.height(2.dp * scale))
                Text(
                    lineAt(offset) ?: " ",
                    fontSize = 10.5.sp * scale,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                )
            }
        }
    }
}
