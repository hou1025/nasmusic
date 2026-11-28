package com.nasmusic.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nasmusic.desktop.ui.App

/** Compose Desktop 入口：run 任务与 IDEA 的 MainKt 都从这里启动。 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        // 名称与 Android 端 strings.xml 的 app_name 保持一致
        title = "音乐",
        // 图标由 tools/MakeIcon.java 按 Android 端 ic_launcher 的矢量路径 1:1 渲染
        icon = painterResource("icon.png"),
        state = rememberWindowState(width = 1360.dp, height = 860.dp)
    ) {
        App()
    }
}
