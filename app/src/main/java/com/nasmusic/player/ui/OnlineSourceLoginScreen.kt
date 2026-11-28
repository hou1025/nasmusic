package com.nasmusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.SourceType
import com.nasmusic.player.core.friendlyError
import java.util.UUID

private val OnlineBlue = Color(0xFF1B6EF3)
private val OnlineDeep = Color(0xFF0E3E91)

/**
 * 把用户输入的服务地址补全为最终请求地址：
 * 显式带 scheme 的原样保留；裸 IP / localhost 默认 http（Navidrome 局域网 4533 常见用法），
 * 域名默认 https。末尾斜杠去掉，避免拼 Subsonic 接口时出现双斜杠。
 */
private fun normalizeOnlineBaseUrl(input: String): String {
    val value = input.trim().trimEnd('/')
    if (value.isBlank()) return ""
    if (value.contains("://")) return value
    val host = value.substringBefore(':')
    val isIp = host == "localhost" ||
        Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)
    return (if (isIp) "http://" else "https://") + value
}

/**
 * 在线音乐源登录页：添加 Subsonic 兼容服务（Navidrome / Gonic / Airsonic / Subsonic），
 * 「测试连接」走内核的 OnlineMusicApi.testConnection，保存后立即扫描曲库。
 * 所有网络协程都跑在 ViewModel scope，退出页面不会被取消，busy 由 VM 复位。
 */
@Composable
fun OnlineSourceLoginScreen(
    vm: MainViewModel,
    sourceId: String,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val sources by vm.nasSources.collectAsStateWithLifecycle()
    val existing = remember(sourceId, sources) { sources.find { it.id == sourceId } }
    val testBusy by vm.onlineTestBusy.collectAsStateWithLifecycle()
    val saveBusy by vm.onlineSaveBusy.collectAsStateWithLifecycle()
    val busy = testBusy || saveBusy

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }

    // 重新登录时回填名称/地址/账号；密码出于安全不回填
    LaunchedEffect(existing?.id) {
        if (existing != null) {
            name = existing.name
            host = existing.baseUrl
            username = existing.username
        }
    }

    // 输入框下方实时展示最终地址
    val effectiveUrl = remember(host) { normalizeOnlineBaseUrl(host) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        OnlineBlue.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(76.dp)
                        .background(
                            Brush.linearGradient(listOf(OnlineBlue, OnlineDeep)),
                            RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (existing == null) "添加在线音乐源" else "重新登录在线音乐源",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "添加 Subsonic 兼容音乐源（Navidrome / Gonic / Airsonic / Subsonic）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp, bottom = 22.dp)
            )

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        label = { Text("音源名称") },
                        placeholder = { Text("例如：我的 Navidrome") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; error = null; status = null },
                        label = { Text("服务地址") },
                        placeholder = { Text("https://music.example.com 或 192.168.1.10:4533") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (host.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "最终地址：$effectiveUrl",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; error = null },
                        label = { Text("用户名") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("密码") },
                        placeholder = { Text(if (existing == null) "登录密码" else "重新输入密码") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                if (effectiveUrl.isBlank() || username.isBlank() || password.isEmpty()) {
                                    error = "请先填写服务地址、账号和密码"
                                    return@TextButton
                                }
                                error = null
                                status = "正在测试连接…"
                                vm.testOnlineSourceAsync(
                                    effectiveUrl,
                                    username.trim(),
                                    password
                                ) { result ->
                                    if (!active) return@testOnlineSourceAsync
                                    result.fold(
                                        onSuccess = { status = "连接成功：$it" },
                                        onFailure = {
                                            error = "连接失败：${friendlyError(it)}"
                                            status = null
                                        }
                                    )
                                }
                            },
                            enabled = !busy
                        ) {
                            if (testBusy) {
                                CircularProgressIndicator(Modifier.size(16.dp))
                                Text("  正在测试…")
                            } else Text("测试连接")
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (effectiveUrl.isBlank() || username.isBlank() || password.isEmpty()) {
                        error = "请填写服务地址、账号和密码"
                        return@Button
                    }
                    error = null
                    status = "正在保存并同步曲库…"
                    val id = sourceId.ifBlank { UUID.randomUUID().toString() }
                    val source = NasSource(
                        id = id,
                        name = name.ifBlank { "在线音乐源" },
                        type = SourceType.ONLINE,
                        baseUrl = effectiveUrl,
                        username = username.trim(),
                        rootPath = existing?.rootPath.orEmpty(),
                        lastScanAt = existing?.lastScanAt ?: 0L
                    )
                    vm.saveOnlineSourceAndScan(source, password) { result ->
                        if (!active) return@saveOnlineSourceAndScan
                        result.fold(
                            onSuccess = { onDone() },
                            onFailure = {
                                error = "保存失败：${friendlyError(it)}"
                                status = null
                            }
                        )
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OnlineBlue,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (saveBusy) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                    Text("  正在同步曲库…")
                } else Text("保存并同步曲库")
            }

            status?.let {
                Row(
                    Modifier.padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("  $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "支持 Navidrome / Gonic / Airsonic / Subsonic 等 Subsonic 兼容服务。" +
                    "裸 IP 地址默认使用 http，域名默认使用 https；如服务启用了反向代理或 HTTPS，" +
                    "请直接填写带 http:// 或 https:// 的完整地址。凭据经 Android Keystore 加密保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 28.dp)
            )
        }
    }
}
