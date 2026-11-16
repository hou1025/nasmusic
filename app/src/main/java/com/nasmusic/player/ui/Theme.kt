package com.nasmusic.player.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.util.Calendar

/** 深浅色如何决定：跟随系统 / 固定浅色 / 固定深色 / 按时间段自动切换。 */
enum class ThemeMode(val key: String, val label: String, val hint: String) {
    SYSTEM("SYSTEM", "跟随系统", "系统切换深色模式时自动跟随"),
    LIGHT("LIGHT", "浅色", "始终使用浅色配色"),
    DARK("DARK", "深色", "始终使用深色配色"),
    SCHEDULE("SCHEDULE", "定时自动", "按设定时段在浅色与深色之间自动切换");

    companion object {
        fun fromKey(key: String): ThemeMode = values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

const val DYNAMIC_THEME_KEY = "DYNAMIC"

fun isDynamicThemeAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 八色种子：主/辅/点缀的明暗两版 + 明暗背景，其余容器色与中性色由种子派生。 */
private data class Seed(
    val key: String,
    val label: String,
    val desc: String,
    val pLight: Color, val pDark: Color,
    val sLight: Color, val sDark: Color,
    val tLight: Color, val tDark: Color,
    val bgLight: Color, val bgDark: Color
)

private fun mix(base: Color, other: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = base.red + (other.red - base.red) * t,
        green = base.green + (other.green - base.green) * t,
        blue = base.blue + (other.blue - base.blue) * t,
        alpha = 1f
    )
}

private fun buildScheme(s: Seed, dark: Boolean): ColorScheme {
    if (!dark) {
        val p = s.pLight
        val sec = s.sLight
        val ter = s.tLight
        val bg = s.bgLight
        return lightColorScheme(
            primary = p,
            onPrimary = Color.White,
            primaryContainer = mix(p, Color.White, 0.84f),
            onPrimaryContainer = mix(p, Color.Black, 0.62f),
            secondary = sec,
            onSecondary = Color.White,
            secondaryContainer = mix(sec, Color.White, 0.84f),
            onSecondaryContainer = mix(sec, Color.Black, 0.62f),
            tertiary = ter,
            onTertiary = Color.White,
            tertiaryContainer = mix(ter, Color.White, 0.84f),
            onTertiaryContainer = mix(ter, Color.Black, 0.62f),
            background = bg,
            onBackground = mix(bg, Color.Black, 0.88f),
            surface = mix(bg, Color.White, 0.65f),
            onSurface = mix(bg, Color.Black, 0.88f),
            surfaceVariant = mix(mix(bg, Color.White, 0.35f), p, 0.055f),
            onSurfaceVariant = mix(bg, Color.Black, 0.62f),
            outline = mix(bg, Color.Black, 0.24f),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002)
        )
    }
    val p = s.pDark
    val sec = s.sDark
    val ter = s.tDark
    val bg = s.bgDark
    return darkColorScheme(
        primary = p,
        onPrimary = mix(p, Color.Black, 0.78f),
        primaryContainer = mix(p, Color.Black, 0.62f),
        onPrimaryContainer = mix(p, Color.White, 0.86f),
        secondary = sec,
        onSecondary = mix(sec, Color.Black, 0.78f),
        secondaryContainer = mix(sec, Color.Black, 0.62f),
        onSecondaryContainer = mix(sec, Color.White, 0.86f),
        tertiary = ter,
        onTertiary = mix(ter, Color.Black, 0.78f),
        tertiaryContainer = mix(ter, Color.Black, 0.62f),
        onTertiaryContainer = mix(ter, Color.White, 0.86f),
        background = bg,
        onBackground = mix(bg, Color.White, 0.90f),
        surface = mix(bg, Color.White, 0.055f),
        onSurface = mix(bg, Color.White, 0.90f),
        surfaceVariant = mix(bg, Color.White, 0.13f),
        onSurfaceVariant = mix(bg, Color.White, 0.72f),
        outline = mix(bg, Color.White, 0.30f),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF4A0B06),
        errorContainer = Color(0xFF6B1A13),
        onErrorContainer = Color(0xFFFFD9D5)
    )
}

/**
 * 全 App 共用的背景三色。列表页/设置页直接用 [brush]，播放页在此基础上叠加封面取色，
 * 因为基底完全一致，页面之间切换不会再出现渐变跳色。
 */
