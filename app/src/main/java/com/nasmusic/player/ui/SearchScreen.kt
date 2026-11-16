package com.nasmusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nasmusic.player.core.Song

/** 顶部搜索框输入时显示的浮层结果；本地音乐与已入库的 NAS 音乐统一检索。 */
@Composable
fun SearchResultsPanel(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onPicked: (() -> Unit)? = null
) {
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val state by playerVm.state.collectAsStateWithLifecycle()
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var editTarget by remember { mutableStateOf<Song?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text(
            if (results.isEmpty()) "没有找到匹配的歌曲" else "找到 ${results.size} 首 · 点击即可播放",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (results.isEmpty()) {
            Box(Modifier.weight(1f)) { EmptyState("换个关键词试试，或检查扫描过滤规则") }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(results, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        playing = state.currentSong?.id == song.id,
                        onClick = {
                            playerVm.playSongs(results, results.indexOf(song))
                            onPicked?.invoke()
                        },
                        trailing = {
                            SongOptionsMenu(
                                vm = vm,
                                playerVm = playerVm,
                                song = song,
                                onAddToPlaylist = { addTarget = song },
                                onEditTags = { editTarget = song }
                            )
                        }
                    )
                }
            }
        }
    }

    addTarget?.let { song ->
        AddToPlaylistDialog(vm, listOf(song.id), onDismiss = { addTarget = null })
    }
    editTarget?.let { song ->
        EditTagsDialog(song, vm, onDismiss = { editTarget = null })
    }
}
