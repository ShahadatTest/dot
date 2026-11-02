package com.dj.dot.widget

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dj.dot.playback.DotService
import com.google.common.util.concurrent.MoreExecutors

/**
 * Sends a command through the media session, the same path the notification uses. Binding a
 * controller starts the playback service if Dot isn't running, so the widget and tile work cold.
 */
object RemoteControl {
    fun send(context: Context, done: () -> Unit = {}, command: (MediaController) -> Unit) {
        val token = SessionToken(context, ComponentName(context, DotService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { controller ->
                // The engine restores the last queue once the library loads; wait briefly for it.
                fun attempt(triesLeft: Int) {
                    if (controller.mediaItemCount > 0 || triesLeft == 0) {
                        command(controller)
                        MediaController.releaseFuture(future)
                        done()
                    } else {
                        android.os.Handler(context.mainLooper).postDelayed({ attempt(triesLeft - 1) }, 150)
                    }
                }
                attempt(20)
            }.onFailure { done() }
        }, MoreExecutors.directExecutor())
    }
}