data class BackdropColors(val top: Color, val mid: Color, val bottom: Color) {
    fun brush(): Brush = Brush.verticalGradient(listOf(top, mid, bottom))
}

/** 一套完整主题：明暗配色、背景渐变、系统栏颜色与预览色。 */
data class ThemePalette(
    val key: String,
    val label: String,
    val desc: String,
    val light: ColorScheme,
    val dark: ColorScheme,
    val lightBackdrop: Brush,
    val darkBackdrop: Brush,
    val lightBackdropColors: BackdropColors,
    val darkBackdropColors: BackdropColors,
    val swatch: List<Color>,
    val statusBarLight: Color,
    val statusBarDark: Color,
    val navBarLight: Color,
    val navBarDark: Color,
    val dynamic: Boolean = false
) {
    fun scheme(isDark: Boolean): ColorScheme = if (isDark) this.dark else light
    fun backdrop(dark: Boolean): Brush = if (dark) darkBackdrop else lightBackdrop
    fun backdropColors(dark: Boolean): BackdropColors =
        if (dark) darkBackdropColors else lightBackdropColors
    fun statusBar(dark: Boolean): Color = if (dark) statusBarDark else statusBarLight
    fun navBar(dark: Boolean): Color = if (dark) navBarDark else navBarLight
}

private fun buildPalette(s: Seed): ThemePalette {
    val lTop = mix(s.bgLight, s.pLight, 0.10f)
    val lMid = mix(s.bgLight, s.pLight, 0.035f)
    val dTop = mix(s.bgDark, s.pDark, 0.10f)
    val dMid = mix(s.bgDark, s.pDark, 0.035f)
    val lightColors = BackdropColors(lTop, lMid, s.bgLight)
    val darkColors = BackdropColors(dTop, dMid, s.bgDark)
    return ThemePalette(
        key = s.key,
        label = s.label,
        desc = s.desc,
        light = buildScheme(s, false),
        dark = buildScheme(s, true),
        lightBackdrop = lightColors.brush(),
        darkBackdrop = darkColors.brush(),
        lightBackdropColors = lightColors,
        darkBackdropColors = darkColors,
        swatch = listOf(s.pLight, s.sLight, s.tLight),
        statusBarLight = lTop,
        statusBarDark = dTop,
        navBarLight = s.bgLight,
        navBarDark = s.bgDark
    )
}

private val SEEDS = listOf(
    Seed(
        "INDIGO", "靛紫", "默认 · 柔和紫配薄荷青",
        Color(0xFF5B4BD6), Color(0xFFC3B2FF),
        Color(0xFF0E8C76), Color(0xFF7DE2C3),
        Color(0xFFB55B2E), Color(0xFFFFB693),
        Color(0xFFF7F7FC), Color(0xFF10111A)
    ),
    Seed(
        "OCEAN", "海蓝", "清爽蓝配青绿",
        Color(0xFF1565C0), Color(0xFFA5C8FF),
        Color(0xFF00838F), Color(0xFF79DEE8),
        Color(0xFFB26A00), Color(0xFFFFC46B),
        Color(0xFFF5F9FF), Color(0xFF0B1220)
    ),
    Seed(
        "MINT", "森绿", "低饱和绿配暖橙",
        Color(0xFF0F766E), Color(0xFF7FE0CC),
        Color(0xFF4D7C0F), Color(0xFFB9DE7A),
        Color(0xFFB45309), Color(0xFFFFBE8F),
        Color(0xFFF6FAF7), Color(0xFF0C1512)
    ),
    Seed(
        "SAKURA", "樱粉", "柔和粉配藕紫",
        Color(0xFFB23A63), Color(0xFFFFB1C8),
        Color(0xFF7B4BC9), Color(0xFFD2B7FF),
        Color(0xFF1D6FA5), Color(0xFF8FD0FF),
        Color(0xFFFDF6F9), Color(0xFF170F16)
    ),
    Seed(
        "AMBER", "暖橙", "日暮橙配砖红",
        Color(0xFFA84800), Color(0xFFFFBE8C),
        Color(0xFFB03A48), Color(0xFFFFB3B8),
        Color(0xFF2E6F6B), Color(0xFF88D8D0),
        Color(0xFFFDF8F3), Color(0xFF17110D)
    ),
    Seed(
        "GRAPHITE", "石墨", "低干扰中性灰",
        Color(0xFF3F4453), Color(0xFFCFD3E6),
        Color(0xFF4A6076), Color(0xFFAFC6DE),
        Color(0xFF6B5B8A), Color(0xFFCFC0E8),
        Color(0xFFF8F8FA), Color(0xFF101116)
    )
)

