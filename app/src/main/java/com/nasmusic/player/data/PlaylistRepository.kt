package com.nasmusic.player.data

import com.nasmusic.player.core.Playlist
import com.nasmusic.player.core.Song
import com.nasmusic.player.data.db.PlaylistDao
import com.nasmusic.player.data.db.PlaylistEntity
import com.nasmusic.player.data.db.PlaylistSongEntity
import com.nasmusic.player.data.db.toModel
import com.nasmusic.player.data.db.toSong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaylistRepository(private val dao: PlaylistDao) {

    val playlists: Flow<List<Playlist>> = dao.all().map { it.map { p -> p.toModel() } }

    fun songs(playlistId: String): Flow<List<Song>> =
        dao.songs(playlistId).map { it.map { e -> e.toSong() } }

    fun songCount(playlistId: String): Flow<Int> = dao.songCount(playlistId)

    suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        dao.insertPlaylist(PlaylistEntity(id = id, name = name.trim().ifBlank { "新建播放列表" }))
        return id
    }

    suspend fun rename(id: String, name: String) {
        if (name.isBlank()) return
        dao.rename(id, name.trim())
    }

    suspend fun delete(id: String) {
        dao.clearSongs(id)
        dao.deletePlaylist(id)
    }

    suspend fun addSongs(id: String, songIds: List<String>) {
        var position = dao.maxPosition(id) + 1
        songIds.distinct().forEach { songId ->
            dao.addSong(PlaylistSongEntity(id, songId, position++))
        }
    }

    suspend fun removeSong(id: String, songId: String) = dao.removeSong(id, songId)
}
