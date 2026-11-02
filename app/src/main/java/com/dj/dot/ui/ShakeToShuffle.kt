package com.dj.dot.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import kotlin.math.abs

/**
 * A real back-and-forth shake jumps to a random song: four strong movements in alternating
 * directions within about a second, with gravity removed. Putting the phone down makes one jolt
 * and a small bounce, so it never qualifies. Listens only while Dot is on screen.
 */
@Composable
fun ShakeToShuffle() {
    val dot = DotApp.instance
    val enabled by dot.settings.shakeForRandom.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    LifecycleStartEffect(enabled) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravity = FloatArray(3)
        var lastSign = 0
        var swings = 0
        var windowStart = 0L
        var lastSwing = 0L
        var cooldownUntil = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = FloatArray(3)
                for (i in 0..2) {
                    if (linear == null) {
                        gravity[i] = gravity[i] * 0.8f + e.values[i] * 0.2f
                        v[i] = e.values[i] - gravity[i]
                    } else {
                        v[i] = e.values[i]
                    }
                }
                val axis = (0..2).maxBy { abs(v[it]) }
                val a = v[axis]
                val now = SystemClock.uptimeMillis()
                if (abs(a) < SWING || now < cooldownUntil) return
                if (now - windowStart > WINDOW_MS) {
                    windowStart = now
                    swings = 0
                    lastSign = 0
                }
                val sign = if (a > 0) 1 else -1
                if (sign != lastSign && now - lastSwing > 70) {
                    swings++
                    lastSign = sign
                    lastSwing = now
                }
                if (swings >= SWINGS_NEEDED) {
                    swings = 0
                    cooldownUntil = now + 2000
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dot.engine.playRandom()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && sensor != null) sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onStopOrDispose { sensors.unregisterListener(listener) }
    }
}

/** Linear acceleration (m/s², gravity removed) that counts as one swing of a shake. */
private const val SWING = 14f
private const val SWINGS_NEEDED = 4
private const val WINDOW_MS = 1200L
