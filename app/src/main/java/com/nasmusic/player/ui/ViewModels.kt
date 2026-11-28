package com.nasmusic.player.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.player.core.AppContainer
import com.nasmusic.player.core.ViperModules
import com.nasmusic.player.core.toEqString
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/**
 * 统一的「可播放歌曲」过滤口径：本地歌曲永远可见；网络歌曲只有在对应音源
 * 探测为连通时才显示。收藏 / 搜索 / 随机漫游等派生列表都必须套用同一函数，
 * 否则断连音源里的歌曲点开必然播放失败（歌曲 Tab 已按此口径过滤）。
 */
fun List<Song>.filterConnected(connectionStatus: Map<String, Boolean>): List<Song> =
    filter { !it.isNas || connectionStatus[it.sourceId] == true }

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

    /**
     * 各网络音源的连接状态：sourceId -> 是否连通。
     * 进入音乐列表/网络音源页面时探测。断连会静默删除该音源的曲库歌曲（不提示）；
     * 再次连上则自动重新扫描恢复，整个清空/恢复过程对用户无提示打扰。
     */
    private val _nasConnectionStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val nasConnectionStatus: StateFlow<Map<String, Boolean>> = _nasConnectionStatus.asStateFlow()
    private var connectionCheckJob: Job? = null
    // 上一次探测到的连接状态，用于检测「连通→断开」「断开→连通」的翻转；
    // 只有状态真正变化时才删除/重扫，避免每次进入列表都触发全量扫描。
    private var previousNasConnection: Map<String, Boolean> = emptyMap()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val searchResults: StateFlow<List<Song>> = combine(
        _query.flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else container.musicRepository.search(q)
        },
        _nasConnectionStatus
    ) { results, status -> results.filterConnected(status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scan = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scan.asStateFlow()

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    // 登录/测试连接的 busy 状态由 ViewModel 持有：页面退出不会取消协程，也不会因状态无处复位而卡住按钮
    private val _fnosLoginBusy = MutableStateFlow(false)
    val fnosLoginBusy: StateFlow<Boolean> = _fnosLoginBusy.asStateFlow()
    private val _onlineTestBusy = MutableStateFlow(false)
    val onlineTestBusy: StateFlow<Boolean> = _onlineTestBusy.asStateFlow()
    private val _onlineSaveBusy = MutableStateFlow(false)
    val onlineSaveBusy: StateFlow<Boolean> = _onlineSaveBusy.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var scanJob: Job? = null

    // 扫描入库后的歌词/封面预下载：与扫描任务独立，扫描完成才启动，
    // 重新扫描/清理缓存时可取消。避让播放：缓冲不足时暂停下载。
    private var prefetchJob: Job? = null
    private val _prefetch = MutableStateFlow<Pair<Int, Int>?>(null)
    /** 预下载进度 (已完成, 总数)；null 表示当前没有预下载任务。 */
    val prefetch: StateFlow<Pair<Int, Int>?> = _prefetch.asStateFlow()

    init { refreshPermissions() }

    /**
     * 扫描完成后批量补齐缺失的歌词/封面。
     * 遵循用户的自动下载开关；进度通过 prefetch 暴露；给播放让路（缓冲 < 8 秒暂停）。
     */
    private fun kickoffMetadataPrefetch() {
        val settings = settingsState.value
        if (!settings.autoDownloadLyrics && !settings.autoDownloadCover) return
        prefetchJob?.cancel()
        _prefetch.value = 0 to 0
        prefetchJob = viewModelScope.launch {
            val prefetcher = container.newMetadataPrefetcher(viewModelScope) { done, total ->
                _prefetch.value = if (total == 0) null else done to total
            }
            prefetcher.start(
                enableLyrics = settings.autoDownloadLyrics,
                enableCover = settings.autoDownloadCover,
                shouldYield = {
                    val st = player.state.value
                    // 没在播放，或正在播本地曲目：无需让路；
                    // 正在播网络曲目且处于缓冲中：暂停预下载，把带宽留给音频
                    !st.isPlaying || st.currentSong?.isNas != true || !st.buffering
                }
                // 预下载写的是数据库；当前播放曲目若正好被补齐，切歌/重新进入时
                // loadExtras 会重新读库拿到最新 URI，无需跨 ViewModel 主动刷新
            )
        }
    }

    fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        _prefetch.value = null
        emit("已停止后台下载")
    }

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
            kickoffMetadataPrefetch()
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            val count = runCatching { container.musicRepository.scanFolder(treeUri) }
                .getOrElse { emit("添加失败：${friendlyError(it)}"); return@launch }
            emit("已从文件夹导入 $count 首歌曲")
            kickoffMetadataPrefetch()
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
            val favorite = runCatching { container.musicRepository.toggleFavorite(song.id) }
                .getOrElse { emit("操作失败：${friendlyError(it)}"); return@launch }
            // 列表与播放页里的 Song 都是数据库快照，写库后不会自己变；
            // 通知播放器重读当前曲目/队列，心形图标才会立刻跟着变。
            container.playbackController.reloadCurrentSong()
            emit(if (favorite) "已加入收藏" else "已取消收藏")
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

    /**
     * 飞牛登录在 ViewModel 作用域执行：退出登录页不会取消登录，busy 由这里统一复位。
     * 成功时顺带保存音源并启动曲库同步，再通过回调通知页面收尾。
     */
    fun loginFnosAndWait(
        sourceId: String,
        host: String,
        username: String,
        password: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            _fnosLoginBusy.value = true
            try {
                val result = loginFnos(sourceId, host, username, password)
                result.onSuccess { baseUrl ->
                    val uri = Uri.parse(baseUrl)
                    val source = NasSource(
                        id = sourceId,
                        name = "飞牛音乐",
                        type = SourceType.FNOS,
                        host = uri.host.orEmpty(),
                        port = if (uri.port > 0) uri.port else 0,
                        baseUrl = baseUrl,
                        username = username.trim(),
                        lastScanAt = nasSources.value.find { it.id == sourceId }?.lastScanAt ?: 0L
                    )
                    addFnosSource(source, password)
                }
                withContext(Dispatchers.Main) { onResult(result) }
            } finally {
                _fnosLoginBusy.value = false
            }
        }
    }

    /** 在线音乐源（Subsonic 兼容）连接测试，见 ui/OnlineSourceLoginScreen.kt。 */
    suspend fun testOnlineSource(
        baseUrl: String,
        username: String,
        password: String
    ): Result<String> = withContext(Dispatchers.IO) {
        container.onlineApi.testConnection(baseUrl, username, password)
    }

    /** 测试连接在 VM 作用域执行，页面退出不取消，busy 必定复位。 */
    fun testOnlineSourceAsync(
        baseUrl: String,
        username: String,
        password: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            _onlineTestBusy.value = true
            try {
                val result = testOnlineSource(baseUrl, username, password)
                withContext(Dispatchers.Main) { onResult(result) }
            } finally {
                _onlineTestBusy.value = false
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

    private suspend fun persistNasSource(source: NasSource, password: String?) {
        container.nasRepository.save(source, password)
    }

    fun saveNasSource(source: NasSource, password: String?) {
        viewModelScope.launch {
            runCatching { persistNasSource(source, password) }
                .onFailure { emit("保存失败：${friendlyError(it)}"); return@launch }
            emit("网络音乐源已保存")
        }
    }

    /** 在线音乐源保存并立即扫描入库（与「保存网络源」共用同一持久化路径）。 */
    fun saveOnlineSourceAndScan(
        source: NasSource,
        password: String,
        onResult: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            _onlineSaveBusy.value = true
            try {
                val saved = runCatching { persistNasSource(source, password) }
                if (saved.isFailure) {
                    val e = saved.exceptionOrNull()!!
                    emit("保存失败：${friendlyError(e)}")
                    withContext(Dispatchers.Main) { onResult(Result.failure(e)) }
                    return@launch
                }
                scanNasSource(source)
                emit("${source.name} 已添加，正在同步曲库…")
                withContext(Dispatchers.Main) { onResult(Result.success(Unit)) }
            } finally {
                _onlineSaveBusy.value = false
            }
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
                // 协程取消（用户点「取消扫描」、或被下一次扫描的 scanJob.cancel() 顶替）不是失败。
                // runCatching 会连 CancellationException 一起捕获，必须单独排除，
                // 否则会把正常的取消误报成「扫描失败：StandaloneCoroutine was cancelled」。
                if (e is CancellationException) return@launch
                // 保留异常类型和原始消息，避免空消息异常只显示「扫描失败」而无法定位
                val detail = friendlyError(e, "")
                    .ifBlank { e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName }
                _scan.value = ScanProgress(error = "扫描失败：$detail")
                emit("扫描失败：$detail")
                return@launch
            }
            _scan.value = ScanProgress(message = "扫描完成，共 $count 首")
            emit("${source.name} 扫描完成，共 $count 首")
            kickoffMetadataPrefetch()
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

    // ---------- 音效（蝰蛇 / 车载）----------
    /** 切换音效模式后，若当前在车机宽屏且开启自动切换，车载模式会被优先采用。 */
    fun setSoundMode(value: String) = viewModelScope.launch { container.settings.setSoundMode(value) }
    fun setViperPreset(value: String) = viewModelScope.launch { container.settings.setViperPreset(value) }
    fun setCarPreset(value: String) = viewModelScope.launch { container.settings.setCarPreset(value) }
    fun setSoundCustomEq(values: IntArray) = viewModelScope.launch {
        container.settings.setSoundCustomEq(values.toEqString())
    }
    fun setSoundBass(value: Int) = viewModelScope.launch { container.settings.setSoundBass(value) }
    fun setSoundClarity(value: Int) = viewModelScope.launch { container.settings.setSoundClarity(value) }
    fun setSoundSurround(value: Int) = viewModelScope.launch { container.settings.setSoundSurround(value) }
    fun setSoundReverb(value: Int) = viewModelScope.launch { container.settings.setSoundReverb(value) }
    fun setSoundLoudness(value: Int) = viewModelScope.launch { container.settings.setSoundLoudness(value) }
    fun setViperModule(module: Int, enabled: Boolean) = viewModelScope.launch {
        val current = container.settings.snapshot().viperModules
        container.settings.setViperModules(if (enabled) current or module else current and module.inv())
    }
    fun setCarSeat(value: String) = viewModelScope.launch { container.settings.setCarSeat(value) }
    fun setCarNoise(value: String) = viewModelScope.launch { container.settings.setCarNoise(value) }
    fun setCarDucking(value: Boolean) = viewModelScope.launch { container.settings.setCarDucking(value) }
    fun setCarAutoEnable(value: Boolean) = viewModelScope.launch { container.settings.setCarAutoEnable(value) }
    /** 恢复音效全部参数为默认直通。 */
    fun resetSoundEffect() = viewModelScope.launch {
        container.settings.resetSoundEffect()
        emit("音效已恢复默认")
    }
    /** 检测设备是否安装了 ViPER4Android（提示用户避免双重叠加）。 */
    fun isViperInstalled(): Boolean =
        com.nasmusic.player.playback.AudioEffectChain(context).isViperInstalled()

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
            // 保留正在播放那首的缓存文件，否则清完缓存的瞬间播放就断了
            val keepId = player.state.value.currentSong?.id
            withContext(Dispatchers.IO) { container.nasCache.clear(keepId) }
            // 队列里其它曲目原先指向已被删除的 file:// 文件，重建成在线 URI，切换时才不会报错
            player.refreshQueueUris()
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

    /**
     * 进入网络音源歌曲列表时调用：逐个探测已保存音源是否可连通，并按连接状态维护曲库。
     * - 连通 → 该音源歌曲保留/显示；
     * - 连通→断开（状态翻转）→ 静默删除该音源在曲库中的歌曲、清歌单引用与播放队列，不弹任何提示；
     * - 断开→连通（状态翻转）→ 自动重新扫描该音源，歌曲重新回到列表，同样无提示。
     * 状态未变化时不做任何写库/扫描，避免每次进入列表都全量扫描。
     */
    fun refreshNasConnections() {
        connectionCheckJob?.cancel()
        connectionCheckJob = viewModelScope.launch {
            val snapshot = nasSources.value.filter { it.type != SourceType.LOCAL }
            if (snapshot.isEmpty()) {
                _nasConnectionStatus.value = emptyMap()
                previousNasConnection = emptyMap()
                return@launch
            }
            // 并发探测，避免某个音源网络超时拖住整个列表
            val probes = snapshot.map { source ->
                async(Dispatchers.IO) {
                    val connected = runCatching {
                        // 用最短路径做一次真实目录读取，验证网络 + 凭据都可用。
                        // FNOS 的 listDir 只在根目录真正请求接口，其它路径会直接返回空，
                        // 因此统一探测根目录，避免「假连通」。
                        val probePath = if (source.type == SourceType.FNOS ||
                            source.type == SourceType.ONLINE
                        ) "/" else source.rootPath
                        browseNas(source.id, probePath)
                        true
                    }.getOrDefault(false)
                    source to connected
                }
            }
            val results = probes.awaitAll()
            val status = results.associate { (source, connected) -> source.id to connected }
            _nasConnectionStatus.value = status

            // 只处理状态翻转，避免重复清理/重扫
            val prev = previousNasConnection
            for ((source, connected) in results) {
                // 首次探测（prev 中无记录）只建立基线，不进入删除/重扫分支：
                // 冷启动或新加源时网络常未就绪，若此时探测失败就删库会造成误删。
                if (!prev.containsKey(source.id)) continue
                val wasConnected = prev[source.id] == true
                if (wasConnected && !connected) {
                    // 刚刚断开：静默清空该音源曲库，不提示
                    container.nasRepository.clearSongsFor(source.id)
                    container.playbackController.removeBySourceId(source.id)
                    FnosSession.remove(source.id)
                } else if (!wasConnected && connected) {
                    // 重新连上：静默重新扫描该音源恢复列表，不提示
                    runCatching { container.nasScanner.scan(source) { } }
                }
            }
            previousNasConnection = status
        }
    }

    /** 某音源当前是否连通；未探测过时默认不显示，避免断网仍展示旧列表。 */
    fun isNasConnected(sourceId: String): Boolean =
        _nasConnectionStatus.value[sourceId] == true
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
    // 网络曲目切歌时延后加载的附加信息任务（歌词/封面/标签），新切歌时取消上一首的
    private var extrasJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                state.map { it.currentSong }.distinctUntilChangedBy { it?.id },
                settingsState.map { it.lyricsOffsetMs }.distinctUntilChangedBy { it },
                _lyricsVersion
            ) { song, offset, _ -> song to offset }
                .collect { (song, offset) ->
                    // 歌词/封面即时加载：本地歌词文件与已缓存封面都是磁盘读取，不抢带宽，
                    // 必须立刻显示，否则切歌后歌词/封面要等好几秒才出现。
                    loadExtras(song, offset)
                    // 网络曲目的在线匹配（搜索/下载）才延后，给音频首块让路；本地曲目即时。
                    extrasJob?.cancel()
                    if (song?.isNas == true) {
                        extrasJob = viewModelScope.launch {
                            delay(600)
                            maybeFetchCover(song)
                            maybeAutoFetch(song)
                        }
                    } else {
                        maybeFetchCover(song)
                        maybeAutoFetch(song)
                    }
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
        // 网络曲目不读内嵌标签（那要发 Range 请求抢带宽、拖慢切歌），
        // 只用同名 .lrc / 已下载的歌词文件；内嵌歌词由后续 extrasJob 补。
        val tags = if (current.isNas) null else runCatching { readTags(current) }.getOrNull()
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
    /** 「下一首播放」：在当前项之后插入，而不是追加到队尾（内核组需实现 PlaybackController.playNext）。 */
    fun playNext(songs: List<Song>) = container.playbackController.playNext(songs)
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
        // 歌词落盘后顺带把封面也存下来：用户主动下载时不受「自动下载封面」开关限制，
        // 后台自动下载则遵循开关；封面失败完全静默，不影响歌词已经保存成功。
        downloadCoverAlongside(song, candidate, respectSetting = silent)
    }

    /**
     * 下载歌词时同步保存封面。
     * - 已有本地封面缓存：直接复用，不重复走网络；
     * - 候选自带专辑图（网易云）：直接下载该地址，少一次搜索且匹配更准；
     * - 没有地址：回退到封面双源匹配，并强制重试绕过失败冷却。
     */
    private fun downloadCoverAlongside(song: Song, candidate: LyricCandidate, respectSetting: Boolean) {
        if (respectSetting && !settingsState.value.autoDownloadCover) return
        container.coverFetcher.cached(song.id)?.let {
            setCover(song.id, it)
            return
        }
        viewModelScope.launch {
            val direct = candidate.coverUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { container.coverFetcher.fetchFromUrl(song, it) }.getOrNull() }
            val uri = direct ?: runCatching { container.coverFetcher.fetch(song, force = true) }.getOrNull()
            if (uri != null) {
                container.musicRepository.setArtworkUri(song.id, uri)
                setCover(song.id, uri)
                if (!respectSetting) _message.emit("封面已随歌词一起保存")
            }
        }
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
            val favorite = runCatching { container.musicRepository.toggleFavorite(song.id) }
                .getOrElse { _message.emit("操作失败：${friendlyError(it)}"); return@launch }
            // song 来自 state.currentSong 快照，不主动重读的话心形图标会一直保持旧状态，
            // 只有切歌/重启后才"突然变对"。这里写库后立刻刷新当前曲目与队列。
            container.playbackController.reloadCurrentSong()
            _message.emit(if (favorite) "已加入收藏" else "已取消收藏")
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
            // playPause() 是 toggle 语义：播放中→暂停，暂停中→播放。若倒计时内用户
            // 已手动暂停，到点直接 toggle 会反过来「开始播放」。这里用 state 的
            // isPlaying 先判定：只有确实在播放时才切换（此时切换即暂停），已暂停则
            // 什么都不做，从而保证「到点只暂停、绝不开始播放」。
            // 注：PlaybackController 未对外暴露 pause() 或 MediaController，state
            // 是本类唯一可用的播放状态来源，故采用该守卫方式。
            if (state.value.isPlaying) {
                container.playbackController.playPause()
                _message.emit("睡眠定时器结束，已暂停播放")
            } else {
                // 已暂停/未播放时不做任何操作，文案也不要谎报"已暂停"
                _message.emit("睡眠定时器结束")
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = 0L
    }
}
