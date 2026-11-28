package com.nasmusic.desktop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 上次播放的快照：队列、当前下标、进度、音量。 */
@Serializable
data class PlaybackSnapshot(
    val songIds: List<String> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
    val volume: Double = 1.0
)

/**
 * 播放记忆（对应 Android 端的"杀进程重启后恢复队列与进度"）。
 *
 * 桌面端单独落一个 playback.json，和数据配置分开：播放进度变化频繁，
 * 没必要每次都重写整份 library.json。
 */
class PlaybackMemory {
    private val dir = File(System.getProperty("user.home"), ".nasmusic-desktop")
    private val file = File(dir, "playback.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): PlaybackSnapshot? = runCatching {
        if (!file.isFile) return null
        json.decodeFromString<PlaybackSnapshot>(file.readText())
    }.getOrNull()

    fun save(snapshot: PlaybackSnapshot) {
        runCatching {
            dir.mkdirs()
            file.writeText(json.encodeToString(snapshot))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
