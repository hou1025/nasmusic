package com.nasmusic.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.nasmusic.desktop.data.CoverArt
import com.nasmusic.desktop.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

/**
 * 异步解析并解码某首歌的封面，返回可直接绘制的 ImageBitmap。
 *
 * - 解码在 IO 线程完成，避免切歌时卡 UI；
 * - 解析逻辑见 [CoverArt]：内嵌标签 → 同名图片 → 目录封面 → 音源封面地址；
 * - 找不到或解码失败返回 null，调用方据此回退到默认黑胶盘面。
 */
@Composable
fun rememberSongCover(song: Song?): ImageBitmap? {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(song?.id, song?.artworkUri, song?.uri) {
        val target = song
        if (target == null) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            val bytes = CoverArt.load(target) ?: return@withContext null
            // Skia 能直接解 jpg/png/webp/gif 首帧，无需额外图片库
            runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}
