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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Sound modes that change the character of the whole track. */
enum class Vibe(val label: String) { Off("Off"), Slowed("Slowed + Reverb"), Nightcore("Nightcore"), EightD("8D"), Karaoke("Karaoke") }

/** One complete set of sound settings. Never changed after it is built, so it can cross threads safely. */
class DspConfig(
    val enabled: Boolean = false,
    /** Ten band gains in dB, 31 Hz to 16 kHz. */
    val bands: FloatArray = FloatArray(DspParams.BAND_COUNT),
    val bassDb: Float = 0f,
    /** Stereo width, 1 = original, up to ~2.2. */
    val width: Float = 1f,
    val reverbSize: Float = 0f,
    val reverbWet: Float = 0f,
    val reverbDamp: Float = 0.4f,
    val makeupDb: Float = 0f,
    val levelling: Boolean = false,
    val vibe: Vibe = Vibe.Off,
    /** -1 = all left, 1 = all right. */
    val balance: Float = 0f,
    val mono: Boolean = false,
)

/**
 * The settings handed from the UI thread to the audio thread. Each edit publishes a whole new
 * [DspConfig] in one write, so the processor never sees half of an update.
 */
class DspParams {
    @Volatile var current = DspConfig()

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
    /** The config the filters were last built from; null means start fresh without gliding. */
    private var built: DspConfig? = null

    private val eq = Array(2) { Array(DspParams.BAND_COUNT) { Biquad() } }
    private val activeBands = BooleanArray(DspParams.BAND_COUNT)
    private val shelf = Array(2) { Biquad() }
    private val karaokeLow = Biquad()
    private val reverb = Array(2) { Reverb(it) }

    // Gains move towards their targets over ~15 ms instead of jumping, so edits never click.
    private var preamp = 1f
    private var preampTarget = 1f
    private var makeup = 1f
    private var makeupTarget = 1f
    private var widthNow = 1f
    private var wetNow = 0f
    private var balanceNow = 0f
    private var monoMix = 0f
    /** Per-sample smoothing step for gains, and per-[GLIDE_BLOCK] step for filter coefficients. */
    private var gainStep = 0f
    private var glideStep = 0f

    // 8D rotation
    private var phase = 0.0

    // Levelling: slow RMS follower and a gain that drifts towards the target.
    private var rms = 0.01f
    private var levelGain = 1f

    // Limiter envelope
    private var envelope = 0f
    private var limiterRelease = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Surround and other formats pass through untouched instead of failing the track.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        // Everything that depends only on the sample rate is set up here, once per format,
        // so a settings change never allocates on the audio thread.
        reverb.forEach { it.prepare(rate) }
        karaokeLow.lowPass(rate, 160f)
        karaokeLow.snap()
        limiterRelease = exp(-1.0 / (0.12 * rate)).toFloat()
        gainStep = (1.0 - exp(-1.0 / (SMOOTH_SECONDS * rate))).toFloat()
        glideStep = (1.0 - exp(-GLIDE_BLOCK / (SMOOTH_SECONDS * rate))).toFloat()
        built = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        // Read the settings once per buffer; everything below works from this snapshot.
        val cfg = params.current
        if (!cfg.enabled) {
            out.put(input)
            out.flip()
            // Filter state is stale by the time effects come back on, so start clean then.
            built = null
            return
        }
        if (built !== cfg) rebuild(cfg)

        val vibe = cfg.vibe
        val width = cfg.width
        val stereo = channels == 2
        val reverbWet = cfg.reverbWet
        val levelling = cfg.levelling
        val monoTarget = if (cfg.mono) 1f else 0f
        val balance = cfg.balance
        val gk = gainStep
        val phaseStep = 2.0 * PI * 0.11 / rate // one turn around the head every ~9 s

