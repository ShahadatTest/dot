package com.dj.dot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Size
import android.widget.RemoteViews
import com.dj.dot.DotApp
import com.dj.dot.MainActivity
import com.dj.dot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Home-screen widget: cover, title in dot type, artist, play/pause and next. */
class DotWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = push(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE, ACTION_NEXT -> {
                val pending = goAsync()
                RemoteControl.send(context, done = { pending.finish() }) { c ->
                    if (intent.action == ACTION_NEXT) c.seekToNext() else if (c.isPlaying) c.pause() else c.play()
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.dj.dot.widget.TOGGLE"
        private const val ACTION_NEXT = "com.dj.dot.widget.NEXT"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraws every placed widget from the engine's current state. Cheap no-op when none are placed. */
        fun push(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DotWidget::class.java))
            if (ids.isEmpty()) return
            val state = DotApp.instance.engine.state.value
            val song = state.current
            scope.launch {
                val art: Bitmap? = song?.let { s ->
                    runCatching { context.contentResolver.loadThumbnail(s.uri, Size(192, 192), null) }.getOrNull()
                }
                val views = RemoteViews(context.packageName, R.layout.widget_dot).apply {
                    setTextViewText(R.id.widget_title, song?.title ?: "Dot")
                    setTextViewText(R.id.widget_artist, song?.artist ?: "Tap to start")
                    if (art != null) setImageViewBitmap(R.id.widget_art, art) else setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)
                    setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                    setOnClickPendingIntent(R.id.widget_play, broadcast(context, ACTION_TOGGLE, 1))
                    setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 2))
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                    )
                }
                manager.updateAppWidget(ids, views)
            }
        }

        private fun broadcast(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, DotWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
