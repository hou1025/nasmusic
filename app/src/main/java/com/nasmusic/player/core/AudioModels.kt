package com.nasmusic.player.core

/**
 * 音效工作模式。
 * - OFF：原生直通，不附加任何音效（默认，保证不劣化音质）。
 * - VIPER：内置"蝰蛇风格"音效链路（10 段 EQ + 动态低音 + 清晰度 + 环绕 + 混响 + 限幅）。
 * - CAR：车载场景优化（人声清晰 + 低音补偿路噪 + 驾驶位声场 + 导航闪避）。
 */
enum class SoundMode(val key: String, val label: String, val description: String) {
    OFF("OFF", "原生直通", "不启用音效处理，保留音源原始声音"),
    VIPER("VIPER", "蝰蛇音效", "10 段 EQ / 动态低音 / 清晰度 / 环绕 / 混响 / 限幅"),
    CAR("CAR", "车载音效", "人声清晰、低音补偿、驾驶位声场，适配行车环境");

    companion object {
        fun from(key: String?): SoundMode = values().find { it.key == key } ?: OFF
    }
}

/** 蝰蛇音效的子模块开关（参考 ViPER4Android 的功能划分）。 */
object ViperModules {
    const val EQUALIZER = 1 shl 0       // 10 段 IIR 均衡器
    const val DYNAMIC_BASS = 1 shl 1    // 动态低音（自然低音）
    const val CLARITY = 1 shl 2         // ViPER Clarity：提升人声/乐器清晰度
    const val SURROUND = 1 shl 3        // 场环绕：拓宽立体声场
    const val REVERB = 1 shl 4          // 数字混响：模拟房间/大厅
    const val PLAYBACK_GAIN = 1 shl 5   // 回放增益：稳定歌曲间音量
    const val LIMITER = 1 shl 6         // 限幅器：防止破音（用动态压缩近似）
    const val SPECTRAL_EXTEND = 1 shl 7 // 频谱扩展：补偿有损压缩丢失的高频

    const val ALL = EQUALIZER or DYNAMIC_BASS or CLARITY or SURROUND or REVERB or
            PLAYBACK_GAIN or LIMITER or SPECTRAL_EXTEND
    const val DEFAULT = EQUALIZER or DYNAMIC_BASS or CLARITY or SURROUND or PLAYBACK_GAIN or LIMITER

    fun enabled(mask: Int, module: Int): Boolean = mask and module != 0
}

/**
 * 蝰蛇风格预设。每套预设定义 10 段 EQ 增益（dB）、低音/环绕强度、混响与响度。
 * EQ 频段对应 Android Equalizer 的 10 段（约 60Hz ~ 16kHz），具体中心频率由设备决定。
 */
enum class ViperPreset(
    val key: String,
    val label: String,
    val description: String,
    val eqDb: IntArray,
    val bass: Int,
    val clarity: Int,
    val surround: Int,
    val reverb: Short,
    val loudnessMb: Int
) {
    ORIGINAL("ORIGINAL", "原声", "平直曲线，仅做轻微响度补偿", intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0, 0, 0, 0),
    POP("POP", "流行", "低频微升、高频提亮，适合流行乐", intArrayOf(2, 1, 0, 0, 1, 2, 2, 1, 0, 0), 220, 120, 80, 1, 80),
    ROCK("ROCK", "摇滚", "强化低频与高频，中频略凹", intArrayOf(4, 3, 1, -1, -1, 1, 3, 4, 3, 2), 420, 100, 160, 0, 120),
    JAZZ("JAZZ", "爵士", "中频温暖，高频柔和不刺耳", intArrayOf(2, 1, 1, 2, 2, 1, 1, 0, 0, 0), 180, 180, 220, 2, 60),
    CLASSICAL("CLASSICAL", "古典", "两端微扬、中频平直，还原声场", intArrayOf(2, 1, 0, 0, 0, 0, 1, 2, 2, 1), 120, 140, 280, 3, 60),
    ELECTRONIC("ELECTRONIC", "电子", "强低音配合亮高频，适合电音", intArrayOf(5, 4, 2, 0, -1, 1, 3, 5, 4, 3), 620, 80, 200, 0, 150),
    VOCAL("VOCAL", "人声", "突出 500Hz~4kHz 人声频段", intArrayOf(0, 0, 1, 3, 4, 4, 3, 1, 0, 0), 100, 320, 60, 1, 90),
    BASS("BASS", "重低音", "极强低频，适合电子与嘻哈", intArrayOf(7, 6, 4, 1, 0, 0, 0, 0, 0, 0), 850, 60, 40, 0, 120),
    HEADPHONE("HEADPHONE", "耳机", "修正耳机头中效应，声场更开阔", intArrayOf(1, 0, -1, 0, 1, 2, 2, 1, 0, 0), 160, 160, 420, 2, 80),
    LIVE("LIVE", "现场", "大厅混响，模拟演唱会氛围", intArrayOf(2, 1, 0, 0, 0, 1, 2, 3, 3, 2), 240, 120, 300, 4, 110),
    ACG("ACG", "纯净 ACG", "人声与乐器分离度更高，高频通透", intArrayOf(1, 0, 0, 1, 2, 3, 3, 2, 1, 0), 140, 280, 180, 1, 95),
    HIPHOP("HIPHOP", "嘻哈", "强劲节奏感，低频厚实", intArrayOf(6, 5, 3, 1, 0, 0, 1, 2, 2, 1), 720, 140, 120, 0, 130);

    companion object {
        fun from(key: String?): ViperPreset = values().find { it.key == key } ?: ORIGINAL
    }
}

