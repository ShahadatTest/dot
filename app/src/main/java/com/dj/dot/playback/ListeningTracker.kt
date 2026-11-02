package com.dj.dot.playback

import android.os.SystemClock
import com.dj.dot.data.PlaylistStore

/**
 * Turns the engine's playback tick into listening statistics and learned tempos. Listening time
 * is flushed every minute, on pause and on every change of song; a song's tempo is learned from
 * its first 25 seconds and never measured again.
 *
 * Beats are timed as the audio is played, so at another [speed] the measured tempo is scaled by
 * it; the stored tempo is always the song's own.
 */
class ListeningTracker(
    private val store: PlaylistStore,
    private val spectrum: Spectrum,
    private val speed: () -> Float,
) {
    private var listenSong = -1L
    private var listenAcc = 0L
    private var lastTick = SystemClock.uptimeMillis()

    private var tempoSong = -1L
    private var tempoSince = 0L
    private var tempoListening = false

    /** Call when playback (re)starts so paused time isn't counted. */
    fun resume() {
        lastTick = SystemClock.uptimeMillis()
    }

    /** Called about ten times a second while music plays. */
    fun tick(songId: Long?, isPlaying: Boolean) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceIn(0, 1000)
        lastTick = now
        if (isPlaying && songId != null) {
            if (songId != listenSong) { flush(); listenSong = songId }
            listenAcc += dt
            if (listenAcc >= 60_000) flush()
            learnTempo(songId, now)
        } else {
            flush()
        }
    }

    /** Called when playback stops: saves pending time and stops listening for tempo. */
    fun pause() {
        flush()
        stopLearningTempo()
    }

    private fun flush() {
        if (listenAcc > 0 && listenSong >= 0) store.addListen(listenSong, listenAcc)
        listenAcc = 0
    }

    private fun learnTempo(songId: Long, now: Long) {
        if (songId in store.bpm.value) { stopLearningTempo(); return }
        if (!tempoListening) { spectrum.acquire(needsWave = false); tempoListening = true }
        if (songId != tempoSong) {
            tempoSong = songId
            tempoSince = now
            spectrum.resetTempo()
        } else if (now - tempoSince > 25_000 && spectrum.bpm > 0) {
            store.setBpm(songId, songTempo(spectrum.bpm, speed()))
            stopLearningTempo()
        }
    }

    private fun stopLearningTempo() {
        if (tempoListening) { spectrum.release(needsWave = false); tempoListening = false }
        tempoSong = -1L
    }
}

/** The song's own tempo from one measured at playback [speed], folded into the same 70–180 range. */
internal fun songTempo(measured: Int, speed: Float): Int {
    var t = measured / speed.coerceIn(0.25f, 4f).toDouble()
    while (t < 70) t *= 2
    while (t > 180) t /= 2
    return kotlin.math.round(t).toInt()
}
