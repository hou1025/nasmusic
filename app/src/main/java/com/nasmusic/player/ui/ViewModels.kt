package com.nasmusic.player.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.player.core.AppContainer
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.ScanProgress
import com.nasmusic.player.core.SettingsState
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.SourceType
import com.nasmusic.player.core.friendlyError
import com.nasmusic.player.core.nasUri
import com.nasmusic.player.core.parseNasUri
import com.nasmusic.player.data.AudioTags
import com.nasmusic.player.data.ContentRandomReader
import com.nasmusic.player.data.FnosApi
import com.nasmusic.player.data.FnosSession
import com.nasmusic.player.data.LyricCandidate
import com.nasmusic.player.data.Lyrics
import com.nasmusic.player.data.LyricsSource
import com.nasmusic.player.data.NasRandomAccessReader
import com.nasmusic.player.data.TagReader
import com.nasmusic.player.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context = app.applicationContext
    private val container = AppContainer.get(app)

    val player = container.playbackController
    val settingsState: StateFlow<SettingsState> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    val songs: StateFlow<List<Song>> = container.musicRepository.allSongs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites: StateFlow<List<Song>> = container.musicRepository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders: StateFlow<List<String>> = container.musicRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = container.playlistRepository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val nasSources = container.nasRepository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val searchResults: StateFlow<List<Song>> = _query
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else container.musicRepository.search(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scan = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scan.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var scanJob: Job? = null

    init { refreshPermissions() }

    fun refreshPermissions() {
        _permissionGranted.value = ContextCompat.checkSelfPermission(
            context,
            if (android.os.Build.VERSION.SDK_INT >= 33)
                android.Manifest.permission.READ_MEDIA_AUDIO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun setQuery(value: String) { _query.value = value }

    fun emit(message: String) { viewModelScope.launch { _message.emit(message) } }

    // ---------- 本地音乐库 ----------
    fun scanLocal() {
        viewModelScope.launch {
            emit("正在扫描本地音乐…")
            val count = runCatching { container.musicRepository.scanLocal() }
                .getOrElse { emit("扫描失败：${friendlyError(it)}"); return@launch }
            emit("扫描完成，共 $count 首本地歌曲")
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            val count = runCatching { container.musicRepository.scanFolder(treeUri) }
                .getOrElse { emit("添加失败：${friendlyError(it)}"); return@launch }
            emit("已从文件夹导入 $count 首歌曲")
        }
    }

    fun playlistSongs(id: String) = container.playlistRepository.songs(id)

    fun createPlaylist(name: String, songIds: List<String> = emptyList()) {
        viewModelScope.launch {
            val id = container.playlistRepository.create(name)
            if (songIds.isNotEmpty()) container.playlistRepository.addSongs(id, songIds)
            emit("播放列表已创建")
        }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { container.playlistRepository.rename(id, name) }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { container.playlistRepository.delete(id) }
    }

    fun addToPlaylist(id: String, songIds: List<String>) {
        viewModelScope.launch {
            container.playlistRepository.addSongs(id, songIds)
            emit("已加入播放列表")
        }
    }

    fun removeFromPlaylist(id: String, songId: String) {
        viewModelScope.launch { container.playlistRepository.removeSong(id, songId) }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            container.musicRepository.setFavorite(song.id, !song.isFavorite)
            emit(if (song.isFavorite) "已取消收藏" else "已加入收藏")
        }
    }

    fun updateTags(song: Song, title: String, artist: String, album: String) {
        viewModelScope.launch {
            container.musicRepository.updateTags(song.id, title, artist, album)
            emit("标签已更新（仅本应用内生效）")
        }
    }

    // ---------- NAS / 飞牛音乐 ----------

    /** 登录页「测试连接」：只探测网关是否可达，不需要账号。 */
    suspend fun testFnosConnection(host: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = FnosApi.normalizeHost(host)
            val api = FnosApi(baseUrl, "", "", "fnos_probe")
            try {
                api.probe()
                baseUrl
            } finally {
                api.close()
            }
        }
    }

    /** 登录并顺带验证曲库接口可用；返回规范化后的服务器地址。 */
    suspend fun loginFnos(
        sourceId: String,
        host: String,
        username: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = FnosApi.normalizeHost(host)
            if (username.isBlank()) throw IllegalArgumentException("请输入飞牛 NAS 账号")
            if (password.isEmpty()) throw IllegalArgumentException("请输入密码")
            val api = FnosApi(baseUrl, username.trim(), password, sourceId)
            try {
                // 只校验登录握手；曲库同步在后台进行，失败会在网络源卡片上给出具体错误
                api.login()
                baseUrl
            } finally {
                api.close()
            }
        }
    }

    /** 保存飞牛源后立即同步曲库，避免用户还要手动点一次扫描。 */
    fun addFnosSource(source: NasSource, password: String) {
        viewModelScope.launch {
            runCatching { container.nasRepository.save(source, password) }
                .onFailure { emit("保存失败：${friendlyError(it)}"); return@launch }
            FnosSession.remove(source.id)
            emit("飞牛音乐已连接，正在同步曲库…")
            scanNasSource(source)
        }
    }

    fun saveNasSource(source: NasSource, password: String?) {
        viewModelScope.launch {
            runCatching { container.nasRepository.save(source, password) }
                .onFailure { emit("保存失败：${friendlyError(it)}"); return@launch }
            emit("网络音乐源已保存")
        }
    }

    fun deleteNasSource(id: String) {
        viewModelScope.launch {
            runCatching { container.nasRepository.delete(id) }
                .onFailure { emit("删除失败：${friendlyError(it)}"); return@launch }
            // 清掉播放队列里属于该源的曲目，避免继续播放已失效的地址
            container.playbackController.removeBySourceId(id)
            FnosSession.remove(id)
            emit("网络音乐源及曲库已删除")
        }
    }

    fun scanNasSource(source: NasSource) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scan.value = ScanProgress(running = true, sourceId = source.id, message = "开始扫描")
            val count = runCatching {
                container.nasScanner.scan(source) { progress -> _scan.value = progress }
            }.getOrElse { e ->
                // 保留异常类型和原始消息，避免空消息异常只显示「扫描失败」而无法定位
                val detail = friendlyError(e, "")
                    .ifBlank { e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName }
                _scan.value = ScanProgress(error = "扫描失败：$detail")
                emit("扫描失败：$detail")
                return@launch
            }
            _scan.value = ScanProgress(message = "扫描完成，共 $count 首")
            emit("${source.name} 扫描完成，共 $count 首")
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = ScanProgress(message = "扫描已取消")
    }

    /** 按文件夹浏览 NAS 时，把目录条目映射到已入库的歌曲。 */
    suspend fun nasSong(
        sourceId: String,
        path: String,
        name: String,
        type: SourceType = SourceType.SMB
    ): Song? {
        val id = "nas:$sourceId:$path"
        return container.musicRepository.getById(id)
            ?: Song(
                id = id,
                title = name.substringBeforeLast('.'),
                uri = nasUri(sourceId, path),
                sourceType = type,
                sourceId = sourceId,
                folder = path.substringBeforeLast('/', ""),
                mimeType = com.nasmusic.player.core.mimeForName(name)
            )
    }

    // ---------- 设置 / 缓存 ----------
    fun setDarkMode(value: Boolean) = viewModelScope.launch { container.settings.setDarkMode(value) }
    fun setThemePalette(value: String) = viewModelScope.launch { container.settings.setThemePalette(value) }
    fun setThemeMode(value: String) = viewModelScope.launch { container.settings.setThemeMode(value) }
    fun setScheduleHours(start: Int, end: Int) = viewModelScope.launch {
        container.settings.setScheduleHours(start, end)
    }
    fun setCacheLimitMb(value: Int) = viewModelScope.launch { container.settings.setCacheLimitMb(value) }
    fun setRestorePlayback(value: Boolean) = viewModelScope.launch { container.settings.setRestorePlayback(value) }
    fun setAutoScan(value: Boolean) = viewModelScope.launch { container.settings.setAutoScan(value) }
    fun setLyricsOffset(value: Long) = viewModelScope.launch { container.settings.setLyricsOffset(value) }
    fun setLyricsSource(value: String) = viewModelScope.launch { container.settings.setLyricsSource(value) }
    fun setAutoDownloadLyrics(value: Boolean) = viewModelScope.launch { container.settings.setAutoDownloadLyrics(value) }
    fun setArtworkTint(value: Boolean) = viewModelScope.launch { container.settings.setArtworkTint(value) }
    fun setAutoDownloadCover(value: Boolean) = viewModelScope.launch { container.settings.setAutoDownloadCover(value) }

    /** 清掉在线封面缓存，之后按需要重新匹配。 */
    fun clearCoverCache() {
        viewModelScope.launch {
            container.coverFetcher.clear()
            emit("封面缓存已清空")
        }
    }

    // ---------- 扫描过滤（时长阈值 / 排除文件夹） ----------
    fun setMinDurationSec(value: Int) {
        viewModelScope.launch {
            container.settings.setMinDurationSec(value)
            val removed = container.musicRepository.purgeFiltered()
            emit(if (value == 0) "已关闭短音频过滤" else "已忽略短于 ${value}s 的音频，移除 $removed 首")
        }
    }

    /** 加入黑名单并立即把匹配到的歌曲从索引中移除；磁盘/NAS 上的原文件不会被删除。 */
    fun excludeFolder(rule: String) {
        val clean = rule.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            container.settings.addExcludedFolder(clean)
            container.musicRepository.deleteFolder(clean)
            // 移除文件夹是即时生效的批量操作，不再弹出提示打扰
        }
    }

    fun restoreFolder(rule: String) {
        viewModelScope.launch {
            container.settings.removeExcludedFolder(rule)
            emit("已恢复「$rule」，重新扫描后歌曲会回到音乐库")
        }
    }

    fun purgeFiltered() {
        viewModelScope.launch {
            container.musicRepository.purgeFiltered()
            // 静默清理，不再显示长时间停留的提示
        }
    }

    fun rescanAfterRestore() {
        viewModelScope.launch {
            val count = runCatching { container.musicRepository.scanLocal() }
                .getOrElse { emit("扫描失败：${friendlyError(it)}"); return@launch }
            emit("重新扫描完成，共 $count 首本地歌曲")
        }
    }

    fun clearDownloadedLyrics() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.lyricsStore.clear()
                container.musicRepository.clearDownloadedLyrics()
            }
            emit("已清除全部下载的歌词")
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.nasCache.clear() }
            emit("缓存已清空")
        }
    }

    suspend fun cacheState(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        container.nasCache.cachedCount() to container.nasCache.cachedSizeBytes()
    }

    fun enforceCacheLimit() = viewModelScope.launch {
        withContext(Dispatchers.IO) { container.nasCache.enforceLimit() }
    }

    suspend fun ensureSongs(songs: List<Song>) = container.musicRepository.saveSongs(songs)

    suspend fun browseNas(sourceId: String, path: String) = withContext(Dispatchers.IO) {
        val browser = container.nasRepository.browserFor(sourceId)
        try {
            browser.listDir(path)
        } finally {
            runCatching { browser.close() }
        }
    }
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)
    val state: StateFlow<PlaybackUiState> = container.playbackController.state
    val settingsState: StateFlow<SettingsState> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _audioTags = MutableStateFlow<AudioTags?>(null)
    val audioTags: StateFlow<AudioTags?> = _audioTags.asStateFlow()

    private val _sleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message.asSharedFlow()

    private val _lyricCandidates = MutableStateFlow<List<LyricCandidate>>(emptyList())
    val lyricCandidates: StateFlow<List<LyricCandidate>> = _lyricCandidates.asStateFlow()
    private val _lyricsBusy = MutableStateFlow(false)
    val lyricsBusy: StateFlow<Boolean> = _lyricsBusy.asStateFlow()
    private val _lyricsVersion = MutableStateFlow(0)
    private val autoFetchAttempted = mutableSetOf<String>()
    private val lyricsInFlight = mutableSetOf<String>()

    /** songId -> 本地封面 URI；下载完成后立即生效，无需重新扫描媒体库。 */
    private val _coverOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val coverOverrides: StateFlow<Map<String, String>> = _coverOverrides.asStateFlow()
    private val coverAttempted = mutableSetOf<String>()
    private val coverInFlight = mutableSetOf<String>()

    private var sleepJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                state.map { it.currentSong }.distinctUntilChangedBy { it?.id },
                settingsState.map { it.lyricsOffsetMs }.distinctUntilChangedBy { it },
                _lyricsVersion
            ) { song, offset, _ -> song to offset }
                .collect { (song, offset) ->
                    // 封面下载先异步启动，与本地歌词/标签读取并行，不拖慢首屏
                    maybeFetchCover(song)
                    loadExtras(song, offset)
                    maybeAutoFetch(song)
                }
        }
    }

    private suspend fun loadExtras(song: Song?, offset: Long) = withContext(Dispatchers.IO) {
        if (song == null) {
            _lyrics.value = null
            _audioTags.value = null
            return@withContext
        }
        // 重新取一次索引，确保刚下载的 lyricsUri 立即生效
        val current = container.musicRepository.getById(song.id) ?: song
        val tags = runCatching { readTags(current) }.getOrNull()
        _audioTags.value = tags
        _lyrics.value = runCatching {
            container.lyricsLoader.load(current, tags?.embeddedLyrics, offset)
        }.getOrNull()
    }

    private fun readTags(song: Song): AudioTags {
        if (!song.isNas) {
            return TagReader.read(
                ContentRandomReader(getApplication<Application>(), Uri.parse(song.uri))
            )
        }
        val (sourceId, path) = parseNasUri(song.uri) ?: return AudioTags()
        val browser = container.nasRepository.browserFor(sourceId)
        val access = browser.open(path)
        try {
            return TagReader.read(NasRandomAccessReader(access, song.sizeBytes))
        } finally {
            runCatching { access.close() }
            runCatching { browser.close() }
        }
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0) = container.playbackController.playSongs(songs, startIndex)
    fun playSong(song: Song) = container.playbackController.playSong(song)
    fun addToQueue(songs: List<Song>) = container.playbackController.addToQueue(songs)
    fun playPause() = container.playbackController.playPause()
    fun next() = container.playbackController.next()
    fun previous() = container.playbackController.previous()
    fun seekTo(ms: Long) = container.playbackController.seekTo(ms)
    fun cycleRepeat() = container.playbackController.cycleRepeatMode()
    fun toggleShuffle() = container.playbackController.toggleShuffle()
    fun retry() = container.playbackController.retry()
    fun clearError() = container.playbackController.clearError()

    // ---------- 在线封面 ----------
    private fun setCover(songId: String, uri: String) {
        _coverOverrides.value = _coverOverrides.value + (songId to uri)
    }

    /** 本地没封面时后台匹配在线封面；失败静默，播放页显示动态占位。 */
    private fun maybeFetchCover(song: Song?) {
        if (song == null || !settingsState.value.autoDownloadCover) return
        container.coverFetcher.cached(song.id)?.let {
            setCover(song.id, it)
            return
        }
        if (!song.artworkUri.isNullOrBlank() && !song.artworkUri.startsWith("file://")) return
        if (!coverAttempted.add(song.id) || !coverInFlight.add(song.id)) return
        viewModelScope.launch {
            try {
                val uri = runCatching { container.coverFetcher.fetch(song) }.getOrNull()
                if (uri != null) {
                    container.musicRepository.setArtworkUri(song.id, uri)
                    setCover(song.id, uri)
                }
            } finally {
                coverInFlight.remove(song.id)
            }
        }
    }

    /** 菜单里的「重新匹配封面」：忽略缓存重新下载。 */
    fun refreshCover(song: Song) {
        viewModelScope.launch {
            container.coverFetcher.forget(song.id)
            coverAttempted.remove(song.id)
            val uri = runCatching { container.coverFetcher.fetch(song, force = true) }.getOrNull()
            if (uri != null) {
                container.musicRepository.setArtworkUri(song.id, uri)
                setCover(song.id, uri)
                _message.emit("封面已更新")
            } else {
                container.musicRepository.setArtworkUri(song.id, null)
                _coverOverrides.value = _coverOverrides.value - song.id
                _message.emit("没有找到匹配的封面，将显示动态背景")
            }
        }
    }

    // ---------- 在线歌词 ----------
    private fun maybeAutoFetch(song: Song?) {
        if (song == null || _lyrics.value != null) return
        if (!settingsState.value.autoDownloadLyrics) return
        if (!autoFetchAttempted.add(song.id)) return
        viewModelScope.launch { downloadBest(song, silent = true) }
    }

    /** 手动下载：列出候选供用户挑选。 */
    fun searchLyrics(song: Song) {
        viewModelScope.launch {
            _lyricsBusy.value = true
            val list = runCatching { searchCandidates(song) }
                .getOrElse {
                    _message.emit("歌词搜索失败：${friendlyError(it)}")
                    _lyricsBusy.value = false
                    return@launch
                }
            _lyricCandidates.value = list
            _lyricsBusy.value = false
            if (list.isEmpty()) _message.emit("没有找到歌词，可换歌词源或检查网络")
        }
    }

    fun dismissLyricsCandidates() { _lyricCandidates.value = emptyList() }

    fun autoDownloadLyrics(song: Song) {
        viewModelScope.launch { downloadBest(song, silent = false) }
    }

    fun selectLyrics(song: Song, candidate: LyricCandidate) {
        viewModelScope.launch { applyCandidate(song, candidate, silent = false) }
    }

    fun clearLyrics(song: Song) {
        viewModelScope.launch {
            container.lyricsStore.delete(song.id)
            container.musicRepository.setLyricsUri(song.id, null)
            autoFetchAttempted.remove(song.id)
            _lyricsVersion.value++
            _message.emit("已删除下载的歌词")
        }
    }

    private suspend fun searchCandidates(song: Song): List<LyricCandidate> =
        container.lyricsFetcher.search(
            song.title,
            song.artist,
            (song.durationMs / 1000L).toInt(),
            LyricsSource.fromKey(settingsState.value.lyricsSource)
        )

    /** 自动下载走竞速搜索：拿到足够匹配就返回，慢源不再拖时间。 */
    private suspend fun fastCandidates(song: Song): List<LyricCandidate> =
        container.lyricsFetcher.searchFast(
            song.title,
            song.artist,
            (song.durationMs / 1000L).toInt(),
            LyricsSource.fromKey(settingsState.value.lyricsSource)
        )

    private suspend fun downloadBest(song: Song, silent: Boolean) {
        if (!lyricsInFlight.add(song.id)) return
        _lyricsBusy.value = true
        try {
            val list = runCatching { fastCandidates(song) }
                .getOrElse {
                    if (!silent) _message.emit("歌词下载失败：${friendlyError(it)}")
                    return
                }
            val best = container.lyricsFetcher.bestMatch(
                list, song.title, song.artist, (song.durationMs / 1000L).toInt()
            )
            if (best == null) {
                if (!silent) _message.emit("没有足够匹配的歌词，请手动选择")
                return
            }
            // 网易云歌词已在搜索阶段预取，这里通常零网络请求
            val ready = runCatching {
                container.lyricsFetcher.prefetch(listOf(best)).firstOrNull() ?: best
            }.getOrDefault(best)
            applyCandidate(song, ready, silent)
        } finally {
            _lyricsBusy.value = false
            lyricsInFlight.remove(song.id)
        }
    }

    private suspend fun applyCandidate(song: Song, candidate: LyricCandidate, silent: Boolean) {
        val text = runCatching { container.lyricsFetcher.fetch(candidate) }
            .getOrElse {
                if (!silent) _message.emit("歌词获取失败：${friendlyError(it)}")
                return
            }
        if (text.isNullOrBlank()) {
            if (!silent) _message.emit("该结果没有可用歌词")
            return
        }
        val uri = container.lyricsStore.save(song.id, text)
        container.musicRepository.setLyricsUri(song.id, uri)
        autoFetchAttempted.add(song.id)
        _lyricCandidates.value = emptyList()
        _lyricsVersion.value++
        if (!silent) _message.emit("歌词已保存：${candidate.title}")
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        viewModelScope.launch {
            val next = settingsState.value.lyricsOffsetMs + deltaMs
            container.settings.setLyricsOffset(next)
            loadExtras(state.value.currentSong, next)
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            container.musicRepository.setFavorite(song.id, !song.isFavorite)
        }
    }

    fun cacheCurrentSong() {
        val song = state.value.currentSong ?: return
        viewModelScope.launch {
            if (!song.isNas) {
                _message.emit("本地歌曲无需缓存")
                return@launch
            }
            val ok = runCatching { container.nasCache.cacheSong(song) }
                .getOrElse { _message.emit("缓存失败：${friendlyError(it)}"); return@launch }
            _message.emit(if (ok) "已缓存当前歌曲，断网可继续播放" else "缓存失败")
        }
    }

    fun cacheQueue() {
        viewModelScope.launch {
            val count = runCatching { container.nasCache.cachePlaylist(state.value.queue) }
                .getOrElse { _message.emit("缓存失败：${friendlyError(it)}"); return@launch }
            _message.emit("已缓存 $count 首歌曲")
        }
    }

    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepRemainingMs.value = 0L
            return
        }
        sleepJob = viewModelScope.launch {
            var remaining = minutes * 60_000L
            while (remaining > 0) {
                _sleepRemainingMs.value = remaining
                delay(1000L)
                remaining -= 1000L
            }
            _sleepRemainingMs.value = 0L
            container.playbackController.playPause()
            _message.emit("睡眠定时器结束，已暂停播放")
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = 0L
    }
}
