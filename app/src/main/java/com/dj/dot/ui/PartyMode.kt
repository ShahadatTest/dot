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
                        Canvas(Modifier.size(7.dp)) { drawCircle(if (viz.pulse > 0.25f) Dot.Red else Dot.Faint) }
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

/** One spark from a beat burst. */
private class Spark(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float, val red: Boolean)

/**
 * The party scene, all dots: a red sun on the horizon that swells with the bass, a skyline of
 * spectrum columns rising from it, a perspective floor rushing towards you at the song's tempo,
 * and sparks bursting from the horizon on every beat.
 */
@Composable
private fun PartyField(viz: VizState, modifier: Modifier) {
    val sparks = remember { ArrayList<Spark>() }
    val clock = remember { LongArray(2) } // last frame time, last burst start
    val travel = remember { FloatArray(1) } // floor scroll position in rows

    Canvas(modifier) {
        val now = viz.frame
        val dt = if (clock[0] == 0L) 0f else ((now - clock[0]) / 1e9f).coerceIn(0f, 0.05f)
        clock[0] = now
        val w = size.width
        val h = size.height
        val cx = w / 2
        val horizon = h * 0.5f
        val bands = viz.bands
        val bass = (viz.levels[0] + viz.levels[1] + viz.levels[2]) / 3f

        // Sun: a ring of red dots behind the skyline, breathing with the bass.
        val sunR = h * (0.24f + 0.05f * bass + 0.03f * viz.pulse)
        val sunDots = 96
        for (k in 0 until sunDots) {
            val a = PI.toFloat() + k * PI.toFloat() / (sunDots - 1) // top half only
            val p = Offset(cx + cos(a) * sunR, horizon + sin(a) * sunR)
            drawCircle(Dot.Red.copy(alpha = 0.35f + 0.5f * bass), h * 0.006f, p)
        }
        for (ring in 1..4) {
            val r = sunR * (1f - ring * 0.2f)
            val n = (sunDots * (1f - ring * 0.2f)).toInt()
            for (k in 0 until n) {
                val a = PI.toFloat() + k * PI.toFloat() / (n - 1)
                drawCircle(Dot.Red.copy(alpha = (0.12f + 0.25f * bass) * (1f - ring * 0.18f)), h * 0.004f, Offset(cx + cos(a) * r, horizon + sin(a) * r))
            }
        }

        // Skyline: mirrored spectrum columns standing on the horizon.
        val cols = bands * 2 - 1
        val colStep = w * 0.8f / cols
        val left = cx - colStep * (cols - 1) / 2
        val rowStep = colStep * 0.75f
        val maxRows = ((horizon - h * 0.08f) / rowStep).toInt().coerceAtLeast(6)
        val dotR = colStep * 0.24f
        for (c in 0 until cols) {
            val band = abs(c - cols / 2).coerceIn(0, bands - 1)
            val lit = (viz.levels[band] * maxRows).roundToInt()
            for (row in 0 until maxRows) {
                val on = row < lit
                val color = when {
                    on && row == lit - 1 && lit > maxRows * 0.7f -> Dot.Red
                    on -> Dot.White
                    else -> Dot.Faint.copy(alpha = 0.35f)
                }
                drawCircle(color, if (on) dotR * (1f + 0.15f * viz.pulse) else dotR * 0.6f, Offset(left + c * colStep, horizon - rowStep * (row + 0.6f)))
            }
        }

        // Floor: rows that rush towards you, one row per beat.
        val rowsPerSecond = if (viz.bpm > 0) viz.bpm / 60f else 1.6f
        travel[0] = (travel[0] + dt * rowsPerSecond * (0.35f + bass)) % 1f
        val floorRows = 18
        val floorCols = 31
        val spread = w * 1.1f / floorCols
        for (i in 0 until floorRows) {
            val d = ((i + 1 - travel[0]) / floorRows).coerceIn(0.001f, 1f) // 0 = at your feet, 1 = horizon
            val z = 1f / (1f - d * 0.94f)
            val y = horizon + (h * 1.02f - horizon) / z
            val near = 1f - d
            for (c in 0 until floorCols) {
                val offset = c - floorCols / 2
                val band = (abs(offset) * (bands - 1) / (floorCols / 2)).coerceIn(0, bands - 1)
                val level = viz.levels[band]
                val x = cx + offset * spread / z
                if (x < -spread || x > w + spread) continue
                val alpha = (near * near * (0.25f + 0.75f * level)).coerceIn(0.04f, 1f)
                val color = if (d < 0.12f && viz.pulse > 0.3f) Dot.Red else Dot.White
                drawCircle(color.copy(alpha = alpha), (h * 0.012f) / z * 1.6f, Offset(x, y))
            }
        }

        // Sparks: a burst from the horizon on every new beat.
        val latest = viz.ripples.lastOrNull()
        if (latest != null && latest.first != clock[1]) {
            clock[1] = latest.first
            val count = (20 + 30 * latest.second).toInt()
            repeat(count) { k ->
                val a = (PI + Math.random() * PI).toFloat() // upward fan
                val speed = h * (0.35f + Math.random().toFloat() * 0.6f)
                sparks += Spark(cx, horizon, cos(a) * speed * 1.6f, sin(a) * speed, 1f, k % 4 == 0)
            }
        }
        val it = sparks.iterator()
        while (it.hasNext()) {
            val sp = it.next()
            sp.x += sp.vx * dt
            sp.y += sp.vy * dt
            sp.life -= dt * 0.9f
            if (sp.life <= 0f) { it.remove(); continue }
            drawCircle((if (sp.red) Dot.Red else Dot.White).copy(alpha = sp.life), h * 0.006f * (0.5f + sp.life), Offset(sp.x, sp.y))
        }
    }
}
