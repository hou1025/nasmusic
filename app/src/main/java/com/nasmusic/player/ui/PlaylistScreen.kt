package com.nasmusic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nasmusic.player.core.Playlist
import com.nasmusic.player.core.Song

@Composable
fun PlaylistScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    nav: NavController,
    onOpenPlaylist: ((String) -> Unit)? = null
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("播放列表", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${playlists.size} 个列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建播放列表")
            }
        }
        if (playlists.isEmpty()) {
            Box(Modifier.weight(1f)) { EmptyState("还没有播放列表，点击右上角新建") }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (onOpenPlaylist != null) onOpenPlaylist(playlist.id)
                                else nav.navigate("playlist/${playlist.id}")
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                            Text("播放列表", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { renameTarget = playlist }) {
                            Icon(Icons.Default.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = { vm.deletePlaylist(playlist.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }

    if (showCreate) CreatePlaylistDialog(vm, onDismiss = { showCreate = false })
    renameTarget?.let { target ->
        RenamePlaylistDialog(
            vm = vm,
            playlistId = target.id,
            current = target.name,
            onDismiss = { renameTarget = null }
        )
    }
}

@Composable
fun PlaylistDetailScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    playlistId: String,
    onBack: () -> Unit
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.find { it.id == playlistId }
    val flow = remember(playlistId) { vm.playlistSongs(playlistId) }
    val songs by flow.collectAsState(initial = emptyList())
    val state by playerVm.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var addTarget by remember { mutableStateOf<Song?>(null) }
    var editTarget by remember { mutableStateOf<Song?>(null) }

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(
            title = playlist?.name ?: "播放列表",
            onBack = onBack,
            actions = {
                IconButton(onClick = { if (songs.isNotEmpty()) playerVm.playSongs(songs) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "播放全部")
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加歌曲")
                }
            }
        )
        if (songs.isEmpty()) {
            Box(Modifier.weight(1f)) { EmptyState("列表为空，点击右上角添加歌曲") }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        playing = state.currentSong?.id == song.id,
                        onClick = { playerVm.playSongs(songs, songs.indexOf(song)) },
                        trailing = {
                            Row {
                                SongOptionsMenu(
                                    vm = vm,
                                    playerVm = playerVm,
                                    song = song,
                                    onAddToPlaylist = { addTarget = song },
                                    onEditTags = { editTarget = song }
                                )
                                IconButton(onClick = { vm.removeFromPlaylist(playlistId, song.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "从列表移除")
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAdd) AddSongsDialog(vm, playlistId, onDismiss = { showAdd = false })
    addTarget?.let { song ->
        AddToPlaylistDialog(vm, listOf(song.id), onDismiss = { addTarget = null })
    }
    editTarget?.let { song ->
        EditTagsDialog(song, vm, onDismiss = { editTarget = null })
    }
}
