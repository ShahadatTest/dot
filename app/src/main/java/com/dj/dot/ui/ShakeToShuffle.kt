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
import kotlin.math.sqrt

/**
 * Two firm shakes within 0.8 s jump to a random song. Listens only while Dot is on screen, and
 * needs two peaks above 2.7 g so walking or a bumpy ride won't set it off.
 */
@Composable
fun ShakeToShuffle() {
    val dot = DotApp.instance
    val enabled by dot.settings.shakeForRandom.flow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    LifecycleStartEffect(enabled) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var firstPeak = 0L
        var lastPeak = 0L
        var cooldownUntil = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.uptimeMillis()
                if (g < 2.7f || now < cooldownUntil || now - lastPeak < 120) return
                lastPeak = now
                if (now - firstPeak > 800) {
                    firstPeak = now
                } else {
                    firstPeak = 0
                    cooldownUntil = now + 1500
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    dot.engine.playRandom()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && accel != null) sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        onStopOrDispose { sensors.unregisterListener(listener) }
    }
}
