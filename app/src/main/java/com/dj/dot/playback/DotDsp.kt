package com.dj.dot.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Sound modes that change the character of the whole track. */
enum class Vibe(val label: String) { Off("Off"), Slowed("Slowed + Reverb"), Nightcore("Nightcore"), EightD("8D"), Karaoke("Karaoke") }

/**
 * Everything the processor needs, written from the UI thread and read on the audio thread.
 * [version] changes on every edit so processors know to rebuild their filters.
 */
class DspParams {
    @Volatile var enabled = false
    /** Ten band gains in dB, 31 Hz to 16 kHz. */
    @Volatile var bands = FloatArray(BAND_COUNT)
    @Volatile var bassDb = 0f
    /** Stereo width, 1 = original, up to ~2.2. */
    @Volatile var width = 1f
    @Volatile var reverbSize = 0f
    @Volatile var reverbWet = 0f
    @Volatile var reverbDamp = 0.4f
    @Volatile var makeupDb = 0f
    @Volatile var levelling = false
    @Volatile var vibe = Vibe.Off
    /** -1 = all left, 1 = all right. */
    @Volatile var balance = 0f
    @Volatile var mono = false
    @Volatile var version = 0
        private set

    fun commit() { version++ }

    companion object {
        const val BAND_COUNT = 10
        val FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    }
}

/**
 * Dot's own audio chain, running inside ExoPlayer on 16-bit PCM:
 * preamp (automatic headroom) → 10-band EQ → bass shelf → stereo width / 8D / karaoke →
 * reverb → volume levelling → makeup gain → mono / balance → limiter. Processing is in float; the limiter and a
 * final soft clip mean boosted settings never crackle.
 */
@OptIn(UnstableApi::class)
class DotDsp(private val params: DspParams) : BaseAudioProcessor() {
    private var rate = 44100
    private var channels = 2
    private var builtVersion = -1

    private val eq = Array(2) { Array(DspParams.BAND_COUNT) { Biquad() } }
    private val activeBands = BooleanArray(DspParams.BAND_COUNT)
    private val shelf = Array(2) { Biquad() }
    private val karaokeLow = Biquad()
    private var preamp = 1f
    private var makeup = 1f

    private val reverb = Array(2) { Reverb(it) }

    // 8D rotation
    private var phase = 0.0

    // Levelling: slow RMS follower and a gain that drifts towards the target.
    private var rms = 0.01f
    private var levelGain = 1f

    // Limiter envelope
    private var envelope = 0f
    private var limiterRelease = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        builtVersion = -1
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (!params.enabled) {
            out.put(input)
            out.flip()
            return
        }
        if (builtVersion != params.version) rebuild()

        val vibe = params.vibe
        val width = params.width
        val stereo = channels == 2
        val useReverb = params.reverbWet > 0.001f
        val levelling = params.levelling
        val phaseStep = 2.0 * PI * 0.11 / rate // one turn around the head every ~9 s

