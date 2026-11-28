package com.nasmusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.desktop.model.Song

/** 全库搜索页：输入即筛（本地曲库 + 已扫描的远程曲目）。 */
@Composable
fun SearchPane(
    query: String,
    onQuery: (String) -> Unit,
    songs: List<Song>,
    currentSongId: String?,
    onPlay: (List<Song>, Song) -> Unit,
    onFavorite: (String) -> Unit,
    onBack: () -> Unit,
    scale: Float = 1f
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val hits = remember(songs, query) {
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) ||
                it.album.contains(q, true) || it.folder.contains(q, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PaneHeader(title = "搜索", onBack = onBack, scale = scale)
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp * scale).focusRequester(focus),
            placeholder = { Text("输入歌名 / 歌手 / 专辑", fontSize = 13.sp * scale) },
            singleLine = true
        )
        Text(
            when {
                query.isBlank() -> "输入关键词开始搜索，共 ${songs.size} 首可搜"
                hits.isEmpty() -> "没有匹配「$query」的歌曲"
                else -> "找到 ${hits.size} 首"
            },
            fontSize = 11.sp * scale,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp * scale)
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp * scale)
        ) {
            items(hits, key = { it.id }) { song ->
                SongRowItem(
                    song = song,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlay(hits, song) },
                    onFavorite = { onFavorite(song.id) },
                    scale = scale
                )
            }
        }
    }
}
