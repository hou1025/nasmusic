@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nasmusic.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nasmusic.player.core.Song
import com.nasmusic.player.playback.PlaybackUiState

private val LIBRARY_TABS = listOf("歌曲", "专辑", "歌手", "文件夹", "收藏")

@Composable
fun LibraryScreen(vm: MainViewModel, playerVm: PlayerViewModel, nav: NavController) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val state by playerVm.state.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var group by remember { mutableStateOf<String?>(null) }
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var editTarget by remember { mutableStateOf<Song?>(null) }
    var excludeTarget by remember { mutableStateOf<String?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.addFolder(it) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("音乐列表", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${songs.size} 首 · 已应用时长与文件夹过滤",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (progress.running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { vm.scanLocal() }) {
                Icon(Icons.Default.Refresh, contentDescription = "重新扫描本地音乐")
            }
            IconButton(onClick = { folderLauncher.launch(null) }) {
                Icon(Icons.Default.Add, contentDescription = "手动添加音乐文件夹")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            LIBRARY_TABS.forEachIndexed { index, title ->
                val selected = tab == index
                Surface(
                    onClick = { tab = index; group = null },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        val groups: Map<String, List<Song>> = when (tab) {
            1 -> songs.filter { it.album.isNotBlank() }.groupBy { it.album }
            2 -> songs.filter { it.artist.isNotBlank() }.groupBy { it.artist }
            3 -> songs.filter { it.folder.isNotBlank() }.groupBy { it.folder }
            else -> emptyMap()
        }

        when {
            group != null -> {
                val current = when (tab) {
                    1 -> songs.filter { it.album == group }
                    2 -> songs.filter { it.artist == group }
                    3 -> songs.filter { it.folder == group }
                    else -> emptyList()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    IconButton(onClick = { group = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回分组")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(group.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            "${current.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (tab == 3) {
                        TextButton(onClick = { excludeTarget = group }) { Text("排除") }
                    }
                }
                SongList(
                    songs = current,
                    state = state,
                    vm = vm,
                    playerVm = playerVm,
                    onAddToPlaylist = { addTarget = it },
                    onEditTags = { editTarget = it },
                    modifier = Modifier.weight(1f)
                )
            }
            tab in 1..3 -> {
                if (groups.isEmpty()) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EmptyState("暂无分组，请先扫描音乐")
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(groups.entries.sortedBy { it.key }, key = { it.key }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { group = entry.key }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (tab == 3) Icons.Default.Folder else Icons.Default.Album,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(
                                        entry.key,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${entry.value.size} 首",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (tab == 3) {
                                    IconButton(onClick = { excludeTarget = entry.key }) {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = "排除并移除该文件夹",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Text("›", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            tab == 4 -> SongList(
                songs = favorites,
                state = state,
                vm = vm,
                playerVm = playerVm,
                onAddToPlaylist = { addTarget = it },
                onEditTags = { editTarget = it },
                modifier = Modifier.weight(1f),
                emptyText = "还没有收藏歌曲"
            )
            else -> SongList(
                songs = songs,
                state = state,
                vm = vm,
                playerVm = playerVm,
                onAddToPlaylist = { addTarget = it },
                onEditTags = { editTarget = it },
                modifier = Modifier.weight(1f)
            )
        }
    }

    addTarget?.let { song ->
        AddToPlaylistDialog(vm, listOf(song.id), onDismiss = { addTarget = null })
    }
    editTarget?.let { song ->
        EditTagsDialog(song, vm, onDismiss = { editTarget = null })
    }
    excludeTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { excludeTarget = null },
            title = { Text("排除并移除文件夹") },
            text = {
                Text(
                    "「$folder」及其子目录中的歌曲将从音乐库移除，之后扫描也不会再收录，" +
                        "因此不会再出现在播放列表里。手机 / NAS 上的原文件不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.excludeFolder(folder)
                    if (group == folder) group = null
                    excludeTarget = null
                }) { Text("排除并移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { excludeTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    state: PlaybackUiState,
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onAddToPlaylist: (Song) -> Unit,
    onEditTags: (Song) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "暂无歌曲，请先扫描本地音乐或添加 NAS 音乐源"
) {
    if (songs.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { EmptyState(emptyText) }
        return
    }
    LazyColumn(modifier) {
        items(songs, key = { it.id }) { song ->
            SongRow(
                song = song,
                playing = state.currentSong?.id == song.id,
                onClick = { playerVm.playSongs(songs, songs.indexOf(song)) },
                trailing = {
                    SongOptionsMenu(
                        vm = vm,
                        playerVm = playerVm,
                        song = song,
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onEditTags = { onEditTags(song) }
                    )
                }
            )
        }
    }
}
