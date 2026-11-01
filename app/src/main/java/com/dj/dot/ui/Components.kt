package com.dj.dot.ui

import android.content.Context
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dj.dot.data.Song
import com.dj.dot.playback.Spectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.min
import kotlin.math.roundToInt

// ---- Text ---------------------------------------------------------------------------------

@Composable
fun DotTitle(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 34.sp,
    color: Color = Dot.White,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) = Text(
    text.uppercase(), modifier, color = color, fontFamily = DotoFamily, fontSize = size,
    lineHeight = size * 1.1f, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
)

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Dot.Muted, align: TextAlign = TextAlign.Start) =
    Text(text.uppercase(), modifier, color = color, style = MonoLabel, textAlign = align, maxLines = 1, overflow = TextOverflow.Ellipsis)

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// ---- Controls -----------------------------------------------------------------------------

@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accent: Boolean = false,
    icon: ImageVector? = null,
) {
    val bg = when { selected -> Dot.White; accent -> Dot.Red; else -> Color.Transparent }
    val fg = if (selected) Dot.Black else Dot.White
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (selected || accent) Modifier else Modifier.border(1.dp, Dot.Line, CircleShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text.uppercase(), color = fg, style = MonoLabel, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dot.White,
    size: Dp = 24.dp,
    description: String? = null,
) {
    Box(modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, onBack)
        Label(title, Modifier.weight(1f).padding(start = 4.dp), color = Dot.White)
        actions()
    }
}

@Composable
fun DottedDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val step = 6.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawCircle(Dot.Line, 1.dp.toPx(), Offset(x, size.height / 2))
            x += step
        }
    }
}

/** Horizontal dot-matrix slider. With [live] it reports while dragging, otherwise on release. */
@Composable
fun DotSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val cb by rememberUpdatedState(onChange)
    val shown = (drag ?: value).coerceIn(0f, 1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) { detectTapGestures { cb((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(live) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(cb); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    val v = (change.position.x / size.width).coerceIn(0f, 1f)
                    drag = v
                    if (live) cb(v)
                }
            },
    ) {
        val thumb = 5.dp.toPx()
        val r = 1.6.dp.toPx()
        val step = 6.dp.toPx()
        val cy = size.height / 2
        val usable = size.width - thumb * 2
        val count = (usable / step).toInt()
        val litX = thumb + shown * usable
        for (i in 0..count) {
            val x = thumb + i * step
            drawCircle(if (x <= litX) Dot.White else Dot.Faint, r, Offset(x, cy))
        }
        drawCircle(Dot.Red, thumb, Offset(litX, cy))
    }
}

/** Vertical bipolar dot slider for equalizer bands; [value] is -1..1. */
@Composable
fun VerticalDotSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .width(36.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { cb((1f - 2f * it.y / size.height).coerceIn(-1f, 1f)) }
            }
            .pointerInput(enabled) {
                if (enabled) detectVerticalDragGestures { change, _ ->
                    cb((1f - 2f * change.position.y / size.height).coerceIn(-1f, 1f))
                }
            },
    ) {
        val step = 8.dp.toPx()
        val rows = (size.height / step).toInt().let { if (it % 2 == 0) it - 1 else it }
        val top = (size.height - (rows - 1) * step) / 2
        val mid = rows / 2
        val target = (mid - value * mid).roundToInt().coerceIn(0, rows - 1)
        val cx = size.width / 2
        val lit = if (enabled) Dot.White else Dot.Muted
        for (r in 0 until rows) {
            val on = r in min(mid, target)..maxOf(mid, target)
            drawCircle(if (on) lit else Dot.Faint, 2.dp.toPx(), Offset(cx, top + r * step))
        }
        drawCircle(if (enabled) Dot.Red else Dot.Muted, 6.dp.toPx(), Offset(cx, top + target * step))
    }
}

// ---- Artwork ------------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
object ArtLoader {
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val missing = Collections.synchronizedSet(HashSet<String>())
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(context: Context, song: Song, key: String, px: Int): ImageBitmap? {
        cache.get(key)?.let { return it }
        if (key in missing) return null
        return withContext(io) {
            runCatching { context.contentResolver.loadThumbnail(song.uri, Size(px, px), null).asImageBitmap() }
                .getOrNull()
                ?.also { cache.put(key, it) }
                ?: run { missing += key; null }
        }
    }
}

