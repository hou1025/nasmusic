package com.nasmusic.player.core

import java.util.Locale

/** 排除规则的匹配方式：EXACT 只匹配完整路径/子目录，CONTAINS 允许路径片段命中。 */
enum class FolderMatchMode { EXACT, CONTAINS }

/**
 * 音乐库过滤规则：短音频阈值 + 文件夹黑名单。
 * 本地 MediaStore、SAF 文件夹、NAS 扫描与所有 UI 查询共用同一套判定，避免出现「库里有但不显示」的分歧。
 */
data class LibraryFilterState(
    val minDurationSec: Int = 20,
    val excludedFolders: Set<String> = emptySet(),
    val matchMode: FolderMatchMode = FolderMatchMode.CONTAINS
)

fun SettingsState.libraryFilter(): LibraryFilterState = LibraryFilterState(
    minDurationSec,
    excludedFolders,
    if (excludedMatchMode == "EXACT") FolderMatchMode.EXACT else FolderMatchMode.CONTAINS
)

/** 统一斜杠、去掉首尾分隔符、忽略大小写，便于跨平台（Windows 共享 / NAS / 本地）匹配。 */
fun normalizeFolder(value: String): String =
    value.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)

/**
 * 路径排除判定。默认 CONTAINS 保持旧行为；EXACT 只命中完整路径或其子目录，
 * 避免「专辑名恰好包含 Excluded 字样」被误伤（README 2.3 提到的坑）。
 */
fun isExcludedFolder(
    folder: String,
    rules: Set<String>,
    mode: FolderMatchMode = FolderMatchMode.CONTAINS
): Boolean {
    if (rules.isEmpty()) return false
    val path = normalizeFolder(folder)
    if (path.isEmpty()) return false
    return rules.any { raw ->
        val rule = normalizeFolder(raw)
        if (rule.isEmpty()) false
        else if (mode == FolderMatchMode.EXACT) path == rule || path.startsWith("$rule/")
        else path == rule || path.startsWith("$rule/") || path.contains(rule)
    }
}

fun Song.isAllowedBy(filter: LibraryFilterState): Boolean {
    val thresholdMs = filter.minDurationSec.coerceAtLeast(0) * 1000L
    // durationMs == 0 表示元数据缺失，此时保守保留，避免误删无法识别时长的文件
    if (thresholdMs > 0 && durationMs > 0 && durationMs < thresholdMs) return false
    return !isExcludedFolder(folder, filter.excludedFolders, filter.matchMode)
}
