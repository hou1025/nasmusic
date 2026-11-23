package com.nasmusic.player.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.SourceType
import com.nasmusic.player.core.formatSize
import com.nasmusic.player.core.friendlyError
import com.nasmusic.player.core.isAudioFile
import com.nasmusic.player.data.FnosSession
import com.nasmusic.player.data.NasEntry
import kotlinx.coroutines.launch
import java.util.UUID

/** 网络源管理内嵌在设置页，不再占用底部导航。 */
@Composable
fun NasSourcesSection(
    vm: MainViewModel,
    nav: NavController,
    onFnosLogin: ((String) -> Unit)? = null,
    onBrowse: ((String, String) -> Unit)? = null
) {
    val sources by vm.nasSources.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<NasSource?>(null) }
    // 传入内嵌回调时走右栏子页面（左侧播放卡片常驻）；否则保持原整页导航
    val goFnosLogin: (String) -> Unit = { id ->
        if (onFnosLogin != null) onFnosLogin(id)
        else nav.navigate("fnosLogin?sourceId=${Uri.encode(id)}")
    }
    val goBrowse: (String, String) -> Unit = { id, path ->
        if (onBrowse != null) onBrowse(id, path)
        else nav.navigate(nasBrowserRoute(id, path))
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "共 ${sources.size} 个网络源",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { goFnosLogin("") }) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 飞牛登录")
            }
            TextButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 添加网络源")
            }
        }

        if (progress.running || progress.message.isNotEmpty() || progress.error != null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        progress.error ?: progress.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (progress.running) {
                        Text(
                            "已扫描 ${progress.scanned} 个文件 · 入库 ${progress.found} 首",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                            TextButton(onClick = { vm.cancelScan() }) { Text("取消扫描") }
                        }
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            InfoText("还没有网络音乐源。点击「添加网络源」接入 SMB/CIFS / WebDAV，或用「飞牛登录」直接接入飞牛音乐曲库。")
        } else {
            sources.forEach { source ->
                NasSourceCard(
                    source = source,
                    onScan = { vm.scanNasSource(source) },
                    onBrowse = { goBrowse(source.id, source.rootPath) },
                    onEdit = {
                        if (source.type == SourceType.FNOS) {
                            goFnosLogin(source.id)
                        } else editTarget = source
                    },
                    onDelete = {
                        if (source.type == SourceType.FNOS) FnosSession.remove(source.id)
                        vm.deleteNasSource(source.id)
                    }
                )
            }
        }
    }

    if (showAdd || editTarget != null) {
        NasSourceDialog(
            vm = vm,
            initial = editTarget,
            onDismiss = { showAdd = false; editTarget = null }
        )
    }
}

@Composable
private fun NasSourceCard(
    source: NasSource,
    onScan: () -> Unit,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text(
                when (source.type) {
                    SourceType.SMB -> "SMB/CIFS · ${source.host}:${source.port}/${source.share}"
                    SourceType.WEBDAV -> "WebDAV · ${source.baseUrl}"
                    SourceType.FNOS -> "飞牛音乐 · ${source.baseUrl} · ${source.username}"
                    SourceType.LOCAL -> "本地"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "根目录：${source.rootPath.ifBlank { "/" }} · 上次扫描：" +
                    if (source.lastScanAt > 0) java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.getDefault()
                    ).format(java.util.Date(source.lastScanAt)) else "尚未扫描",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onScan) { Text("扫描入库") }
                TextButton(onClick = onBrowse) { Text("浏览") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
            }
        }
    }
}

@Composable
private fun NasSourceDialog(vm: MainViewModel, initial: NasSource?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var type by remember { mutableStateOf(initial?.type ?: SourceType.SMB) }
    var host by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var share by remember { mutableStateOf(initial?.share.orEmpty()) }
    var rootPath by remember { mutableStateOf(initial?.rootPath.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var anonymous by remember { mutableStateOf(initial?.anonymous ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加网络音乐源" else "编辑网络音乐源") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.padding(vertical = 8.dp)) {
                    Button(onClick = { type = SourceType.SMB }) { Text("SMB/CIFS") }
                    Button(onClick = { type = SourceType.WEBDAV }) { Text("WebDAV") }
                    Text(
                        "  当前：${if (type == SourceType.SMB) "SMB" else "WebDAV"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                if (type == SourceType.SMB) {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("NAS 地址（IP 或域名）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口（默认 445）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("共享文件夹名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("WebDAV 地址（http/https）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(value = rootPath, onValueChange = { rootPath = it }, label = { Text("音乐根目录（相对共享/URL）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(if (initial == null) "密码" else "密码（留空保持不变）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = anonymous, onCheckedChange = { anonymous = it })
                    Text("匿名访问", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "登录信息使用 Android Keystore 加密保存；应用不会主动扫描全网设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val source = NasSource(
                    id = initial?.id ?: UUID.randomUUID().toString(),
                    name = name.ifBlank { "NAS 音乐源" },
                    type = type,
                    host = host.trim(),
                    port = port.trim().toIntOrNull() ?: 0,
                    share = share.trim(),
                    rootPath = rootPath.trim(),
                    baseUrl = baseUrl.trim(),
                    username = username.trim(),
                    passwordEnc = initial?.passwordEnc.orEmpty(),
                    anonymous = anonymous,
                    lastScanAt = initial?.lastScanAt ?: 0L
                )
                vm.saveNasSource(source, password.takeIf { it.isNotEmpty() })
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun NasBrowserScreen(
    vm: MainViewModel,
    playerVm: PlayerViewModel,
    sourceId: String,
    path: String,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit
) {
    val sources by vm.nasSources.collectAsStateWithLifecycle()
    val source = sources.find { it.id == sourceId }
    var entries by remember(sourceId, path) { mutableStateOf(emptyList<NasEntry>()) }
    var loading by remember(sourceId, path) { mutableStateOf(true) }
    var error by remember(sourceId, path) { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sourceId, path, reloadKey) {
        loading = true
        error = null
        val result = runCatching { vm.browseNas(sourceId, path) }
        entries = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.let { friendlyError(it) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(
            title = "浏览：${path.ifBlank { "/" }}",
            onBack = onBack,
            actions = {
                IconButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
        )
        if (loading) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text("  正在读取目录…", style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        if (!loading && entries.isEmpty() && error == null) {
            Box(Modifier.weight(1f)) { EmptyState("目录为空或没有可访问的条目") }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(
                    entries.sortedWith(compareByDescending<NasEntry> { it.isDirectory }.thenBy { it.name }),
                    key = { it.path }
                ) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) {
                                    onOpenFolder(entry.path)
                                } else if (isAudioFile(entry.name)) {
                                    scope.launch {
                                        val song = vm.nasSong(
                                            sourceId, entry.path, entry.name,
                                            source?.type ?: SourceType.SMB
                                        )
                                        if (song != null) {
                                            vm.ensureSongs(listOf(song))
                                            playerVm.playSong(song)
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                if (entry.isDirectory) "文件夹" else formatSize(entry.sizeBytes),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!entry.isDirectory && isAudioFile(entry.name)) {
                            Text("播放", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
