package com.nasmusic.player.core

import android.content.Context
import com.nasmusic.player.data.CoverFetcher
import com.nasmusic.player.data.LyricsFetcher
import com.nasmusic.player.data.LyricsLoader
import com.nasmusic.player.data.LyricsStore
import com.nasmusic.player.data.MusicRepository
import com.nasmusic.player.data.NasCacheManager
import com.nasmusic.player.data.NasCredentialsCrypto
import com.nasmusic.player.data.NasRepository
import com.nasmusic.player.data.NasScanner
import com.nasmusic.player.data.PlaylistRepository
import com.nasmusic.player.data.db.AppDatabase
import com.nasmusic.player.playback.PlaybackController

/** 轻量手动依赖容器：保持 MVVM 分层，同时避免引入 Hilt/Kapt 的构建复杂度。 */
class AppContainer(private val appContext: Context) {

    val database by lazy { AppDatabase.build(appContext) }
    val settings by lazy { SettingsStore(appContext) }
    val playbackState by lazy { PlaybackStateStore(appContext) }
    val crypto by lazy { NasCredentialsCrypto() }

    val musicRepository by lazy { MusicRepository(appContext, database.songDao(), settings) }
    val playlistRepository by lazy { PlaylistRepository(database.playlistDao()) }
    val nasRepository by lazy {
        NasRepository(appContext, database.nasSourceDao(), database.songDao(), crypto)
    }
    val nasCache by lazy { NasCacheManager(appContext, settings, nasRepository) }
    val nasScanner by lazy { NasScanner(appContext, nasRepository, database.songDao(), settings) }
    val lyricsLoader by lazy { LyricsLoader(appContext, nasRepository) }
    val lyricsFetcher by lazy { LyricsFetcher() }
    val lyricsStore by lazy { LyricsStore(appContext) }
    val coverFetcher by lazy { CoverFetcher(appContext) }
    val playbackController by lazy {
        PlaybackController(appContext, musicRepository, nasCache)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
