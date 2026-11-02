package com.dj.dot.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps decoded PCM straight out of ExoPlayer (no RECORD_AUDIO permission needed) and turns it
 * into [bands] log-spaced levels for the dot-matrix visualizer, plus beat onsets and a tempo
 * estimate. Only does work while someone has called [acquire].
 *
 * Beats use spectral flux: the summed *increase* in (log) magnitude since the previous frame,
 * weighted towards the kick-drum range. A beat fires when the flux is a local rise that clears
 * the recent mean by a few standard deviations, with a refractory gap so one hit can't fire twice.
 */
@OptIn(UnstableApi::class)
class Spectrum(val bands: Int = 18) : TeeAudioProcessor.AudioBufferSink {
    private val users = java.util.concurrent.atomic.AtomicInteger(0)
    private val waveUsers = java.util.concurrent.atomic.AtomicInteger(0)
    /** True while at least one visualizer or the Glyph lights is listening. */
    val enabled: Boolean get() = users.get() > 0
    /** The Glyph strip only needs levels and beats, so it skips the waveform work. */
    fun acquire(needsWave: Boolean = true) {
        users.incrementAndGet()
        if (needsWave) waveUsers.incrementAndGet()
    }
    fun release(needsWave: Boolean = true) {
        users.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (needsWave) waveUsers.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    private val n = 1024
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
    private val ring = FloatArray(n)
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val mags = FloatArray(bands)
    private var filled = 0
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var sampleRate = 44100
    private var peak = 1e-3f

    // Onset detection
    private val binMag = FloatArray(n / 2)
    private val prevLogMag = FloatArray(n / 2)
    private val fluxHist = FloatArray(43) // ~1 s of frames
    private var fluxHead = 0
    private var fluxCount = 0
    private var fluxSum = 0f
    private var fluxSumSq = 0f
    private var prevFlux = 0f
    @Volatile private var lastBeatNs = 0L
    private val beatTimes = LongArray(16)
    private var beatHead = 0
    private var beatCount = 0

    /** Rough tempo of the current track, 0 until enough beats are heard. */
    @Volatile var bpm = 0
        private set

    // Analysed frames run ahead of the speaker by the AudioTrack buffer, so keep a short
    // history and let the UI read the frame matching what is audible right now.
    private val histSize = 48
    private val histT = LongArray(histSize)
    private val histV = arrayOfNulls<FloatArray>(histSize)
    private val histBeat = FloatArray(histSize)
    private val histWave = arrayOfNulls<FloatArray>(histSize)
    private var waveAmp = 1e-3f

    /** Points per waveform snapshot. */
    val wavePoints = 64
    private var head = 0

    @Synchronized
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        filled = 0
        prevLogMag.fill(0f)
        fluxCount = 0
        fluxSum = 0f
        fluxSumSq = 0f
        beatCount = 0
        bpm = 0
    }

