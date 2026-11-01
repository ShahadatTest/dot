package com.dj.dot.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/** Equalizer, bass boost and loudness bound to the shared audio session of both players. */
class AudioFx(sessionId: Int) {
    private val eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
    private val bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
    private val loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()

    val available: Boolean get() = eq != null
    val bandCount: Int = eq?.numberOfBands?.toInt() ?: 5
    /** Level range in millibels, usually -1500..1500. */
    val range: IntRange = eq?.bandLevelRange?.let { it[0].toInt()..it[1].toInt() } ?: -1500..1500
    /** Centre frequency of each band in Hz. */
    val frequencies: List<Int> = (0 until bandCount).map { b ->
        eq?.getCenterFreq(b.toShort())?.div(1000) ?: listOf(60, 230, 910, 3600, 14000).getOrElse(b) { 1000 }
    }

    fun apply(enabled: Boolean, levels: List<Int>, bassStrength: Int, loudnessMb: Int) {
        runCatching {
            eq?.let { e ->
                e.enabled = enabled
                if (enabled) levels.take(bandCount).forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.coerceIn(range).toShort()) }
            }
            bass?.let { b ->
                if (b.strengthSupported) b.setStrength(bassStrength.coerceIn(0, 1000).toShort())
                b.enabled = enabled && bassStrength > 0
            }
            loud?.let { l ->
                l.setTargetGain(loudnessMb.coerceIn(0, 1500))
                l.enabled = enabled && loudnessMb > 0
            }
        }
    }

    companion object {
        /** Preset curves in dB over five reference bands; interpolated to the device's band count. */
        val presets: List<Pair<String, FloatArray>> = listOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(7f, 4.5f, 0f, 0f, 0f),
            "Bass + Treble" to floatArrayOf(6f, 3f, -1f, 3f, 6f),
            "Vocal" to floatArrayOf(-2f, 0f, 3.5f, 3f, 1f),
            "Treble" to floatArrayOf(0f, 0f, 0f, 3.5f, 6f),
            "Rock" to floatArrayOf(4.5f, 2f, -1.5f, 2f, 4.5f),
            "Pop" to floatArrayOf(-1f, 2.5f, 4f, 2.5f, -1f),
            "Electronic" to floatArrayOf(5f, 3f, 0f, 2f, 4.5f),
            "Classical" to floatArrayOf(3.5f, 2f, -1f, 2f, 3.5f),
        )

        fun presetLevels(curve: FloatArray, bands: Int, range: IntRange): List<Int> = List(bands) { b ->
            val t = if (bands == 1) 0f else b * (curve.size - 1f) / (bands - 1f)
            val i = t.toInt().coerceAtMost(curve.size - 2)
            val db = curve[i] + (curve[i + 1] - curve[i]) * (t - i)
            (db * 100).toInt().coerceIn(range)
        }
    }
}
