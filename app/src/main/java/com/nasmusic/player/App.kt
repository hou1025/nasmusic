package com.nasmusic.player

import android.app.Application
import com.nasmusic.player.core.AppContainer

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.get(this)
    }
}