    @Synchronized
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        when (encoding) {
            C.ENCODING_PCM_16BIT -> repeat(b.remaining() / (2 * channels)) {
                var s = 0f
                repeat(channels) { s += b.short / 32768f }
                push(s / channels)
            }
            C.ENCODING_PCM_FLOAT -> repeat(b.remaining() / (4 * channels)) {
                var s = 0f
                repeat(channels) { s += b.float }
                push(s / channels)
            }
        }
    }

    /** Levels (0..1) closest to [nanoTime], or null if nothing recent. */
    @Synchronized
    fun levelsAt(nanoTime: Long): FloatArray? = frameAt(nanoTime)?.let { histV[it] }

    /** Waveform (-1..1) closest to [nanoTime], or null if nothing recent. */
    @Synchronized
    fun waveAt(nanoTime: Long): FloatArray? = frameAt(nanoTime)?.let { histWave[it] }

    /** Newest frame at or before [nanoTime]; walks back from the head, usually a few steps. */
    private fun frameAt(nanoTime: Long): Int? {
        for (k in 1..histSize) {
            val i = (head - k + histSize) % histSize
            if (histV[i] == null) return null
            val t = histT[i]
            if (t <= nanoTime) return if (nanoTime - t < 400_000_000L) i else null
        }
        return null
    }

    /** When the most recent beat was decoded (nanoTime), 0 if none yet. */
    fun lastBeatNanos(): Long = lastBeatNs

    /** Starts a fresh tempo estimate, used when a new song begins. */
    @Synchronized
    fun resetTempo() {
        beatCount = 0
        beatHead = 0
        lastBeatNs = 0L
        bpm = 0
    }

    /** Strongest beat (0..1) analysed in (fromNs, toNs], 0 if none. Walks back only as far as [fromNs]. */
    @Synchronized
    fun beatBetween(fromNs: Long, toNs: Long): Float {
        var strength = 0f
        for (k in 1..histSize) {
            val i = (head - k + histSize) % histSize
            val t = histT[i]
            if (histV[i] == null || t <= fromNs) break
            if (t <= toNs) strength = max(strength, histBeat[i])
        }
        return strength
    }

    private fun push(v: Float) {
        ring[filled++] = v
        if (filled == n) { analyse(); filled = 0 }
    }

    private fun analyse() {
        for (i in 0 until n) { re[i] = ring[i] * window[i]; im[i] = 0f }
        fft()
        for (i in 0 until n / 2) binMag[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        val now = System.nanoTime()
        val binHz = sampleRate.toFloat() / n

        // Levels
        val lo = 40f
        val ratio = 16000f / lo
        for (b in 0 until bands) {
            val f0 = lo * ratio.pow(b.toFloat() / bands)
            val f1 = lo * ratio.pow((b + 1f) / bands)
            val i0 = (f0 / binHz).toInt().coerceIn(1, n / 2 - 1)
            val i1 = (f1 / binHz).toInt().coerceIn(i0, n / 2 - 1)
            var m = 0f
            for (i in i0..i1) m = max(m, binMag[i])
            mags[b] = m
        }
        peak = max(mags.max(), max(peak * 0.995f, 1e-3f))
        // History slots are reused rather than reallocated 43 times a second.
        val levels = histV[head]?.takeIf { it.size == bands } ?: FloatArray(bands)
        for (b in 0 until bands) levels[b] = (log10(1f + 9f * mags[b] / peak)).coerceIn(0f, 1f)

        // Spectral flux, kick range counted fully and the rest at a third.
        val kickTop = (180f / binHz).toInt()
        val fluxTop = (8000f / binHz).toInt().coerceAtMost(n / 2 - 1)
        var flux = 0f
        for (i in 1..fluxTop) {
            val lm = ln(1f + 100f * binMag[i] / peak)
            val rise = lm - prevLogMag[i]
            if (rise > 0) flux += if (i <= kickTop) rise else rise * 0.33f
            prevLogMag[i] = lm
        }

        // Running sums keep the mean and spread O(1) per frame.
        val mean = if (fluxCount > 0) fluxSum / fluxCount else 0f
        val std = if (fluxCount > 1) sqrt((fluxSumSq / fluxCount - mean * mean).coerceAtLeast(0f)) else 0f

        var beat = 0f
        val threshold = mean + 1.5f * std + 0.05f * mean
        if (fluxCount >= 20 && flux > threshold && flux >= prevFlux && now - lastBeatNs > 280_000_000L) {
            beat = ((flux - mean) / (3f * std + 1e-6f)).coerceIn(0.35f, 1f)
            registerBeat(now)
        }
        prevFlux = flux
        if (fluxCount == fluxHist.size) {
            val old = fluxHist[fluxHead]
            fluxSum -= old
            fluxSumSq -= old * old
        } else {
            fluxCount++
        }
        fluxHist[fluxHead] = flux
        fluxSum += flux
        fluxSumSq += flux * flux
        fluxHead = (fluxHead + 1) % fluxHist.size

        // Waveform: average each 1/64th of the frame, normalised with a slow auto-gain.
        val step = n / wavePoints
        val wave = histWave[head] ?: FloatArray(wavePoints)
        var frameAmp = 0f
        if (waveUsers.get() > 0) for (p in 0 until wavePoints) {
            var acc = 0f
            for (k in 0 until step) acc += ring[p * step + k]
            wave[p] = acc / step
            frameAmp = max(frameAmp, kotlin.math.abs(wave[p]))
        }
        waveAmp = max(frameAmp, max(waveAmp * 0.99f, 1e-3f))
        if (waveUsers.get() > 0) for (p in 0 until wavePoints) wave[p] = (wave[p] / waveAmp).coerceIn(-1f, 1f)

        histT[head] = now
        histV[head] = levels
        histBeat[head] = beat
        histWave[head] = wave
        head = (head + 1) % histSize
    }

    private fun registerBeat(now: Long) {
        if (lastBeatNs != 0L) {
            val gap = now - lastBeatNs
            if (gap in 300_000_000L..1_500_000_000L) {
                beatTimes[beatHead] = gap
                beatHead = (beatHead + 1) % beatTimes.size
                if (beatCount < beatTimes.size) beatCount++
                if (beatCount >= 6) {
                    val median = beatTimes.copyOf(beatCount).sorted()[beatCount / 2]
                    var tempo = 60_000_000_000.0 / median
                    while (tempo < 70) tempo *= 2
                    while (tempo > 180) tempo /= 2
                    bpm = tempo.roundToInt()
                }
            }
        }
        lastBeatNs = now
    }

    private fun fft() {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len / 2
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
