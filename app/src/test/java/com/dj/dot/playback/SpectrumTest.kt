package com.dj.dot.playback

import androidx.media3.common.C
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Beat detection, tempo and band levels on synthetic audio, with a clock that follows the samples. */
class SpectrumTest {
    private val rate = 44100
    private var now = 0L
    private fun spectrum() = Spectrum(clock = { now }).apply {
        acquire(needsWave = false)
        flush(rate, 2, C.ENCODING_PCM_16BIT)
    }

    /** Feeds [seconds] of [sample] in 1024-frame chunks, advancing the clock with the audio. */
    private fun feed(s: Spectrum, seconds: Double, sample: (Double) -> Double) {
        val chunk = 1024
        var frame = 0
        val total = (seconds * rate).toInt()
        while (frame < total) {
            val buf = ByteBuffer.allocateDirect(chunk * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until chunk) {
                val v = (sample((frame + i).toDouble() / rate).coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                buf.putShort(v); buf.putShort(v)
            }
            buf.flip()
            frame += chunk
            now += chunk * 1_000_000_000L / rate
            s.handleBuffer(buf)
        }
    }

    /** A kick drum every [period] seconds over a quiet hiss. */
    private fun kicks(period: Double): (Double) -> Double {
        val noise = java.util.Random(1)
        return { t ->
            val since = t % period
            0.9 * exp(-since * 28) * sin(2 * PI * (55 + 90 * exp(-since * 40)) * since) + 0.02 * (noise.nextDouble() - 0.5)
        }
    }

    @Test fun `finds 120 bpm in a steady kick pattern`() {
        val s = spectrum()
        feed(s, 14.0, kicks(0.5))
        assertTrue("bpm was ${s.bpm}", s.bpm in 116..124)
    }

    @Test fun `finds 90 bpm too`() {
        val s = spectrum()
        feed(s, 16.0, kicks(60.0 / 90))
        assertTrue("bpm was ${s.bpm}", s.bpm in 87..93)
    }

    @Test fun `reports beats in the window they happened`() {
        val s = spectrum()
        feed(s, 6.0, kicks(0.5))
        val since = now - 2_000_000_000L
        assertTrue(s.beatBetween(since, now) > 0f)
    }

    @Test fun `a low tone lights the low bands, not the high ones`() {
        val s = spectrum()
        feed(s, 1.0) { t -> 0.6 * sin(2 * PI * 80 * t) }
        val levels = s.levelsAt(now)!!
        assertTrue(levels.take(4).max() > levels.takeLast(4).max() + 0.3f)
    }

    @Test fun `does no work while nobody listens`() {
        val s = Spectrum(clock = { now }).apply { flush(rate, 2, C.ENCODING_PCM_16BIT) }
        feed(s, 2.0, kicks(0.5))
        assertTrue(s.levelsAt(now) == null && s.bpm == 0)
    }
}
