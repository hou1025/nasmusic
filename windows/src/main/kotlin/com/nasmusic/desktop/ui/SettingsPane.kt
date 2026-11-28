package com.nasmusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

private const val APP_VERSION = "2.3.9"

/** 设置页：本地目录管理 + 扫描 + 存储位置 + 关于。 */
@Composable
fun SettingsPane(
    roots: List<String>,
    songCount: Int,
    scanMessage: String,
    scanError: String?,
    scanning: Boolean,
    sleepRemainingMs: Long,
    onSetSleepTimer: (Int) -> Unit,
    onAddRoot: (String) -> Unit,
    onRemoveRoot: (String) -> Unit,
    onScan: () -> Unit,
    scale: Float = 1f
) {
    var path by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp * scale)
    ) {
        SettingCard(title = "本地音乐目录", scale = scale) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("目录绝对路径，例如 D:\\Music", fontSize = 12.sp * scale) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp * scale))
                Button(
                    onClick = {
                        if (path.isNotBlank()) {
                            onAddRoot(path.trim())
                            path = ""
                        }
                    }
                ) { Text("添加", fontSize = 13.sp * scale) }
            }
            if (roots.isEmpty()) {
                Text(
                    "还没有添加目录。添加后点下面的「重新扫描」导入歌曲。",
                    fontSize = 11.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                roots.forEach { root ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(root, fontSize = 12.sp * scale, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemoveRoot(root) }) {
                            Icon(Icons.Default.Delete, contentDescription = "移除目录",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onScan, enabled = !scanning) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp * scale))
                    Text(if (scanning) "扫描中…" else "重新扫描", fontSize = 13.sp * scale)
                }
                Spacer(Modifier.width(10.dp * scale))
                Text(
                    "当前曲库 $songCount 首",
                    fontSize = 12.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (scanMessage.isNotBlank()) {
                Text(scanMessage, fontSize = 11.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            scanError?.let {
                Text(it, fontSize = 11.sp * scale, color = MaterialTheme.colorScheme.error)
            }
        }

        SettingCard(title = "睡眠定时器", scale = scale) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp * scale)
            ) {
                listOf(15, 30, 60).forEach { minutes ->
                    OutlinedButton(onClick = { onSetSleepTimer(minutes) }) {
                        Text("$minutes 分钟", fontSize = 12.sp * scale)
                    }
                }
                OutlinedButton(
                    onClick = { onSetSleepTimer(0) },
                    enabled = sleepRemainingMs > 0
                ) { Text("关闭", fontSize = 12.sp * scale) }
            }
            Text(
                if (sleepRemainingMs > 0) {
                    "剩余约 ${sleepRemainingMs / 60_000 + 1} 分钟；到点只暂停，不会自动开始播放"
                } else {
                    "到点自动暂停播放；如果你已手动暂停，到点不会反过来开始播放"
                },
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingCard(title = "数据与存储", scale = scale) {
            Text(
                "配置、收藏、歌单存在：${System.getProperty("user.home")}\\.nasmusic-desktop\\library.json",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "换机器时把该文件拷过去即可恢复收藏与歌单。",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingCard(title = "关于", scale = scale) {
            Text("NasMusic 桌面版 v$APP_VERSION", fontSize = 13.sp * scale, fontWeight = FontWeight.Medium)
            Text(
                "支持格式：mp3 / wav / m4a / aac（flac 取决于系统解码器）。",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "歌词：把与歌曲同名的 .lrc 放在同一目录即可显示（支持 UTF-8 / GBK）。",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "音源：本地目录 / WebDAV / 飞牛 NAS；SMB 需额外依赖，暂未内置。",
                fontSize = 11.sp * scale,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    scale: Float,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp * scale),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp * scale),
            verticalArrangement = Arrangement.spacedBy(8.dp * scale)
        ) {
            Text(title, fontSize = 14.sp * scale, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            content()
        }
    }
}

/**
 * 音效页：桌面端基于 JavaFX Media，没有 Android 的 AudioEffect 链路，
 * 因此不假装有 10 段 EQ，而是说清限制并给出可用的替代操作（系统音量混音器）。
 */
@Composable
fun SoundPane(scale: Float = 1f) {
    var message by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp * scale)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp * scale),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp * scale),
                verticalArrangement = Arrangement.spacedBy(8.dp * scale)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(8.dp * scale))
                    Text("音效设置", fontSize = 15.sp * scale, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "桌面版使用 JavaFX Media 解码播放，系统未开放均衡器 / 环绕 / 低音增强接口，" +
                        "所以这一版不提供 EQ。需要音效时请在 Android 端使用（那边的音效链是完整的）。",
                    fontSize = 12.sp * scale,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            message = runCatching {
                                ProcessBuilder("sndvol.exe").start()
                                "已打开系统音量混音器"
                            }.getOrElse { "打开失败：${it.message}" }
                        }
                    ) { Text("打开系统音量混音器", fontSize = 12.sp * scale) }
                    Spacer(Modifier.width(10.dp * scale))
                    Text("音量请用左侧播放卡片的滑杆调节", fontSize = 11.sp * scale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.isNotBlank()) {
                    Text(message, fontSize = 11.sp * scale,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}
