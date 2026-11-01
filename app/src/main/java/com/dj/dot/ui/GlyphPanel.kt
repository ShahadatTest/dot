@file:OptIn(ExperimentalMaterial3Api::class)

package com.dj.dot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.playback.GlyphMode
import kotlin.math.roundToInt

/** Six dots in a column, the Glyph strip in miniature; red when the lights are on. */
@Composable
fun GlyphIcon(on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width = 10.dp, height = 24.dp)) {
            val step = size.height / 6
            for (i in 0 until 6) {
                drawCircle(if (on) Dot.Red else Dot.White, 1.7.dp.toPx(), Offset(size.width / 2, step * i + step / 2))
            }
        }
    }
}

@Composable
fun GlyphPanel(onDismiss: () -> Unit) {
    val dot = DotApp.instance
    val s = dot.settings
    val mode by s.glyphMode.flow.collectAsStateWithLifecycle()
    val brightness by s.glyphBrightness.flow.collectAsStateWithLifecycle()
    val sensitivity by s.glyphSensitivity.flow.collectAsStateWithLifecycle()
    val beatFlash by s.glyphBeatFlash.flow.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Dot.Surface2) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            StripMirror(Modifier.width(56.dp).height(260.dp))
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                DotTitle("Glyph", size = 26.sp)
                Label("The light strip on the back")
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlyphMode.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { m -> Pill(m.label, { s.glyphMode.set(m.ordinal) }, selected = mode == m.ordinal) }
                        }
                    }
                }
                if (mode != GlyphMode.Off.ordinal) {
                    Spacer(Modifier.height(14.dp))
                    PanelSlider("Brightness", "$brightness%", (brightness - 10) / 90f) { s.glyphBrightness.set(10 + (it * 90).roundToInt()) }
                    if (mode == GlyphMode.Meter.ordinal) {
                        PanelSlider("Sensitivity", "${sensitivity}%", (sensitivity - 50) / 150f) { s.glyphSensitivity.set(50 + (it * 150).roundToInt()) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Flash on beats", color = Dot.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
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
    }
}

@Composable
private fun PanelSlider(title: String, value: String, fraction: Float, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
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
    var frame by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Dot.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(24.dp).height(230.dp)) {
            frame
            val values = glyph.preview
            val step = size.height / values.size
            for (i in values.indices) {
                val v = values[i].coerceIn(0f, 1f)
                val c = Offset(size.width / 2, step * i + step / 2)
                drawRoundRect(
                    lerp(Dot.Faint, Dot.White, v),
                    topLeft = Offset(c.x - 4.dp.toPx(), c.y - step * 0.36f),
                    size = androidx.compose.ui.geometry.Size(8.dp.toPx(), step * 0.72f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                )
                if (v > 0.6f) drawCircle(Dot.White.copy(alpha = (v - 0.6f) * 0.5f), 12.dp.toPx(), c)
            }
        }
    }
}
