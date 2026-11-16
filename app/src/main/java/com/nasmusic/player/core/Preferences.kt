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
    /** AUTO / LRCLIB / NETEASE */
    val lyricsSource: String = "AUTO",
    val autoDownloadLyrics: Boolean = true,
    /** 播放页根据专辑封面主色微调渐变背景，换歌时平滑过渡。 */
    val artworkTint: Boolean = true,
    /** 本地无封面时自动从网络匹配并缓存专辑封面。 */
    val autoDownloadCover: Boolean = true
)

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
        val LYRICS_SOURCE = stringPreferencesKey("lyrics_source")
        val AUTO_LYRICS = booleanPreferencesKey("auto_download_lyrics")
        val ARTWORK_TINT = booleanPreferencesKey("artwork_tint")
        val AUTO_COVER = booleanPreferencesKey("auto_download_cover")
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
            lyricsSource = p[Keys.LYRICS_SOURCE] ?: "AUTO",
            autoDownloadLyrics = p[Keys.AUTO_LYRICS] ?: true,
            artworkTint = p[Keys.ARTWORK_TINT] ?: true,
            autoDownloadCover = p[Keys.AUTO_COVER] ?: true
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
        val s = start.coerceIn(0, 11)
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
}

data class SavedPlayback(
    val ids: List<String>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean
)

/** 播放队列 / 位置 / 循环模式 / 随机模式的本地持久化（播放记忆）。 */
class PlaybackStateStore(context: Context) {
    private val sp = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    fun save(state: SavedPlayback) {
        sp.edit()
            .putString("ids", state.ids.joinToString("\n"))
            .putInt("index", state.index)
            .putLong("position", state.positionMs)
            .putInt("repeat", state.repeatMode)
            .putBoolean("shuffle", state.shuffle)
            .apply()
    }

    fun restore(): SavedPlayback? {
        val raw = sp.getString("ids", null) ?: return null
        val ids = raw.split('\n').filter { it.isNotBlank() }
        if (ids.isEmpty()) return null
        return SavedPlayback(
            ids = ids,
            index = sp.getInt("index", 0).coerceIn(0, ids.lastIndex),
            positionMs = sp.getLong("position", 0L),
            repeatMode = sp.getInt("repeat", 0),
            shuffle = sp.getBoolean("shuffle", false)
        )
    }

    fun clear() = sp.edit().clear().apply()
}
