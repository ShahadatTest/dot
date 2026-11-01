package com.dj.dot

import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dj.dot.playback.DotService
import com.dj.dot.ui.DotRoot
import com.dj.dot.ui.DotTheme
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { DotTheme { DotRoot() } }
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller keeps the playback service alive so it can go foreground.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, DotService::class.java))).buildAsync()
    }

    override fun onStop() {
        DotApp.instance.engine.save()
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}
