package com.nasmusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.desktop.model.Playlist
import com.nasmusic.desktop.model.Song

/** 「我的」页：新建歌单 + 歌单列表 + 收藏列表。 */
@Composable
fun MinePane(
    playlists: List<Playlist>,
    favorites: List<Song>,
    songsInPlaylist: (String) -> List<Song>,
    currentSongId: String?,
    onCreate: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onPlayList: (List<Song>) -> Unit,
    onPlay: (Song) -> Unit,
    onFavorite: (String) -> Unit,
    scale: Float = 1f
) {
    var name by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp * scale)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp * scale)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新建歌单", fontSize = 12.sp * scale) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onCreate(name)
                            name = ""
                        }
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp * scale))
                    Text("创建", fontSize = 13.sp * scale)
                }
            }
        }

        item { SectionTitle("我的歌单（${playlists.size}）", scale = scale) }

        if (playlists.isEmpty()) {
            item { EmptyHint("还没有歌单，先在上面建一个", scale) }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                val songs = songsInPlaylist(playlist.id)
                Card(
                    shape = RoundedCornerShape(14.dp * scale),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp * scale),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                fontSize = 14.sp * scale,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                            Text(
                                "${songs.size} 首",
                                fontSize = 11.sp * scale,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { onPlayList(songs) },
                            enabled = songs.isNotEmpty()
                        ) { Text("播放", fontSize = 12.sp * scale) }
                        TextButton(onClick = { onOpenPlaylist(playlist.id) }) {
                            Text("打开", fontSize = 12.sp * scale)
                        }
                    }
                }
            }
        }

        item { SectionTitle("我的收藏（${favorites.size}）", scale = scale) }

        if (favorites.isEmpty()) {
            item { EmptyHint("还没有收藏歌曲，在列表里点心形就能收藏", scale) }
        } else {
            items(favorites, key = { it.id }) { song ->
                SongRowItem(
                    song = song,
                    isCurrent = song.id == currentSongId,
                    onClick = { onPlay(song) },
                    onFavorite = { onFavorite(song.id) },
                    scale = scale
                )
            }
        }
    }
}

/** 歌单详情：播放全部 + 曲目列表。 */
@Composable
fun PlaylistDetailPane(
    title: String,
    songs: List<Song>,
    currentSongId: String?,
    onBack: () -> Unit,
    onPlayList: (List<Song>) -> Unit,
    onPlay: (Song) -> Unit,
    onFavorite: (String) -> Unit,
    scale: Float = 1f
) {
    Column(Modifier.fillMaxSize()) {
        PaneHeader(title = title, onBack = onBack, scale = scale) {
            PlayAllButton(
                text = "播放全部",
                onClick = { if (songs.isNotEmpty()) onPlayList(songs) },
                scale = scale
            )
        }
        if (songs.isEmpty()) {
            EmptyHint("这个歌单还是空的", scale)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp * scale)
            ) {
                items(songs, key = { it.id }) { song ->
                    SongRowItem(
                        song = song,
                        isCurrent = song.id == currentSongId,
                        onClick = { onPlay(song) },
                        onFavorite = { onFavorite(song.id) },
                        scale = scale
                    )
                }
            }
        }
    }
}
