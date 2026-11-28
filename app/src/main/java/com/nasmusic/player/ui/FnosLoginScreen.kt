package com.nasmusic.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nasmusic.player.core.friendlyError
import com.nasmusic.player.data.FnosApi
import kotlinx.coroutines.launch

private val FnosRed = Color(0xFFFA2D48)
private val FnosDeep = Color(0xFFB3132B)

/**
 * 飞牛音乐登录页：输入 NAS 地址与账号密码，登录后把飞牛曲库作为一个网络音乐源接入。
 * 视觉参考飞牛音乐客户端（品牌色 + 大幅留白 + 居中登录卡片），沿用应用自身深浅色主题。
 */
@Composable
fun FnosLoginScreen(
    vm: MainViewModel,
    sourceId: String,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val sources by vm.nasSources.collectAsStateWithLifecycle()
    val existing = remember(sourceId, sources) { sources.find { it.id == sourceId } }

    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    // busy 归 ViewModel 管：退出页面不会取消登录，失败/成功都会复位按钮
    val busy by vm.fnosLoginBusy.collectAsStateWithLifecycle()
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { active = false } }
    var probing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 0=局域网 IP，1=FN ID（飞牛远程），2=自定义域名/DDNS/反代
    var mode by remember { mutableStateOf(0) }
    var fnSuffix by remember { mutableStateOf("5ddd.com") }
    val scope = rememberCoroutineScope()

    // 重新登录时回填服务器与账号；密码出于安全不回填
    LaunchedEffect(existing?.id) {
        if (existing != null) {
            host = existing.baseUrl
            username = existing.username
            val ep = runCatching { FnosApi.resolve(existing.baseUrl) }.getOrNull()
            when {
                ep?.isFnId == true -> {
                    mode = 1
                    host = ep.host.substringBefore('.')
                    fnSuffix = ep.host.substringAfter('.')
                }
                ep?.isConnect == true || existing.baseUrl.contains("://") -> mode = 2
                else -> mode = 0
            }
        }
    }

    // 根据连接方式把用户输入转成统一地址
    val effectiveHost = when (mode) {
        1 -> {
            val v = host.trim().trimEnd('/')
                .removePrefix("http://").removePrefix("https://")
            if (v.isBlank()) v else if (v.contains('.')) v else "$v.$fnSuffix"
        }
        else -> host.trim()
    }
    val preview = if (effectiveHost.isBlank()) null
    else runCatching { FnosApi.resolve(effectiveHost) }.getOrNull()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        FnosDeep.copy(alpha = 0.16f),
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
            // 品牌区
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(76.dp)
                        .background(
                            Brush.linearGradient(listOf(FnosRed, FnosDeep)),
                            RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (existing == null) "连接飞牛音乐" else "重新登录飞牛音乐",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                "登录 NAS 上的飞牛音乐服务，直接使用你的曲库",
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
                    Text("连接方式", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("局域网", "FN ID", "域名").forEachIndexed { index, label ->
                            OutlinedButton(
                                onClick = { mode = index; error = null; status = null },
                                modifier = Modifier.weight(1f).height(38.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (mode == index) FnosRed
                                    else MaterialTheme.colorScheme.outline
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (mode == index) {
                                        FnosRed.copy(alpha = 0.10f)
                                    } else Color.Transparent,
                                    contentColor = if (mode == index) {
                                        FnosRed
                                    } else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it; error = null },
                        label = {
                            Text(
                                when (mode) {
                                    1 -> "FN ID"
                                    2 -> "服务器地址"
                                    else -> "NAS 地址（局域网）"
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                when (mode) {
                                    1 -> "在 NAS 的「远程访问 → FN Connect」中设置的 ID"
                                    2 -> "nas.example.com 或 https://nas.example.com:5666"
                                    else -> "192.168.1.10（默认 5666 端口）"
                                }
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mode == 1) {
                        Spacer(Modifier.height(8.dp))
                        Text("FN ID 域名", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("5ddd.com", "fnos.net", "fnofly.com").forEach { suffix ->
                                TextButton(
                                    onClick = { fnSuffix = suffix; error = null },
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (fnSuffix == suffix) FnosRed
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) { Text(suffix, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (preview != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "将连接：${preview.baseUrl} · ${preview.label}" +
                                if (preview.isConnect) " · 已启用中继" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; error = null },
                        label = { Text("飞牛账号") },
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
                                if (effectiveHost.isBlank()) {
                                    error = if (mode == 1) "请先填写 FN ID" else "请先填写服务器地址"
                                    return@TextButton
                                }
                                scope.launch {
                                    probing = true
                                    error = null
                                    status = null
                                    vm.testFnosConnection(effectiveHost).fold(
                                        onSuccess = { status = "服务可达：$it" },
                                        onFailure = { error = "连接失败：${friendlyError(it)}" }
                                    )
                                    probing = false
                                }
                            },
                            enabled = !busy && !probing
                        ) {
                            if (probing) {
                                CircularProgressIndicator(Modifier.size(16.dp))
                                Text("  正在探测…")
                            } else Text("测试连接")
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (effectiveHost.isBlank() || username.isBlank() || password.isEmpty()) {
                        error = "请填写服务器地址、账号和密码"
                        return@Button
                    }
                    error = null
                    status = if (preview?.isConnect == true) {
                        "正在通过 FN Connect 中继登录…"
                    } else "正在登录并验证曲库…"
                    val id = sourceId.ifBlank { java.util.UUID.randomUUID().toString() }
                    // 登录跑在 ViewModel scope：退页面不取消，成功保存+同步曲库，失败由 VM 复位 busy
                    vm.loginFnosAndWait(id, effectiveHost, username.trim(), password) { result ->
                        if (!active) return@loginFnosAndWait
                        result.fold(
                            onSuccess = { onDone() },
                            onFailure = {
                                error = friendlyError(it, "登录失败")
                                status = null
                            }
                        )
                    }
                },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FnosRed,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = Color.White
                    )
                    Text("  正在连接…")
                } else Text("登录并同步曲库")
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
                "FN ID 需先在 NAS「设置 → 远程访问 → FN Connect」中开启，中继会限速；" +
                    "域名方式适用于 DDNS / 反向代理 / 公网 IP。使用飞牛 NAS 的账号密码登录，" +
                    "凭据经 Android Keystore 加密保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 28.dp)
            )
        }
    }
}
