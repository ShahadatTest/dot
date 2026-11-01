package com.dj.dot.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Full-screen landscape visualizer for when the phone sits on a table: a giant mirrored dot
 * field driven by the spectrum, beat rings from the centre, and the song in big dot type.
 * Tap to show controls; back exits.
 */
@Composable
fun PartyMode(onExit: () -> Unit) {
    val dot = DotApp.instance
    val engine = dot.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val song = state.current
    val viz = rememberVizState(engine.spectrum, state.playing)
    val context = LocalContext.current
    val view = LocalView.current

    // Landscape, immersive, screen kept on; all undone on exit.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val previous = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = previous
        }
    }
    BackHandler(onBack = onExit)

    var touches by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(touches) {
        controlsVisible = true
        delay(3500)
        controlsVisible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Dot.Black)
            .clickable(remember { MutableInteractionSource() }, null) { touches++ },
    ) {
        PartyField(viz, Modifier.fillMaxSize())

        if (song != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 36.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(song, Modifier.size(64.dp), px = 192, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    DotTitle(song.title, size = 34.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(song.artist, color = Dot.White)
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (viz.pulse > 0.25f) Dot.Red else Dot.Faint))
                        Spacer(Modifier.width(6.dp))
                        Label(if (viz.bpm > 0) "${viz.bpm} BPM" else "— BPM")
                    }
                }
            }
        }

        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.padding(horizontal = 28.dp, vertical = 28.dp).clip(CircleShape).background(Dot.Surface2.copy(alpha = 0.85f)).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(Icons.Rounded.SkipPrevious, { engine.previous(); touches++ })
                IconBtn(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, { engine.toggle(); touches++ }, size = 30.dp)
                IconBtn(Icons.Rounded.SkipNext, { engine.next(); touches++ })
                IconBtn(Icons.Rounded.Close, onExit, tint = Dot.Muted)
            }
        }
    }
}

/** The mirrored dot field: bass in the middle, highs to the edges, peaks red, rings on beats. */
@Composable
private fun PartyField(viz: VizState, modifier: Modifier) {
    Canvas(modifier) {
        viz.frame
        val cols = viz.bands * 2 - 1
        val cw = size.width / cols
        val rows = ((size.height / cw).roundToInt() or 1).coerceAtLeast(9)
        val rh = size.height / rows
        val mid = rows / 2
        val swell = 1f + 0.18f * viz.pulse
        val r = min(cw, rh) * 0.28f
        val center = Offset(size.width / 2, size.height / 2)

        // Beat rings first, so the dots sit on top of them.
        val now = viz.frame
        val maxR = size.width * 0.6f
        for ((start, strength) in viz.ripples) {
            val t = ((now - start).toFloat() / 1_200_000_000L).coerceIn(0f, 1f)
            val radius = maxR * t
            val dots = 90
            for (k in 0 until dots) {
                val a = k * 2f * PI.toFloat() / dots
                drawCircle(Dot.Red.copy(alpha = (1f - t) * strength * 0.7f), r * 0.8f, Offset(center.x + cos(a) * radius, center.y + sin(a) * radius * 0.55f))
            }
        }

        for (c in 0 until cols) {
            val band = abs(c - cols / 2).coerceIn(0, viz.bands - 1)
            val reach = (viz.levels[band] * (mid + 0.5f)).roundToInt()
            for (row in 0 until rows) {
                val d = abs(row - mid)
                val on = d <= reach
                val color = when {
                    d == 0 && viz.pulse > 0.3f -> Dot.Red
                    on && d == reach && reach >= mid - 1 -> Dot.Red
                    on -> Dot.White
                    else -> Dot.Faint.copy(alpha = 0.6f + 0.4f * viz.pulse)
                }
                drawCircle(color, if (on) r * swell else r * 0.7f, Offset(c * cw + cw / 2, row * rh + rh / 2))
            }
        }
    }
}
