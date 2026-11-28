package com.nasmusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 右侧主区域的四个标签页，顺序与 Android 横屏顶栏一致。 */
enum class HomeTab(val label: String) {
    DISCOVER("发现"),
    LOCAL("本地音乐"),
    SOURCES("网络音源"),
    MINE("我的")
}

/**
 * 顶栏：图标 + 四个标签 +「设置」文字入口 + 搜索图标。
 * 与 Android 横屏 DiscoverTopBar 对齐：选中项为红色粗体并带下划线指示条。
 */
@Composable
fun DashboardTopBar(
    active: HomeTab,
    onTab: (HomeTab) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    scale: Float = 1f
) {
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
            HomeTab.entries.forEach { tab ->
                val selected = active == tab
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onTab(tab) }
                        .padding(horizontal = 14.dp * scale, vertical = 4.dp * scale)
                ) {
                    Text(
                        tab.label,
                        color = if (selected) DiscoverRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 16.sp * scale
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
        Text(
            "设置",
            color = DiscoverInk.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp * scale,
            modifier = Modifier
                .clickable(onClick = onSettings)
                .padding(horizontal = 8.dp * scale, vertical = 8.dp * scale)
        )
        IconButton(onClick = onSearch, modifier = Modifier.size(44.dp * scale)) {
            Icon(
                Icons.Default.Search,
                contentDescription = "搜索",
                tint = DiscoverInk.copy(alpha = 0.75f),
                modifier = Modifier.size(23.dp * scale)
            )
        }
    }
}

/** 区块标题（快捷入口 / 我的歌单 / 最近播放）。 */
@Composable
fun SectionTitle(
    title: String,
    scale: Float = 1f,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 16.sp * scale, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
