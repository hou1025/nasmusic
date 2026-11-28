package com.nasmusic.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nasmusic.player.core.Song

@Composable
fun SongOptionsMenu(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    song: Song,
    onAddToPlaylist: () -> Unit,
    onEditTags: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (song.isFavorite) "取消收藏" else "加入收藏") },
                onClick = { vm.toggleFavorite(song); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("下一首播放") },
                onClick = { playerVm.playNext(listOf(song)); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("加入播放列表…") },
                onClick = { expanded = false; onAddToPlaylist() }
            )
            DropdownMenuItem(
                text = { Text("编辑标签") },
                onClick = { expanded = false; onEditTags() }
            )
        }
    }
}

@Composable
fun AddToPlaylistDialog(vm: MainViewModel, songIds: List<String>, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放列表") },
        text = {
            if (playlists.isEmpty()) {
                Text("暂无播放列表，可直接新建一个")
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.addToPlaylist(playlist.id, songIds); onDismiss() }
                        ) { Text(playlist.name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.createPlaylist("新播放列表", songIds); onDismiss() }) {
                Text("新建并加入")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun CreatePlaylistDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建播放列表") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("列表名称") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) vm.createPlaylist(name); onDismiss() }) {
                Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun RenamePlaylistDialog(vm: MainViewModel, playlistId: String, current: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名播放列表") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            Button(onClick = { vm.renamePlaylist(playlistId, name); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun EditTagsDialog(song: Song, vm: MainViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artist) }
    var album by remember { mutableStateOf(song.album) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            LazyColumn {
                item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("歌名") }, singleLine = true) }
                item { OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("歌手") }, singleLine = true) }
                item { OutlinedTextField(value = album, onValueChange = { album = it }, label = { Text("专辑") }, singleLine = true) }
            }
        },
        confirmButton = {
            Button(onClick = { vm.updateTags(song, title, artist, album); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun AddSongsDialog(vm: MainViewModel, playlistId: String, onDismiss: () -> Unit) {
    val all by vm.songs.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加歌曲（${selected.size}）") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(all, key = { it.id }) { song ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (song.id in selected) selected - song.id else selected + song.id
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = song.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + song.id else selected - song.id
                            }
                        )
                        Text(song.title, modifier = Modifier.weight(1f), maxLines = 1)
                        if (song.isNas) SourceBadge(song)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.addToPlaylist(playlistId, selected.toList()); onDismiss() }) {
                Text("添加")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
