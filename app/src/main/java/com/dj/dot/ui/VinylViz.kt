package com.dj.dot.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.dj.dot.DotApp
import com.dj.dot.data.Song
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** 33⅓ RPM in degrees per second. */
private const val RPM_33 = 200f
/** How much music one full turn of a scratch covers, like a real 33 on a deck. */
private const val MS_PER_TURN = 1800f

/**
 * The cover as the label of a spinning record. It spins up on play and winds down on pause;
 * grab it to scratch through the song. A dotted tonearm tracks progress across the grooves.
 */
@Composable
fun VinylViz(song: Song, playing: Boolean, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val engine = DotApp.instance.engine
    val haptic = LocalHapticFeedback.current
    var angle by remember { mutableFloatStateOf(0f) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var scratching by remember { mutableStateOf(false) }
    val grooves = remember { arrayOfNulls<ImageBitmap>(1) }

    LaunchedEffect(playing, scratching) {
        if (scratching) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val target = if (playing) RPM_33 else 0f
            // Motors spin up quicker than a platter coasts down.
            val rate = if (target > velocity) 3f else 0.9f
            velocity += (target - velocity) * min(1f, rate * dt)
            angle = (angle + velocity * dt) % 360f
            if (!playing && velocity < 0.5f) { velocity = 0f; break }
        }
    }

    Box(
        modifier.then(
            if (!interactive) Modifier else Modifier.pointerInput(Unit) {
                var lastAngle = 0f
                var scratchMs = 0f
                var tick = 0f
                fun fingerAngle(o: Offset) = Math.toDegrees(atan2((o.y - size.height / 2).toDouble(), (o.x - size.width / 2).toDouble())).toFloat()
                detectDragGestures(
                    onDragStart = { o ->
                        scratching = true
                        velocity = 0f
                        lastAngle = fingerAngle(o)
                        scratchMs = 0f
                    },
                    onDragEnd = {
                        val d = engine.duration()
                        engine.seekTo((engine.position() + scratchMs.toLong()).coerceIn(0L, (d - 500).coerceAtLeast(0L)))
                        scratching = false
                    },
                    onDragCancel = { scratching = false },
                ) { change, _ ->
                    change.consume()
                    val a = fingerAngle(change.position)
                    var delta = a - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = a
                    angle = (angle + delta) % 360f
                    scratchMs += delta / 360f * MS_PER_TURN
                    tick += delta
                    if (kotlin.math.abs(tick) > 24f) {
                        tick = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2 * 0.96f
            val c = Offset(size.width / 2, size.height / 2)
            val px = (r * 2).toInt().coerceAtLeast(1)
            val image = grooves[0]?.takeIf { it.width == px } ?: paintGrooves(px, 3.5.dp.toPx(), 5.dp.toPx()).also { grooves[0] = it }

            drawCircle(Color(0xFF070707), r, c)
            rotate(angle, c) { drawImage(image, Offset(c.x - px / 2f, c.y - px / 2f)) }
            // Light from a fixed window: two soft bands that stay put while the grooves turn.
            drawCircle(
                Brush.sweepGradient(
                    0f to Color.Transparent, 0.08f to Color.White.copy(alpha = 0.09f), 0.16f to Color.Transparent,
                    0.5f to Color.Transparent, 0.58f to Color.White.copy(alpha = 0.06f), 0.66f to Color.Transparent,
                    1f to Color.Transparent,
                    center = c,
                ),
                r, c,
            )
            drawCircle(Dot.Line, r, c, style = Stroke(1.dp.toPx()))
        }
        Art(song, Modifier.fillMaxSize(0.38f).graphicsLayer { rotationZ = angle }, px = 420, shape = CircleShape)
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2 * 0.96f
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Dot.Black, 4.dp.toPx(), c)
            drawCircle(Dot.Muted, 4.dp.toPx(), c, style = Stroke(1.dp.toPx()))

            // Tonearm from a pivot past the top-right edge to a stylus that walks inward.
            val pivot = Offset(c.x + r * 0.98f, c.y - r * 0.98f)
            val reach = r * (0.93f - 0.45f * engine.progress)
            val theta = (-30f * PI / 180f).toFloat()
            val stylus = Offset(c.x + cos(theta) * reach, c.y + sin(theta) * reach)
            val steps = 18
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                drawCircle(Dot.White.copy(alpha = 0.9f), 2.dp.toPx(), Offset(pivot.x + (stylus.x - pivot.x) * t, pivot.y + (stylus.y - pivot.y) * t))
            }
            drawCircle(Dot.Surface2, 9.dp.toPx(), pivot)
            drawCircle(Dot.White, 9.dp.toPx(), pivot, style = Stroke(1.5.dp.toPx()))
            drawCircle(Dot.Red, 4.dp.toPx(), stylus)
        }
    }
}

/** Concentric dotted grooves with a few gaps and blank bands between "tracks", painted once. */
private fun paintGrooves(size: Int, ringGap: Float, dotGap: Float): ImageBitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val c = size / 2f
    val outer = c * 0.97f
    val inner = c * 0.42f
    val rnd = Random(7)
    val bands = List(3) { inner + (outer - inner) * (0.25f + it * 0.25f) + rnd.nextFloat() * ringGap * 2 }
    var radius = inner
    var ring = 0
    while (radius < outer) {
        if (bands.none { kotlin.math.abs(it - radius) < ringGap * 0.9f }) {
            val count = (2 * PI * radius / dotGap).toInt()
            val gapStart = rnd.nextInt(count)
            val gapLen = 2 + rnd.nextInt(6)
            paint.color = if (ring % 5 == 0) 0xFF3A3A3A.toInt() else 0xFF262626.toInt()
            for (k in 0 until count) {
                if ((k - gapStart + count) % count < gapLen) continue
                val a = k * 2 * PI / count
                canvas.drawCircle(c + cos(a).toFloat() * radius, c + sin(a).toFloat() * radius, ringGap * 0.22f, paint)
            }
        }
        radius += ringGap
        ring++
    }
    return bmp.asImageBitmap()
}