/**
 * 车载音效预设。针对行车环境：路噪掩蔽低频、人声需要穿透、声场偏向驾驶位。
 */
enum class CarPreset(
    val key: String,
    val label: String,
    val description: String,
    val eqDb: IntArray,
    val bass: Int,
    val clarity: Int,
    val surround: Int,
    val loudnessMb: Int
) {
    CITY("CITY", "城市通勤", "中频清晰、低音适中，适合市区低速", intArrayOf(2, 1, 1, 2, 3, 3, 2, 1, 0, 0), 260, 220, 120, 100),
    HIGHWAY("HIGHWAY", "高速巡航", "加强低音与中频补偿风噪，人声更突出", intArrayOf(4, 3, 2, 2, 4, 4, 3, 2, 1, 0), 480, 300, 100, 180),
    NIGHT("NIGHT", "夜间驾驶", "柔和高低频，避免疲劳与刺耳", intArrayOf(2, 2, 1, 1, 2, 2, 1, 0, -1, -1), 200, 180, 160, 70),
    ALL_SEATS("ALL_SEATS", "全车共享", "拓宽声场、提升高频，照顾乘客", intArrayOf(3, 2, 1, 1, 2, 3, 4, 4, 3, 2), 300, 160, 360, 120),
    ACG("ACG", "纯净 ACG", "高频通透、人声清晰，适合长途", intArrayOf(1, 1, 0, 1, 2, 3, 3, 2, 1, 0), 220, 260, 200, 95),
    LIVE("LIVE", "现场 Live", "大厅感环绕，适合摇滚与演唱会", intArrayOf(3, 2, 1, 0, 1, 2, 3, 4, 3, 2), 380, 140, 340, 130);

    companion object {
        fun from(key: String?): CarPreset = values().find { it.key == key } ?: CITY
    }
}

/** 车内声场聚焦位置（对应车载 APP 的"驾驶座/副驾"模式）。 */
enum class CarSeat(val key: String, val label: String, val surround: Int, val reverb: Short) {
    DRIVER("DRIVER", "驾驶位", 90, 0),
    PASSENGER("PASSENGER", "副驾驶", 90, 0),
    FRONT("FRONT", "前排", 180, 1),
    ALL("ALL", "全车", 360, 2);

    companion object {
        fun from(key: String?): CarSeat = values().find { it.key == key } ?: DRIVER
    }
}

/** 车内噪音档位，用于自动补偿音量与低频（无法读取车速时的手动等价方案）。 */
enum class CarNoise(val key: String, val label: String, val extraLoudnessMb: Int, val extraBass: Int) {
    QUIET("QUIET", "安静", 0, 0),
    CITY("CITY", "城市", 60, 80),
    NOISY("NOISY", "嘈杂 / 高速", 140, 200);

    companion object {
        fun from(key: String?): CarNoise = values().find { it.key == key } ?: CITY
    }
}

/**
 * 音效配置状态。所有字段持久化在 SettingsStore；-1 表示沿用预设值（未手动覆盖）。
 */