        val frames = bytes / (2 * channels)
        for (f in 0 until frames) {
            var l = input.short / 32768f
            var r = if (stereo) input.short / 32768f else l

            l *= preamp
            r *= preamp
            for (b in 0 until DspParams.BAND_COUNT) {
                if (!activeBands[b]) continue
                l = eq[0][b].process(l)
                r = eq[1][b].process(r)
            }
            if (params.bassDb != 0f) {
                l = shelf[0].process(l)
                r = shelf[1].process(r)
            }

            if (stereo) {
                when (vibe) {
                    Vibe.Karaoke -> {
                        // Vocals sit in the centre: keep the sides plus the low end of the centre.
                        val mid = (l + r) * 0.5f
                        val side = (l - r) * 0.5f
                        val low = karaokeLow.process(mid)
                        l = side * 1.4f + low
                        r = -side * 1.4f + low
                    }
                    Vibe.EightD -> {
                        val mid = (l + r) * 0.5f
                        val side = (l - r) * 0.5f
                        val p = sin(phase).toFloat()
                        phase += phaseStep
                        if (phase > 2 * PI) phase -= 2 * PI
                        val gl = sqrt((1f - p) * 0.5f) * 1.3f
                        val gr = sqrt((1f + p) * 0.5f) * 1.3f
                        l = mid * gl + side * 0.4f
                        r = mid * gr - side * 0.4f
                    }
                    else -> if (width != 1f) {
                        val mid = (l + r) * 0.5f
                        val side = (l - r) * 0.5f * width
                        l = mid + side
                        r = mid - side
                    }
                }
            }

            if (useReverb) {
                val dry = 1f - params.reverbWet * 0.5f
                val inMono = (l + r) * 0.5f
                l = l * dry + reverb[0].process(inMono) * params.reverbWet
                r = r * dry + reverb[1].process(inMono) * params.reverbWet
            }

            if (levelling) {
                val p = (l * l + r * r) * 0.5f
                rms += (p - rms) * LEVEL_COEF
                val target = (TARGET_RMS / sqrt(max(rms, 1e-6f))).coerceIn(0.5f, 2f)
                levelGain += (target - levelGain) * LEVEL_COEF
                l *= levelGain
                r *= levelGain
            }

            l *= makeup
            r *= makeup

            if (stereo) {
                if (params.mono) {
                    val m = (l + r) * 0.5f
                    l = m
                    r = m
                }
                val b = params.balance
                if (b > 0f) l *= 1f - b else if (b < 0f) r *= 1f + b
            }

            // Peak limiter: instant attack, ~120 ms release, then a soft clip for safety.
            val peak = max(abs(l), abs(r))
            envelope = if (peak > envelope) peak else envelope * limiterRelease + peak * (1f - limiterRelease)
            if (envelope > LIMIT) {
                val g = LIMIT / envelope
                l *= g
                r *= g
            }
            l = softClip(l)
            r = softClip(r)

            out.putShort((l * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            if (stereo) out.putShort((r * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onFlush() {
        eq.forEach { ch -> ch.forEach { it.reset() } }
        shelf.forEach { it.reset() }
        karaokeLow.reset()
        reverb.forEach { it.clear() }
        envelope = 0f
        builtVersion = -1
    }

    override fun onReset() {
        onFlush()
        rms = 0.01f
        levelGain = 1f
    }

    private fun rebuild() {
        val bands = params.bands
        var headroom = 0f
        for (b in 0 until DspParams.BAND_COUNT) {
            val g = bands.getOrElse(b) { 0f }
            val f = min(DspParams.FREQUENCIES[b], rate * 0.45f)
            activeBands[b] = abs(g) > 0.05f
            for (c in 0..1) eq[c][b].peaking(rate, f, 1.1f, g)
            headroom = max(headroom, g)
        }
        for (c in 0..1) shelf[c].lowShelf(rate, 95f, params.bassDb)
        karaokeLow.lowPass(rate, 160f)
        headroom = max(headroom, params.bassDb)
        // Pull the signal down by most of the biggest boost so the EQ can't clip; the limiter
        // handles whatever is left.
        preamp = dbToGain(-headroom * 0.85f)
        makeup = dbToGain(params.makeupDb)
        reverb.forEach { it.tune(rate, params.reverbSize, params.reverbDamp) }
        limiterRelease = kotlin.math.exp(-1.0 / (0.12 * rate)).toFloat()
        builtVersion = params.version
    }

    private companion object {
        const val LIMIT = 0.92f
        const val TARGET_RMS = 0.16f
        const val LEVEL_COEF = 0.00002f

        fun dbToGain(db: Float) = 10f.pow(db / 20f)

        /** Linear below ±0.9, then rounds smoothly towards ±1 instead of clipping. */
        fun softClip(x: Float): Float {
            val a = abs(x)
            if (a <= 0.9f) return x
            val over = (a - 0.9f) / 0.1f
            val y = 0.9f + 0.1f * tanh(over)
            return if (x < 0) -y else y
        }
    }
}

/** Transposed direct form II biquad with RBJ cookbook designs. */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    fun peaking(rate: Int, freq: Float, q: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / rate
        val alpha = sin(w) / (2 * q)
        set(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
    }

    fun lowShelf(rate: Int, freq: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2 * PI * freq / rate
        val alpha = sin(w) / 2 * sqrt(2.0)
        val c = cos(w)
        val sa = 2 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa,
        )
    }

    fun lowPass(rate: Int, freq: Float) {
        val w = 2 * PI * freq / rate
        val alpha = sin(w) / (2 * 0.707)
        val c = cos(w)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }
}

/**
 * A small Freeverb: four damped feedback combs into two allpasses. The right channel uses
 * slightly longer delays so the tail spreads across the stereo field.
 */
private class Reverb(private val channel: Int) {
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356)
    private val allpassTunings = intArrayOf(556, 441)
    private var combs = arrayOf<FloatArray>()
    private var combIdx = IntArray(4)
    private val combStore = FloatArray(4)
    private var allpasses = arrayOf<FloatArray>()
    private var apIdx = IntArray(2)
    private var feedback = 0.8f
    private var damp = 0.3f

    fun tune(rate: Int, size: Float, dampening: Float) {
        val scale = rate / 44100f
        val spread = if (channel == 1) 23 else 0
        combs = Array(4) { FloatArray(((combTunings[it] + spread) * scale).toInt().coerceAtLeast(1)) }
        allpasses = Array(2) { FloatArray(((allpassTunings[it] + spread) * scale).toInt().coerceAtLeast(1)) }
        combIdx = IntArray(4)
        apIdx = IntArray(2)
        combStore.fill(0f)
        feedback = 0.7f + 0.28f * size.coerceIn(0f, 1f)
        damp = dampening.coerceIn(0f, 1f)
    }

    fun clear() {
        combs.forEach { it.fill(0f) }
        allpasses.forEach { it.fill(0f) }
        combStore.fill(0f)
    }

    fun process(x: Float): Float {
        if (combs.isEmpty()) return 0f
        val input = x * 0.015f
        var sum = 0f
        for (i in 0 until 4) {
            val buf = combs[i]
            val idx = combIdx[i]
            val y = buf[idx]
            combStore[i] = y * (1 - damp) + combStore[i] * damp
            buf[idx] = input + combStore[i] * feedback
            combIdx[i] = if (idx + 1 == buf.size) 0 else idx + 1
            sum += y
        }
        var out = sum
        for (i in 0 until 2) {
            val buf = allpasses[i]
            val idx = apIdx[i]
            val b = buf[idx]
            buf[idx] = out + b * 0.5f
            out = b - out
            apIdx[i] = if (idx + 1 == buf.size) 0 else idx + 1
        }
        return out * 3f
    }
}
