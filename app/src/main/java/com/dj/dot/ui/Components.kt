package com.dj.dot.ui

import android.content.Context
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.draw.blur
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

/** Shrinks a little while pressed and springs back, for any clickable. */
fun Modifier.pressable(interaction: androidx.compose.foundation.interaction.MutableInteractionSource, pressedScale: Float = 0.86f): Modifier =
    composed {
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) pressedScale else 1f,
            androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = if (pressed) 1200f else 500f),
            label = "press",
        )
        graphicsLayer { scaleX = scale; scaleY = scale }
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
    val bg by androidx.compose.animation.animateColorAsState(
        when { selected -> Dot.White; accent -> Dot.Red; else -> Color.Transparent }, tween(200), label = "pillBg",
    )
    val fg by androidx.compose.animation.animateColorAsState(if (selected) Dot.Black else Dot.White, tween(200), label = "pillFg")
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier
            .pressable(interaction, 0.93f)
            .clip(CircleShape)
            .background(bg)
            .then(if (selected || accent) Modifier else Modifier.border(1.dp, Dot.Line, CircleShape))
            .clickable(interaction, androidx.compose.material3.ripple(), onClick = onClick)
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
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.size(44.dp).pressable(interaction).clip(CircleShape)
            .clickable(interaction, androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedIcon(icon, tint, size, description)
    }
}

