package com.nasmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.desktop.model.Song

/** 毫秒转 mm:ss；时长未知时显示 --:--，而不是 00:00 骗人。 */
internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

/** 二级页（设置/搜索/歌单详情）的统一返回栏。 */
@Composable
internal fun PaneHeader(
    title: String,
    onBack: () -> Unit,
    scale: Float = 1f,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(34.dp * scale).clickable(onClick = onBack)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("‹", fontSize = 20.sp * scale, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp * scale))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp * scale),
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 列表中的一行歌曲：封面占位 + 标题/歌手 + 收藏。 */
@Composable
internal fun SongRowItem(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    scale: Float = 1f
) {
    Surface(
        shape = RoundedCornerShape(12.dp * scale),
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp * scale, vertical = 8.dp * scale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp * scale)
                    .clip(RoundedCornerShape(10.dp * scale))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp * scale)
                )
            }
            Spacer(Modifier.width(10.dp * scale))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp * scale,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) DiscoverRed else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${song.artist.ifBlank { "未知歌手" }} · ${song.sourceLabel}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp * scale)) {
                Icon(
                    if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (song.isFavorite) "取消收藏" else "收藏",
                    tint = if (song.isFavorite) DiscoverRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp * scale)
                )
            }
        }
    }
}

/** 空状态提示：所有列表页共用，避免每处各写一套措辞。 */
@Composable
internal fun EmptyHint(text: String, scale: Float = 1f) {
    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp * scale), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.sp * scale,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 小圆形按钮（播放/收藏等轻量操作）。 */
@Composable
internal fun RoundIconButton(
    onClick: () -> Unit,
    active: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    scale: Float = 1f,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (active) DiscoverRed.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(size * scale).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** 「播放全部」小按钮，供歌单/收藏区块复用。 */
@Composable
internal fun PlayAllButton(text: String, onClick: () -> Unit, scale: Float = 1f) {
    Surface(
        shape = RoundedCornerShape(50),
        color = DiscoverRed,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp * scale, vertical = 6.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(16.dp * scale))
            Text(text, fontSize = 12.sp * scale, color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Medium)
        }
    }
}
