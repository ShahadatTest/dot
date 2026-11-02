@file:OptIn(ExperimentalMaterial3Api::class)

package com.dj.dot.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.data.Song
import com.dj.dot.playback.GlyphMode
import kotlin.math.roundToInt

/**
 * Everything visual in one place: cover style, visualizer and the Glyph strip. Every option is
 * a live preview of the song that's playing, so you pick by looking, not by reading.
 */
@Composable
fun VisualsSheet(song: Song, playing: Boolean, onDismiss: () -> Unit) {
    val dot = DotApp.instance
    val s = dot.settings
    val haptic = LocalHapticFeedback.current
    val artStyle by s.artStyle.flow.collectAsStateWithLifecycle()
    val stripStyle by s.stripStyle.flow.collectAsStateWithLifecycle()
    val vizOn by s.visualizer.flow.collectAsStateWithLifecycle()
    val viz = rememberVizState(dot.engine.spectrum, playing)
    val app = LocalAppState.current

    fun pick(action: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dot.Black,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            DotTitle("Visuals", size = 30.sp)
            Label("Modes, cover styles, visualizer and Glyph lights")

            SectionHeader("Modes", "Full-screen ways to listen")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Party mode", false, Modifier.weight(1f).aspectRatio(1.35f), { pick { onDismiss(); app.party = true } }) {
                    StripVisualizer(viz, StripStyle.Mirror, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 22.dp), showReadout = false)
                }
                Tile("Bedside clock", false, Modifier.weight(1f).aspectRatio(1.35f), { pick { onDismiss(); app.bedside = true } }) {
                    val now = remember { java.util.Date() }
                    DotTitle(android.text.format.DateFormat.format("h:mm", now).toString(), size = 30.sp)
                }
            }

            SectionHeader("Cover", "Tap the art on the player to cycle")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ArtStyle.entries.forEach { style ->
                    Tile(style.label, artStyle == style.ordinal, Modifier.weight(1f).aspectRatio(0.78f), { pick { s.artStyle.set(style.ordinal) } }) {
                        when (style) {
                            ArtStyle.Cover -> Art(song, Modifier.fillMaxSize().padding(10.dp), px = 300, shape = RoundedCornerShape(10.dp))
                            ArtStyle.DotArt -> DotArtViz(song, viz, Modifier.fillMaxSize().padding(8.dp), grid = 30)
                            ArtStyle.Halo -> HaloViz(song, viz, Modifier.fillMaxSize().padding(6.dp))
                            ArtStyle.Vinyl -> VinylViz(song, playing, Modifier.fillMaxSize().padding(6.dp), interactive = false)
                        }
                    }
                }
            }

            SectionHeader("Visualizer", "Tap the visualizer on the player to cycle")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StripStyle.entries.forEach { style ->
                    Tile(style.label, vizOn && stripStyle == style.ordinal, Modifier.weight(1f).aspectRatio(0.9f), {
                        pick { s.visualizer.set(true); s.stripStyle.set(style.ordinal) }
                    }) {
                        StripVisualizer(viz, style, Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 14.dp), showReadout = false)
                    }
                }
                Tile("Off", !vizOn, Modifier.weight(1f).aspectRatio(0.9f), { pick { s.visualizer.set(false) } }) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = Dot.Muted)
                }
            }

            if (dot.glyph.supported) GlyphSection(::pick)
        }
    }
}

