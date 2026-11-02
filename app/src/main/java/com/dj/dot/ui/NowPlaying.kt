@file:OptIn(ExperimentalMaterial3Api::class)

package com.dj.dot.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.dj.dot.DotApp
import kotlinx.coroutines.delay

private enum class Panel { Art, Lyrics, Queue }

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val dot = DotApp.instance
    val engine = dot.engine
    val app = LocalAppState.current
    val state by engine.state.collectAsStateWithLifecycle()
    val favs by dot.store.favourites.collectAsStateWithLifecycle()
    val showViz by dot.settings.visualizer.flow.collectAsStateWithLifecycle()
    val sleepAt by engine.sleepAt.collectAsStateWithLifecycle()
    val song = state.current ?: return
    var panel by remember { mutableStateOf(Panel.Art) }
    var sleepSheet by remember { mutableStateOf(false) }
    var visualsSheet by remember { mutableStateOf(false) }
    val glyphMode by dot.settings.glyphMode.flow.collectAsStateWithLifecycle()
    var pullDown by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Dot.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    // Down closes the player, up opens the queue.
                    onDragEnd = {
                        if (pullDown > 220f) onClose() else if (pullDown < -220f) panel = Panel.Queue
                        pullDown = 0f
                    },
                    onDragCancel = { pullDown = 0f },
                ) { _, dy -> pullDown += dy }
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.KeyboardArrowDown, onClose, size = 30.dp)
            IconBtn(Icons.Rounded.Fullscreen, { app.party = true }, size = 24.dp)
            Label("Now playing", Modifier.weight(1f), color = Dot.White, align = TextAlign.Center)
            DotIconBtn(DotIcons.Visuals, { visualsSheet = true }, tint = if (glyphMode != 0) Dot.Red else Dot.White)
            IconBtn(Icons.Rounded.Tune, { app.push(Screen.Equalizer) }, description = "Sound and equalizer")
        }

        // Phones in landscape get two panes: the art, lyrics or queue on the left, controls on the right.
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        @Composable
        fun PanelArea(modifier: Modifier) {
            Box(modifier, contentAlignment = Alignment.Center) {
                AnimatedContent(panel, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "panel") { p ->
                    when (p) {
                        Panel.Art -> ArtPanel(song, state.playing, showViz)
                        Panel.Lyrics -> LyricsView()
                        Panel.Queue -> QueuePanel()
                    }
                }
            }
        }

        @Composable
        fun Controls() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DotTitle(song.title, Modifier.basicMarquee(), size = 28.sp)
                    Text(
                        "${song.artist}  ·  ${song.album}", color = Dot.Muted, style = MonoSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val format by engine.format.collectAsStateWithLifecycle()
                    val earphones by dot.headphones.connected.collectAsStateWithLifecycle()
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        format?.let { Chip(it) }
                        earphones?.battery?.let { Chip("Earbuds $it%", red = it <= 20) }
                    }
                }
                val fav = song.id in favs
                // Liking pops the heart; unliking just morphs back.
                val pop = remember { androidx.compose.animation.core.Animatable(1f) }
                var lastFav by remember(song.id) { mutableStateOf(fav) }
                LaunchedEffect(fav) {
                    if (fav && !lastFav) {
                        pop.animateTo(1.4f, androidx.compose.animation.core.tween(110))
                        pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 300f))
                    }
                    lastFav = fav
                }
                IconBtn(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    { dot.store.toggleFavourite(song.id) },
                    Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                    tint = if (fav) Dot.Red else Dot.White,
                )
            }

            Spacer(Modifier.height(10.dp))
            SeekSection()

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(Icons.Rounded.Shuffle, state.shuffle, engine::toggleShuffle)
                IconBtn(Icons.Rounded.SkipPrevious, engine::previous, size = 34.dp)
                val playInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    Modifier
                        .size(78.dp)
                        .pressable(playInteraction, 0.9f)
                        .clip(CircleShape)
                        .background(Dot.White)
                        .clickable(playInteraction, androidx.compose.material3.ripple(color = Dot.Black), onClick = engine::toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, Dot.Black, 38.dp)
                }
                IconBtn(Icons.Rounded.SkipNext, engine::next, size = 34.dp)
                ToggleIcon(
                    if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    state.repeat != Player.REPEAT_MODE_OFF,
                    engine::cycleRepeat,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Pill("Lyrics", { panel = if (panel == Panel.Lyrics) Panel.Art else Panel.Lyrics }, selected = panel == Panel.Lyrics, icon = Icons.Rounded.Lyrics)
                Pill("Queue", { panel = if (panel == Panel.Queue) Panel.Art else Panel.Queue }, selected = panel == Panel.Queue, icon = Icons.AutoMirrored.Rounded.QueueMusic)
                Pill(sleepLabel(sleepAt), { sleepSheet = true }, selected = sleepAt != 0L, icon = Icons.Rounded.Bedtime)
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)) {
                PanelArea(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) { Controls() }
            }
        } else {
            PanelArea(Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(12.dp))
            Controls()
        }
    }

    if (sleepSheet) SleepSheet(onDismiss = { sleepSheet = false })
    if (visualsSheet) VisualsSheet(song, state.playing, onDismiss = { visualsSheet = false })
}

