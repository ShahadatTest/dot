package com.dj.dot.playback

import android.os.SystemClock
import com.dj.dot.data.PlaylistStore

/**
 * Turns the engine's playback tick into listening statistics and learned tempos. Listening time
 * is flushed every minute, on pause and on every change of song; a song's tempo is learned from
 * its first 25 seconds and never measured again.
 */
class ListeningTracker(private val store: PlaylistStore, private val spectrum: Spectrum) {
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
            store.setBpm(songId, spectrum.bpm)
            stopLearningTempo()
        }
    }

    private fun stopLearningTempo() {
        if (tempoListening) { spectrum.release(needsWave = false); tempoListening = false }
        tempoSong = -1L
    }
}