@Composable
private fun GlyphSection(pick: (() -> Unit) -> Unit) {
    val s = DotApp.instance.settings
    val mode by s.glyphMode.flow.collectAsStateWithLifecycle()
    val brightness by s.glyphBrightness.flow.collectAsStateWithLifecycle()
    val sensitivity by s.glyphSensitivity.flow.collectAsStateWithLifecycle()
    val beatFlash by s.glyphBeatFlash.flow.collectAsStateWithLifecycle()

    SectionHeader("Glyph lights", "The light strip on the back of your phone")
    Row(Modifier.fillMaxWidth().height(232.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(72.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Dot.Surface2), contentAlignment = Alignment.Center) {
            StripMirror(Modifier.width(24.dp).fillMaxHeight().padding(vertical = 16.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlyphMode.entries.chunked(2).forEach { pair ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { m ->
                        Tile(m.label, mode == m.ordinal, Modifier.weight(1f).fillMaxHeight(), { pick { s.glyphMode.set(m.ordinal) } }) {
                            ModeArt(m)
                        }
                    }
                }
            }
        }
    }

    if (mode != GlyphMode.Off.ordinal) {
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dot.Surface2).padding(horizontal = 16.dp, vertical = 10.dp)) {
            PanelSlider("Brightness", "$brightness%", (brightness - 10) / 90f) { s.glyphBrightness.set(10 + (it * 90).roundToInt()) }
            if (mode == GlyphMode.Meter.ordinal) {
                PanelSlider("Sensitivity", "$sensitivity%", (sensitivity - 50) / 150f) { s.glyphSensitivity.set(50 + (it * 150).roundToInt()) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Flash on beats", color = Dot.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Every kick lights the whole strip", color = Dot.Muted, style = MonoSmall)
                }
                Switch(
                    beatFlash, { s.glyphBeatFlash.set(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Dot.White, checkedTrackColor = Dot.Red, checkedBorderColor = Dot.Red,
                        uncheckedThumbColor = Dot.Muted, uncheckedTrackColor = Dot.Black, uncheckedBorderColor = Dot.Line,
                    ),
                )
            }
        }
    }
}

/** Little static drawings of what each Glyph mode does. */
@Composable
private fun ModeArt(mode: GlyphMode) {
    Canvas(Modifier.size(width = 12.dp, height = 44.dp)) {
        val step = size.height / 6
        val levels = when (mode) {
            GlyphMode.Off -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            GlyphMode.Meter -> floatArrayOf(0f, 0f, 0.4f, 1f, 1f, 1f)
            GlyphMode.Pulse -> floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f)
            GlyphMode.Progress -> floatArrayOf(0f, 0f, 0f, 0.7f, 0.7f, 0.7f)
        }
        for (i in 0 until 6) {
            drawRoundRect(
                lerp(Dot.Faint, if (mode == GlyphMode.Pulse) Dot.Red else Dot.White, levels[i]),
                topLeft = Offset(size.width / 2 - 3.dp.toPx(), step * i + step * 0.15f),
                size = Size(6.dp.toPx(), step * 0.7f),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

/** A selectable preview card; selected gets a white outline and a red dot. */
@Composable
private fun Tile(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val ring by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "ring")
    Column(modifier) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Dot.Surface2)
                .border(2.dp, lerp(Color.Transparent, Dot.White, ring), RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(7.dp).clip(CircleShape).background(Dot.Red))
        }
        Spacer(Modifier.height(6.dp))
        Label(label, Modifier.fillMaxWidth(), color = if (selected) Dot.White else Dot.Muted, align = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Dot.Red))
        Spacer(Modifier.width(8.dp))
        Label(title, color = Dot.White)
    }
    Text(hint, color = Dot.Muted, style = MonoSmall, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
}

@Composable
fun SheetHandle() {
    Canvas(Modifier.padding(vertical = 12.dp).size(width = 44.dp, height = 4.dp)) {
        val step = size.width / 8
        for (i in 0 until 8) drawCircle(Dot.Line, 2.dp.toPx(), Offset(step * i + step / 2, size.height / 2))
    }
}

@Composable
private fun PanelSlider(title: String, value: String, fraction: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(title, color = Dot.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Label(value, color = Dot.White)
        }
        DotSlider(fraction, onChange, live = true, steps = 18)
    }
}

/** Live copy of the six rear LEDs, top to bottom. */
@Composable
private fun StripMirror(modifier: Modifier) {
    val glyph = DotApp.instance.glyph
    val mode by DotApp.instance.settings.glyphMode.flow.collectAsStateWithLifecycle()
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mode) {
        while (mode != GlyphMode.Off.ordinal) {
            withFrameNanos { frame = it }
            kotlinx.coroutines.delay(33)
        }
        frame = 0L
    }
    Canvas(modifier) {
        frame
        val values = glyph.preview
        val step = size.height / values.size
        for (i in values.indices) {
            val v = values[i].coerceIn(0f, 1f)
            val cy = step * i + step / 2
            if (v > 0.5f) drawCircle(Dot.White.copy(alpha = (v - 0.5f) * 0.4f), size.width * 0.9f, Offset(size.width / 2, cy))
            drawRoundRect(
                lerp(Dot.Faint, Dot.White, v),
                topLeft = Offset(size.width / 2 - 4.dp.toPx(), cy - step * 0.36f),
                size = Size(8.dp.toPx(), step * 0.72f),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}
