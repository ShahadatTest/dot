package com.dj.dot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.playback.Lyrics
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the lyrics list shows: a sung line, or an instrumental break between lines. */
private sealed interface LyricItem {
    val start: Long
    val end: Long

    data class Line(val text: String, override val start: Long, override val end: Long) : LyricItem
    data class Break(override val start: Long, override val end: Long) : LyricItem
}

private val lineStyle = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)

@Composable
fun LyricsView(modifier: Modifier = Modifier) {
    val dot = DotApp.instance
    val engine = dot.engine
    val lyrics by engine.lyrics.collectAsStateWithLifecycle()
    val state by engine.state.collectAsStateWithLifecycle()
    val song = state.current
    val l = lyrics

    if (l == null || song == null) {
        EmptyState(
            "No lyrics",
            if (android.os.Environment.isExternalStorageManager()) "Keep a .lrc file with the song's name in the same folder."
            else "Embedded lyrics show here. For .lrc files, allow file access in Settings.",
            modifier,
        )
        return
    }

    // Playhead read every frame while visible, so the karaoke fill is smooth.
    var position by remember { mutableLongStateOf(engine.position()) }
    LaunchedEffect(state.playing) {
        while (true) {
            withFrameNanos { position = engine.position() }
            if (!state.playing) { delay(250) }
        }
    }

    if (l.synced) SyncedLyrics(l, song.id, song.duration, position, modifier)
    else PlainLyrics(l, position, engine.duration(), modifier)
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, songId: Long, songDuration: Long, position: Long, modifier: Modifier) {
    val dot = DotApp.instance
    var offset by remember(songId) { mutableIntStateOf(dot.settings.lyricsOffset(songId)) }
    val items = remember(lyrics, songDuration) { buildItems(lyrics, songDuration) }
    val shifted = position - offset
    val active = items.indexOfLast { it.start <= shifted }

    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var autoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) autoScroll = false
        else if (!autoScroll) { delay(3500); autoScroll = true }
    }
    LaunchedEffect(active, autoScroll) {
        if (autoScroll && active >= 0) {
            val anchor = (listState.layoutInfo.viewportSize.height * 0.32f).toInt()
            listState.animateScrollToItem(active, -anchor)
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 120.dp, bottom = 260.dp),
            modifier = Modifier
                .fillMaxSize()
                .edgeFade()
                .padding(end = 8.dp),
        ) {
            itemsIndexed(items, key = { i, _ -> i }) { i, item ->
                val distance = if (active < 0) i + 1 else i - active
                when (item) {
                    is LyricItem.Line -> LyricLineRow(item, distance, shifted) { dot.engine.seekTo((item.start + offset).coerceAtLeast(0)) }
                    is LyricItem.Break -> BreakRow(item, i == active, shifted)
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Earlier", { offset -= 250; dot.settings.setLyricsOffset(songId, offset) })
            Label(if (offset == 0) "In sync" else "%+.2fs".format(offset / 1000f), color = if (offset == 0) Dot.Muted else Dot.White)
            Pill("Later", { offset += 250; dot.settings.setLyricsOffset(songId, offset) })
            AnimatedVisibility(!autoScroll, enter = fadeIn(), exit = fadeOut()) {
                Pill("Sync", { autoScroll = true }, accent = true)
            }
        }
    }
}

@Composable
private fun LyricLineRow(item: LyricItem.Line, distance: Int, position: Long, onTap: () -> Unit) {
    val isActive = distance == 0
    val targetAlpha = when {
        isActive -> 1f
        distance < 0 -> 0.32f
        distance == 1 -> 0.55f
        distance == 2 -> 0.4f
        else -> 0.26f
    }
    val alpha by animateFloatAsState(targetAlpha, tween(350), label = "alpha")
    val scale by animateFloatAsState(if (isActive) 1f else 0.9f, tween(350), label = "scale")

    // Most lines are sung faster than the gap to the next one, so cap the fill duration.
    val span = item.end - item.start
    val sung = min(span, max(1500L, item.text.length * 110L)).coerceAtLeast(1)
    val progress = if (isActive) ((position - item.start).toFloat() / sung).coerceIn(0f, 1f) else if (distance < 0) 1f else 0f

    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 4.dp, vertical = 11.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    ) {
        KaraokeText(item.text, if (isActive) progress else if (distance < 0) 1f else 0f)
    }
}

