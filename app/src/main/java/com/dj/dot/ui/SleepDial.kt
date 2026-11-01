package com.dj.dot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val SLEEP_DIAL_MAX = 180

/**
 * Analog timer dial. Drag the red knob around the face: one full turn is an hour and you can
 * keep winding up to three. Each hour fills its own ring of 60 dots, outer ring first.
 */
@Composable
fun SleepDial(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val callback by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(minutes)
    var wound by remember { mutableFloatStateOf(minutes.toFloat()) }
    var lastAngle by remember { mutableStateOf<Float?>(null) }

    fun set(value: Int) {
        val v = value.coerceIn(1, SLEEP_DIAL_MAX)
        if (v != current) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            callback(v)
        }
    }

    Box(
        modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    // Tap jumps within the hour the dial is currently on.
                    val hourBase = ((current - 1).coerceAtLeast(0) / 60) * 60
                    val m = hourBase + (angleOf(o, size) / 6f).roundToInt().coerceAtLeast(1)
                    wound = m.toFloat()
                    set(m)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> wound = current.toFloat(); lastAngle = angleOf(o, size) },
                    onDragEnd = { lastAngle = null },
                    onDragCancel = { lastAngle = null },
                ) { change, _ ->
                    change.consume()
                    val a = angleOf(change.position, size)
                    val prev = lastAngle ?: a
                    var delta = a - prev
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    wound = (wound + delta / 6f).coerceIn(1f, SLEEP_DIAL_MAX.toFloat())
                    lastAngle = a
                    set(wound.roundToInt())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val outer = min(size.width, size.height) / 2 * 0.92f
            val ringGap = outer * 0.11f
            val hours = SLEEP_DIAL_MAX / 60
            for (ring in 0 until hours) {
                val radius = outer - ring * ringGap
                val filled = (minutes - ring * 60).coerceIn(0, 60)
                for (i in 0 until 60) {
                    val a = i * 6f
                    val major = i % 5 == 0
                    val lit = i < filled
                    val dotR = when {
                        ring == 0 && major -> 3.2.dp.toPx()
                        ring == 0 -> 1.8.dp.toPx()
                        else -> 1.5.dp.toPx()
                    }
                    drawCircle(
                        if (lit) Dot.White else if (ring == 0 && major) Dot.Muted else Dot.Faint,
                        dotR, polar(c, radius, a),
                    )
                }
            }

            // Knob and a dotted hand on the ring of the hour being set.
            val ring = ((minutes - 1).coerceAtLeast(0) / 60).coerceAtMost(hours - 1)
            val knobAngle = if (minutes % 60 == 0 && minutes > 0) 360f else (minutes % 60) * 6f
            val knobRadius = outer - ring * ringGap
            val handStart = outer * 0.42f
            var d = handStart
            while (d < knobRadius - 10.dp.toPx()) {
                drawCircle(Dot.Red.copy(alpha = 0.55f), 1.6.dp.toPx(), polar(c, d, knobAngle))
                d += 7.dp.toPx()
            }
            drawCircle(Dot.Red, 9.dp.toPx(), polar(c, knobRadius, knobAngle))
            drawCircle(Dot.White, 3.dp.toPx(), polar(c, knobRadius, knobAngle))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DotTitle(if (minutes >= 60) "%d:%02d".format(minutes / 60, minutes % 60) else "$minutes", size = 52.sp)
            Label(if (minutes >= 60) "Hours" else "Minutes")
            Label("Stops at " + LocalTime.now().plusMinutes(minutes.toLong()).format(DateTimeFormatter.ofPattern("h:mm a")), color = Dot.White)
        }
    }
}

/** Clockwise angle from 12 o'clock, 0..360. */
private fun angleOf(p: Offset, size: IntSize): Float {
    val dx = p.x - size.width / 2f
    val dy = p.y - size.height / 2f
    val deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
    return if (deg < 0) deg + 360f else deg
}

private fun polar(c: Offset, r: Float, degFromTop: Float): Offset {
    val rad = (degFromTop - 90f) * PI.toFloat() / 180f
    return Offset(c.x + cos(rad) * r, c.y + sin(rad) * r)
}
