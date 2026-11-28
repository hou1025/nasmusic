package com.nasmusic.player.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nasmusic_settings")

data class SettingsState(
    /** 兼容旧版本设置；v1.2 起外观由 themePalette / themeMode 决定。 */
    val darkMode: Boolean = false,
    /** 色板 key：INDIGO / OCEAN / MINT / SAKURA / AMBER / GRAPHITE / DYNAMIC。 */
    val themePalette: String = "INDIGO",
    /** SYSTEM / LIGHT / DARK / SCHEDULE。 */
    val themeMode: String = "SYSTEM",
    /** 定时模式下的浅色时段 [start, end)。 */
    val scheduleStartHour: Int = 7,
    val scheduleEndHour: Int = 19,
    val cacheLimitMb: Int = 512,
    val restorePlayback: Boolean = true,
    val autoScanOnStart: Boolean = true,
    val lyricsOffsetMs: Long = 0L,
    /** 低于该时长（秒）的音频不收入音乐库，0 表示不限制。 */
    val minDurationSec: Int = 20,
    /** 被排除的文件夹路径片段；命中即不扫描、不展示、不播放。 */
    val excludedFolders: Set<String> = emptySet(),
    /** 排除规则匹配方式：CONTAINS（包含，默认）/ EXACT（精确路径，避免误伤同名专辑）。 */
    val excludedMatchMode: String = "CONTAINS",
    /** AUTO / LRCLIB / NETEASE */
    val lyricsSource: String = "AUTO",
    val autoDownloadLyrics: Boolean = true,
    /** 播放页根据专辑封面主色微调渐变背景，换歌时平滑过渡。 */
    val artworkTint: Boolean = true,
    /** 本地无封面时自动从网络匹配并缓存专辑封面。 */
    val autoDownloadCover: Boolean = true,
    // ===== 音效设置（蝰蛇 / 车载）=====
    val soundMode: String = "OFF",
    val viperPreset: String = "ORIGINAL",
    val carPreset: String = "CITY",
    val soundCustomEq: String = "",
    val soundBass: Int = -1,
    val soundClarity: Int = -1,
    val soundSurround: Int = -1,
    val soundReverb: Int = -1,
    val soundLoudness: Int = -1,
    val viperModules: Int = ViperModules.DEFAULT,
    val carSeat: String = "DRIVER",
    val carNoise: String = "CITY",
    val carDucking: Boolean = true,
    val carAutoEnable: Boolean = true
) {
    /** 供音效引擎消费的聚合视图。 */
    val soundEffect: SoundEffectState
        get() = SoundEffectState(
            modeKey = soundMode, viperPresetKey = viperPreset, carPresetKey = carPreset,
            customEq = soundCustomEq, bass = soundBass, clarity = soundClarity,
            surround = soundSurround, reverb = soundReverb, loudness = soundLoudness,
            viperModules = viperModules, carSeatKey = carSeat, carNoiseKey = carNoise,
            carDucking = carDucking, carAutoEnable = carAutoEnable
        )
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val THEME_PALETTE = stringPreferencesKey("theme_palette")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SCHEDULE_START = intPreferencesKey("schedule_start_hour")
        val SCHEDULE_END = intPreferencesKey("schedule_end_hour")
        val CACHE_LIMIT_MB = intPreferencesKey("cache_limit_mb")
        val RESTORE_PLAYBACK = booleanPreferencesKey("restore_playback")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val LYRICS_OFFSET = longPreferencesKey("lyrics_offset_ms")
        val MIN_DURATION_SEC = intPreferencesKey("min_duration_sec")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val EXCLUDED_MATCH_MODE = stringPreferencesKey("excluded_match_mode")
        val LYRICS_SOURCE = stringPreferencesKey("lyrics_source")
        val AUTO_LYRICS = booleanPreferencesKey("auto_download_lyrics")
        val ARTWORK_TINT = booleanPreferencesKey("artwork_tint")
        val AUTO_COVER = booleanPreferencesKey("auto_download_cover")
        val SOUND_MODE = stringPreferencesKey("sound_mode")
        val VIPER_PRESET = stringPreferencesKey("viper_preset")
        val CAR_PRESET = stringPreferencesKey("car_preset")
        val SOUND_CUSTOM_EQ = stringPreferencesKey("sound_custom_eq")
        val SOUND_BASS = intPreferencesKey("sound_bass")
        val SOUND_CLARITY = intPreferencesKey("sound_clarity")
        val SOUND_SURROUND = intPreferencesKey("sound_surround")
        val SOUND_REVERB = intPreferencesKey("sound_reverb")
        val SOUND_LOUDNESS = intPreferencesKey("sound_loudness")
        val VIPER_MODULES = intPreferencesKey("viper_modules")
        val CAR_SEAT = stringPreferencesKey("car_seat")
        val CAR_NOISE = stringPreferencesKey("car_noise")
        val CAR_DUCKING = booleanPreferencesKey("car_ducking")
        val CAR_AUTO_ENABLE = booleanPreferencesKey("car_auto_enable")
    }

    val settings: Flow<SettingsState> = context.dataStore.data.map { p ->
        SettingsState(
            darkMode = p[Keys.DARK_MODE] ?: false,
            // 旧版只有深色开关：升级后深色用户保持深色，其余默认跟随系统。
            themePalette = p[Keys.THEME_PALETTE] ?: "INDIGO",
            themeMode = p[Keys.THEME_MODE] ?: if (p[Keys.DARK_MODE] == true) "DARK" else "SYSTEM",
            scheduleStartHour = p[Keys.SCHEDULE_START] ?: 7,
            scheduleEndHour = p[Keys.SCHEDULE_END] ?: 19,
            cacheLimitMb = p[Keys.CACHE_LIMIT_MB] ?: 512,
            restorePlayback = p[Keys.RESTORE_PLAYBACK] ?: true,
            autoScanOnStart = p[Keys.AUTO_SCAN] ?: true,
            lyricsOffsetMs = p[Keys.LYRICS_OFFSET] ?: 0L,
            minDurationSec = p[Keys.MIN_DURATION_SEC] ?: 20,
            excludedFolders = p[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            excludedMatchMode = p[Keys.EXCLUDED_MATCH_MODE] ?: "CONTAINS",
            lyricsSource = p[Keys.LYRICS_SOURCE] ?: "AUTO",
            autoDownloadLyrics = p[Keys.AUTO_LYRICS] ?: true,
            artworkTint = p[Keys.ARTWORK_TINT] ?: true,
            autoDownloadCover = p[Keys.AUTO_COVER] ?: true,
            soundMode = p[Keys.SOUND_MODE] ?: "OFF",
            viperPreset = p[Keys.VIPER_PRESET] ?: "ORIGINAL",
            carPreset = p[Keys.CAR_PRESET] ?: "CITY",
            soundCustomEq = p[Keys.SOUND_CUSTOM_EQ] ?: "",
            soundBass = p[Keys.SOUND_BASS] ?: -1,
            soundClarity = p[Keys.SOUND_CLARITY] ?: -1,
            soundSurround = p[Keys.SOUND_SURROUND] ?: -1,
            soundReverb = p[Keys.SOUND_REVERB] ?: -1,
            soundLoudness = p[Keys.SOUND_LOUDNESS] ?: -1,
            viperModules = p[Keys.VIPER_MODULES] ?: ViperModules.DEFAULT,
            carSeat = p[Keys.CAR_SEAT] ?: "DRIVER",
            carNoise = p[Keys.CAR_NOISE] ?: "CITY",
            carDucking = p[Keys.CAR_DUCKING] ?: true,
            carAutoEnable = p[Keys.CAR_AUTO_ENABLE] ?: true
        )
    }

    /** 扫描器等非 UI 场景读取一次性快照。 */
    suspend fun snapshot(): SettingsState = settings.first()

    suspend fun setDarkMode(value: Boolean) = context.dataStore.edit {
        it[Keys.DARK_MODE] = value
        it[Keys.THEME_MODE] = if (value) "DARK" else "LIGHT"
    }

    suspend fun setThemePalette(value: String) = context.dataStore.edit {
        it[Keys.THEME_PALETTE] = value
    }

    suspend fun setThemeMode(value: String) = context.dataStore.edit {
        it[Keys.THEME_MODE] = value
    }

    /** 保证 0 <= start < end <= 24，避免出现无意义的空浅色时段。 */
    suspend fun setScheduleHours(start: Int, end: Int) = context.dataStore.edit {
        // 允许 12:00 及之后开始浅色时段；start 最大 23，end 至少比 start 大 1 小时
        val s = start.coerceIn(0, 23)
        val e = end.coerceIn(s + 1, 24)
        it[Keys.SCHEDULE_START] = s
        it[Keys.SCHEDULE_END] = e
    }

    suspend fun setCacheLimitMb(value: Int) = context.dataStore.edit { it[Keys.CACHE_LIMIT_MB] = value }
    suspend fun setRestorePlayback(value: Boolean) = context.dataStore.edit { it[Keys.RESTORE_PLAYBACK] = value }
    suspend fun setAutoScan(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_SCAN] = value }
    suspend fun setLyricsOffset(value: Long) = context.dataStore.edit { it[Keys.LYRICS_OFFSET] = value }
    suspend fun setMinDurationSec(value: Int) = context.dataStore.edit { it[Keys.MIN_DURATION_SEC] = value.coerceIn(0, 600) }
    suspend fun setLyricsSource(value: String) = context.dataStore.edit { it[Keys.LYRICS_SOURCE] = value }
    suspend fun setAutoDownloadLyrics(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_LYRICS] = value }
    suspend fun setArtworkTint(value: Boolean) = context.dataStore.edit { it[Keys.ARTWORK_TINT] = value }
    suspend fun setAutoDownloadCover(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_COVER] = value }

    // ===== 音效设置 =====
    suspend fun setSoundMode(value: String) = context.dataStore.edit { it[Keys.SOUND_MODE] = value }
    suspend fun setViperPreset(value: String) = context.dataStore.edit { it[Keys.VIPER_PRESET] = value }
    suspend fun setCarPreset(value: String) = context.dataStore.edit { it[Keys.CAR_PRESET] = value }
    suspend fun setSoundCustomEq(value: String) = context.dataStore.edit { it[Keys.SOUND_CUSTOM_EQ] = value }
    suspend fun setSoundBass(value: Int) = context.dataStore.edit { it[Keys.SOUND_BASS] = value }
    suspend fun setSoundClarity(value: Int) = context.dataStore.edit { it[Keys.SOUND_CLARITY] = value }
    suspend fun setSoundSurround(value: Int) = context.dataStore.edit { it[Keys.SOUND_SURROUND] = value }
    suspend fun setSoundReverb(value: Int) = context.dataStore.edit { it[Keys.SOUND_REVERB] = value }
    suspend fun setSoundLoudness(value: Int) = context.dataStore.edit { it[Keys.SOUND_LOUDNESS] = value }
    suspend fun setViperModules(value: Int) = context.dataStore.edit { it[Keys.VIPER_MODULES] = value }
    suspend fun setCarSeat(value: String) = context.dataStore.edit { it[Keys.CAR_SEAT] = value }
    suspend fun setCarNoise(value: String) = context.dataStore.edit { it[Keys.CAR_NOISE] = value }
    suspend fun setCarDucking(value: Boolean) = context.dataStore.edit { it[Keys.CAR_DUCKING] = value }
    suspend fun setCarAutoEnable(value: Boolean) = context.dataStore.edit { it[Keys.CAR_AUTO_ENABLE] = value }

    /** 恢复音效全部参数为默认（直通）。 */
    suspend fun resetSoundEffect() = context.dataStore.edit { p ->
        p[Keys.SOUND_MODE] = "OFF"
        p.remove(Keys.VIPER_PRESET); p.remove(Keys.CAR_PRESET); p.remove(Keys.SOUND_CUSTOM_EQ)
        p.remove(Keys.SOUND_BASS); p.remove(Keys.SOUND_CLARITY); p.remove(Keys.SOUND_SURROUND)
        p.remove(Keys.SOUND_REVERB); p.remove(Keys.SOUND_LOUDNESS); p.remove(Keys.VIPER_MODULES)
        p.remove(Keys.CAR_SEAT); p.remove(Keys.CAR_NOISE)
        p.remove(Keys.CAR_DUCKING); p.remove(Keys.CAR_AUTO_ENABLE)
    }

    suspend fun addExcludedFolder(rule: String) {
        val clean = rule.trim()
        if (clean.isEmpty()) return
        context.dataStore.edit { p ->
            p[Keys.EXCLUDED_FOLDERS] = (p[Keys.EXCLUDED_FOLDERS] ?: emptySet()) + clean
        }
    }

    suspend fun removeExcludedFolder(rule: String) {
        context.dataStore.edit { p ->
            p[Keys.EXCLUDED_FOLDERS] = (p[Keys.EXCLUDED_FOLDERS] ?: emptySet()) - rule
        }
    }

    suspend fun setExcludedMatchMode(mode: String) = context.dataStore.edit { p ->
        p[Keys.EXCLUDED_MATCH_MODE] = if (mode == "EXACT") "EXACT" else "CONTAINS"
    }
}