/** Muted text with a white copy revealed line by line up to [progress]. */
@Composable
private fun KaraokeText(text: String, progress: Float) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box {
        Text(text, style = lineStyle, color = Dot.Muted.copy(alpha = 0.55f), onTextLayout = { layout = it })
        if (progress > 0f) {
            Text(
                text, style = lineStyle, color = Dot.White,
                modifier = Modifier.drawWithContent {
                    val l = layout ?: return@drawWithContent
                    if (progress >= 1f) { drawContent(); return@drawWithContent }
                    val widths = FloatArray(l.lineCount) { l.getLineRight(it) - l.getLineLeft(it) }
                    var remaining = progress * widths.sum()
                    for (i in 0 until l.lineCount) {
                        if (remaining <= 0f) break
                        val left = l.getLineLeft(i)
                        val fill = min(widths[i], remaining)
                        clipRect(left, l.getLineTop(i), left + fill, l.getLineBottom(i)) { this@drawWithContent.drawContent() }
                        remaining -= widths[i]
                    }
                },
            )
        }
    }
}

/** Three dots that fill across an instrumental break and breathe while waiting. */
@Composable
private fun BreakRow(item: LyricItem.Break, active: Boolean, position: Long) {
    AnimatedVisibility(active, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val progress = ((position - item.start).toFloat() / (item.end - item.start).coerceAtLeast(1)).coerceIn(0f, 1f)
        val breathe = 1f + 0.12f * sin(position / 260f)
        Canvas(Modifier.padding(horizontal = 6.dp, vertical = 18.dp).width(84.dp).height(20.dp)) {
            val r = 6.dp.toPx() * breathe
            for (k in 0 until 3) {
                val local = ((progress * 3f) - k).coerceIn(0f, 1f)
                val cx = r + k * (size.width - 2 * r) / 2
                drawCircle(Dot.Faint, r, Offset(cx, size.height / 2))
                if (local > 0f) drawCircle(if (local >= 1f) Dot.White else Dot.Red, r * (0.35f + 0.65f * local), Offset(cx, size.height / 2))
            }
        }
    }
    if (!active) Spacer(Modifier.height(2.dp))
}

@Composable
private fun PlainLyrics(lyrics: Lyrics, position: Long, duration: Long, modifier: Modifier) {
    val listState = rememberLazyListState()
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var autoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(dragged) {
        if (dragged) autoScroll = false else if (!autoScroll) { delay(5000); autoScroll = true }
    }
    // No timestamps: drift through the text in step with the song.
    val target = if (duration > 0) ((position.toFloat() / duration) * lyrics.lines.size).toInt() - 2 else 0
    LaunchedEffect(target / 2, autoScroll) {
        if (autoScroll) listState.animateScrollToItem(target.coerceIn(0, (lyrics.lines.size - 1).coerceAtLeast(0)))
    }
    Column(modifier.fillMaxSize()) {
        Label("Lyrics · not time-synced", Modifier.padding(start = 4.dp, bottom = 6.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().edgeFade(), contentPadding = PaddingValues(vertical = 40.dp)) {
            itemsIndexed(lyrics.lines) { _, line ->
                Text(
                    line.text.ifBlank { " " },
                    style = lineStyle.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
                    color = Dot.White.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** Fades content out towards the top and bottom edges. */
private fun Modifier.edgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.14f to Color.Black,
                0.8f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private fun buildItems(lyrics: Lyrics, songDuration: Long): List<LyricItem> {
    val lines = lyrics.lines
    val out = ArrayList<LyricItem>()
    if (lines.isNotEmpty() && lines.first().timeMs > 4000) out += LyricItem.Break(0, lines.first().timeMs)
    for (i in lines.indices) {
        val start = lines[i].timeMs
        val end = lines.getOrNull(i + 1)?.timeMs ?: max(songDuration, start + 4000)
        val text = lines[i].text.trim()
        if (text.isEmpty()) {
            if (end - start > 3000) out += LyricItem.Break(start, end)
        } else {
            out += LyricItem.Line(text, start, end)
            // A long silence after a line becomes its own break.
            val gap = end - start
            if (gap > 12_000 && lines.getOrNull(i + 1)?.text?.isNotBlank() == true) {
                val breakStart = start + max(5000L, text.length * 140L)
                if (end - breakStart > 4000) out += LyricItem.Break(breakStart, end)
            }
        }
    }
    return out.ifEmpty { lines.map { LyricItem.Line(it.text, it.timeMs, it.timeMs + 4000) } }
}
