package com.nasmusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nasmusic.desktop.model.NasSource
import com.nasmusic.desktop.model.SourceType
import kotlinx.coroutines.launch

/** 音源表单的暂存数据，提交时由上层转换为 NasSource 并加密密码。 */
data class SourceDraft(
    val name: String = "",
    val type: SourceType = SourceType.LOCAL,
    val baseUrl: String = "",
    val host: String = "",
    val port: Int = 0,
    val rootPath: String = "",
    val username: String = "",
    val password: String = ""
)

/**
 * 音源管理页：已有音源列表 + 添加表单（名称/类型/地址/账号/密码/测试连接）。
 * 网络请求由上层以 suspend 回调注入，UI 只负责展示可读结果。
 */
@Composable
fun SourceSettings(
    sources: List<NasSource>,
    onSave: (SourceDraft) -> Result<String>,
    onRemove: (String) -> Unit,
    onTestDraft: suspend (SourceDraft) -> Result<String>,
    onTestSaved: suspend (NasSource) -> Result<String>,
    onRefresh: suspend (NasSource) -> Result<Int>
) {
    var draft by remember { mutableStateOf(SourceDraft()) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("已配置音源", style = MaterialTheme.typography.titleMedium) }
        items(sources, key = { it.id }) { source ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${source.name} · ${source.type}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        source.baseUrl.ifBlank { source.host } +
                            if (source.port > 0) ":${source.port}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                message = onTestSaved(source).fold(
                                    { "测试成功：$it" },
                                    { "测试失败：${it.message ?: "未知错误"}" }
                                )
                            }
                        }) { Text("测试连接") }
                        TextButton(onClick = {
                            scope.launch {
                                message = onRefresh(source).fold(
                                    { "刷新完成，新增 $it 首" },
                                    { "刷新失败：${it.message ?: "未知错误"}" }
                                )
                            }
                        }) { Text("刷新曲库") }
                        TextButton(onClick = { onRemove(source.id) }) { Text("删除") }
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            Text("添加音源", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("类型", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceType.entries.forEach { option ->
                    FilterChip(
                        selected = draft.type == option,
                        onClick = { draft = draft.copy(type = option) },
                        label = { Text(option.name) }
                    )
                }
            }
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = { draft = draft.copy(baseUrl = it) },
                label = { Text("地址（http(s)://... 或 FN ID / IP）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.host,
                    onValueChange = { draft = draft.copy(host = it) },
                    label = { Text("主机 / IP") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = if (draft.port == 0) "" else draft.port.toString(),
                    onValueChange = { draft = draft.copy(port = it.toIntOrNull() ?: 0) },
                    label = { Text("端口") },
                    modifier = Modifier.width(110.dp),
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = draft.rootPath,
                onValueChange = { draft = draft.copy(rootPath = it) },
                label = { Text("根目录 / 共享名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                label = { Text("账号") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    message = onSave(draft).fold(
                        { "已保存：$it" },
                        { "保存失败：${it.message ?: "未知错误"}" }
                    )
                    draft = SourceDraft()
                }) { Text("保存") }
                TextButton(onClick = {
                    scope.launch {
                        message = onTestDraft(draft).fold(
                            { "测试成功：$it" },
                            { "测试失败：${it.message ?: "未知错误"}" }
                        )
                    }
                }) { Text("测试连接（当前输入）") }
            }

            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