/** Icon that scales and cross-fades when it changes (play to pause, heart, repeat) and fades its tint. */
@Composable
fun AnimatedIcon(icon: ImageVector, tint: Color, size: Dp, description: String? = null) {
    val color by androidx.compose.animation.animateColorAsState(tint, tween(220), label = "iconTint")
    androidx.compose.animation.AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.4f) +
                androidx.compose.animation.fadeIn(tween(120))) togetherWith
                (androidx.compose.animation.scaleOut(tween(120), targetScale = 0.4f) + androidx.compose.animation.fadeOut(tween(100)))
        },
        label = "icon",
    ) { target ->
        Icon(target, description, tint = color, modifier = Modifier.size(size))
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

/**
 * Horizontal dot-matrix slider. It swells while touched and, given [bubble], floats a value
 * label above the finger. With [steps] it snaps and ticks; with [live] it reports while
 * dragging, otherwise on release.
 */
@Composable
fun DotSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    steps: Int = 0,
    bubble: ((Float) -> String)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val cb by rememberUpdatedState(onChange)
    var drag by remember { mutableStateOf<Float?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val grow by animateFloatAsState(if (pressed || drag != null) 1f else 0f, tween(160), label = "grow")

    fun snap(v: Float) = if (steps > 0) (v * steps).roundToInt() / steps.toFloat() else v
    fun update(x: Float, report: Boolean) {
        val v = snap((x / width.coerceAtLeast(1)).coerceIn(0f, 1f))
        val before = drag
        drag = v
        if (steps > 0 && before != null && before != v) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (report) cb(v)
    }

    val shown = (drag ?: value).coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            cb(snap((it.x / size.width).coerceIn(0f, 1f)))
                        },
                    )
                }
                .pointerInput(live, steps) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            update(it.x, live)
                        },
                        onDragEnd = { drag?.let(cb); drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        update(change.position.x, live)
                    }
                },
        ) {
            val thumb = (5f + 4f * grow).dp.toPx()
            val r = (1.6f + 0.9f * grow).dp.toPx()
            val step = 6.dp.toPx()
            val cy = size.height / 2
            val usable = size.width - 9.dp.toPx() * 2
            val start = 9.dp.toPx()
            val count = (usable / step).toInt()
            val litX = start + shown * usable
            for (i in 0..count) {
                val x = start + i * step
                drawCircle(if (x <= litX) Dot.White else Dot.Faint, r, Offset(x, cy))
            }
            drawCircle(Dot.Red, thumb, Offset(litX, cy))
            if (grow > 0f) drawCircle(Dot.Red.copy(alpha = 0.18f * grow), thumb * 2.2f, Offset(litX, cy))
        }
        if (bubble != null && drag != null && width > 0) {
            val density = LocalDensity.current
            Box(
                Modifier.layout { measurable, _ ->
                    val p = measurable.measure(Constraints())
                    val start = with(density) { 9.dp.toPx() }
                    val x = start + shown * (width - start * 2)
                    layout(0, 0) {
                        p.place(
                            (x - p.width / 2).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0)),
                            -p.height - with(density) { 6.dp.roundToPx() },
                        )
                    }
                },
            ) {
                Box(Modifier.clip(CircleShape).background(Dot.White).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(bubble(shown), color = Dot.Black, style = MonoLabel, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Drag-to-fly scroller for long lists. Appears while the list moves, then fades; while dragged
 * it shows a big dot-matrix bubble with the section under the finger.
 */
@Composable
fun FastScroller(
    listState: LazyListState,
    count: Int,
    itemOffset: Int,
    labelFor: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    if (count < 30) return
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1400); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 500), label = "scroller")
    val fraction = if (dragging) dragFraction
    else ((listState.firstVisibleItemIndex - itemOffset).coerceAtLeast(0).toFloat() / (count - 1)).coerceIn(0f, 1f)

    BoxWithConstraints(modifier.fillMaxHeight().width(64.dp)) {
        val heightPx = constraints.maxHeight.toFloat()
        fun jump(y: Float) {
            dragFraction = (y / heightPx).coerceIn(0f, 1f)
            val index = (dragFraction * (count - 1)).roundToInt()
            val l = labelFor(index)
            if (l != label) { label = l; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            scope.launch { listState.scrollToItem(index + itemOffset) }
        }
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .then(
                    if (shown) Modifier.pointerInput(count) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; jump(it.y) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ -> change.consume(); jump(change.position.y) }
                    } else Modifier,
                ),
        ) {
            val x = size.width - 10.dp.toPx()
            val step = 9.dp.toPx()
            var y = step
            while (y < size.height - step) {
                drawCircle(Dot.Faint, 1.4.dp.toPx(), Offset(x, y))
                y += step
            }
            val ty = 14.dp.toPx() + fraction * (size.height - 28.dp.toPx())
            for (k in -2..2) drawCircle(if (dragging) Dot.Red else Dot.White, 2.6.dp.toPx(), Offset(x, ty + k * 5.dp.toPx()))
        }
        if (dragging) {
            val pad = with(LocalDensity.current) { 14.dp.toPx() }
            val bubbleY = pad + fraction * (heightPx - pad * 2)
            Box(
                Modifier
                    .offset { IntOffset(-90.dp.roundToPx(), (bubbleY - 32.dp.toPx()).roundToInt()) }
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Dot.White),
                contentAlignment = Alignment.Center,
            ) { DotTitle(label, size = 30.sp, color = Dot.Black) }
        }
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
    // Three buckets keep one decode per album per size class instead of one per call site.
    val bucket = when { px <= 200 -> 192; px <= 640 -> 600; else -> 1080 }
    val key = song?.let { "${it.albumId}_$bucket" }
    var bitmap by remember(key) { mutableStateOf(key?.let(ArtLoader::cached)) }
    LaunchedEffect(key) {
        if (bitmap == null && song != null && key != null) bitmap = ArtLoader.load(context, song, key, bucket)
    }
    Box(modifier.clip(shape).background(Dot.Surface2), contentAlignment = Alignment.Center) {
        val b = bitmap
        when {
            b == null -> DotPlaceholder(Modifier.fillMaxSize())
            // Square-ish covers fill the tile; a hair of trim is invisible.
            b.width.toFloat() / b.height in 0.9f..1.1f -> Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Wide or tall covers show in full over a blurred copy of themselves.
            else -> {
                Image(
                    b, null,
                    Modifier.fillMaxSize().blur(20.dp).graphicsLayer { alpha = 0.5f },
                    contentScale = ContentScale.Crop,
                )
                Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
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
