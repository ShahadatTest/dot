package com.dj.dot.playback

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.dj.dot.data.Settings
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

enum class GlyphMode(val label: String) { Off("Off"), Meter("Meter"), Pulse("Pulse"), Progress("Progress") }

/**
 * Drives the rear Glyph strip on the Nothing Phone (4a): six LEDs, A1 at the top to A6 at the
 * bottom, each with its own brightness. Runs only while music plays and a mode is selected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlyphLights(
    private val context: Context,
    private val spectrum: Spectrum,
    private val settings: Settings,
    private val playing: StateFlow<Boolean>,
    private val progress: () -> Float,
) {
    /** One thread for every binder call so the strip never blocks the UI. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var manager: GlyphManager? = null
    private var ready = false
    private var connecting = false
    private var loop: Job? = null
    private var listening = false
    private val last = IntArray(LEDS) { -1 }

    val supported: Boolean = Build.VERSION.SDK_INT >= 34 && runCatching { Common.is25111() }.getOrDefault(false)

    fun start() {
        if (!supported) return
        scope.launch {
            combine(playing, settings.glyphMode.flow) { p, m -> p to GlyphMode.entries.getOrElse(m) { GlyphMode.Off } }
                .collect { (isPlaying, mode) -> if (isPlaying && mode != GlyphMode.Off) run(mode) else idle() }
        }
    }

    private fun connect(onReady: () -> Unit) {
        if (ready) return onReady()
        if (connecting) return
        connecting = true
        val m = manager ?: GlyphManager.getInstance(context.applicationContext).also { manager = it }
        m.init(object : GlyphManager.Callback {
            override fun onServiceConnected(name: ComponentName) {
                scope.launch {
                    runCatching {
                        m.register(Glyph.DEVICE_25111)
                        m.openSession()
                        ready = true
                        onReady()
                    }.onFailure { Log.w(TAG, "Glyph session failed", it) }
                    connecting = false
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                ready = false
                connecting = false
            }
        })
    }

    private fun run(mode: GlyphMode) {
        loop?.cancel()
        connect {
            if (!listening) { spectrum.acquire(); listening = true }
            loop = scope.launch { animate(mode) }
        }
    }

    private suspend fun CoroutineScope.animate(mode: GlyphMode) {
        var level = 0f
        var pulse = 0f
        var lastAudible = System.nanoTime() - LATENCY_NS
        while (isActive) {
            val audible = System.nanoTime() - LATENCY_NS
            val beat = spectrum.beatBetween(lastAudible, audible)
            lastAudible = audible
            pulse = max(pulse * 0.8f, beat)

            val values = IntArray(LEDS)
            when (mode) {
                GlyphMode.Meter -> {
                    // Weight the low bands: that's where the groove is.
                    val bands = spectrum.levelsAt(audible)
                    val target = if (bands == null) 0f else (bands[0] * 0.3f + bands[1] * 0.3f + bands[2] * 0.25f + bands[3] * 0.15f)
                    level = if (target > level) level + (target - level) * 0.6f else level - (level - target) * 0.18f
                    val height = level * LEDS
                    for (i in 0 until LEDS) {
                        val fromBottom = LEDS - 1 - i
                        val fill = (height - fromBottom).coerceIn(0f, 1f)
                        values[i] = (max(fill, pulse * 0.55f) * MAX).roundToInt()
                    }
                }
                GlyphMode.Pulse -> for (i in 0 until LEDS) values[i] = (pulse * MAX).roundToInt()
                GlyphMode.Progress -> {
                    val height = progress().coerceIn(0f, 1f) * LEDS
                    for (i in 0 until LEDS) {
                        val fill = (height - (LEDS - 1 - i)).coerceIn(0f, 1f)
                        values[i] = (max(fill * 0.7f, pulse * 0.35f) * MAX).roundToInt()
                    }
                }
                GlyphMode.Off -> Unit
            }
            push(values)
            delay(FRAME_MS)
        }
    }

    /** Sends a frame only when it visibly differs from the last one. */
    private fun push(values: IntArray) {
        val m = manager ?: return
        if (values.indices.all { kotlin.math.abs(values[it] - last[it]) < MAX / 40 }) return
        runCatching {
            val builder = m.glyphFrameBuilder
            for (i in values.indices) builder.buildChannel(i, values[i])
            m.toggle(builder.build())
            values.copyInto(last)
        }
    }

    private fun idle() {
        loop?.cancel()
        loop = null
        if (listening) { spectrum.release(); listening = false }
        scope.launch {
            runCatching { manager?.turnOff() }
            last.fill(-1)
        }
    }

    private companion object {
        const val TAG = "GlyphLights"
        const val LEDS = 6
        const val MAX = 4095
        const val FRAME_MS = 33L
        const val LATENCY_NS = 200_000_000L
    }
}
