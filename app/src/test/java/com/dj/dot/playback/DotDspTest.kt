package com.dj.dot.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Behaviour of the sound chain on synthetic signals, measured after the settings have settled. */
class DotDspTest {
    private val rate = 44100

    private fun processor(config: DspConfig): DotDsp {
        val params = DspParams().apply { current = config }
        return DotDsp(params).apply {
            configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
    }

    /** Interleaved stereo 16-bit samples; [left] and [right] give each channel's value at time t. */
    private fun signal(seconds: Float, left: (Double) -> Double, right: (Double) -> Double = left): ShortArray {
        val frames = (rate * seconds).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val v = if (i % 2 == 0) left(t) else right(t)
            (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private fun sine(freq: Double, amp: Double = 0.5): (Double) -> Double = { t -> amp * sin(2 * PI * freq * t) }

    /** Runs [input] through in 4096-frame chunks, like ExoPlayer would, and returns the output. */
    private fun run(dsp: DotDsp, input: ShortArray): ShortArray {
        val out = ArrayList<Short>(input.size)
        var i = 0
        while (i < input.size) {
            val n = minOf(8192, input.size - i)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (k in 0 until n) buf.putShort(input[i + k])
            buf.flip()
            dsp.queueInput(buf)
            val o = dsp.output.order(ByteOrder.nativeOrder())
            while (o.remaining() >= 2) out += o.short
            i += n
        }
        return out.toShortArray()
    }

    /** RMS of one channel (0 = left, 1 = right) over the last [seconds]. */
    private fun rms(samples: ShortArray, channel: Int, seconds: Float = 0.5f): Double {
        val frames = (rate * seconds).toInt()
        var sum = 0.0
        val start = samples.size / 2 - frames
        for (f in start until samples.size / 2) {
            val v = samples[f * 2 + channel] / 32768.0
            sum += v * v
        }
        return sqrt(sum / frames)
    }

    @Test fun `passes audio through untouched when off`() {
        val input = signal(0.5f, sine(440.0), sine(660.0))
        assertArrayEquals(input, run(processor(DspConfig(enabled = false)), input))
    }

    @Test fun `heavy boost never hard clips`() {
        val loud = DspConfig(enabled = true, bands = FloatArray(10) { 12f }, bassDb = 10f, makeupDb = 12f)
        val input = signal(2f, { t -> 0.7 * sin(2 * PI * 60 * t) + 0.3 * sin(2 * PI * 1000 * t) })
        val peak = run(processor(loud), input).maxOf { abs(it.toInt()) }
        assertTrue("peak $peak should stay below full scale", peak < 32000)
    }

    @Test fun `balance fully right silences the left`() {
        val out = run(processor(DspConfig(enabled = true, balance = 1f)), signal(1f, sine(440.0)))
        assertTrue(rms(out, 0) < rms(out, 1) * 0.01)
    }

    @Test fun `mono sends a left only signal to both ears`() {
        val out = run(processor(DspConfig(enabled = true, mono = true)), signal(1f, sine(440.0), { 0.0 }))
        assertEquals(rms(out, 0), rms(out, 1), rms(out, 0) * 0.02)
        assertTrue(rms(out, 1) > 0.05)
    }

    @Test fun `karaoke removes a centred voice`() {
        val voice = signal(2f, sine(1000.0))
        val out = run(processor(DspConfig(enabled = true, vibe = Vibe.Karaoke)), voice)
        assertTrue(rms(out, 0) < rms(voice, 0) * 0.1)
    }

    @Test fun `a band boost lifts its own frequency more than others`() {
        val config = DspConfig(enabled = true, bands = FloatArray(10).also { it[5] = 12f }) // 1 kHz
        val at1k = rms(run(processor(config), signal(2f, sine(1000.0, 0.2))), 0) / 0.2
        val at125 = rms(run(processor(config), signal(2f, sine(125.0, 0.2))), 0) / 0.2
        val liftDb = 20 * log10(at1k / at125)
        assertTrue("1 kHz should be at least 9 dB above 125 Hz, was $liftDb", liftDb > 9)
    }
}
