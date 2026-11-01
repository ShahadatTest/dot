package com.dj.dot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dj.dot.data.Song
import com.dj.dot.playback.Spectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class StripStyle(val label: String) { Grid("Grid"), Mirror("Mirror"), Wave("Wave") }
enum class ArtStyle(val label: String) { Cover("Cover"), DotArt("Dot art"), Halo("Halo"), Vinyl("Vinyl") }

/** Roughly how far decoded audio runs ahead of the speaker. */
private const val AUDIO_LATENCY_NS = 200_000_000L
private const val RIPPLE_NS = 1_200_000_000L
private const val FRAME_NS = 15_000_000L

/** Smoothed, speaker-aligned analysis shared by every visualizer on screen. */
@Stable
class VizState(val bands: Int, wavePoints: Int) {
    val levels = FloatArray(bands)
    val wave = FloatArray(wavePoints)
    /** Start times (nanoTime) and strengths of beat ripples still on screen. */
    val ripples = ArrayDeque<Pair<Long, Float>>()
    var pulse by mutableFloatStateOf(0f)
    var bpm by mutableIntStateOf(0)
    var frame by mutableLongStateOf(0L)
}

@Composable
fun rememberVizState(spectrum: Spectrum, playing: Boolean): VizState {
    val state = remember { VizState(spectrum.bands, spectrum.wavePoints) }
    DisposableEffect(Unit) {
        spectrum.acquire()
        onDispose { spectrum.release() }
    }
    LaunchedEffect(playing) {
        var lastAudible = System.nanoTime() - AUDIO_LATENCY_NS
        var lastFrame = 0L
        while (true) {
            val settled = withFrameNanos { now ->
                // 60 fps is plenty for dots; skip every other frame on a 120 Hz screen.
                if (now - lastFrame < FRAME_NS) return@withFrameNanos false
                lastFrame = now
                val audible = System.nanoTime() - AUDIO_LATENCY_NS
                val target = if (playing) spectrum.levelsAt(audible) else null
                val wave = if (playing) spectrum.waveAt(audible) else null
                val beat = if (playing) spectrum.beatBetween(lastAudible, audible) else 0f
                lastAudible = audible

                if (beat > 0f) state.ripples.addLast(now to beat)
                while (state.ripples.isNotEmpty() && now - state.ripples.first().first > RIPPLE_NS) state.ripples.removeFirst()
                state.pulse = maxOf(state.pulse * 0.86f, beat)
                state.bpm = spectrum.bpm

                var max = 0f
                for (i in state.levels.indices) {
                    val t = target?.get(i) ?: 0f
                    val l = state.levels[i]
                    state.levels[i] = if (t > l) l + (t - l) * 0.55f else l - (l - t) * 0.12f
                    max = maxOf(max, state.levels[i])
                }
                for (i in state.wave.indices) {
                    val t = wave?.get(i) ?: 0f
                    state.wave[i] += (t - state.wave[i]) * 0.5f
                    max = maxOf(max, abs(state.wave[i]))
                }
                state.frame = now
                !playing && max < 0.01f && state.pulse < 0.01f && state.ripples.isEmpty()
            }
            if (settled) break
        }
    }
    return state
}

// ---- Strip visualizers (under the art) ----------------------------------------------------

@Composable
fun StripVisualizer(state: VizState, style: StripStyle, modifier: Modifier = Modifier, showReadout: Boolean = true) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (style) {
                StripStyle.Grid -> GridViz(state, Modifier.fillMaxSize())
                StripStyle.Mirror -> MirrorViz(state, Modifier.fillMaxSize())
                StripStyle.Wave -> WaveViz(state, Modifier.fillMaxSize())
            }
        }
        if (showReadout) BeatReadout(state)
    }
}

@Composable
private fun BeatReadout(state: VizState) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // The pulse changes every frame, so it's read while drawing, not while composing.
        Canvas(Modifier.size(8.dp)) { drawCircle(if (state.pulse > 0.25f) Dot.Red else Dot.Faint) }
        Spacer(Modifier.width(8.dp))
        val bpm = state.bpm
        Label(if (bpm > 0) "$bpm BPM" else "— BPM", color = if (bpm > 0) Dot.White else Dot.Muted)
    }
}

