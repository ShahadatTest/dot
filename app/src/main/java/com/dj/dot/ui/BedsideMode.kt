package com.dj.dot.ui

import android.app.Activity
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Date

/**
 * A night-stand clock: big dot-matrix time, the date, and what's playing. Keeps the screen on,
 * dims itself when left alone, and drifts a few pixels each minute so the OLED never burns in.
 */
@Composable
fun BedsideMode(onExit: () -> Unit) {
    val dot = DotApp.instance
    val engine = dot.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val song = state.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(onBack = onExit)

    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val clock = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
    val date = DateFormat.format("EEE, d MMM", now).toString()
    val minute = Calendar.getInstance().apply { time = now }.get(Calendar.MINUTE)

    var touches by remember { mutableIntStateOf(0) }
    var awake by remember { mutableStateOf(true) }
    LaunchedEffect(touches) {
        awake = true
        delay(15_000)
        awake = false
    }
    val dim by animateFloatAsState(if (awake) 1f else 0.35f, tween(1200), label = "dim")

    val viz = if (state.playing) rememberVizState(engine.spectrum, true) else null

    Box(
        Modifier
            .fillMaxSize()
            .background(Dot.Black)
            .clickable(remember { MutableInteractionSource() }, null) { touches++ },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .offset(x = ((minute % 5) * 3 - 6).dp, y = ((minute / 5 % 3) * 3 - 3).dp)
                .graphicsLayer { alpha = dim },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DotTitle(clock, size = 120.sp, align = TextAlign.Center)
            Label(date, color = Dot.Muted)
            if (song != null) {
                Spacer(Modifier.height(36.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(song, Modifier.size(44.dp), px = 132, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.width(200.dp)) {
                        DotTitle(song.title, size = 18.sp)
                        Label(song.artist)
                    }
                }
                if (viz != null) {
                    Spacer(Modifier.height(18.dp))
                    StripVisualizer(viz, StripStyle.Mirror, Modifier.width(260.dp).height(44.dp), showReadout = false)
                }
            }
        }

        AnimatedVisibility(awake, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.padding(bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                if (song != null) {
                    IconBtn(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, { engine.toggle(); touches++ }, size = 28.dp)
                    IconBtn(Icons.Rounded.SkipNext, { engine.next(); touches++ })
                }
                IconBtn(Icons.Rounded.Close, onExit, tint = Dot.Muted)
            }
        }
    }
}
