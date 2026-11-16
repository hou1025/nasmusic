package com.nasmusic.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasmusic.player.playback.PlaybackService

class MainActivity : ComponentActivity() {

    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPlayer.value = intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false) == true
        setContent {
            val vm: MainViewModel = viewModel()
            val settings by vm.settingsState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            // configuration 变化（横竖屏 / uiMode / 字体缩放）时重新读取车机尺寸资源
            val configuration = LocalConfiguration.current
            val metrics = remember(configuration) { carMetrics(this@MainActivity) }
            val dark = rememberAppDarkTheme(
                modeKey = settings.themeMode,
                systemDark = systemDark,
                startHour = settings.scheduleStartHour,
                endHour = settings.scheduleEndHour
            )
            NasMusicTheme(paletteKey = settings.themePalette, darkTheme = dark, metrics = metrics) {
                AppRoot(vm = vm, openPlayer = openPlayer.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPlayer.value = intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)
    }
}
