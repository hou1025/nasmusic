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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
    val allSongs by vm.songs.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val state by playerVm.state.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val connectionStatus by vm.nasConnectionStatus.collectAsStateWithLifecycle()

    // 进入音乐列表时探测各网络音源：连上的显示其歌曲，连不上/失败的清空其歌曲；
    // 每次进入（含从网络音源页返回、切到「网络音乐源」筛选）都会重新探测，重连后自动恢复。
    var connectionVersion by remember { mutableStateOf(0) }
    LaunchedEffect(connectionVersion) { vm.refreshNasConnections() }

    // 只保留已连通音源的网络歌曲；未连通音源的列表清空，避免断网仍显示旧曲目。
    // 本地歌曲不受影响。探测未完成时 connectionStatus 为空，网络歌曲暂时隐藏。
    val songs = remember(allSongs, connectionStatus) { allSongs.filterConnected(connectionStatus) }
    // 收藏 / 搜索等派生列表必须与歌曲 Tab 用同一口径，断连音源歌曲不可点击
    val connectedFavorites = remember(favorites, connectionStatus) {
        favorites.filterConnected(connectionStatus)
    }

    var tab by rememberSaveable { mutableStateOf(0) }
    // 歌曲 Tab 的来源筛选：0=全部 1=网络音乐源 2=本地音乐（旋转/切 Tab 不丢失）
    var songFilter by rememberSaveable { mutableStateOf(0) }
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
            IconButton(onClick = { vm.scanLocal(); connectionVersion++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "重新扫描并检测网络音源")
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
                songs = connectedFavorites,
                state = state,
                vm = vm,
                playerVm = playerVm,
                onAddToPlaylist = { addTarget = it },
                onEditTags = { editTarget = it },
                modifier = Modifier.weight(1f),
                emptyText = "还没有收藏歌曲"
            )
            else -> GroupedSongList(
                songs = songs,
                filter = songFilter,
                onFilterChange = { songFilter = it },
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

/** 「歌曲」Tab：顶部来源筛选栏（全部/网络音乐源/本地音乐），可点击切换，列表只显示所选来源。 */
@Composable
private fun GroupedSongList(
    songs: List<Song>,
    filter: Int,
    onFilterChange: (Int) -> Unit,
    state: PlaybackUiState,
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    onAddToPlaylist: (Song) -> Unit,
    onEditTags: (Song) -> Unit,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState("暂无歌曲，请先扫描本地音乐或添加 NAS 音乐源")
        }
        return
    }
    val network = songs.filter { it.isNas }
    val local = songs.filter { !it.isNas }
    val hasNetwork = network.isNotEmpty()
    // 没有网络源时强制回到「全部」，不显示筛选栏，本地歌曲平铺。
    val effectiveFilter = if (!hasNetwork) 0 else filter

    // 当前筛选要展示的队列
    val shown = when (effectiveFilter) {
        1 -> network
        2 -> local
        else -> songs
    }

    LazyColumn(modifier) {
        // 筛选栏放在原「网络音乐源」那一行，三个选项排在一起、可点击切换
        if (hasNetwork) {
            item(key = "source_filter") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val options = listOf(0 to "全部", 1 to "网络音乐源", 2 to "本地音乐")
                    options.forEach { (value, label) ->
                        val selected = effectiveFilter == value
                        Surface(
                            onClick = { onFilterChange(value) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "filter_empty") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when (effectiveFilter) {
                            1 -> "暂无网络音乐源歌曲"
                            2 -> "暂无本地歌曲"
                            else -> "暂无歌曲"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(shown, key = { it.id }) { song ->
            SongRow(
                song = song,
                playing = state.currentSong?.id == song.id,
                onClick = { playerVm.playSongs(shown, shown.indexOf(song)) },
                trailing = {
                    SongOptionsMenu(
                        vm = vm, playerVm = playerVm, song = song,
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onEditTags = { onEditTags(song) }
                    )
                }
            )
        }
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
