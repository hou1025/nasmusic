package com.nasmusic.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 配色刻意与 Android 端「发现页（横屏双栏）」保持一致：
 * 强调色是那抹红，正文用近黑的墨色，背景是极浅的灰蓝，卡片纯白。
 * 这样两端看起来是同一个产品，而不是"同一个名字的两个软件"。
 */
internal val DiscoverRed = Color(0xFFF0334F)
internal val DiscoverInk = Color(0xFF23252B)

private val AppColors = lightColorScheme(
    primary = DiscoverRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFADCE1),
    onPrimaryContainer = Color(0xFF5B0F1B),
    secondary = Color(0xFF2C3E62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E7F2),
    onSecondaryContainer = Color(0xFF1B263C),
    tertiary = Color(0xFF6A4FB3),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE6FA),
    onTertiaryContainer = Color(0xFF2E1F57),
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF17181C),
    surface = Color.White,
    onSurface = Color(0xFF17181C),
    surfaceVariant = Color(0xFFEAEDF2),
    onSurfaceVariant = Color(0xFF5F6672),
    outline = Color(0xFFC6CBD4),
    error = Color(0xFFB3261E)
)

@Composable
internal fun NasTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
