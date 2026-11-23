package com.nasmusic.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nasmusic.player.core.AUDIO_EXTENSIONS
import com.nasmusic.player.core.formatDuration
import com.nasmusic.player.core.formatSize
import com.nasmusic.player.data.LyricsSource

@Composable
fun SettingsScreen(vm: MainViewModel, playerVm: PlayerViewModel, nav: NavController) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val sleepRemaining by playerVm.sleepRemainingMs.collectAsStateWithLifecycle()

    var cacheVersion by remember { mutableStateOf(0) }
    var cacheInfo by remember { mutableStateOf(0 to 0L) }
    var limit by remember(settings.cacheLimitMb) { mutableStateOf(settings.cacheLimitMb.toFloat()) }
    var threshold by remember(settings.minDurationSec) { mutableStateOf(settings.minDurationSec.toFloat()) }
    var manualRule by remember { mutableStateOf("") }
    // 两个文件夹列表都默认折叠，避免规则很多时把设置页撑得很长
    var foldersExpanded by remember { mutableStateOf(false) }
    var excludedExpanded by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.addFolder(it) } }

    LaunchedEffect(cacheVersion) { cacheInfo = vm.cacheState() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
        )

        SectionCard("主题外观", Icons.Default.Palette) {
            val dark = LocalIsDarkTheme.current
            val activeKey = LocalThemePaletteKey.current
            val currentMode = ThemeMode.fromKey(settings.themeMode)

            Text("切换模式", style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp)
            ) {
                ThemeMode.values().forEach { mode ->
                    TinyChip(
                        text = mode.label,
                        selected = currentMode == mode,
                        onClick = { vm.setThemeMode(mode.key) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            InfoText(currentMode.hint, Modifier.padding(top = 6.dp))

            if (currentMode == ThemeMode.SCHEDULE) {
                var start by remember(settings.scheduleStartHour) {
                    mutableStateOf(settings.scheduleStartHour.toFloat())
                }
                var end by remember(settings.scheduleEndHour) {
                    mutableStateOf(settings.scheduleEndHour.toFloat())
                }
                Text(
                    "浅色时段 ${start.toInt()}:00 – ${end.toInt()}:00（其余时间自动为深色）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp)
                ) {
                    listOf(7 to 19, 6 to 18, 8 to 20, 9 to 22).forEach { (s, e) ->
                        TinyChip(
                            text = "$s–${e}点",
                            selected = settings.scheduleStartHour == s && settings.scheduleEndHour == e,
                            onClick = { vm.setScheduleHours(s, e) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(
                    "浅色开始 ${start.toInt()}:00",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = start,
                    onValueChange = { start = it.coerceAtMost(end - 1f) },
                    onValueChangeFinished = { vm.setScheduleHours(start.toInt(), end.toInt()) },
                    valueRange = 0f..11f,
                    steps = 10
                )
                Text("浅色结束 ${end.toInt()}:00", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = end,
                    onValueChange = { end = it.coerceAtLeast(start + 1f) },
                    onValueChangeFinished = { vm.setScheduleHours(start.toInt(), end.toInt()) },
                    valueRange = 12f..24f,
                    steps = 11
                )
            }

            Text(
                "配色方案（${ThemePalettes.count { !it.dynamic || isDynamicThemeAvailable() }} 套）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            ThemePalettes
                .filter { !it.dynamic || isDynamicThemeAvailable() }
                .chunked(2)
                .forEach { rowItems ->
                    Row(Modifier.padding(top = 8.dp)) {
                        rowItems.forEach { spec ->
                            Box(Modifier.weight(1f)) {
                                PaletteCard(
                                    spec = spec,
                                    selected = activeKey == spec.key,
                                    dark = dark,
                                    onClick = { vm.setThemePalette(spec.key) }
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            InfoText(
                "每套配色都含浅色与深色两组；切换后立即生效，无需重启。" +
                    if (isDynamicThemeAvailable()) " 动态取色读取系统壁纸颜色。"
                    else " 当前系统版本不支持动态取色。",
                Modifier.padding(top = 8.dp)
            )
            SettingSwitchRow(
                "封面取色背景",
                "根据当前专辑封面调整播放页渐变，换歌平滑过渡；关闭则只用主题色",
                settings.artworkTint
            ) { vm.setArtworkTint(it) }
            SettingSwitchRow(
                "自动下载专辑封面",
                "本地无封面时联网匹配并缓存；匹配不到则显示动态光影封面",
                settings.autoDownloadCover
            ) { vm.setAutoDownloadCover(it) }
            OutlinedButton(
                onClick = { vm.clearCoverCache() },
                modifier = Modifier.padding(top = 4.dp)
            ) { Text("清空封面缓存") }
        }

        SectionCard("常规行为", Icons.Default.Settings) {
            SettingSwitchRow("启动时自动扫描本地音乐", checked = settings.autoScanOnStart) { vm.setAutoScan(it) }
            SettingSwitchRow("播放记忆", "恢复上次队列与播放位置", settings.restorePlayback) { vm.setRestorePlayback(it) }
        }

        SectionCard("扫描过滤（短音频 / 排除文件夹）", Icons.Default.Tune) {
            Text(
                "最短时长：${threshold.toInt()} 秒",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { vm.setMinDurationSec((threshold / 5f).toInt() * 5) },
                valueRange = 0f..120f
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(0, 10, 15, 20, 30, 60, 120).forEach { seconds ->
                    TinyChip(
                        text = if (seconds == 0) "不限" else "${seconds}s",
                        selected = settings.minDurationSec == seconds,
                        onClick = { vm.setMinDurationSec(seconds) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            InfoText(
                "低于该时长的音频（录音、提示音、语音片段等）不会进入音乐库，修改后立即从列表移除。",
                Modifier.padding(top = 6.dp)
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = settings.excludedFolders.isNotEmpty()) {
                        excludedExpanded = !excludedExpanded
                    }
            ) {
                Text(
                    "已排除的文件夹（${settings.excludedFolders.size}）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (excludedExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (excludedExpanded) "收起已排除列表" else "展开已排除列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settings.excludedFolders.isEmpty()) {
                InfoText("暂无排除规则", Modifier.padding(top = 4.dp))
            } else if (excludedExpanded) {
                settings.excludedFolders.sorted().forEach { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rule,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { vm.restoreFolder(rule) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "恢复该文件夹",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                InfoText("点击上方标题展开已排除列表", Modifier.padding(top = 4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = manualRule,
                    onValueChange = { manualRule = it },
                    placeholder = { Text("输入文件夹名或路径片段") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = {
                        vm.excludeFolder(manualRule)
                        manualRule = ""
                    },
                    enabled = manualRule.isNotBlank()
                ) { Text("排除") }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable(enabled = folders.isNotEmpty()) {
                        foldersExpanded = !foldersExpanded
                    }
            ) {
                Text(
                    "从音乐库中选择要排除并移除的文件夹（${folders.size}）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (foldersExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (foldersExpanded) "收起文件夹列表" else "展开文件夹列表",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (folders.isEmpty()) {
                InfoText("音乐库里暂时没有文件夹", Modifier.padding(top = 4.dp))
            } else if (foldersExpanded) {
                folders.take(20).forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folder,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.excludeFolder(folder) }) {
                            Text("排除并移除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (folders.size > 20) InfoText("仅显示前 20 个，其余可在「音乐库 → 文件夹」中排除")
            } else {
                InfoText("点击上方标题展开文件夹列表", Modifier.padding(top = 4.dp))
            }

            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { vm.purgeFiltered() }) { Text("立即清理") }
                OutlinedButton(
                    onClick = { vm.rescanAfterRestore() },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("重新扫描") }
            }
            InfoText(
                "排除只影响本应用的音乐库索引，不会删除手机或 NAS 上的文件；恢复规则后重新扫描即可找回。",
                Modifier.padding(top = 6.dp)
            )
        }

        SectionCard("音乐列表", Icons.Default.LibraryMusic) {
            Row {
                OutlinedButton(onClick = { vm.scanLocal() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  重新扫描")
                }
                OutlinedButton(
                    onClick = { folderLauncher.launch(null) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  添加文件夹")
                }
            }
            InfoText(
                "本地扫描基于 MediaStore，手动添加使用系统文件选择器，兼容分区存储。",
                Modifier.padding(top = 8.dp)
            )
        }

        SectionCard("网络音乐源（NAS）", Icons.Default.Cloud) {
            NasSourcesSection(vm, nav)
        }

        SectionCard("在线歌词", Icons.Default.MusicNote) {
            SettingSwitchRow(
                "自动下载歌词",
                "缺少歌词时自动匹配并保存到本机",
                settings.autoDownloadLyrics
            ) { vm.setAutoDownloadLyrics(it) }
            Text("歌词源", style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                LyricsSource.values().forEach { source ->
                    TinyChip(
                        text = source.label,
                        selected = settings.lyricsSource == source.key,
                        onClick = { vm.setLyricsSource(source.key) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            InfoText(
                "自动模式同时检索 LRCLIB 与网易云音乐；下载的歌词保存在应用私有目录，不写入系统媒体库。" +
                    "播放页可手动搜索并挑选版本。",
                Modifier.padding(top = 6.dp)
            )
            Text(
                "时间偏移：${settings.lyricsOffsetMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
            Row {
                OutlinedButton(onClick = { playerVm.adjustLyricsOffset(-500L) }) { Text("-0.5s") }
                OutlinedButton(
                    onClick = { playerVm.adjustLyricsOffset(500L) },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("+0.5s") }
                OutlinedButton(
                    onClick = {
                        vm.setLyricsOffset(0L)
                        playerVm.adjustLyricsOffset(0L)
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("重置") }
            }
            OutlinedButton(
                onClick = { vm.clearDownloadedLyrics() },
                modifier = Modifier.padding(top = 6.dp)
            ) { Text("清除已下载歌词") }
        }

        SectionCard("NAS 缓存（LRU）", Icons.Default.Storage) {
            Text("缓存上限：${limit.toInt()} MB", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = limit,
                onValueChange = { limit = it },
                onValueChangeFinished = {
                    vm.setCacheLimitMb(limit.toInt())
                    vm.enforceCacheLimit()
                    cacheVersion++
                },
                valueRange = 64f..2048f
            )
            InfoText("已缓存 ${cacheInfo.first} 首 · ${formatSize(cacheInfo.second)}（不写入系统媒体库）")
            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { vm.clearCache(); cacheVersion++ }) { Text("清空缓存") }
                OutlinedButton(
                    onClick = { playerVm.cacheQueue() },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("缓存当前列表") }
            }
        }

        SectionCard("睡眠定时器", Icons.Default.Timer) {
            if (sleepRemaining > 0) {
                Text("剩余 ${formatDuration(sleepRemaining)}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(15, 30, 60).forEach { minutes ->
                    OutlinedButton(
                        onClick = { playerVm.startSleepTimer(minutes) },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("${minutes}分") }
                }
                OutlinedButton(onClick = { playerVm.cancelSleepTimer() }) { Text("取消") }
            }
        }

        SectionCard("关于", Icons.Default.Info) {
            InfoText("支持格式：${AUDIO_EXTENSIONS.sorted().joinToString(", ").uppercase()}")
            InfoText(
                "Kotlin · Compose · MVVM · Room · Media3 · SMBJ · OkHttp",
                Modifier.padding(top = 4.dp)
            )
            InfoText("v2.1.2 · 轻量 · 无广告 · 支持车机/平板双栏", Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** 主题卡片：用该配色自身渲染一张迷你界面预览，所见即所得。 */
@Composable
private fun PaletteCard(
    spec: ThemePalette,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit
) {
    val scheme = if (spec.dynamic) MaterialTheme.colorScheme else spec.scheme(dark)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp)
            .clip(shape)
            .background(scheme.surface)
            .then(
                if (selected) Modifier.border(2.dp, scheme.primary, shape)
                else Modifier.border(1.dp, scheme.outline.copy(alpha = 0.5f), shape)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                spec.swatch.forEach { color ->
                    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(4.dp))
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已选择",
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(scheme.onSurface.copy(alpha = 0.26f))
            )
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.52f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.onSurface.copy(alpha = 0.14f))
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        spec.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        spec.desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(scheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
