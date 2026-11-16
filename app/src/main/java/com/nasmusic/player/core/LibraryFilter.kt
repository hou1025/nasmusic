package com.nasmusic.player.core

import java.util.Locale

/**
 * 音乐库过滤规则：短音频阈值 + 文件夹黑名单。
 * 本地 MediaStore、SAF 文件夹、NAS 扫描与所有 UI 查询共用同一套判定，避免出现「库里有但不显示」的分歧。
 */
data class LibraryFilterState(
    val minDurationSec: Int = 20,
    val excludedFolders: Set<String> = emptySet()
)

fun SettingsState.libraryFilter(): LibraryFilterState =
    LibraryFilterState(minDurationSec, excludedFolders)

/** 统一斜杠、去掉首尾分隔符、忽略大小写，便于跨平台（Windows 共享 / NAS / 本地）匹配。 */
fun normalizeFolder(value: String): String =
    value.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)

/** 路径片段匹配：精确、子目录，或用户手输的片段（如 Recordings、录音）。 */
fun isExcludedFolder(folder: String, rules: Set<String>): Boolean {
    if (rules.isEmpty()) return false
    val path = normalizeFolder(folder)
    if (path.isEmpty()) return false
    return rules.any { raw ->
        val rule = normalizeFolder(raw)
        rule.isNotEmpty() && (path == rule || path.startsWith("$rule/") || path.contains(rule))
    }
}

fun Song.isAllowedBy(filter: LibraryFilterState): Boolean {
    val thresholdMs = filter.minDurationSec.coerceAtLeast(0) * 1000L
    // durationMs == 0 表示元数据缺失，此时保守保留，避免误删无法识别时长的文件
    if (thresholdMs > 0 && durationMs > 0 && durationMs < thresholdMs) return false
    return !isExcludedFolder(folder, filter.excludedFolders)
}
