package com.nasmusic.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.nasmusic.player.core.LibraryFilterState
import com.nasmusic.player.core.SettingsStore
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.SourceType
import com.nasmusic.player.core.isAllowedBy
import com.nasmusic.player.core.isAudioFile
import com.nasmusic.player.core.isExcludedFolder
import com.nasmusic.player.core.libraryFilter
import com.nasmusic.player.core.mimeForName
import com.nasmusic.player.data.db.SongDao
import com.nasmusic.player.data.db.SongEntity
import com.nasmusic.player.data.db.toEntity
import com.nasmusic.player.data.db.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 本地音乐库：MediaStore 查询 + Room 缓存。
 * 所有对外 Flow 统一叠加「时长阈值 + 排除文件夹」过滤，扫描时同步下推，保证库内数据即时一致。
 */
class MusicRepository(
    private val context: Context,
    private val dao: SongDao,
    private val settings: SettingsStore
) {

    private val resolver get() = context.contentResolver

    private val filterFlow: Flow<LibraryFilterState> = settings.settings
        .map { it.libraryFilter() }
        .distinctUntilChanged()

    // 时长阈值由 SQL 的 allFiltered 承担；文件夹排除规则仍需归一化后在内存判定。
    // filterFlow 变化时用 flatMapLatest 换掉旧的查询流，避免残留旧条件的发射。
    private val rowsFlow: Flow<List<SongEntity>> =
        filterFlow.map { it.minDurationSec.coerceAtLeast(0) * 1000L }
            .distinctUntilChanged()
            .flatMapLatest { dao.allFiltered(it) }

    val allSongs: Flow<List<Song>> = combine(rowsFlow, filterFlow) { rows, filter ->
        rows.map { it.toSong() }.filter { it.isAllowedBy(filter) }
    }

    val favorites: Flow<List<Song>> = allSongs.map { list -> list.filter { it.isFavorite } }
    val albums: Flow<List<String>> = allSongs.map { list ->
        list.map { it.album }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase(Locale.ROOT) }
    }
    val artists: Flow<List<String>> = allSongs.map { list ->
        list.map { it.artist }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase(Locale.ROOT) }
    }
    val folders: Flow<List<String>> = allSongs.map { list ->
        list.map { it.folder }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase(Locale.ROOT) }
    }
    val count: Flow<Int> = allSongs.map { it.size }

    fun search(query: String): Flow<List<Song>> = allSongs.map { list ->
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else list.filter { s ->
            s.title.contains(q, true) || s.artist.contains(q, true) ||
                s.album.contains(q, true) || s.folder.contains(q, true) ||
                s.uri.contains(q, true)
        }
    }

    fun bySource(type: SourceType): Flow<List<Song>> =
        allSongs.map { list -> list.filter { it.sourceType == type } }

    fun byAlbum(album: String): Flow<List<Song>> = allSongs.map { list -> list.filter { it.album == album } }
    fun byArtist(artist: String): Flow<List<Song>> = allSongs.map { list -> list.filter { it.artist == artist } }
    fun byFolder(folder: String): Flow<List<Song>> = allSongs.map { list -> list.filter { it.folder == folder } }

    suspend fun getById(id: String): Song? = withContext(Dispatchers.IO) {
        val row = dao.byId(id) ?: return@withContext null
        row.toSong().takeIf { it.isAllowedBy(settings.snapshot().libraryFilter()) }
    }

    suspend fun getByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val filter = settings.snapshot().libraryFilter()
        // 分批查询：一次性把上千个 id 塞进 IN (:ids) 会超过 SQLite 变量上限
        // （旧设备为 999），导致播放记忆恢复整段失败。按 500 一批后合并再按原顺序映射。
        val map = HashMap<String, SongEntity>()
        for (batch in ids.chunked(500)) {
            dao.byIds(batch).forEach { map[it.id] = it }
        }
        ids.mapNotNull { map[it]?.toSong()?.takeIf { song -> song.isAllowedBy(filter) } }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) =
        withContext(Dispatchers.IO) { dao.setFavorite(id, favorite) }

    /**
     * 原子取反收藏，返回取反后的状态。
     *
     * 以数据库当前值为基准，而不是 UI 传进来的 Song 快照：列表/播放页里的 Song 可能是
     * 几秒前读出来的旧对象，连续点两次会算成同一个目标值（收藏→收藏），表现就是"点了没反应"。
     */
    suspend fun toggleFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        val next = !(dao.byId(id)?.isFavorite ?: false)
        dao.setFavorite(id, next)
        next
    }

    suspend fun updateTags(id: String, title: String, artist: String, album: String) =
        withContext(Dispatchers.IO) { dao.updateTags(id, title, artist, album) }

    suspend fun setLyricsUri(id: String, uri: String?) =
        withContext(Dispatchers.IO) { dao.setLyricsUri(id, uri) }

    suspend fun setArtworkUri(id: String, uri: String?) =
        withContext(Dispatchers.IO) { dao.setArtworkUri(id, uri) }

    suspend fun clearDownloadedLyrics() =
        withContext(Dispatchers.IO) { dao.clearDownloadedLyrics() }

    suspend fun saveSongs(songs: List<Song>) =
        withContext(Dispatchers.IO) { dao.upsert(songs.map { it.toEntity() }) }

    /** 按当前规则立即清库（只删本应用索引，不动磁盘文件），返回移除数量。 */
    suspend fun purgeFiltered(): Int = withContext(Dispatchers.IO) {
        val filter = settings.snapshot().libraryFilter()
        val ids = dao.allEntities().filterNot { it.toSong().isAllowedBy(filter) }.map { it.id }
        // SQLite 变量上限在旧设备仅 999，整库 id 必须分批删除
        ids.chunked(500).forEach { dao.deleteByIds(it) }
        ids.size
    }

    /** 排除某个文件夹时，把该文件夹（含子目录）下的歌曲从索引中删除。 */
    suspend fun deleteFolder(folder: String): Int = withContext(Dispatchers.IO) {
        val filter = settings.snapshot().libraryFilter()
        val rules = setOf(folder)
        val ids = dao.allEntities()
            .filter { isExcludedFolder(it.folder, rules, filter.matchMode) }
            .map { it.id }
        ids.chunked(500).forEach { dao.deleteByIds(it) }
        ids.size
    }

    /** 通过 MediaStore 全量刷新本地索引；不直接遍历 /storage/emulated/0。 */
    suspend fun scanLocal(): Int = withContext(Dispatchers.IO) {
        val filter = settings.snapshot().libraryFilter()
        val thresholdMs = filter.minDurationSec.coerceAtLeast(0) * 1000L
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            projection += MediaStore.Audio.Media.DATA
        }

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val found = mutableListOf<SongEntity>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            selection,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.str(MediaStore.Audio.Media.DISPLAY_NAME)
                val mime = cursor.str(MediaStore.Audio.Media.MIME_TYPE)
                if (!isAudioFile(name) && !mime.startsWith("audio/")) continue

                val duration = cursor.lng(MediaStore.Audio.Media.DURATION)
                val relative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.str(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    cursor.str(MediaStore.Audio.Media.DATA).substringBeforeLast('/', "")
                }
                if (thresholdMs > 0 && duration > 0 && duration < thresholdMs) continue
                if (isExcludedFolder(relative, filter.excludedFolders, filter.matchMode)) continue

                val mediaId = cursor.lng(MediaStore.Audio.Media._ID)
                val albumId = cursor.lng(MediaStore.Audio.Media.ALBUM_ID)
                val art = if (albumId > 0) {
                    ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId
                    ).toString()
                } else null

                found += SongEntity(
                    id = "local:$mediaId",
                    title = cursor.str(MediaStore.Audio.Media.TITLE)
                        .ifBlank { name.substringBeforeLast('.', name) },
                    uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId
                    ).toString(),
                    artist = cursor.str(MediaStore.Audio.Media.ARTIST).cleanUnknown(),
                    album = cursor.str(MediaStore.Audio.Media.ALBUM).cleanUnknown(),
                    durationMs = duration,
                    sourceType = SourceType.LOCAL.name,
                    folder = relative.trimEnd('/'),
                    artworkUri = art,
                    sizeBytes = cursor.lng(MediaStore.Audio.Media.SIZE),
                    lastModified = cursor.lng(MediaStore.Audio.Media.DATE_MODIFIED) * 1000L,
                    mimeType = mime.ifBlank { mimeForName(name) }
                )
            }
        }

        // 无结果时（例如权限被拒绝）保留已有索引，避免误清空音乐库
        if (found.isNotEmpty()) {
            // 未变化的曲目不重写 Room：几千首歌全量 REPLACE 会让「刷新」卡顿
            val existing = dao.allEntities().associateBy { it.id }
            val changed = found.filter { new ->
                val old = existing[new.id]
                old == null || old.lastModified != new.lastModified ||
                    old.sizeBytes != new.sizeBytes || old.title != new.title ||
                    old.artist != new.artist || old.album != new.album
            }
            if (changed.isNotEmpty()) dao.upsert(changed)
            // 仅清理由 MediaStore 建立、但设备上已删除的记录；不影响手动添加的文件夹曲目
            found.map { it.id }.chunked(500).forEach { dao.deleteStaleLocalMedia(it) }
        }
        purgeFiltered()
        found.size
    }

    /** 用户手动添加的音乐文件夹（SAF 目录树），兼容分区存储。 */
    suspend fun scanFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val filter = settings.snapshot().libraryFilter()
        val thresholdMs = filter.minDurationSec.coerceAtLeast(0) * 1000L
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
        val folderName = root.name ?: "手动添加的文件夹"
        val found = mutableListOf<SongEntity>()

        fun walk(dir: DocumentFile) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    walk(file)
                } else {
                    val name = file.name ?: continue
                    if (!isAudioFile(name) || isExcludedFolder(folderName, filter.excludedFolders, filter.matchMode)) continue
                    val uri = file.uri.toString()
                    val durationMs = if (thresholdMs > 0) {
                        runCatching {
                            val reader = ContentRandomReader(context, file.uri)
                            try { TagReader.read(reader).durationMs } finally { reader.close() }
                        }.getOrDefault(0L)
                    } else 0L
                    if (thresholdMs > 0 && durationMs > 0 && durationMs < thresholdMs) continue
                    found += SongEntity(
                        id = "doc:$uri",
                        title = name.substringBeforeLast('.', name),
                        uri = uri,
                        artist = "未知歌手",
                        album = folderName,
                        durationMs = durationMs,
                        sourceType = SourceType.LOCAL.name,
                        folder = folderName,
                        sizeBytes = file.length(),
                        lastModified = file.lastModified(),
                        mimeType = file.type?.takeIf { it.startsWith("audio/") } ?: mimeForName(name)
                    )
                }
            }
        }
        walk(root)
        if (found.isNotEmpty()) {
            dao.upsert(found)
            // 按文件夹限定清理范围：只删除当前文件夹内已失效的条目，
            // 否则添加第二个文件夹会把第一个文件夹导入的歌曲一起删掉。
            found.map { it.id }.chunked(500).forEach { dao.deleteStaleFolderIn(folderName, it) }
        }
        purgeFiltered()
        found.size
    }

    private fun Cursor.str(column: String, default: String = ""): String {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) ?: default else default
    }

    private fun Cursor.lng(column: String, default: Long = 0L): Long {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getLong(i) else default
    }

    private fun String.cleanUnknown(): String =
        if (isBlank() || this == "<unknown>" || equals("unknown", true)) "" else this
}
