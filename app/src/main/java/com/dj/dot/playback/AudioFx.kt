package com.dj.dot.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer

/**
 * Equalizer, bass boost, 3D surround and loudness bound to the shared audio session of both
 * players, plus a global reverb that the players feed through an aux send.
 */
class AudioFx(sessionId: Int) {
    private val eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
    private val bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
    private val virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
    private val loud = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
    private val reverb = runCatching { PresetReverb(0, 0) }.getOrNull()

    val available: Boolean get() = eq != null
    val surroundAvailable: Boolean get() = virtualizer?.strengthSupported == true
    val reverbAvailable: Boolean get() = reverb != null
    val bandCount: Int = eq?.numberOfBands?.toInt() ?: 5
    /** Level range in millibels, usually -1500..1500. */
    val range: IntRange = eq?.bandLevelRange?.let { it[0].toInt()..it[1].toInt() } ?: -1500..1500
    /** Centre frequency of each band in Hz. */
    val frequencies: List<Int> = (0 until bandCount).map { b ->
        eq?.getCenterFreq(b.toShort())?.div(1000) ?: listOf(60, 230, 910, 3600, 14000).getOrElse(b) { 1000 }
    }

    /** Effect id the players should send to, or null when reverb is off. */
    var reverbSendId: Int? = null
        private set

    fun apply(enabled: Boolean, levels: List<Int>, bassStrength: Int, loudnessMb: Int, surround: Int, reverbIndex: Int) {
        runCatching {
            eq?.let { e ->
                e.enabled = enabled
                if (enabled) levels.take(bandCount).forEachIndexed { i, l -> e.setBandLevel(i.toShort(), l.coerceIn(range).toShort()) }
            }
            bass?.let { b ->
                if (b.strengthSupported) b.setStrength(bassStrength.coerceIn(0, 1000).toShort())
                b.enabled = enabled && bassStrength > 0
            }
            virtualizer?.let { v ->
                if (v.strengthSupported) v.setStrength(surround.coerceIn(0, 1000).toShort())
                v.enabled = enabled && surround > 0
            }
            loud?.let { l ->
                l.setTargetGain(loudnessMb.coerceIn(0, 1500))
                l.enabled = enabled && loudnessMb > 0
            }
        }
        reverbSendId = runCatching {
            val r = reverb ?: return@runCatching null
            val preset = reverbs.getOrNull(reverbIndex)?.second
            if (enabled && preset != null) {
                r.preset = preset
                r.enabled = true
                r.id
            } else {
                r.enabled = false
                null
            }
        }.getOrNull()
    }

    data class SoundEffect(
        val name: String,
        val note: String,
        val eq: String,
        val bass: Int,
        val surround: Int,
        val reverb: Int,
        val loudness: Int,
    )

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

        /** Index 0 is "off"; the rest map onto platform reverb presets. */
        val reverbs: List<Pair<String, Short?>> = listOf(
            "Off" to null,
            "Room" to PresetReverb.PRESET_SMALLROOM,
            "Studio" to PresetReverb.PRESET_MEDIUMROOM,
            "Hall" to PresetReverb.PRESET_MEDIUMHALL,
            "Arena" to PresetReverb.PRESET_LARGEHALL,
            "Plate" to PresetReverb.PRESET_PLATE,
        )

        /** One-tap sound modes that set every effect at once. */
        val effects: List<SoundEffect> = listOf(
            SoundEffect("Hi-Fi", "Pure, untouched", "Flat", 0, 0, 0, 0),
            SoundEffect("Bass Boost", "Deep, punchy lows", "Bass Boost", 700, 0, 0, 150),
            SoundEffect("Super Bass", "Maximum thump", "Bass Boost", 1000, 250, 0, 300),
            SoundEffect("Panoramic", "Wide 3D stage", "Bass + Treble", 300, 1000, 0, 100),
            SoundEffect("Concert", "Live hall feel", "Rock", 300, 600, 4, 150),
            SoundEffect("Vocal", "Crisp, clear voices", "Vocal", 0, 200, 0, 150),
            SoundEffect("Club", "Party energy", "Electronic", 750, 400, 2, 300),
            SoundEffect("Cinema", "Big and cinematic", "Bass + Treble", 500, 850, 3, 200),
        )

        fun presetLevels(curve: FloatArray, bands: Int, range: IntRange): List<Int> = List(bands) { b ->
            val t = if (bands == 1) 0f else b * (curve.size - 1f) / (bands - 1f)
            val i = t.toInt().coerceAtMost(curve.size - 2)
            val db = curve[i] + (curve[i + 1] - curve[i]) * (t - i)
            (db * 100).toInt().coerceIn(range)
        }
    }
}