        val frames = bytes / (2 * channels)
        for (f in 0 until frames) {
            if (f % GLIDE_BLOCK == 0) glide()
            preamp += (preampTarget - preamp) * gk
            makeup += (makeupTarget - makeup) * gk
            widthNow += (width - widthNow) * gk
            wetNow += (reverbWet - wetNow) * gk
            balanceNow += (balance - balanceNow) * gk
            monoMix += (monoTarget - monoMix) * gk

            var l = input.short / 32768f
            var r = if (stereo) input.short / 32768f else l

            l *= preamp
            r *= preamp
            for (b in 0 until DspParams.BAND_COUNT) {
                if (!activeBands[b]) continue
                l = eq[0][b].process(l)
                r = eq[1][b].process(r)
            }
            if (cfg.bassDb != 0f || !shelf[0].settled) {
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
                    else -> if (abs(widthNow - 1f) > 1e-4f) {
                        val mid = (l + r) * 0.5f
                        val side = (l - r) * 0.5f * widthNow
                        l = mid + side
                        r = mid - side
                    }
                }
            }

            if (wetNow > 1e-4f) {
                val dry = 1f - wetNow * 0.5f
                val inMono = (l + r) * 0.5f
                l = l * dry + reverb[0].process(inMono) * wetNow
                r = r * dry + reverb[1].process(inMono) * wetNow
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
                if (monoMix > 1e-4f) {
                    val m = (l + r) * 0.5f
                    l += (m - l) * monoMix
                    r += (m - r) * monoMix
                }
                val b = balanceNow
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
        resetState()
        built = null
    }

    override fun onReset() {
        onFlush()
        rms = 0.01f
        levelGain = 1f
    }

    private fun resetState() {
        eq.forEach { ch -> ch.forEach { it.reset() } }
        shelf.forEach { it.reset() }
        karaokeLow.reset()
        reverb.forEach { it.clear() }
        envelope = 0f
    }

    /**
     * Points the filters and gains at [cfg]. After a flush or a fresh start they jump straight
     * there; otherwise they glide, and the reverb keeps its tail. Never allocates.
     */
    private fun rebuild(cfg: DspConfig) {
        val fresh = built == null
        if (fresh) resetState()
        val bands = cfg.bands
        var headroom = 0f
        for (b in 0 until DspParams.BAND_COUNT) {
            val g = bands.getOrElse(b) { 0f }
            val f = min(DspParams.FREQUENCIES[b], rate * 0.45f)
            val wasActive = activeBands[b]
            val on = abs(g) > 0.05f
            for (c in 0..1) {
                eq[c][b].peaking(rate, f, 1.1f, if (on) g else 0f)
                // A band that was being skipped has stale state; start it from silence.
                if (on && !wasActive) eq[c][b].reset()
            }
            // Keep processing a band that was just switched off until it has glided to flat.
            activeBands[b] = on || wasActive
            headroom = max(headroom, g)
        }
        val prev = built
        // The shelf and reverb sit idle while off; clear what they held so turning them on is clean.
        if (cfg.bassDb != 0f && (prev?.bassDb ?: 0f) == 0f && shelf[0].settled) shelf.forEach { it.reset() }
        if (cfg.reverbWet > 0.001f && (prev?.reverbWet ?: 0f) <= 0.001f && wetNow <= 1e-4f) reverb.forEach { it.clear() }
        for (c in 0..1) shelf[c].lowShelf(rate, 95f, cfg.bassDb)
        headroom = max(headroom, cfg.bassDb)
        // Pull the signal down by most of the biggest boost so the EQ can't clip; the limiter
        // handles whatever is left.
        preampTarget = dbToGain(-headroom * 0.85f)
        makeupTarget = dbToGain(cfg.makeupDb)
        reverb.forEach { it.tune(cfg.reverbSize, cfg.reverbDamp) }
        if (fresh) {
            eq.forEach { ch -> ch.forEach { it.snap() } }
            shelf.forEach { it.snap() }
            for (b in 0 until DspParams.BAND_COUNT) activeBands[b] = abs(bands.getOrElse(b) { 0f }) > 0.05f
            preamp = preampTarget
            makeup = makeupTarget
            widthNow = cfg.width
            wetNow = cfg.reverbWet
            balanceNow = cfg.balance
            monoMix = if (cfg.mono) 1f else 0f
        }
        built = cfg
    }

