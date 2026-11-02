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
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.sqrt
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
                Modifier.align(Alignment.TopStart).padding(horizontal = 32.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(song, Modifier.size(44.dp), px = 192, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    DotTitle(song.title, size = 22.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(song.artist, color = Dot.White)
                        Spacer(Modifier.width(12.dp))
                        Canvas(Modifier.size(7.dp)) { drawCircle(if (viz.pulse > 0.25f) Dot.Red else Dot.Faint) }
                        Spacer(Modifier.width(6.dp))
                        Label(if (viz.bpm > 0) "${viz.bpm} BPM" else "— BPM")
                    }
                }
            }
        }

        StageLyrics(Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp, start = 48.dp, end = 48.dp))

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

/** The current synced lyric line, big and centred, fading between lines. */
@Composable
private fun StageLyrics(modifier: Modifier) {
    val engine = DotApp.instance.engine
    val lyrics by engine.lyrics.collectAsStateWithLifecycle()
    val l = lyrics?.takeIf { it.synced } ?: return
    val line by androidx.compose.runtime.produceState("", l) {
        while (true) {
            value = l.lines.getOrNull(l.lineAt(engine.position()))?.text.orEmpty()
            delay(120)
        }
    }
    androidx.compose.animation.AnimatedContent(
        line, modifier,
        transitionSpec = { fadeIn(androidx.compose.animation.core.tween(300)) togetherWith fadeOut(androidx.compose.animation.core.tween(200)) },
        label = "stage-lyric",
    ) { text ->
        DotTitle(text, size = 30.sp, align = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
    }
}

/** One spark from a beat burst. */
private class Spark(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float, val red: Boolean)

private class Star(val x: Float, val y: Float, val phase: Float, val size: Float)

/** Everything the party scene remembers between frames. */
private class PartyScene(bands: Int) {
    val rows = 28
    /** Spectrum snapshots for the terrain; index 0 is the row nearest you. */
    val history = Array(rows) { FloatArray(bands) }
    var travel = 0f
    var lastFrame = 0L
    var lastBeat = 0L
    var t = 0f
    var fast = 0f
    var slow = 0f
    var drop = 0f
    var lastDrop = -100f
    var redTerrain = false
    var beats = 0
    var punch = 0f
    var shake = 0f
    var shooting = -1f
    var shootY = 0f
    val sparks = ArrayList<Spark>()
    val stars = List(150) {
        Star(Math.random().toFloat(), Math.random().toFloat(), (Math.random() * 6.28).toFloat(), 0.4f + Math.random().toFloat())
    }
    // Reused point buckets so drawing allocates nothing per frame.
    val buckets = Array(8) { ArrayList<Offset>(256) }
}

/**
 * The party stage, all dots: a twinkling sky, a striped red sun, lasers when the energy is
 * high, and a terrain built from the music itself rolling towards you at the song's tempo.
 * The camera sways and punches on beats; drops flash, shake and swap the terrain's colour.
 */
@Composable
private fun PartyField(viz: VizState, modifier: Modifier) {
    val scene = remember { PartyScene(viz.bands) }

    Canvas(modifier) {
        val now = viz.frame
        val dt = if (scene.lastFrame == 0L) 0f else ((now - scene.lastFrame) / 1e9f).coerceIn(0f, 0.05f)
        scene.lastFrame = now
        scene.t += dt
        val t = scene.t
        val w = size.width
        val h = size.height
        val bands = viz.bands
        val levels = viz.levels

        // Energy: a fast and a slow average; a sudden jump of fast over slow is a drop.
        var energy = 0f
        for (b in 0 until bands) energy += levels[b]
        energy /= bands
        scene.fast += (energy - scene.fast) * min(1f, dt * 4f)
        scene.slow += (energy - scene.slow) * min(1f, dt * 0.35f)
        if (scene.fast > 0.45f && scene.fast > scene.slow * 1.55f && t - scene.lastDrop > 8f) {
            scene.lastDrop = t
            scene.drop = 1f
            scene.shake = 1f
            scene.redTerrain = !scene.redTerrain
        }
        scene.drop = max(0f, scene.drop - dt * 1.2f)
        scene.shake = max(0f, scene.shake - dt * 2.5f)
        val hype = ((scene.fast - 0.3f) / 0.4f).coerceIn(0f, 1f)

        // Beats: a punch every beat, a bigger one on the first beat of each bar.
        val latest = viz.ripples.lastOrNull()
        var bar = false
        if (latest != null && latest.first != scene.lastBeat) {
            scene.lastBeat = latest.first
            scene.beats++
            bar = scene.beats % 4 == 1
            scene.punch = max(scene.punch, if (bar) 0.06f else 0.025f)
            if (bar && scene.shooting < 0f) { scene.shooting = 0f; scene.shootY = (0.05f + Math.random().toFloat() * 0.3f) }
            val count = ((if (bar) 50 else 18) * (0.5f + latest.second)).toInt()
            repeat(count) { k ->
                val a = (PI + Math.random() * PI).toFloat()
                val speed = h * (0.3f + Math.random().toFloat() * (0.5f + 0.6f * hype))
                scene.sparks += Spark(w / 2, h * 0.5f, cos(a) * speed * 1.7f, sin(a) * speed, 1f, k % 3 == 0)
            }
        }
        scene.punch *= (1f - min(1f, dt * 6f))

        val sway = sin(t * 0.21f) * w * 0.018f + (Math.random().toFloat() - 0.5f) * scene.shake * w * 0.02f
        val bob = sin(t * 0.33f) * h * 0.01f + levels[0] * h * 0.012f
        val tilt = sin(t * 0.13f) * 1.6f + (Math.random().toFloat() - 0.5f) * scene.shake * 3f
        val zoom = 1f + scene.punch
        val cx = w / 2
        val horizon = h * 0.5f

        withTransform({
            translate(sway, bob)
            rotate(tilt, Offset(cx, horizon))
            scale(zoom, zoom, Offset(cx, horizon))
        }) {
            // Sky: stars that twinkle and drift; a shooting star on bar accents.
            for (s in scene.stars) {
                val x = ((s.x - t * 0.004f * s.size) % 1f + 1f) % 1f * w * 1.1f - w * 0.05f
                val y = s.y * horizon * 0.95f
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (1.5f + s.size) + s.phase))
                drawCircle(Dot.White.copy(alpha = tw * (0.25f + 0.35f * (1f - hype))), h * 0.0022f * s.size, Offset(x, y))
            }
            if (scene.shooting >= 0f) {
                scene.shooting += dt * 1.4f
                val p = scene.shooting
                for (k in 0 until 14) {
                    val q = p - k * 0.012f
                    if (q < 0f) continue
                    drawCircle(Dot.White.copy(alpha = (1f - k / 14f) * (1f - p)), h * 0.004f, Offset(w * (1.05f - q * 1.2f), h * (scene.shootY + q * 0.25f)))
                }
                if (p >= 1f) scene.shooting = -1f
            }

            // Sun: a dotted half-disc with stripes that scroll down, swelling with the bass.
            val bass = (levels[0] + levels[1] + levels[2]) / 3f
            val sunR = h * (0.25f + 0.04f * bass + 0.025f * viz.pulse)
            val step = h * 0.017f
            val stripe = (t * 0.6f) % 1f
            for (b in scene.buckets) b.clear()
            var yy = horizon - sunR
            while (yy < horizon) {
                val rel = (horizon - yy) / sunR // 1 at the top, 0 at the horizon
                val band = ((rel * 6f + stripe) % 1f)
                // Stripes widen towards the horizon, like a synthwave sun.
                if (band > 0.25f + (1f - rel) * 0.45f) {
                    val half = sqrt(max(0f, sunR * sunR - (horizon - yy) * (horizon - yy)))
                    var xx = cx - half
                    while (xx <= cx + half) {
                        scene.buckets[(rel * 3.99f).toInt()] += Offset(xx, yy)
                        xx += step
                    }
                }
                yy += step
            }
            for (i in 0 until 4) {
                drawPoints(scene.buckets[i], PointMode.Points, Dot.Red.copy(alpha = 0.35f + 0.15f * i + 0.25f * bass), step * 0.55f, StrokeCap.Round)
            }

            // Lasers: dotted beams sweeping out of the sun once the energy is up.
            if (hype > 0.05f) {
                val beams = 6
                for (k in 0 until beams) {
                    val spread = sin(t * 0.9f + k) * 0.5f
                    val a = (PI * (1.0 + (k + 0.5) / beams)).toFloat() + spread * 0.35f
                    val len = w * 0.75f
                    val dots = 40
                    for (d in 2 until dots) {
                        val r = len * d / dots
                        val alpha = hype * (1f - d / dots.toFloat()) * (0.5f + 0.5f * viz.pulse)
                        drawCircle(
                            (if (k % 2 == 0) Dot.Red else Dot.White).copy(alpha = alpha),
                            h * 0.0035f, Offset(cx + cos(a) * r, horizon + sin(a) * r),
                        )
                    }
                }
            }

            // Terrain: each row is a snapshot of the spectrum, rolling towards you.
            val rowsPerSecond = (if (viz.bpm > 0) viz.bpm / 60f else 1.6f) * (0.6f + 0.9f * hype + 0.4f * bass)
            scene.travel += dt * rowsPerSecond
            while (scene.travel >= 1f) {
                scene.travel -= 1f
                val oldest = scene.history[0]
                for (r in 0 until scene.rows - 1) scene.history[r] = scene.history[r + 1]
                levels.copyInto(oldest)
                scene.history[scene.rows - 1] = oldest
            }
            val cols = bands * 2 - 1
            val spread = w * 1.25f / cols
            val accent = if (scene.redTerrain) Dot.Red else Dot.White
            val other = if (scene.redTerrain) Dot.White else Dot.Red
            for (r in scene.rows - 1 downTo 0) {
                val d = ((r + 1 - scene.travel) / scene.rows).coerceIn(0.001f, 1f)
                val z = 1f / (1f - d * 0.95f)
                val baseY = horizon + (h * 1.04f - horizon) / z
                val lift = h * 0.32f / z
                val near = 1f - d
                val snapshot = scene.history[r]
                for (b in 0 until 4) scene.buckets[b].clear()
                scene.buckets[4].clear()
                for (c in 0 until cols) {
                    val band = abs(c - cols / 2).coerceIn(0, bands - 1)
                    val level = snapshot[band]
                    val x = cx + (c - cols / 2) * spread / z
                    if (x < -spread || x > w + spread) continue
                    val p = Offset(x, baseY - level * lift)
                    if (level > 0.82f) scene.buckets[4] += p else scene.buckets[(level * 3.99f).toInt()] += p
                }
                val dotSize = (h * 0.016f) / z * 1.5f
                val rowAlpha = near * near
                for (b in 0 until 4) {
                    if (scene.buckets[b].isEmpty()) continue
                    drawPoints(scene.buckets[b], PointMode.Points, accent.copy(alpha = (rowAlpha * (0.18f + 0.27f * b)).coerceIn(0.03f, 1f)), dotSize, StrokeCap.Round)
                }
                if (scene.buckets[4].isNotEmpty()) drawPoints(scene.buckets[4], PointMode.Points, other.copy(alpha = rowAlpha.coerceIn(0.1f, 1f)), dotSize * 1.15f, StrokeCap.Round)
            }

            // Sparks from the horizon.
            val it = scene.sparks.iterator()
            while (it.hasNext()) {
                val sp = it.next()
                sp.x += sp.vx * dt
                sp.y += sp.vy * dt
                sp.life -= dt * 0.85f
                if (sp.life <= 0f) { it.remove(); continue }
                drawCircle((if (sp.red) Dot.Red else Dot.White).copy(alpha = sp.life), h * 0.006f * (0.5f + sp.life), Offset(sp.x, sp.y))
            }
        }

        // Drop flash over everything.
        if (scene.drop > 0f) drawRect(Dot.Red.copy(alpha = scene.drop * 0.28f))
    }
}