@Composable
fun Art(song: Song?, modifier: Modifier = Modifier, px: Int = 192, shape: Shape = RoundedCornerShape(10.dp)) {
    val context = LocalContext.current
    val key = song?.let { "${it.albumId}_$px" }
    var bitmap by remember(key) { mutableStateOf(key?.let(ArtLoader::cached)) }
    LaunchedEffect(key) {
        if (bitmap == null && song != null && key != null) bitmap = ArtLoader.load(context, song, key, px)
    }
    Box(modifier.clip(shape).background(Dot.Surface2), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DotPlaceholder(Modifier.fillMaxSize())
    }
}

/** Faint dot grid with a red centre dot, used when a track has no cover. */
@Composable
fun DotPlaceholder(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = 7
        val step = min(size.width, size.height) / (n + 1)
        val r = step * 0.12f
        val ox = (size.width - step * (n - 1)) / 2
        val oy = (size.height - step * (n - 1)) / 2
        for (i in 0 until n) for (j in 0 until n) {
            val centre = i == n / 2 && j == n / 2
            drawCircle(if (centre) Dot.Red else Dot.Faint, if (centre) r * 2.2f else r, Offset(ox + i * step, oy + j * step))
        }
    }
}

// ---- Lists --------------------------------------------------------------------------------

@Composable
fun SongRow(
    song: Song,
    current: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Art(song, Modifier.size(48.dp), px = 144, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, color = if (current) Dot.Red else Dot.White, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${song.artist}  ·  ${formatTime(song.duration)}", color = Dot.Muted, style = MonoSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) Box(Modifier.size(6.dp).clip(CircleShape).background(Dot.Red))
        IconBtn(Icons.Rounded.MoreVert, onMore, tint = Dot.Muted, size = 20.dp)
    }
}

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        DotPlaceholder(Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        DotTitle(title, size = 22.sp, align = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(hint, color = Dot.Muted, style = MonoSmall, textAlign = TextAlign.Center)
    }
}

// ---- Visualizer ---------------------------------------------------------------------------

/**
 * Glyph-style dot-matrix spectrum. Columns are frequency bands (bass on the left), rows light
 * from the bottom, and the peak dot of a loud band glows red. On every detected beat the grid
 * swells, the bottom rows flash red, and the beat dot under it blinks next to the live BPM.
 */
@Composable
fun GlyphVisualizer(spectrum: Spectrum, playing: Boolean, modifier: Modifier = Modifier, rows: Int = 9) {
    val cols = spectrum.bands
    val levels = remember { FloatArray(cols) }
    var frame by remember { mutableLongStateOf(0L) }
    var pulse by remember { mutableStateOf(0f) }
    var bpm by remember { mutableStateOf(0) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        spectrum.enabled = true
        onDispose { spectrum.enabled = false }
    }
    LaunchedEffect(playing) {
        var lastAudible = System.nanoTime() - AUDIO_LATENCY_NS
        while (true) {
            val settled = withFrameNanos { now ->
                // Read what is audible now, not what was just decoded.
                val audible = System.nanoTime() - AUDIO_LATENCY_NS
                val target = if (playing) spectrum.levelsAt(audible) else null
                val beat = if (playing) spectrum.beatBetween(lastAudible, audible) else 0f
                lastAudible = audible
                pulse = maxOf(pulse * 0.86f, beat)
                bpm = spectrum.bpm
                var max = 0f
                for (i in 0 until cols) {
                    val t = target?.get(i) ?: 0f
                    val l = levels[i]
                    levels[i] = if (t > l) l + (t - l) * 0.55f else l - (l - t) * 0.12f
                    max = maxOf(max, levels[i])
                }
                frame = now
                !playing && max < 0.01f && pulse < 0.01f
            }
            if (settled) break
        }
    }

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            frame // redraw every animation frame
            val cw = size.width / cols
            val rh = size.height / rows
            val r = min(cw, rh) * 0.3f * (1f + 0.22f * pulse)
            val flashRows = if (pulse > 0.3f) 2 else 0
            for (c in 0 until cols) {
                val lit = (levels[c] * rows).roundToInt()
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
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (pulse > 0.25f) Dot.Red else Dot.Faint),
            )
            Spacer(Modifier.width(8.dp))
            Label(if (bpm > 0) "$bpm BPM" else "— BPM", color = if (bpm > 0) Dot.White else Dot.Muted)
        }
    }
}

/** Roughly how far decoded audio runs ahead of the speaker. */
private const val AUDIO_LATENCY_NS = 200_000_000L