@Composable
private fun ArtPanel(song: com.dj.dot.data.Song, playing: Boolean, showViz: Boolean) {
    val dot = DotApp.instance
    val engine = dot.engine
    val artStyleIndex by dot.settings.artStyle.flow.collectAsStateWithLifecycle()
    val stripStyleIndex by dot.settings.stripStyle.flow.collectAsStateWithLifecycle()
    val artStyle = ArtStyle.entries[artStyleIndex.coerceIn(0, ArtStyle.entries.lastIndex)]
    val stripStyle = StripStyle.entries[stripStyleIndex.coerceIn(0, StripStyle.entries.lastIndex)]
    val needsAnalysis = showViz || artStyle == ArtStyle.DotArt || artStyle == ArtStyle.Halo
    var dragX by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableStateOf<String?>(null) }
    var hearts by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(flash) { if (flash != null) { delay(1100); flash = null } }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val viz = if (needsAnalysis) rememberVizState(engine.spectrum, playing) else null
        Box(
            Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                // On the record, sideways drags scratch instead of skipping.
                .pointerInput(artStyle) {
                    if (artStyle == ArtStyle.Vinyl) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragX < -120f) engine.next() else if (dragX > 120f) engine.previous()
                            dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                    ) { _, dx -> dragX += dx }
                }
                .pointerInput(artStyleIndex) {
                    detectTapGestures(
                        onTap = {
                            val next = (artStyleIndex + 1) % ArtStyle.entries.size
                            dot.settings.artStyle.set(next)
                            flash = ArtStyle.entries[next].label
                        },
                        // Double-tap only ever likes, never unlikes.
                        onDoubleTap = {
                            if (song.id !in dot.store.favourites.value) dot.store.toggleFavourite(song.id)
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            hearts++
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                artStyle == ArtStyle.DotArt && viz != null -> DotArtViz(song, viz, Modifier.fillMaxSize())
                artStyle == ArtStyle.Halo && viz != null -> HaloViz(song, viz, Modifier.fillMaxSize())
                artStyle == ArtStyle.Vinyl -> VinylViz(song, playing, Modifier.fillMaxSize())
                else -> Art(song, Modifier.fillMaxSize(), px = 1080, shape = RoundedCornerShape(26.dp))
            }
            HeartBurst(hearts)
            flash?.let {
                Box(Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(Dot.Black.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Label(it, color = Dot.White)
                }
            }
        }
        if (showViz && viz != null) {
            Spacer(Modifier.height(18.dp))
            StripVisualizer(
                viz, stripStyle,
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .pointerInput(stripStyleIndex) {
                        detectTapGestures {
                            val next = (stripStyleIndex + 1) % StripStyle.entries.size
                            dot.settings.stripStyle.set(next)
                            flash = StripStyle.entries[next].label
                        }
                    },
            )
        }
    }
}

