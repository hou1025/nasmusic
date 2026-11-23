package com.nasmusic.player.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.material3.Typography
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.player.R

/**
 * 车机 / 大屏横屏尺寸契约。
 *
 * 所有数值来自资源限定符：values-car（UI_MODE_TYPE_CAR）与 values-sw720dp-land
 * （基于普通 Android 的中控横屏）会自动覆盖 values 默认值，代码不写死屏幕判断。
 * 同时兼容两种运行时检测：uiMode=car、PackageManager.FEATURE_AUTOMOTIVE。
 */
data class CarMetrics(
    val enabled: Boolean,
    val touchTarget: Dp,
    val rowHeight: Dp,
    val rowArtwork: Dp,
    val buttonSmall: Dp,
    val buttonMedium: Dp,
    val buttonMain: Dp,
    val searchHeight: Dp,
    val contentMaxWidth: Dp,
    val listMaxWidth: Dp,
    val titleSize: TextUnit,
    val lyricSize: TextUnit,
    val textScale: Float
)

private val PhoneMetrics = CarMetrics(
    enabled = false,
    touchTarget = 48.dp,
    rowHeight = 64.dp,
    rowArtwork = 48.dp,
    buttonSmall = 46.dp,
    buttonMedium = 56.dp,
    buttonMain = 76.dp,
    searchHeight = 50.dp,
    contentMaxWidth = 4000.dp,
    listMaxWidth = 4000.dp,
    titleSize = 28.sp,
    lyricSize = 20.sp,
    textScale = 1f
)

/**
 * 运行时判定为车机 / 宽屏横屏、但资源限定符没有命中时的兜底尺寸。
 * 部分国产中控基于普通 Android，uiMode 仍是 normal 且 smallestWidth 换算后不足 600dp，
 * 这时不能只把 enabled 置 true：资源仍是手机数值，会出现「双栏布局 + 48dp 小按钮」。
 * 因此这里直接给出与 values-car 完全一致的一套常量。
 */
private val WidescreenFallback = CarMetrics(
    enabled = true,
    touchTarget = 64.dp,
    rowHeight = 76.dp,
    rowArtwork = 60.dp,
    buttonSmall = 60.dp,
    buttonMedium = 70.dp,
    buttonMain = 92.dp,
    searchHeight = 60.dp,
    contentMaxWidth = 1400.dp,
    listMaxWidth = 1000.dp,
    titleSize = 34.sp,
    lyricSize = 26.sp,
    textScale = 1.18f
)

/**
 * 车机分屏 / 自由窗口下的紧凑尺寸：仍是车机触控目标，但比全屏小一档，
 * 防止半屏窗口里按钮和列表行被挤出可视区域。
 */
private val SplitMetrics = CarMetrics(
    enabled = true,
    touchTarget = 52.dp,
    rowHeight = 68.dp,
    rowArtwork = 54.dp,
    buttonSmall = 52.dp,
    buttonMedium = 62.dp,
    buttonMain = 78.dp,
    searchHeight = 54.dp,
    contentMaxWidth = 1200.dp,
    listMaxWidth = 900.dp,
    titleSize = 30.sp,
    lyricSize = 22.sp,
    textScale = 1.06f
)

/** 未进入 NasMusicTheme 时的安全默认值（例如预览）。 */
val LocalCarMetrics = compositionLocalOf { PhoneMetrics }

/** 当前设备应采用哪一套尺寸；configuration 变化时由 Compose 重组重新读取。 */
fun carMetrics(context: Context): CarMetrics {
    val res = context.resources
    val density = res.displayMetrics.density
    val fontScale = res.configuration.fontScale.coerceAtLeast(0.8f)
    fun dimen(id: Int): Dp = (res.getDimension(id) / density).dp
    fun text(id: Int): TextUnit = (res.getDimension(id) / (density * fontScale)).sp

    val cfg: Configuration = res.configuration
    val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
    val uiModeCar = (cfg.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR
    val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    val screenSize = cfg.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
    // 普通手机横屏的 smallestWidth 一般 <= 480dp，不会误命中；平板横屏按大屏处理同样合理
    val wideLandscape = landscape &&
        (cfg.smallestScreenWidthDp >= 600 || screenSize >= Configuration.SCREENLAYOUT_SIZE_LARGE)
    val resourceCar = res.getBoolean(R.bool.car_layout)
    val runtimeCar = uiModeCar || automotive || wideLandscape
    if (runtimeCar && !resourceCar) return WidescreenFallback
    val enabled = resourceCar || runtimeCar
    // 分屏/自由窗口下 Configuration.screenWidthDp 是「当前窗口」宽度，
    // 而 smallestScreenWidthDp / uiMode 仍是设备级属性，所以必须单独按窗口宽度降级。
    if (enabled && cfg.screenWidthDp in 1..599) return SplitMetrics

    return CarMetrics(
        enabled = enabled,
        touchTarget = dimen(R.dimen.car_touch_target),
        rowHeight = dimen(R.dimen.car_row_height),
        rowArtwork = dimen(R.dimen.car_row_artwork),
        buttonSmall = dimen(R.dimen.car_button_small),
        buttonMedium = dimen(R.dimen.car_button_medium),
        buttonMain = dimen(R.dimen.car_button_main),
        searchHeight = dimen(R.dimen.car_search_height),
        contentMaxWidth = dimen(R.dimen.car_content_max_width),
        listMaxWidth = dimen(R.dimen.car_list_max_width),
        titleSize = text(R.dimen.car_title_size),
        lyricSize = text(R.dimen.car_lyric_size),
        // 车机强光 / 远距离观看：整站排版统一放大，避免细小文字
        textScale = if (enabled) 1.18f else 1f
    )
}

private fun scaleUnit(value: TextUnit, factor: Float): TextUnit {
    val raw = value.value
    return if (raw.isNaN() || raw <= 0f) value else (raw * factor).sp
}

private fun TextStyle.scaled(factor: Float): TextStyle = copy(
    fontSize = scaleUnit(fontSize, factor),
    lineHeight = scaleUnit(lineHeight, factor)
)

/** 等比放大 Material3 全量Typography，保证所有页面（含弹窗/设置）字号一致。 */
fun carTypography(base: Typography, scale: Float): Typography {
    if (scale == 1f) return base
    return Typography(
        displayLarge = base.displayLarge.scaled(scale),
        displayMedium = base.displayMedium.scaled(scale),
        displaySmall = base.displaySmall.scaled(scale),
        headlineLarge = base.headlineLarge.scaled(scale),
        headlineMedium = base.headlineMedium.scaled(scale),
        headlineSmall = base.headlineSmall.scaled(scale),
        titleLarge = base.titleLarge.scaled(scale),
        titleMedium = base.titleMedium.scaled(scale),
        titleSmall = base.titleSmall.scaled(scale),
        bodyLarge = base.bodyLarge.scaled(scale),
        bodyMedium = base.bodyMedium.scaled(scale),
        bodySmall = base.bodySmall.scaled(scale),
        labelLarge = base.labelLarge.scaled(scale),
        labelMedium = base.labelMedium.scaled(scale),
        labelSmall = base.labelSmall.scaled(scale)
    )
}
