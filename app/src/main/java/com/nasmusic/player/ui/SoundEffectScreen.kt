package com.nasmusic.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nasmusic.player.core.CarNoise
import com.nasmusic.player.core.CarPreset
import com.nasmusic.player.core.CarSeat
import com.nasmusic.player.core.SoundEffectState
import com.nasmusic.player.core.SoundMode
import com.nasmusic.player.core.ViperModules
import com.nasmusic.player.core.ViperPreset
import com.nasmusic.player.core.toEqString
import kotlin.math.roundToInt

/**
 * 音效中心：蝰蛇风格音效 + 车载音效的统一设置页。
 * 所有改动即时写入 SettingsStore，播放服务监听后实时应用到音频输出链。
 */
@Composable
fun SoundEffectScreen(vm: MainViewModel, onBack: () -> Unit, embedded: Boolean = false) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val effect = settings.soundEffect
    // V4A 安装检测只做一次（页面进入时）
    val viperInstalled = remember { vm.isViperInstalled() }

    Scaffold(
        // 标题不再固定在顶栏：竖屏只保留返回键，横屏内嵌时外层已有返回栏则完全省略。
        // "音效中心"标题作为正文首行，随设置内容一起滚动。
        topBar = {
            if (!embedded) {
                SimpleTopBar("", onBack)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 标题随内容滚动，不再是常驻顶栏
            Text(
                "音效中心",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            // 说明
            Card(
                Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    "音效在音频输出级处理，对本地 / NAS / 在线音源均生效。" +
                        "所有效果均可一键关闭，默认不改变原始音质。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(14.dp)
                )
            }

            if (viperInstalled) {
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Text(
                        "检测到设备已安装 ViPER4Android。为避免双重叠加导致失真，" +
                            "建议二选一：使用本页音效时请关闭系统 V4A，反之亦然。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 模式切换
            SectionCard("音效模式", Icons.Default.GraphicEq) {
                ModeSelector(effect.mode) { vm.setSoundMode(it.key) }
                Spacer(Modifier.height(8.dp))
                InfoText(effect.mode.description)
            }

            when (effect.mode) {
                SoundMode.OFF -> {
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Text(
                            "当前为原生直通，不附加任何音效。选择「蝰蛇音效」或「车载音效」开始调音。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                SoundMode.VIPER -> ViperSection(effect, vm)
                SoundMode.CAR -> CarSection(effect, vm)
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { vm.resetSoundEffect() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("恢复默认（关闭全部音效）")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeSelector(current: SoundMode, onSelect: (SoundMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SoundMode.values().forEach { mode ->
            val selected = mode == current
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(mode) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ViperSection(effect: SoundEffectState, vm: MainViewModel) {
    SectionCard("蝰蛇预设", Icons.Default.GraphicEq) {
        Text(
            "一键切换调音风格，可在此基础上微调下方面板",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        // 两列网格排布预设
        val presets = ViperPreset.values().toList()
        presets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val selected = preset == effect.viper
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp)
                            .clickable { vm.setViperPreset(preset.key) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                preset.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    SectionCard("蝰蛇功能模块", Icons.Default.Tune) {
        ModuleSwitch("均衡器（10 段）", ViperModules.EQUALIZER, effect, vm)
        ModuleSwitch("动态低音", ViperModules.DYNAMIC_BASS, effect, vm)
        ModuleSwitch("人声清晰度 Clarity", ViperModules.CLARITY, effect, vm)
        ModuleSwitch("场环绕", ViperModules.SURROUND, effect, vm)
        ModuleSwitch("数字混响", ViperModules.REVERB, effect, vm)
        ModuleSwitch("回放增益（稳定音量）", ViperModules.PLAYBACK_GAIN, effect, vm)
        ModuleSwitch("限幅器（防破音）", ViperModules.LIMITER, effect, vm)
        ModuleSwitch("频谱扩展（有损音频）", ViperModules.SPECTRAL_EXTEND, effect, vm)
    }

    SectionCard("精细调音", Icons.Default.GraphicEq) {
        StrengthSlider("动态低音强度", effect.effectiveBass, 0, SoundEffectState.STRENGTH_MAX) {
            vm.setSoundBass(it)
        }
        StrengthSlider("人声清晰度", effect.effectiveClarity, 0, SoundEffectState.STRENGTH_MAX) {
            vm.setSoundClarity(it)
        }
        StrengthSlider("场环绕强度", effect.effectiveSurround, 0, SoundEffectState.STRENGTH_MAX) {
            vm.setSoundSurround(it)
        }
        StrengthSlider(
            "回放增益", effect.effectiveLoudness, 0, SoundEffectState.LOUDNESS_MAX_MB,
            valueLabel = { "${(it / 100f).roundToInt()} dB" }
        ) { vm.setSoundLoudness(it) }
    }

    SectionCard("10 段均衡器", Icons.Default.GraphicEq) {
        Text(
            "自定义频响曲线；调整后即覆盖预设，点「恢复默认」清除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        EqBands(effect, vm)
    }
}

@Composable
private fun CarSection(effect: SoundEffectState, vm: MainViewModel) {
    SectionCard("车载预设", Icons.Default.DirectionsCar) {
        val presets = CarPreset.values().toList()
        presets.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { preset ->
                    val selected = preset == effect.car
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp)
                            .clickable { vm.setCarPreset(preset.key) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                preset.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                preset.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
        }
    }

    SectionCard("声场与路况", Icons.Default.DirectionsCar) {
        Text("声场聚焦", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CarSeat.values().forEach { seat ->
                androidx.compose.material3.FilterChip(
                    selected = seat == effect.carSeat,
                    onClick = { vm.setCarSeat(seat.key) },
                    label = { Text(seat.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("车内噪音补偿", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CarNoise.values().forEach { noise ->
                androidx.compose.material3.FilterChip(
                    selected = noise == effect.carNoise,
                    onClick = { vm.setCarNoise(noise.key) },
                    label = { Text(noise.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingSwitchRow(
            "导航时压低音乐音量",
            "来电 / 导航播报时自动闪避音乐，而不是直接切断",
            effect.carDucking
        ) { vm.setCarDucking(it) }
        SettingSwitchRow(
            "车机模式自动启用",
            "检测到横屏车机界面时优先使用车载音效",
            effect.carAutoEnable
        ) { vm.setCarAutoEnable(it) }
    }

    SectionCard("精细调音", Icons.Default.Tune) {
        StrengthSlider("动态低音强度", effect.effectiveBass, 0, SoundEffectState.STRENGTH_MAX) {
            vm.setSoundBass(it)
        }
        StrengthSlider("人声清晰度", effect.effectiveClarity, 0, SoundEffectState.STRENGTH_MAX) {
            vm.setSoundClarity(it)
        }
        StrengthSlider("声场环绕强度", effect.effectiveSurround, 0, SoundEffectState.STRENGTH_MAX) {
            vm.setSoundSurround(it)
        }
        StrengthSlider(
            "音量补偿", effect.effectiveLoudness, 0, SoundEffectState.LOUDNESS_MAX_MB,
            valueLabel = { "${(it / 100f).roundToInt()} dB" }
        ) { vm.setSoundLoudness(it) }
        Spacer(Modifier.height(8.dp))
        EqBands(effect, vm)
    }
}

@Composable
private fun ModuleSwitch(
    label: String,
    module: Int,
    effect: SoundEffectState,
    vm: MainViewModel
) {
    val on = ViperModules.enabled(effect.viperModules, module)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { vm.setViperModule(module, it) })
    }
}

@Composable
private fun StrengthSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    valueLabel: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel(value), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EqBands(effect: SoundEffectState, vm: MainViewModel) {
    val freqs = listOf("60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k")
    val bands = effect.effectiveEqDb
    bands.forEachIndexed { index, db ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$freqs[index]Hz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp)
            )
            Slider(
                value = db.toFloat(),
                onValueChange = { newDb ->
                    val updated = effect.effectiveEqDb.copyOf()
                    updated[index] = newDb.roundToInt()
                    vm.setSoundCustomEq(updated)
                },
                valueRange = SoundEffectState.EQ_MIN_DB.toFloat()..SoundEffectState.EQ_MAX_DB.toFloat(),
                modifier = Modifier.weight(1f)
            )
            Text(
                "${if (db > 0) "+" else ""}$db",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.End
            )
        }
    }
}
