package com.nasmusic.player.data

import android.content.Context
import android.net.Uri
import com.nasmusic.player.core.NasSource
import com.nasmusic.player.core.Song
import com.nasmusic.player.core.parseNasUri
import com.nasmusic.player.data.db.NasSourceDao
import com.nasmusic.player.data.db.SongDao
import com.nasmusic.player.data.db.toEntity
import com.nasmusic.player.data.db.toModel
import com.nasmusic.player.data.db.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class NasRepository(
    private val context: Context,
    private val sourceDao: NasSourceDao,
    private val songDao: SongDao,
    private val crypto: NasCredentialsCrypto
) {
    val sources: Flow<List<NasSource>> = sourceDao.all().map { it.map { e -> e.toModel() } }

    suspend fun get(id: String): NasSource? = withContext(Dispatchers.IO) {
        sourceDao.get(id)?.toModel()
    }

    /** 保存源；密码以 Keystore 加密后的密文入库，明文只在内存中出现。 */
    suspend fun save(source: NasSource, plainPassword: String?) = withContext(Dispatchers.IO) {
        val enc = if (plainPassword != null) crypto.encrypt(plainPassword) else source.passwordEnc
        sourceDao.upsert(source.copy(passwordEnc = enc).toEntity())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        sourceDao.delete(id)
        songDao.deleteByNasSource(id)
    }

    suspend fun updateScanTime(id: String) = withContext(Dispatchers.IO) {
        sourceDao.updateScanTime(id, System.currentTimeMillis())
    }

    suspend fun songsOf(id: String): List<Song> = withContext(Dispatchers.IO) {
        songDao.byNasSource(id).map { it.toSong() }
    }

    suspend fun upsertSongs(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isNotEmpty()) songDao.upsert(songs.map { it.toEntity() })
    }

    suspend fun configFor(id: String): NasConfig? = withContext(Dispatchers.IO) {
        sourceDao.get(id)?.toModel()?.toConfig()
    }

    /** 供 Media3 DataSource / 缓存任务在非协程上下文中同步获取浏览器。 */
    fun browserFor(id: String): NasBrowser {
        val config = runBlocking(Dispatchers.IO) { configFor(id) }
            ?: throw IOException("网络音乐源不存在或已被删除")
        return NasClients.create(config)
    }

    /** 统一读取 LRC / 目录内文本文件：content://、file://、nas:// 三种来源。 */
    suspend fun readText(uriString: String): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "nas" -> {
                val (sourceId, path) = parseNasUri(uriString) ?: return@withContext null
                val config = sourceDao.get(sourceId)?.toModel()?.toConfig()
                    ?: return@withContext null
                val browser = NasClients.create(config)
                val access = browser.open(path)
                try {
                    access.asInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    runCatching { access.close() }
                    runCatching { browser.close() }
                }
            }
            "content" -> runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            "file" -> runCatching {
                uri.path?.let { File(it).readText(Charsets.UTF_8) }
            }.getOrNull()
            else -> null
        }
    }

    private fun NasSource.toConfig(): NasConfig = NasConfig(
        id = id,
        type = type,
        host = host,
        port = port,
        share = share,
        rootPath = rootPath,
        baseUrl = baseUrl,
        username = username,
        password = crypto.decrypt(passwordEnc),
        anonymous = anonymous
    )
}