private val DYNAMIC_PALETTE = buildPalette(SEEDS[0]).copy(
    key = DYNAMIC_THEME_KEY,
    label = "动态取色",
    desc = "跟随系统壁纸（Android 12+）",
    swatch = listOf(Color(0xFF6750A4), Color(0xFF7D5260), Color(0xFF625B71)),
    dynamic = true
)

val ThemePalettes: List<ThemePalette> = SEEDS.map(::buildPalette) + DYNAMIC_PALETTE

fun palette(key: String): ThemePalette =
    ThemePalettes.firstOrNull { it.key == key } ?: ThemePalettes.first()

/** 浅色时段为 [startHour, endHour)，其余为深色；跨午夜时段同样成立。 */
fun resolveDarkTheme(
    modeKey: String,
    systemDark: Boolean,
    startHour: Int,
    endHour: Int,
    now: Calendar = Calendar.getInstance()
): Boolean {
    val start = startHour.coerceIn(0, 23)
    val end = endHour.coerceIn(1, 24)
    return when (ThemeMode.fromKey(modeKey)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.SCHEDULE -> {
            val hour = now.get(Calendar.HOUR_OF_DAY)
            if (start < end) hour < start || hour >= end else hour >= end && hour < start
        }
    }
}

/** 定时模式下每 30 秒重算一次，保证跨过时段边界时自动换肤。 */
@Composable
fun rememberAppDarkTheme(
    modeKey: String,
    systemDark: Boolean,
    startHour: Int,
    endHour: Int
): Boolean {
    val mode = ThemeMode.fromKey(modeKey)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(mode) {
        if (mode != ThemeMode.SCHEDULE) return@LaunchedEffect
        while (true) {
            delay(30_000L)
            tick++
        }
    }
    return remember(mode, systemDark, startHour, endHour, tick) {
        resolveDarkTheme(mode.key, systemDark, startHour, endHour)
    }
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp)
)

private val DefaultBackdropColors = BackdropColors(
    Color(0xFF10111A), Color(0xFF10111A), Color(0xFF10111A)
)
private val DefaultBackdrop = DefaultBackdropColors.brush()

val LocalAppBackdrop = compositionLocalOf { DefaultBackdrop }
val LocalAppBackdropColors = compositionLocalOf { DefaultBackdropColors }
val LocalIsDarkTheme = compositionLocalOf { false }
val LocalThemePaletteKey = compositionLocalOf { "INDIGO" }

@Composable
private fun SyncSystemBars(dark: Boolean, statusColor: Color, navColor: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = statusColor.toArgb()
        window.navigationBarColor = navColor.toArgb()
        val controller = WindowInsetsControllerCompat(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}

@Composable
fun NasMusicTheme(
    paletteKey: String,
    darkTheme: Boolean,
    metrics: CarMetrics,
    content: @Composable () -> Unit
) {
    val spec = palette(paletteKey)
    val context = LocalContext.current
    val useDynamic = spec.dynamic && isDynamicThemeAvailable()
    val scheme: ColorScheme = if (useDynamic) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        spec.scheme(darkTheme)
    }
    val backdropColors: BackdropColors = if (useDynamic) {
        BackdropColors(
            mix(scheme.surface, scheme.primary, if (darkTheme) 0.10f else 0.06f),
            mix(scheme.background, scheme.primary, 0.02f),
            scheme.background
        )
    } else {
        spec.backdropColors(darkTheme)
    }
    val backdrop: Brush = backdropColors.brush()
    val statusColor = if (useDynamic) scheme.surface else spec.statusBar(darkTheme)
    val navColor = if (useDynamic) scheme.background else spec.navBar(darkTheme)

    CompositionLocalProvider(
        LocalCarMetrics provides metrics,
        LocalAppBackdrop provides backdrop,
        LocalAppBackdropColors provides backdropColors,
        LocalIsDarkTheme provides darkTheme,
        LocalThemePaletteKey provides spec.key
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = carTypography(Typography(), metrics.textScale),
            shapes = AppShapes
        ) {
            SyncSystemBars(darkTheme, statusColor, navColor)
            content()
        }
    }
}