@Composable
private fun QueuePanel() {
    val engine = DotApp.instance.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.index - 1).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        Label("Up next · ${state.queue.size - state.index - 1} songs", Modifier.padding(vertical = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(state.queue, key = { i, s -> "$i-${s.id}" }) { i, s ->
                val current = i == state.index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { engine.jumpTo(i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Art(s, Modifier.size(42.dp), px = 126, shape = RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = if (current) Dot.Red else if (i < state.index) Dot.Muted else Dot.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.artist, color = Dot.Muted, style = MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!current) {
                        IconBtn(Icons.Rounded.KeyboardArrowUp, { engine.moveInQueue(i, i - 1) }, tint = Dot.Muted, size = 20.dp)
                        IconBtn(Icons.Rounded.Close, { engine.removeFromQueue(i) }, tint = Dot.Muted, size = 18.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleIcon(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconBtn(icon, onClick, tint = if (on) Dot.Red else Dot.Muted)
        Box(Modifier.size(4.dp).clip(CircleShape).background(if (on) Dot.Red else Dot.Black))
    }
}

@Composable
private fun sleepLabel(sleepAt: Long): String {
    if (sleepAt == 0L) return "Sleep"
    val left by produceState(sleepAt - SystemClock.elapsedRealtime(), sleepAt) {
        while (true) { value = sleepAt - SystemClock.elapsedRealtime(); delay(1000) }
    }
    return formatTime(left.coerceAtLeast(0))
}

@Composable
private fun SleepSheet(onDismiss: () -> Unit) {
    val engine = DotApp.instance.engine
    val sleepAt by engine.sleepAt.collectAsStateWithLifecycle()
    var minutes by remember { mutableIntStateOf(30) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dot.Surface2,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    DotTitle("Sleep timer", size = 24.sp)
                    Label("Wind the dial, then tap start")
                }
                IconBtn(Icons.Rounded.Close, onDismiss, tint = Dot.Muted)
            }
            Spacer(Modifier.height(8.dp))
            SleepDial(minutes, { minutes = it }, Modifier.fillMaxWidth(0.7f).widthIn(max = 300.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90).forEach { m -> Pill("$m", { minutes = m }, selected = minutes == m) }
            }
            Spacer(Modifier.height(16.dp))
            val startInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(startInteraction, 0.96f)
                    .clip(CircleShape)
                    .background(Dot.Red)
                    .clickable(startInteraction, androidx.compose.material3.ripple()) { engine.setSleepTimer(minutes); onDismiss() }
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Bedtime, null, tint = Dot.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (minutes >= 60) "START · %d:%02d".format(minutes / 60, minutes % 60) else "START · $minutes MIN",
                    color = Dot.White, style = MonoLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("End of song", { engine.sleepAtEndOfSong(); onDismiss() })
                if (sleepAt != 0L) Pill("Turn off", { engine.setSleepTimer(0); onDismiss() })
            }
        }
    }
}

@Composable
fun SheetItem(text: String, icon: ImageVector? = null, red: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (red) Dot.Red else Dot.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Text(text, color = if (red) Dot.Red else Dot.White, fontSize = 15.sp)
    }
}

/** Big red heart that pops and fades over the art on every double-tap. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.HeartBurst(count: Int) {
    if (count == 0) return
    val scale = remember(count) { androidx.compose.animation.core.Animatable(0.3f) }
    val alpha = remember(count) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(count) {
        scale.animateTo(1.15f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 400f))
        alpha.animateTo(0f, androidx.compose.animation.core.tween(350, delayMillis = 250))
    }
    Icon(
        Icons.Rounded.Favorite, null, tint = Dot.Red,
        modifier = Modifier
            .align(Alignment.Center)
            .size(120.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value },
    )
}

/** The only part of Now Playing that changes several times a second, so only it recomposes. */
@Composable
private fun SeekSection() {
    val engine = DotApp.instance.engine
    val (pos, dur) = rememberProgress(200)
    DotSlider(if (dur > 0) pos.toFloat() / dur else 0f, { engine.seekTo((it * dur).toLong()) }, bubble = { formatTime((it * dur).toLong()) })
    Row(Modifier.fillMaxWidth()) {
        Label(formatTime(pos), Modifier.weight(1f))
        Label("-" + formatTime(dur - pos))
    }
}

@Composable
private fun Chip(text: String, red: Boolean = false) {
    Box(Modifier.border(1.dp, if (red) Dot.Red else Dot.Line, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Label(text, color = if (red) Dot.Red else Dot.Muted)
    }
}