    /** Moves filter coefficients a step towards their targets; drops bands once they settle at flat. */
    private fun glide() {
        val k = glideStep
        val bands = built?.bands
        for (b in 0 until DspParams.BAND_COUNT) {
            if (!activeBands[b]) continue
            eq[0][b].glide(k)
            eq[1][b].glide(k)
            if (eq[0][b].settled && abs(bands?.getOrElse(b) { 0f } ?: 0f) <= 0.05f) activeBands[b] = false
        }
        shelf[0].glide(k)
        shelf[1].glide(k)
    }

    private companion object {
        const val LIMIT = 0.92f
        const val TARGET_RMS = 0.16f
        const val LEVEL_COEF = 0.00002f
        const val SMOOTH_SECONDS = 0.015
        /** Frames between coefficient glide steps; cheap enough to run per block, fine enough to be inaudible. */
        const val GLIDE_BLOCK = 32

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

/**
 * Transposed direct form II biquad with RBJ cookbook designs. New designs set a target that the
 * coefficients [glide] towards; interpolating stays stable because the stable region is convex.
 */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var tb0 = 1f; private var tb1 = 0f; private var tb2 = 0f; private var ta1 = 0f; private var ta2 = 0f
    private var z1 = 0f; private var z2 = 0f

    /** True once the coefficients in use have reached the latest design. */
    var settled = true
        private set

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }

    fun glide(k: Float) {
        if (settled) return
        b0 += (tb0 - b0) * k; b1 += (tb1 - b1) * k; b2 += (tb2 - b2) * k
        a1 += (ta1 - a1) * k; a2 += (ta2 - a2) * k
        val d = max(max(abs(tb0 - b0), abs(tb1 - b1)), max(max(abs(tb2 - b2), abs(ta1 - a1)), abs(ta2 - a2)))
        if (d < 1e-6f) snap()
    }

    fun snap() {
        b0 = tb0; b1 = tb1; b2 = tb2; a1 = ta1; a2 = ta2
        settled = true
    }

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
        val nt0 = (nb0 / na0).toFloat(); val nt1 = (nb1 / na0).toFloat(); val nt2 = (nb2 / na0).toFloat()
        val na1f = (na1 / na0).toFloat(); val na2f = (na2 / na0).toFloat()
        if (nt0 == tb0 && nt1 == tb1 && nt2 == tb2 && na1f == ta1 && na2f == ta2) return
        tb0 = nt0; tb1 = nt1; tb2 = nt2; ta1 = na1f; ta2 = na2f
        settled = false
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
    private val combIdx = IntArray(4)
    private val combStore = FloatArray(4)
    private var allpasses = arrayOf<FloatArray>()
    private val apIdx = IntArray(2)
    private var feedback = 0.8f
    private var damp = 0.3f

    private var preparedRate = 0

    /** Sizes the delay lines for [rate]. Only allocates when the rate actually changes. */
    fun prepare(rate: Int) {
        if (rate == preparedRate) { clear(); return }
        preparedRate = rate
        val scale = rate / 44100f
        val spread = if (channel == 1) 23 else 0
        combs = Array(4) { FloatArray(((combTunings[it] + spread) * scale).toInt().coerceAtLeast(1)) }
        allpasses = Array(2) { FloatArray(((allpassTunings[it] + spread) * scale).toInt().coerceAtLeast(1)) }
        combIdx.fill(0)
        apIdx.fill(0)
        combStore.fill(0f)
    }

    /** Changes the room without touching the delay lines, so the tail carries on. */
    fun tune(size: Float, dampening: Float) {
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