data class SavedPlayback(
    val ids: List<String>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    /** 以 mediaId 定位当前曲目；队列被过滤规则删减后，index 不再可靠，必须反查 currentId。 */
    val currentId: String = ""
)

/** 播放队列 / 位置 / 循环模式 / 随机模式的本地持久化（播放记忆）。 */
class PlaybackStateStore(context: Context) {
    private val sp = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    companion object {
        /**
         * 新格式以该标记开头，其后是若干 "<字符数>:<id>" 连续片段。
         * 长度前缀可安全容纳 id 中的换行符等任意字符；旧版本直接以 "\n" 拼接裸 id，
         * 读取时无此标记或新格式解析失败都回退到旧格式，避免升级后队列丢失。
         */
        private const val IDS_FORMAT_PREFIX = "nasmusic_ids_v1:"
    }

    fun save(state: SavedPlayback) {
        val encoded = IDS_FORMAT_PREFIX + state.ids.joinToString("") { "${it.length}:$it" }
        sp.edit()
            .putString("ids", encoded)
            .putInt("index", state.index)
            .putLong("position", state.positionMs)
            .putInt("repeat", state.repeatMode)
            .putBoolean("shuffle", state.shuffle)
            .putString("currentId", state.currentId)
            .apply()
    }

    fun restore(): SavedPlayback? {
        val raw = sp.getString("ids", null) ?: return null
        val ids = decodeIds(raw)
        if (ids.isEmpty()) return null
        val index = sp.getInt("index", 0).coerceIn(0, ids.lastIndex)
        return SavedPlayback(
            ids = ids,
            index = index,
            positionMs = sp.getLong("position", 0L),
            repeatMode = sp.getInt("repeat", 0),
            shuffle = sp.getBoolean("shuffle", false),
            // 老版本没有该字段：退回按 index 取 id，保证旧播放记忆可平滑升级
            currentId = sp.getString("currentId", null)?.takeIf { it.isNotBlank() }
                ?: ids.getOrNull(index).orEmpty()
        )
    }

    /**
     * 解析队列 id：新格式按长度前缀还原（id 内含换行也安全）；
     * 无新格式标记或解析失败则回退为旧版 "\n" 拼接格式。
     */
    private fun decodeIds(raw: String): List<String> {
        fun legacy(): List<String> = raw.split('\n').filter { it.isNotBlank() }
        if (!raw.startsWith(IDS_FORMAT_PREFIX)) return legacy()

        val body = raw.removePrefix(IDS_FORMAT_PREFIX)
        val result = mutableListOf<String>()
        var cursor = 0
        while (cursor < body.length) {
            val colon = body.indexOf(':', cursor)
            if (colon < 0) return legacy()
            val len = body.substring(cursor, colon).toIntOrNull() ?: return legacy()
            val start = colon + 1
            if (len < 0 || start + len > body.length) return legacy()
            result += body.substring(start, start + len)
            cursor = start + len
        }
        return result
    }

    fun clear() = sp.edit().clear().apply()
}
