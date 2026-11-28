package com.nasmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.desktop.model.Playlist
import com.nasmusic.desktop.model.Song
import com.nasmusic.desktop.model.SourceType

private val PlaylistGradients = listOf(
    listOf(Color(0xFFFF3D9A), Color(0xFFC2185B)),
    listOf(Color(0xFF37474F), Color(0xFF12181B)),
    listOf(Color(0xFFD9A441), Color(0xFF8B5E1B)),
    listOf(Color(0xFF8E5BD0), Color(0xFF4A2480)),
    listOf(Color(0xFF3E7CB1), Color(0xFF1B4B73))
)

private val RecentAccents = listOf(
    Color(0xFFF0334F), Color(0xFF43B7C2), Color(0xFFF4A742),
    Color(0xFF7E8BD6), Color(0xFF62B67C)
)

/**
 * 发现页右栏内容：两个横幅入口 + 快捷入口四宫格 + 我的歌单 + 最近播放。
 * 与 Android 横屏发现页的 DiscoverFeed 一一对应。
 */
@Composable
fun DiscoverFeed(
    songs: List<Song>,
    favorites: List<Song>,
    playlists: List<Playlist>,
    queue: List<Song>,
    sourceCount: Int,
    statusMessage: String,
    onPlayList: (List<Song>) -> Unit,
    onOpenTab: (HomeTab) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenSound: () -> Unit,
    onPlayRecent: (Int) -> Unit,
    scale: Float = 1f
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp * scale, vertical = 4.dp * scale)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp * scale)) {
            BannerCard(
                title = "本地音乐",
                subtitle = "${songs.count { it.sourceType == SourceType.LOCAL }} 首歌曲",
                colors = listOf(Color(0xFFB9BEC7), Color(0xFF8E96A3)),
                onClick = { onOpenTab(HomeTab.LOCAL) },
                modifier = Modifier.weight(1f),
                scale = scale
            )
            BannerCard(
                title = if (sourceCount > 0) "网络音乐源" else "连接飞牛 NAS",
                subtitle = if (sourceCount > 0) "$sourceCount 个已配置" else "局域网 / FN ID / 域名",
                colors = listOf(Color(0xFF233B6E), Color(0xFF111C38)),
                onClick = { onOpenTab(HomeTab.SOURCES) },
                modifier = Modifier.weight(1f),
                scale = scale
            )
        }

        if (statusMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp * scale))
            Text(
                statusMessage,
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp * scale))
        SectionTitle("快捷入口", scale = scale)
        Spacer(Modifier.height(8.dp * scale))

        // 固定两列两排：与上方横幅同宽同高，窄窗口也不会把文字挤爆
        val quickCards: List<@Composable () -> Unit> = listOf(
            {
                QuickCard(
                    label = "随机漫游",
                    colors = listOf(Color(0xFFA7ABB4), Color(0xFF8B9099)),
                    enabled = songs.isNotEmpty(),
                    onClick = { if (songs.isNotEmpty()) onPlayList(songs.shuffled()) },
                    scale = scale
                )
            },
            {
                QuickCard(
                    label = "我的收藏",
                    colors = listOf(Color(0xFF1D3A70), Color(0xFF0E214A)),
                    enabled = favorites.isNotEmpty(),
                    onClick = { if (favorites.isNotEmpty()) onPlayList(favorites) },
                    scale = scale
                )
            },
            {
                QuickCard(
                    label = "最近添加",
                    colors = listOf(Color(0xFFA68C71), Color(0xFF7C664F)),
                    enabled = songs.isNotEmpty(),
                    onClick = {
                        val recent = songs.sortedByDescending { it.lastModified }.take(50)
                        if (recent.isNotEmpty()) onPlayList(recent)
                    },
                    scale = scale
                )
            },
            {
                QuickCard(
                    label = "音效设置",
                    colors = listOf(Color(0xFF6A4FB3), Color(0xFF3E2C73)),
                    enabled = true,
                    icon = Icons.Default.GraphicEq,
                    onClick = onOpenSound,
                    scale = scale
                )
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp * scale)) {
            quickCards.chunked(2).forEach { rowCards ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp * scale)) {
                    rowCards.forEach { card -> Box(Modifier.weight(1f)) { card() } }
                }
            }
        }

        Spacer(Modifier.height(20.dp * scale))
        SectionTitle("我的歌单", scale = scale) {
            Text(
                "共 ${playlists.size} 个",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp * scale))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp * scale)
        ) {
            if (playlists.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp * scale),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(width = 148.dp * scale, height = 124.dp * scale)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "还没有歌单\n到「我的」创建",
                            textAlign = TextAlign.Center,
                            fontSize = 11.sp * scale,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                playlists.take(8).forEachIndexed { index, playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        index = index,
                        onClick = { onOpenPlaylist(playlist.id) },
                        scale = scale
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp * scale))
        SectionTitle("最近播放", scale = scale)
        Spacer(Modifier.height(8.dp * scale))
        val recent = queue.take(6)
        if (recent.isEmpty()) {
            Text(
                "播放歌曲后，这里会显示最近队列",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp * scale)
            ) {
                recent.forEachIndexed { index, song ->
                    RecentCard(
                        song = song,
                        index = index,
                        onClick = { onPlayRecent(index) },
                        scale = scale
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp * scale))
    }
}

@Composable
private fun BannerCard(
    title: String,
    subtitle: String,
    colors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f
) {
    Surface(
        shape = RoundedCornerShape(18.dp * scale),
        color = Color.Unspecified,
        modifier = modifier.height(118.dp * scale).clickable(onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(colors)).padding(18.dp * scale),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(title, color = Color.White, fontSize = 20.sp * scale, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp * scale))
                Text(subtitle, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp * scale)
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
private fun QuickCard(
    label: String,
    colors: List<Color>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    icon: ImageVector = Icons.Default.PlayArrow
) {
    Surface(
        shape = RoundedCornerShape(18.dp * scale),
        color = Color.Unspecified,
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp * scale)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Brush.linearGradient(colors))
                .padding(16.dp * scale),
            contentAlignment = Alignment.BottomStart
        ) {
            Text(
                label,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
                fontSize = 18.sp * scale,
                fontWeight = FontWeight.Medium
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(32.dp * scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) 0.28f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (enabled) 1f else 0.6f),
                    modifier = Modifier.size(18.dp * scale)
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    index: Int,
    onClick: () -> Unit,
    scale: Float = 1f
) {
    Surface(
        shape = RoundedCornerShape(14.dp * scale),
        color = Color.Unspecified,
        modifier = Modifier
            .size(width = 148.dp * scale, height = 124.dp * scale)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(PlaylistGradients[index % PlaylistGradients.size]))
                .padding(12.dp * scale)
        ) {
            Text(
                playlist.name,
                color = Color.White,
                fontSize = 14.sp * scale,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 22.dp * scale)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp * scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp * scale)
                )
            }
        }
    }
}

@Composable
private fun RecentCard(
    song: Song,
    index: Int,
    onClick: () -> Unit,
    scale: Float = 1f
) {
    Column(
        Modifier
            .size(width = 112.dp * scale, height = 126.dp * scale)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .size(width = 112.dp * scale, height = 92.dp * scale)
                .clip(RoundedCornerShape(12.dp * scale))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp * scale)
            )
        }
        Spacer(Modifier.height(6.dp * scale))
        Text(
            song.title,
            fontSize = 11.sp * scale,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(3.dp * scale))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp * scale)
                .background(RecentAccents[index % RecentAccents.size], RoundedCornerShape(2.dp))
        )
    }
}
