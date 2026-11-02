package com.dj.dot.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TempoTest {
    @Test fun `normal speed keeps the tempo`() = assertEquals(120, songTempo(120, 1f))

    @Test fun `undoes playback speed`() {
        assertEquals(100, songTempo(82, 0.82f))
        assertEquals(128, songTempo(156, 1.22f))
    }

    @Test fun `folds into 70 to 180`() {
        assertEquals(120, songTempo(60, 1f))
        assertEquals(100, songTempo(200, 1f))
    }
}
