package com.dj.dot

import android.app.Application
import com.dj.dot.data.Library
import com.dj.dot.data.PlaylistStore
import com.dj.dot.data.Settings
import com.dj.dot.playback.DotEngine
import kotlinx.coroutines.MainScope

class DotApp : Application() {
    lateinit var settings: Settings private set
    lateinit var store: PlaylistStore private set
    lateinit var library: Library private set
    lateinit var engine: DotEngine private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val scope = MainScope()
        settings = Settings(this)
        store = PlaylistStore(this, scope)
        library = Library(this, scope, settings)
        engine = DotEngine(this, settings, store, library)
    }

    companion object {
        lateinit var instance: DotApp private set
    }
}
