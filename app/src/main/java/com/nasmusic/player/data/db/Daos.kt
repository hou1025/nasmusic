package com.nasmusic.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun all(): Flow<List<SongEntity>>

    @Query(
        "SELECT * FROM songs WHERE title LIKE '%' || :q || '%' " +
            "OR artist LIKE '%' || :q || '%' OR album LIKE '%' || :q || '%' " +
            "OR folder LIKE '%' || :q || '%' OR uri LIKE '%' || :q || '%' " +
            "ORDER BY title COLLATE NOCASE ASC"
    )
    fun search(q: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE sourceType = :type ORDER BY title COLLATE NOCASE ASC")
    fun bySourceType(type: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY title COLLATE NOCASE ASC")
    fun byAlbum(album: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY title COLLATE NOCASE ASC")
    fun byArtist(artist: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE folder = :folder ORDER BY title COLLATE NOCASE ASC")
    fun byFolder(folder: String): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY title COLLATE NOCASE ASC")
    fun favorites(): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT album FROM songs WHERE album <> '' ORDER BY album COLLATE NOCASE ASC")
    fun albums(): Flow<List<String>>

    @Query("SELECT DISTINCT artist FROM songs WHERE artist <> '' ORDER BY artist COLLATE NOCASE ASC")
    fun artists(): Flow<List<String>>

    @Query("SELECT DISTINCT folder FROM songs WHERE folder <> '' ORDER BY folder COLLATE NOCASE ASC")
    fun folders(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs")
    suspend fun allEntities(): List<SongEntity>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE songs SET lyricsUri = :uri WHERE id = :id")
    suspend fun setLyricsUri(id: String, uri: String?)

    @Query("UPDATE songs SET artworkUri = :uri WHERE id = :id")
    suspend fun setArtworkUri(id: String, uri: String?)

    @Query("UPDATE songs SET lyricsUri = NULL WHERE lyricsUri LIKE 'file://%'")
    suspend fun clearDownloadedLyrics()

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun byId(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE sourceId = :sourceId")
    suspend fun byNasSource(sourceId: String): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(songs: List<SongEntity>)

    @Query("UPDATE songs SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album WHERE id = :id")
    suspend fun updateTags(id: String, title: String, artist: String, album: String)

    @Query(
        "DELETE FROM songs WHERE sourceType = 'LOCAL' " +
            "AND uri LIKE 'content://media/external/audio/media/%' AND id NOT IN (:ids)"
    )
    suspend fun deleteStaleLocalMedia(ids: List<String>)

    @Query("DELETE FROM songs WHERE sourceType = 'LOCAL' AND id LIKE 'doc:%' AND id NOT IN (:ids)")
    suspend fun deleteStaleFolder(ids: List<String>)

    @Query("DELETE FROM songs WHERE sourceId = :sourceId AND sourceType <> 'LOCAL'")
    suspend fun deleteByNasSource(sourceId: String)

    @Query("DELETE FROM songs WHERE sourceId = :sourceId AND id NOT IN (:ids)")
    suspend fun deleteStaleNas(sourceId: String, ids: List<String>)

    @Query("SELECT COUNT(*) FROM songs")
    fun count(): Flow<Int>
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun all(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query(
        "SELECT s.* FROM songs s INNER JOIN playlist_songs ps ON s.id = ps.songId " +
            "WHERE ps.playlistId = :id ORDER BY ps.position ASC"
    )
    fun songs(id: String): Flow<List<SongEntity>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :id")
    fun songCount(id: String): Flow<Int>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :id")
    suspend fun maxPosition(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSong(ref: PlaylistSongEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id AND songId = :songId")
    suspend fun removeSong(id: String, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearSongs(id: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)
}

@Dao
interface NasSourceDao {
    @Query("SELECT * FROM nas_sources ORDER BY name COLLATE NOCASE ASC")
    fun all(): Flow<List<NasSourceEntity>>

    @Query("SELECT * FROM nas_sources WHERE id = :id")
    suspend fun get(id: String): NasSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: NasSourceEntity)

    @Query("UPDATE nas_sources SET lastScanAt = :time WHERE id = :id")
    suspend fun updateScanTime(id: String, time: Long)

    @Query("DELETE FROM nas_sources WHERE id = :id")
    suspend fun delete(id: String)
}