/** Bars of dots rising from the bottom; the peak of a loud band glows red. */
@Composable
private fun GridViz(state: VizState, modifier: Modifier, rows: Int = 9) {
    Canvas(modifier) {
        state.frame
        val cols = state.bands
        val cw = size.width / cols
        val rh = size.height / rows
        val r = min(cw, rh) * 0.3f * (1f + 0.22f * state.pulse)
        val flashRows = if (state.pulse > 0.3f) 2 else 0
        for (c in 0 until cols) {
            val lit = (state.levels[c] * rows).roundToInt()
            for (row in 0 until rows) {
                val on = row < lit
                val color = when {
                    on && row < flashRows -> Dot.Red
                    on && row == lit - 1 && lit >= rows - 2 -> Dot.Red
                    on -> Dot.White
                    else -> Dot.Faint
                }
                drawCircle(color, r, Offset(c * cw + cw / 2, size.height - (row * rh + rh / 2)))
            }
        }
    }
}

/** Bars mirrored above and below a centre line, which flashes red on beats. */
@Composable
private fun MirrorViz(state: VizState, modifier: Modifier, rows: Int = 11) {
    Canvas(modifier) {
        state.frame
        // Bass in the middle, highs towards both edges.
        val cols = state.bands * 2 - 1
        val cw = size.width / cols
        val rh = size.height / rows
        val mid = rows / 2
        val r = min(cw, rh) * 0.3f * (1f + 0.15f * state.pulse)
        for (c in 0 until cols) {
            val band = abs(c - (cols / 2))
            val reach = (state.levels[band] * (mid + 0.5f)).roundToInt()
            for (row in 0 until rows) {
                val d = abs(row - mid)
                val on = d <= reach && (reach > 0 || d == 0)
                val color = when {
                    d == 0 && state.pulse > 0.3f -> Dot.Red
                    on && d == reach && reach >= mid - 1 -> Dot.Red
                    on -> Dot.White
                    else -> Dot.Faint
                }
                drawCircle(color, r, Offset(c * cw + cw / 2, row * rh + rh / 2))
            }
        }
    }
}

/** Dot-matrix oscilloscope of the actual waveform. */
@Composable
private fun WaveViz(state: VizState, modifier: Modifier, rows: Int = 11) {
    Canvas(modifier) {
        state.frame
        val cols = state.wave.size
        val cw = size.width / cols
        val rh = size.height / rows
        val mid = rows / 2
        val r = min(cw, rh) * 0.34f
        for (c in 0 until cols) {
            val target = (mid - state.wave[c] * mid).roundToInt().coerceIn(0, rows - 1)
            val lo = min(mid, target)
            val hi = maxOf(mid, target)
            for (row in 0 until rows) {
                val color = when {
                    row == target -> if (state.pulse > 0.35f) Dot.Red else Dot.White
                    row in lo..hi -> Dot.Muted
                    else -> Dot.Faint
                }
                drawCircle(color, if (row == target) r * 1.15f else r * 0.8f, Offset(c * cw + cw / 2, row * rh + rh / 2))
            }
        }
    }
}

// ---- Art visualizers (replace the cover) --------------------------------------------------

/** Pre-computed halftone: colour and base radius (0..1 of a cell) per dot. */
private class Halftone(val grid: Int, val colors: IntArray, val sizes: FloatArray)

/**
 * The cover as a colour LED-matrix halftone. Rows are offset like a print screen, dot area
 * follows brightness after auto-levels, and each dot keeps the cover's colour. Motion is kept
 * subtle so the image stays readable: a slight swell on beats and a ripple of light that runs
 * outward from the centre on every hit.
 */
