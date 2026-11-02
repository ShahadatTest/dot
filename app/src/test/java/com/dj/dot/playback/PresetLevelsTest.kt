package com.dj.dot.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetLevelsTest {
    private val range = -1200..1200

    @Test fun `flat stays flat`() {
        assertEquals(List(10) { 0 }, AudioFx.presetLevels(floatArrayOf(0f, 0f, 0f, 0f, 0f), 10, range))
    }

    @Test fun `keeps the end points and interpolates between`() {
        val levels = AudioFx.presetLevels(floatArrayOf(6f, 0f, 0f, 0f, -6f), 10, range)
        assertEquals(600, levels.first())
        assertEquals(-600, levels.last())
        assertTrue(levels[1] in 1 until 600)
    }

    @Test fun `clamps to the range`() {
        val levels = AudioFx.presetLevels(floatArrayOf(30f, 30f, 30f, 30f, 30f), 10, range)
        assertTrue(levels.all { it == 1200 })
    }

    @Test fun `every built in preset fits the range`() {
        for ((_, curve) in AudioFx.presets) {
            assertTrue(AudioFx.presetLevels(curve, 10, range).all { it in range })
        }
    }
}
