package com.nasmusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.desktop.model.Song

/**
 * 本地音乐列表：搜索框 + 重新扫描 + 歌曲列表。
 * 搜索与 Android 端同一口径（标题 / 歌手 / 专辑 / 路径）。
 */
@Composable
fun LibraryPane(
    songs: List<Song>,
    currentSongId: String?,
    query: String,
    onQuery: (String) -> Unit,
    onPlay: (List<Song>, Song) -> Unit,
    onFavorite: (String) -> Unit,
    onScan: () -> Unit,
    scanning: Boolean,
    scanMessage: String,
    scale: Float = 1f
) {
    val visible = remember(songs, query) {
        val q = query.trim()
        if (q.isBlank()) songs
        else songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) ||
                it.album.contains(q, true) || it.folder.contains(q, true) ||
                it.uri.contains(q, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp * scale),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp * scale)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索歌名 / 歌手 / 专辑 / 路径", fontSize = 13.sp * scale) },
                singleLine = true
            )
            Button(onClick = onScan, enabled = !scanning) {
                Icon(Icons.Default.Refresh, contentDescription = null,
                    modifier = Modifier.size(16.dp * scale))
                Spacer(Modifier.width(6.dp * scale))
                Text(if (scanning) "扫描中…" else "重新扫描", fontSize = 13.sp * scale)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "共 ${visible.size} 首",
                fontSize = 12.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            if (scanMessage.isNotBlank()) {
                Text(
                    scanMessage,
                    fontSize = 11.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp * scale))

        if (visible.isEmpty()) {
            EmptyHint(
                if (songs.isEmpty()) "曲库还是空的：到「设置」里添加本地音乐目录，然后重新扫描"
                else "没有匹配「$query」的歌曲",
                scale
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp * scale)
            ) {
                items(visible, key = { it.id }) { song ->
                    SongRowItem(
                        song = song,
                        isCurrent = song.id == currentSongId,
                        onClick = { onPlay(visible, song) },
                        onFavorite = { onFavorite(song.id) },
                        scale = scale
                    )
                }
            }
        }
    }
}