@Composable
fun DotArtViz(song: Song, state: VizState, modifier: Modifier = Modifier, grid: Int = 60) {
    val context = LocalContext.current
    var halftone by remember(song.albumId) { mutableStateOf<Halftone?>(null) }
    LaunchedEffect(song.albumId) {
        val art = ArtLoader.load(context, song, "${song.albumId}_512", 512)
        halftone = withContext(Dispatchers.Default) { buildHalftone(art?.asAndroidBitmap(), grid) }
    }

    // The halftone is painted once per size into a bitmap; each frame only scales it and
    // lights the ripple band, instead of redrawing thousands of dots.
    val cache = remember(halftone) { arrayOfNulls<androidx.compose.ui.graphics.ImageBitmap>(1) }
    Canvas(modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
        state.frame
        val h = halftone ?: return@Canvas
        val w = size.width.toInt().coerceAtLeast(1)
        val hgt = size.height.toInt().coerceAtLeast(1)
        val image = cache[0]?.takeIf { it.width == w && it.height == hgt } ?: paintHalftone(h, w, hgt).also { cache[0] = it }
        val swell = 1f + 0.06f * state.pulse
        val center = Offset(size.width / 2, size.height / 2)
        val maxDist = min(size.width, size.height) * 0.72f
        val band = min(size.width, size.height) / h.grid * 3f
        val now = state.frame
        withTransform({ scale(swell, swell, center) }) {
            drawImage(image)
            for ((start, strength) in state.ripples) {
                val t = ((now - start).toFloat() / RIPPLE_NS).coerceIn(0f, 1f)
                drawCircle(
                    androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f * strength * (1f - t)),
                    radius = (t * maxDist).coerceAtLeast(1f),
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = band),
                    blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                )
            }
        }
    }
}

/** Paints the hex-packed halftone dots once at the given size. */
private fun paintHalftone(h: Halftone, width: Int, height: Int): androidx.compose.ui.graphics.ImageBitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val n = h.grid
    val step = min(width, height) / (n + 0.5f)
    val rowStep = step * 0.866f
    val rows = (height / rowStep).toInt().coerceAtMost(n)
    val ox = (width - step * (n - 0.5f)) / 2
    val oy = (height - rowStep * (rows - 1)) / 2
    val maxR = step * 0.56f
    for (y in 0 until rows) {
        val srcY = (y * n / rows).coerceIn(0, n - 1)
        val shift = if (y % 2 == 1) step / 2 else 0f
        for (x in 0 until n) {
            val px = ox + x * step + shift
            if (px > width - step * 0.2f) continue
            val i = srcY * n + x
            paint.color = h.colors[i]
            canvas.drawCircle(px, oy + y * rowStep, (h.sizes[i] * maxR).coerceIn(step * 0.06f, step * 0.62f), paint)
        }
    }
    return bmp.asImageBitmap()
}

