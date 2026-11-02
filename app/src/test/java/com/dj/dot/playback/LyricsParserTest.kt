package com.dj.dot.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {
    @Test fun `parses synced lines and sorts them`() {
        val l = LyricsParser.parse("[00:12.50]second\n[00:03.00]first")!!
        assertTrue(l.synced)
        assertEquals(listOf(3_000L, 12_500L), l.lines.map { it.timeMs })
        assertEquals("first", l.lines[0].text)
    }

    @Test fun `reads one, two and three digit fractions`() {
        val l = LyricsParser.parse("[00:01.5]a\n[00:02.25]b\n[00:03.125]c")!!
        assertEquals(listOf(1_500L, 2_250L, 3_125L), l.lines.map { it.timeMs })
    }

    @Test fun `expands a line with several time tags`() {
        val l = LyricsParser.parse("[00:10.00][01:10.00]chorus")!!
        assertEquals(listOf(10_000L, 70_000L), l.lines.map { it.timeMs })
        assertTrue(l.lines.all { it.text == "chorus" })
    }

    @Test fun `skips metadata tags`() {
        val l = LyricsParser.parse("[ar:Someone]\n[ti:Song]\n[00:01.00]hello")!!
        assertEquals(1, l.lines.size)
    }

    @Test fun `falls back to plain lyrics`() {
        val l = LyricsParser.parse("\nfirst line\nsecond line")!!
        assertFalse(l.synced)
        assertEquals("first line", l.lines.first().text)
    }

    @Test fun `returns null for nothing`() {
        assertNull(LyricsParser.parse(""))
        assertNull(LyricsParser.parse("[ar:Only metadata]"))
    }

    @Test fun `finds the line being sung`() {
        val l = LyricsParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")!!
        assertEquals(-1, l.lineAt(500))
        assertEquals(0, l.lineAt(1_000))
        assertEquals(1, l.lineAt(8_999))
        assertEquals(2, l.lineAt(60_000))
    }

    @Test fun `plain lyrics have no current line`() {
        assertEquals(-1, LyricsParser.parse("just words")!!.lineAt(5_000))
    }
}
