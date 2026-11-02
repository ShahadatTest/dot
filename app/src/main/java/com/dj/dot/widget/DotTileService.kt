package com.dj.dot.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dj.dot.DotApp
import com.dj.dot.MainActivity
import com.dj.dot.R

/** Quick Settings tile: play or pause Dot from the notification shade, showing the current song. */
class DotTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val engine = DotApp.instance.engine
        if (engine.state.value.queue.isEmpty() && DotApp.instance.settings.queueIds.value.isEmpty()) {
            openApp()
            return
        }
        RemoteControl.send(this, done = { refresh() }) { c -> if (c.isPlaying) c.pause() else c.play() }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = DotApp.instance.engine.state.value
        tile.label = "Dot"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = state.current?.title ?: "Play music"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = if (state.playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to refresh the tile, e.g. when playback starts or stops. */
        fun requestRefresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, DotTileService::class.java)) }
        }
    }
}
