package com.dj.dot.playback

/**
 * Translates the sound settings into [DspParams] for Dot's own processor, which runs inside
 * both players. Presets and one-tap sound modes live here too.
 */
class AudioFx {
    val params = DspParams()

    val bandCount: Int = DspParams.BAND_COUNT
    /** Level range in millibels (±12 dB). */
    val range: IntRange = -1200..1200
    /** Centre frequency of each band in Hz. */
    val frequencies: List<Int> = DspParams.FREQUENCIES.map { it.toInt() }

    fun apply(
        enabled: Boolean,
        levels: List<Int>,
        bassStrength: Int,
        loudnessMb: Int,
        surround: Int,
        reverbIndex: Int,
        levelling: Boolean,
        vibe: Vibe,
        balance: Int = 0,
        mono: Boolean = false,
    ) {
        val reverb = if (vibe == Vibe.Slowed) SLOWED_REVERB else reverbs.getOrNull(reverbIndex)?.second?.takeIf { enabled }
        params.enabled = enabled || levelling || vibe != Vibe.Off || balance != 0 || mono
        params.bands = FloatArray(bandCount) { if (enabled) levels.getOrElse(it) { 0 } / 100f else 0f }
        params.bassDb = if (enabled) bassStrength.coerceIn(0, 1000) / 1000f * 10f else 0f
        params.width = if (enabled) 1f + surround.coerceIn(0, 1000) / 1000f * 1.2f else 1f
        params.reverbSize = reverb?.size ?: 0f
        params.reverbWet = reverb?.wet ?: 0f
        params.reverbDamp = reverb?.damp ?: 0.4f
        params.makeupDb = if (enabled) loudnessMb.coerceIn(0, 1200) / 100f else 0f
        params.levelling = levelling
        params.vibe = vibe
        params.balance = balance.coerceIn(-100, 100) / 100f
        params.mono = mono
        params.commit()
    }

    data class ReverbPreset(val size: Float, val wet: Float, val damp: Float)

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
        /** Preset curves in dB over five reference points; interpolated to the ten bands. */
        val presets: List<Pair<String, FloatArray>> = listOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(6f, 3.5f, 0f, 0f, 0f),
            "Bass + Treble" to floatArrayOf(5f, 2.5f, -1f, 2.5f, 5f),
            "Vocal" to floatArrayOf(-2f, 0f, 3.5f, 3f, 1f),
            "Treble" to floatArrayOf(0f, 0f, 0f, 3.5f, 6f),
            "Rock" to floatArrayOf(4.5f, 2f, -1.5f, 2f, 4.5f),
            "Pop" to floatArrayOf(-1f, 2.5f, 4f, 2.5f, -1f),
            "Electronic" to floatArrayOf(5f, 3f, 0f, 2f, 4.5f),
            "Classical" to floatArrayOf(3.5f, 2f, -1f, 2f, 3.5f),
        )

        /** Index 0 is "off". Tuned to sit behind the music rather than wash over it. */
        val reverbs: List<Pair<String, ReverbPreset?>> = listOf(
            "Off" to null,
            "Room" to ReverbPreset(size = 0.45f, wet = 0.10f, damp = 0.55f),
            "Studio" to ReverbPreset(size = 0.6f, wet = 0.13f, damp = 0.45f),
            "Hall" to ReverbPreset(size = 0.8f, wet = 0.18f, damp = 0.4f),
            "Arena" to ReverbPreset(size = 0.92f, wet = 0.24f, damp = 0.35f),
            "Plate" to ReverbPreset(size = 0.7f, wet = 0.16f, damp = 0.15f),
        )

        private val SLOWED_REVERB = ReverbPreset(size = 0.88f, wet = 0.30f, damp = 0.5f)

        /** One-tap sound modes that set every effect at once. */
        val effects: List<SoundEffect> = listOf(
            SoundEffect("Hi-Fi", "Pure, untouched", "Flat", 0, 0, 0, 0),
            SoundEffect("Bass Boost", "Deep, punchy lows", "Bass Boost", 600, 0, 0, 150),
            SoundEffect("Super Bass", "Maximum thump", "Bass Boost", 1000, 200, 0, 300),
            SoundEffect("Panoramic", "Wide 3D stage", "Bass + Treble", 250, 1000, 0, 100),
            SoundEffect("Concert", "Live hall feel", "Rock", 300, 500, 4, 150),
            SoundEffect("Vocal", "Crisp, clear voices", "Vocal", 0, 200, 0, 150),
            SoundEffect("Club", "Party energy", "Electronic", 700, 400, 2, 300),
            SoundEffect("Cinema", "Big and cinematic", "Bass + Treble", 500, 800, 3, 200),
        )

        fun presetLevels(curve: FloatArray, bands: Int, range: IntRange): List<Int> = List(bands) { b ->
            val t = if (bands == 1) 0f else b * (curve.size - 1f) / (bands - 1f)
            val i = t.toInt().coerceAtMost(curve.size - 2)
            val db = curve[i] + (curve[i + 1] - curve[i]) * (t - i)
            (db * 100).toInt().coerceIn(range)
        }
    }
}