data class SoundEffectState(
    val modeKey: String = SoundMode.OFF.key,
    val viperPresetKey: String = ViperPreset.ORIGINAL.key,
    val carPresetKey: String = CarPreset.CITY.key,
    /** 10 段 EQ dB 值，逗号分隔；空串表示沿用当前预设曲线。 */
    val customEq: String = "",
    val bass: Int = -1,
    val clarity: Int = -1,
    val surround: Int = -1,
    val reverb: Int = -1,
    val loudness: Int = -1,
    /** 蝰蛇子模块开关位掩码。 */
    val viperModules: Int = ViperModules.DEFAULT,
    val carSeatKey: String = CarSeat.DRIVER.key,
    val carNoiseKey: String = CarNoise.CITY.key,
    /** 导航播报时压低音乐音量而非切断（由系统音频焦点 ducking 实现）。 */
    val carDucking: Boolean = true,
    /** 检测到车机（横屏宽屏）时自动切换到车载音效。 */
    val carAutoEnable: Boolean = true
) {
    val mode: SoundMode get() = SoundMode.from(modeKey)
    val viper: ViperPreset get() = ViperPreset.from(viperPresetKey)
    val car: CarPreset get() = CarPreset.from(carPresetKey)
    val carSeat: CarSeat get() = CarSeat.from(carSeatKey)
    val carNoise: CarNoise get() = CarNoise.from(carNoiseKey)

    /** 实际生效的 10 段 EQ（dB）。手动曲线优先，其次按模式取预设。 */
    val effectiveEqDb: IntArray
        get() {
            val custom = customEq.parseEq()
            if (custom != null) {
                // 频谱扩展：提升最高两个频段，补偿有损压缩丢失的高频细节。
                if (mode == SoundMode.VIPER && ViperModules.enabled(viperModules, ViperModules.SPECTRAL_EXTEND)) {
                    custom[8] = (custom[8] + 2).coerceIn(SoundEffectState.EQ_MIN_DB, SoundEffectState.EQ_MAX_DB)
                    custom[9] = (custom[9] + 3).coerceIn(SoundEffectState.EQ_MIN_DB, SoundEffectState.EQ_MAX_DB)
                }
                return custom
            }
            val base = when (mode) {
                SoundMode.VIPER -> viper.eqDb.copyOf()
                SoundMode.CAR -> car.eqDb.copyOf()
                SoundMode.OFF -> IntArray(10)
            }
            if (mode == SoundMode.VIPER && ViperModules.enabled(viperModules, ViperModules.SPECTRAL_EXTEND)) {
                base[8] = (base[8] + 2).coerceIn(SoundEffectState.EQ_MIN_DB, SoundEffectState.EQ_MAX_DB)
                base[9] = (base[9] + 3).coerceIn(SoundEffectState.EQ_MIN_DB, SoundEffectState.EQ_MAX_DB)
            }
            return base
        }

    val effectiveBass: Int
        get() {
            val base = if (bass >= 0) bass else when (mode) {
                SoundMode.VIPER -> viper.bass
                SoundMode.CAR -> car.bass
                SoundMode.OFF -> 0
            }
            return if (mode == SoundMode.CAR) (base + carNoise.extraBass) else base
        }

    val effectiveClarity: Int get() = if (clarity >= 0) clarity else when (mode) {
        SoundMode.VIPER -> viper.clarity
        SoundMode.CAR -> car.clarity
        SoundMode.OFF -> 0
    }

    val effectiveSurround: Int
        get() {
            val base = if (surround >= 0) surround else when (mode) {
                SoundMode.VIPER -> viper.surround
                SoundMode.CAR -> car.surround
                SoundMode.OFF -> 0
            }
            // 车载声场聚焦会覆盖环绕强度（驾驶位/副驾/前排/全车）
            return if (mode == SoundMode.CAR) carSeat.surround else base
        }

    val effectiveReverb: Short
        get() {
            val base = if (reverb >= 0) reverb else when (mode) {
                SoundMode.VIPER -> viper.reverb.toInt()
                SoundMode.CAR -> carSeat.reverb.toInt()
                SoundMode.OFF -> 0
            }
            return base.toShort()
        }

    val effectiveLoudness: Int
        get() {
            val base = if (loudness >= 0) loudness else when (mode) {
                SoundMode.VIPER -> viper.loudnessMb
                SoundMode.CAR -> car.loudnessMb
                SoundMode.OFF -> 0
            }
            return if (mode == SoundMode.CAR) (base + carNoise.extraLoudnessMb) else base
        }

    /** 是否已手动改过 EQ（用于 UI 显示"自定义"标记）。 */
    val hasCustomEq: Boolean get() = customEq.parseEq() != null

    companion object {
        const val EQ_MIN_DB = -15
        const val EQ_MAX_DB = 15
        const val STRENGTH_MAX = 1000
        const val LOUDNESS_MAX_MB = 3000
    }
}

/** "1,2,0,-3,..." → IntArray(10)；格式不对返回 null。 */
fun String.parseEq(): IntArray? {
    if (isBlank()) return null
    val parts = split(",")
    if (parts.size != 10) return null
    val values = parts.map { it.trim().toIntOrNull() ?: return null }
    if (values.any { it < SoundEffectState.EQ_MIN_DB || it > SoundEffectState.EQ_MAX_DB }) return null
    return values.toIntArray()
}

/** IntArray(10) → "1,2,0,-3,..." */
fun IntArray.toEqString(): String =
    joinToString(",") { it.coerceIn(SoundEffectState.EQ_MIN_DB, SoundEffectState.EQ_MAX_DB).toString() }
