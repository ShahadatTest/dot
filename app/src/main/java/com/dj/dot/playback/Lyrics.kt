package com.dj.dot.playback

import android.os.Environment
import androidx.annotation.OptIn
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.dj.dot.data.Song
import java.io.File

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

object LyricsParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String): Lyrics? {
        val synced = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        raw.lineSequence().forEach { line ->
            val tags = timeTag.findAll(line).toList()
            if (tags.isNotEmpty()) {
                val text = line.substring(tags.last().range.last + 1).trim()
                tags.forEach { m ->
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0
                        1 -> frac.toInt() * 100
                        2 -> frac.toInt() * 10
                        else -> frac.take(3).toInt()
                    }
                    synced += LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms, text)
                }
            } else if (!line.trimStart().startsWith("[")) {
                plain += line.trimEnd()
            }
        }
        return when {
            synced.isNotEmpty() -> Lyrics(true, synced.sortedBy { it.timeMs })
            plain.any { it.isNotBlank() } -> Lyrics(false, plain.dropWhile { it.isBlank() }.map { LyricLine(0, it) })
            else -> null
        }
    }

    /** A `.lrc` next to the audio file. Needs "All files access" since lrc files aren't media. */
    fun sidecar(song: Song): Lyrics? {
        if (!Environment.isExternalStorageManager() || song.path.isEmpty()) return null
        val base = song.path.substringBeforeLast('.')
        val file = listOf("$base.lrc", "$base.LRC").map(::File).firstOrNull { it.canRead() } ?: return null
        return runCatching { parse(file.readText()) }.getOrNull()
    }

    @OptIn(UnstableApi::class)
    fun embedded(tracks: Tracks): Lyrics? {
        for (group in tracks.groups) for (i in 0 until group.length) {
            val md = group.getTrackFormat(i).metadata ?: continue
            for (j in 0 until md.length()) {
                val text = when (val e = md.get(j)) {
                    is VorbisComment -> e.value.takeIf { e.key.equals("LYRICS", true) || e.key.equals("UNSYNCEDLYRICS", true) }
                    is BinaryFrame -> if (e.id == "USLT") decodeUslt(e.data) else null
                    is TextInformationFrame -> e.values.firstOrNull().takeIf { e.description?.contains("lyric", true) == true }
                    else -> null
                }
                text?.let(::parse)?.let { return it }
            }
        }
        return null
    }

    /** ID3 USLT: encoding byte, 3-byte language, null-terminated description, then the text. */
    private fun decodeUslt(d: ByteArray): String? {
        if (d.size < 5) return null
        val enc = d[0].toInt()
        val charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        var i = 4
        if (enc == 1 || enc == 2) {
            while (i + 1 < d.size && !(d[i].toInt() == 0 && d[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < d.size && d[i].toInt() != 0) i++
            i++
        }
        if (i >= d.size) return null
        return String(d, i, d.size - i, charset).trim('\u0000', ' ', '\n')
    }
}
