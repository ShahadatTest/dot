package com.dj.dot.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps decoded PCM straight out of ExoPlayer (no RECORD_AUDIO permission needed) and turns it
 * into [bands] log-spaced levels for the dot-matrix visualizer. Only does work while [enabled].
 */
@OptIn(UnstableApi::class)
class Spectrum(val bands: Int = 18) : TeeAudioProcessor.AudioBufferSink {
    @Volatile var enabled = false

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

    // Analysed frames run ahead of the speaker by the AudioTrack buffer, so keep a short
    // history and let the UI read the frame matching what is audible right now.
    private val histSize = 48
    private val histT = LongArray(histSize)
    private val histV = arrayOfNulls<FloatArray>(histSize)
    private var head = 0

    @Synchronized
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        filled = 0
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
    fun levelsAt(nanoTime: Long): FloatArray? {
        var best: FloatArray? = null
        var bestT = Long.MIN_VALUE
        for (i in 0 until histSize) {
            val t = histT[i]
            if (t in (bestT + 1)..nanoTime) { bestT = t; best = histV[i] }
        }
        return if (best != null && nanoTime - bestT < 400_000_000L) best else null
    }

    private fun push(v: Float) {
        ring[filled++] = v
        if (filled == n) { analyse(); filled = 0 }
    }

    private fun analyse() {
        for (i in 0 until n) { re[i] = ring[i] * window[i]; im[i] = 0f }
        fft()
        val binHz = sampleRate.toFloat() / n
        val lo = 40f
        val ratio = 16000f / lo
        for (b in 0 until bands) {
            val f0 = lo * ratio.pow(b.toFloat() / bands)
            val f1 = lo * ratio.pow((b + 1f) / bands)
            val i0 = (f0 / binHz).toInt().coerceIn(1, n / 2 - 1)
            val i1 = (f1 / binHz).toInt().coerceIn(i0, n / 2 - 1)
            var m = 0f
            for (i in i0..i1) m = max(m, sqrt(re[i] * re[i] + im[i] * im[i]))
            mags[b] = m
        }
        peak = max(mags.max(), max(peak * 0.995f, 1e-3f))
        val levels = FloatArray(bands) { (log10(1f + 9f * mags[it] / peak)).coerceIn(0f, 1f) }
        histT[head] = System.nanoTime()
        histV[head] = levels
        head = (head + 1) % histSize
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