private fun buildHalftone(source: Bitmap?, grid: Int): Halftone {
    val colors = IntArray(grid * grid)
    val sizes = FloatArray(grid * grid)
    if (source == null) {
        // No cover: a soft radial dot pattern with a red centre.
        for (y in 0 until grid) for (x in 0 until grid) {
            val dx = (x - grid / 2f) / (grid / 2f)
            val dy = (y - grid / 2f) / (grid / 2f)
            val v = (1f - kotlin.math.sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            sizes[y * grid + x] = 0.15f + 0.6f * v
            colors[y * grid + x] = if (v > 0.9f) Dot.Red.toArgbInt() else 0xFF5A5A5A.toInt()
        }
        return Halftone(grid, colors, sizes)
    }

    // Fit the whole cover into a square (wide covers get bars, nothing is cropped), then halve
    // repeatedly so each dot averages its whole cell.
    val src = source.copy(Bitmap.Config.ARGB_8888, false)
    val side = kotlin.math.max(src.width, src.height)
    var bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val left = (side - src.width) / 2
    val top = (side - src.height) / 2
    android.graphics.Canvas(bmp).apply {
        drawColor(android.graphics.Color.BLACK)
        drawBitmap(src, left.toFloat(), top.toFloat(), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
    // Grid cells that fall in the bars, so they can be drawn as a faint backdrop instead.
    val inside = { x: Int, y: Int ->
        val sx = (x + 0.5f) * side / grid
        val sy = (y + 0.5f) * side / grid
        sx >= left && sx < left + src.width && sy >= top && sy < top + src.height
    }
    var w = side
    while (w / 2 >= grid * 2) {
        w /= 2
        bmp = Bitmap.createScaledBitmap(bmp, w, w, true)
    }
    bmp = Bitmap.createScaledBitmap(bmp, grid, grid, true)
    val px = IntArray(grid * grid)
    bmp.getPixels(px, 0, grid, 0, 0, grid, grid)

    val lum = FloatArray(px.size) { i ->
        val c = px[i]
        (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f
    }
    val content = BooleanArray(px.size) { i -> inside(i % grid, i / grid) }
    // Auto-levels on the 3rd..98th percentile of the cover itself so dark or washed-out art still reads.
    val coverLum = lum.filterIndexed { i, _ -> content[i] }.toFloatArray()
    val sorted = (if (coverLum.isNotEmpty()) coverLum else lum.copyOf()).also { it.sort() }
    val lo = sorted[(sorted.size * 0.03f).toInt()]
    val hi = sorted[(sorted.size * 0.98f).toInt().coerceAtMost(sorted.lastIndex)].coerceAtLeast(lo + 0.05f)

    val hsv = FloatArray(3)
    for (i in px.indices) {
        if (!content[i]) {
            sizes[i] = 0.1f
            colors[i] = 0xFF262626.toInt()
            continue
        }
        val v = ((lum[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
        // Dot area tracks brightness, so radius is the square root.
        sizes[i] = 0.12f + 0.88f * kotlin.math.sqrt(v)
        android.graphics.Color.colorToHSV(px[i], hsv)
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f)
        hsv[2] = (0.35f + 0.65f * v).coerceIn(0f, 1f)
        colors[i] = android.graphics.Color.HSVToColor(hsv)
    }
    return Halftone(grid, colors, sizes)
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

/**
 * A round cover in the middle, dot spokes radiating out with the spectrum (bass at the top,
 * mirrored left and right), and red dot rings rippling outward on every beat.
 */
@Composable
fun HaloViz(song: Song, state: VizState, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            state.frame
            val cx = size.width / 2
            val cy = size.height / 2
            val maxR = min(size.width, size.height) / 2
            val inner = maxR * 0.5f
            val spokes = 64
            val dotsPerSpoke = 7
            val gap = (maxR - inner) / (dotsPerSpoke + 0.5f)
            val r = gap * 0.28f

            for (s in 0 until spokes) {
                val angle = (s.toFloat() / spokes) * 2f * PI.toFloat() - PI.toFloat() / 2
                val half = if (s <= spokes / 2) s else spokes - s
                val band = (half * (state.bands - 1) / (spokes / 2)).coerceIn(0, state.bands - 1)
                val lit = (state.levels[band] * dotsPerSpoke).roundToInt()
                val ca = cos(angle)
                val sa = sin(angle)
                for (d in 0 until dotsPerSpoke) {
                    val dist = inner + gap * (d + 0.8f)
                    val on = d < lit
                    val color = when {
                        on && d == lit - 1 && lit >= dotsPerSpoke - 1 -> Dot.Red
                        on -> Dot.White
                        else -> Dot.Faint
                    }
                    drawCircle(color, r, Offset(cx + ca * dist, cy + sa * dist))
                }
            }

            val now = state.frame
            for ((start, strength) in state.ripples) {
                val t = ((now - start).toFloat() / RIPPLE_NS).coerceIn(0f, 1f)
                val radius = inner + (maxR - inner) * t
                val alpha = (1f - t) * strength
                val dots = 48
                for (k in 0 until dots) {
                    val a = k * 2f * PI.toFloat() / dots
                    drawCircle(Dot.Red.copy(alpha = alpha), r * 1.1f, Offset(cx + cos(a) * radius, cy + sin(a) * radius))
                }
            }
        }
        Art(
            song,
            Modifier
                .fillMaxSize(0.46f)
                .graphicsLayer {
                    val scale = 1f + 0.05f * state.pulse
                    scaleX = scale
                    scaleY = scale
                },
            px = 600,
            shape = CircleShape,
        )
    }
}
