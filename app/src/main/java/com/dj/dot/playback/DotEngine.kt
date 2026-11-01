package com.dj.dot.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.dj.dot.data.Library
import com.dj.dot.data.LibraryData
import com.dj.dot.data.PlaylistStore
import com.dj.dot.data.Settings
import com.dj.dot.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(uri)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
    )
    .build()

/**
 * Two ExoPlayers sharing one audio session. One is "active" and holds the whole queue (so
 * gapless and notification controls work natively); when a crossfade starts, the other one
 * loads the next track, the roles swap, and an equal-power curve fades one out and the other in.
 */
@OptIn(UnstableApi::class)
class DotEngine(
    private val app: Context,
    private val settings: Settings,
    private val store: PlaylistStore,
    private val library: Library,
) {
    data class State(
        val queue: List<Song> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Int = Player.REPEAT_MODE_OFF,
    ) {
        val current: Song? get() = queue.getOrNull(index)
    }

    private val scope = MainScope()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics
    /** elapsedRealtime when the sleep timer fires, 0 when off. */
    private val _sleepAt = MutableStateFlow(0L)
    val sleepAt: StateFlow<Long> = _sleepAt

    val spectrum = Spectrum()
    private val audioSessionId = Util.generateAudioSessionIdV21(app)
    val fx = AudioFx(audioSessionId)

    private val players = arrayOf(newPlayer(), newPlayer())
    private var a = 0
    private val active: ExoPlayer get() = players[a]
    private var items: List<MediaItem> = emptyList()
    private var originalQueue: List<Song>? = null

    // Final volume of each player = gain[i] * master * duck.
    private val gain = floatArrayOf(1f, 0f)
    private var master = 1f
    private var duck = 1f
    private var wantPlay = false
    private var fadeJob: Job? = null
    private var masterJob: Job? = null
    private var sleepJob: Job? = null
    private var lastCountedId = -1L
    private var lyricsFor = -1L

    var sessionPlayer: Player = SessionPlayer(players[0])
        private set
    var onSessionPlayerChanged: ((Player) -> Unit)? = null

    // ---- Audio focus & noisy ------------------------------------------------------------

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var resumeOnGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ onFocusChange(it) }, Handler(Looper.getMainLooper()))
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (wantPlay) pause(fade = false)
        }
    }

    init {
        ContextCompat.registerReceiver(
            app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED,
        )
        players.forEach { it.playbackParameters = PlaybackParameters(settings.speed.value) }
        applyVolumes()
        scope.launch { restore(library.data.first { it.ready }) }
        scope.launch {
            merge(
                settings.eqEnabled.flow, settings.eqLevels.flow, settings.bassBoost.flow,
                settings.loudness.flow, settings.virtualizer.flow, settings.reverb.flow,
            ).collect { applyFx() }
        }
        scope.launch {
            // Sleeps completely while paused and only ticks while music is playing.
            while (isActive) {
                _state.first { it.playing }
                lastTick = SystemClock.uptimeMillis()
                while (isActive && wantPlay) {
                    delay(100)
                    autoCrossfadeTick()
                    val d = duration()
                    progress = if (d > 0) (active.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
                    trackListening()
                }
                trackListening()
            }
        }
    }

    private fun newPlayer(): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(spectrum)))
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
        return ExoPlayer.Builder(app, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                false, // focus is handled here, otherwise the two players would steal it from each other
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { p ->
                p.audioSessionId = audioSessionId
                p.addListener(Listener(p))
            }
    }

    private inner class Listener(private val p: ExoPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (p === active) publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (p === active && playbackState == Player.STATE_ENDED) { wantPlay = false; publish() }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (p === active && !playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
                wantPlay = false
                publish()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (p === active) refreshLyrics(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (p !== active) return
            val next = nextIndex()
            if (next != null && next != _state.value.index) {
                active.seekTo(next, 0)
                active.prepare()
            } else {
                wantPlay = false
                publish()
            }
        }
    }

    // ---- Public controls ----------------------------------------------------------------

    /** Playhead as a fraction, refreshed on the main thread for readers on other threads. */
    @Volatile var progress = 0f
        private set

    fun position(): Long = active.currentPosition
    fun duration(): Long = active.duration.takeIf { it > 0 } ?: (_state.value.current?.duration ?: 0L)

    /** Plays [songs] from [start]. If shuffle is on, [start] plays first and the rest is shuffled. */
    fun playSongs(songs: List<Song>, start: Int = 0, shuffleAll: Boolean = false) {
        if (songs.isEmpty()) return
        val st = _state.value
        val first = start.coerceIn(0, songs.lastIndex)
        val shuffle = shuffleAll || st.shuffle
        val list = when {
            shuffleAll -> songs.shuffled()
            shuffle -> listOf(songs[first]) + songs.filterIndexed { i, _ -> i != first }.shuffled()
            else -> songs
        }
        originalQueue = if (shuffle) songs else null
        val index = if (shuffle) 0 else first
        val fade = wantPlay && active.isPlaying && settings.fadeOnSkip.value && settings.skipFadeMs.value > 0
        setQueue(list)
        _state.value = _state.value.copy(shuffle = shuffle)
        if (fade) {
            crossfadeTo(index, settings.skipFadeMs.value.toLong())
        } else {
            cancelFade()
            active.setMediaItems(items, index, 0)
            active.repeatMode = _state.value.repeat
            active.prepare()
            publish()
            play()
        }
    }

    fun toggle() = if (wantPlay) pause() else play()

    fun play() {
        if (items.isEmpty() || !requestFocus()) return
        val p = active
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
        wantPlay = true
        masterJob?.cancel()
        val fadeIn = settings.fadeOnPause.value
        if (fadeIn && !p.isPlaying) master = 0f
        if (!fadeIn) master = 1f
        applyVolumes()
        p.play()
        if (fadeJob?.isActive == true) players[1 - a].play()
        if (fadeIn) masterJob = scope.launch { rampMaster(1f, 450) }
        publish()
    }

    fun pause(fade: Boolean = settings.fadeOnPause.value) {
        wantPlay = false
        masterJob?.cancel()
        if (fade && active.isPlaying) {
            masterJob = scope.launch {
                rampMaster(0f, 350)
                players.forEach { it.pause() }
            }
        } else {
            players.forEach { it.pause() }
        }
        publish()
        save()
    }

    fun next() { nextIndex()?.let(::jumpTo) }

    fun previous() {
        if (active.currentPosition > 3000) { seekTo(0); return }
        val st = _state.value
        val prev = when {
            st.index > 0 -> st.index - 1
            st.repeat == Player.REPEAT_MODE_ALL && items.isNotEmpty() -> items.lastIndex
            else -> null
        }
        if (prev == null) seekTo(0) else jumpTo(prev)
    }

    fun jumpTo(index: Int) {
        if (index !in items.indices) return
        if (wantPlay && settings.fadeOnSkip.value && settings.skipFadeMs.value > 0) {
            crossfadeTo(index, settings.skipFadeMs.value.toLong())
        } else {
            cancelFade()
            active.seekTo(index, 0)
            publish()
        }
    }

    fun seekTo(positionMs: Long) {
        cancelFade()
        active.seekTo(positionMs)
    }

    fun toggleShuffle() {
        val st = _state.value
        val cur = st.current
        if (cur == null) { _state.value = st.copy(shuffle = !st.shuffle); return }
        val newList: List<Song>
        val newIndex: Int
        if (!st.shuffle) {
            originalQueue = st.queue
            newList = listOf(cur) + st.queue.filterIndexed { i, _ -> i != st.index }.shuffled()
            newIndex = 0
        } else {
            val orig = originalQueue ?: st.queue
            val origIds = orig.map { it.id }.toSet()
            newList = orig + st.queue.filterNot { it.id in origIds }
            newIndex = newList.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
            originalQueue = null
        }
        replaceAroundCurrent(newList, newIndex)
        _state.value = _state.value.copy(shuffle = !st.shuffle)
    }

    fun cycleRepeat() {
        val r = when (_state.value.repeat) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _state.value = _state.value.copy(repeat = r)
        active.repeatMode = r
    }

    fun playNext(songs: List<Song>) {
        if (items.isEmpty()) return playSongs(songs)
        val at = _state.value.index + 1
        setQueue(_state.value.queue.toMutableList().apply { addAll(at, songs) })
        active.addMediaItems(at, songs.map { it.toMediaItem() })
        publish()
    }

    fun addToQueue(songs: List<Song>) {
        if (items.isEmpty()) return playSongs(songs)
        setQueue(_state.value.queue + songs)
        active.addMediaItems(songs.map { it.toMediaItem() })
        publish()
    }

    fun removeFromQueue(index: Int) {
        if (index == _state.value.index || index !in items.indices) return
        setQueue(_state.value.queue.filterIndexed { i, _ -> i != index })
        active.removeMediaItem(index)
        publish()
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        setQueue(_state.value.queue.toMutableList().apply { add(to, removeAt(from)) })
        active.moveMediaItem(from, to)
        publish()
    }

    fun setSpeed(speed: Float) {
        settings.speed.set(speed)
        players.forEach { it.playbackParameters = PlaybackParameters(speed) }
    }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { _sleepAt.value = 0; return }
        scheduleSleep(minutes * 60_000L)
    }

    fun sleepAtEndOfSong() {
        val d = active.duration
        if (d <= 0) return
        sleepJob?.cancel()
        scheduleSleep(((d - active.currentPosition) / active.playbackParameters.speed).toLong())
    }

    // ---- Listening stats ----

    private var listenSong = -1L
    private var listenAcc = 0L
    private var lastTick = SystemClock.uptimeMillis()

    /** Counts real listening time, flushed every minute, on pause and on every change of song. */
    private fun trackListening() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastTick).coerceIn(0, 1000)
        lastTick = now
        val current = _state.value.current?.id
        if (active.isPlaying && current != null) {
            if (current != listenSong) { flushListening(); listenSong = current }
            listenAcc += dt
            if (listenAcc >= 60_000) flushListening()
        } else {
            flushListening()
        }
    }

    private fun flushListening() {
        if (listenAcc > 0 && listenSong >= 0) store.addListen(listenSong, listenAcc)
        listenAcc = 0
    }

    // ---- Crossfade ----------------------------------------------------------------------

    private fun autoCrossfadeTick() {
        val cf = settings.crossfadeMs.value.toLong()
        if (cf <= 0 || !wantPlay || fadeJob?.isActive == true) return
        if (_state.value.repeat == Player.REPEAT_MODE_ONE) return
        val sleep = _sleepAt.value
        if (sleep != 0L && sleep - SystemClock.elapsedRealtime() <= cf + 1000) return
        val p = active
        if (!p.isPlaying) return
        val d = p.duration
        if (d == C.TIME_UNSET || d < 5000) return
        val remaining = ((d - p.currentPosition) / p.playbackParameters.speed).toLong()
        if (remaining <= min(cf, d / 3)) {
            val next = nextIndex() ?: return
            crossfadeTo(next, remaining.coerceAtLeast(250))
        }
    }

    private fun crossfadeTo(index: Int, durationMs: Long) {
        if (index !in items.indices) return
        fadeJob?.cancel()
        val outI = a
        val inI = 1 - a
        val out = players[outI]
        val inc = players[inI]

        // Whatever was still fading out on the spare player is cut now.
        inc.stop()
        gain[inI] = 0f
        applyVolumes()
        inc.pauseAtEndOfMediaItems = false
        inc.setMediaItems(items, index, 0)
        inc.repeatMode = _state.value.repeat
        inc.playbackParameters = PlaybackParameters(settings.speed.value)
        inc.prepare()
        inc.playWhenReady = true
        // The outgoing track must not roll into the next item on its own.
        out.pauseAtEndOfMediaItems = true

        a = inI
        sessionPlayer = SessionPlayer(inc)
        onSessionPlayerChanged?.invoke(sessionPlayer)
        masterJob?.cancel()
        master = 1f
        wantPlay = true
        requestFocus()
        publish()

        val outStart = gain[outI]
        if (durationMs <= 0 || !out.isPlaying) {
            out.stop()
            gain[outI] = 0f
            gain[inI] = 1f
            applyVolumes()
            return
        }
        fadeJob = scope.launch {
            val waitFrom = SystemClock.uptimeMillis()
            while (inc.playbackState != Player.STATE_READY && SystemClock.uptimeMillis() - waitFrom < 1500) delay(10)
            var elapsed = 0L
            var last = SystemClock.uptimeMillis()
            while (elapsed < durationMs) {
                delay(16)
                val now = SystemClock.uptimeMillis()
                if (wantPlay) elapsed += now - last
                last = now
                val x = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                gain[inI] = sin(x * PI.toFloat() / 2)
                gain[outI] = outStart * cos(x * PI.toFloat() / 2)
                applyVolumes()
            }
            out.stop()
            gain[outI] = 0f
            applyVolumes()
        }
    }

    private fun cancelFade() {
        if (fadeJob?.isActive == true) {
            fadeJob?.cancel()
            players[1 - a].stop()
        }
        gain[1 - a] = 0f
        gain[a] = 1f
        applyVolumes()
    }

    private suspend fun rampMaster(to: Float, ms: Long) {
        val from = master
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            val x = ((SystemClock.uptimeMillis() - t0).toFloat() / ms).coerceIn(0f, 1f)
            master = from + (to - from) * x
            applyVolumes()
            if (x >= 1f) break
            delay(16)
        }
    }

    private fun applyVolumes() {
        for (i in players.indices) players[i].volume = (gain[i] * master * duck).coerceIn(0f, 1f)
    }

    // ---- Internals ----------------------------------------------------------------------

    private fun nextIndex(): Int? {
        val st = _state.value
        return when {
            st.index + 1 < items.size -> st.index + 1
            st.repeat == Player.REPEAT_MODE_ALL && items.isNotEmpty() -> 0
            else -> null
        }
    }

    private fun setQueue(list: List<Song>) {
        items = list.map { it.toMediaItem() }
        _state.value = _state.value.copy(queue = list)
    }

    /** Swaps the queue order without interrupting the track that is playing. */
    private fun replaceAroundCurrent(newList: List<Song>, newIndex: Int) {
        val p = active
        val idx = p.currentMediaItemIndex
        val count = p.mediaItemCount
        setQueue(newList)
        if (idx + 1 < count) p.removeMediaItems(idx + 1, count)
        if (idx > 0) p.removeMediaItems(0, idx)
        if (newIndex > 0) p.addMediaItems(0, items.subList(0, newIndex))
        if (newIndex + 1 < items.size) p.addMediaItems(newIndex + 1, items.subList(newIndex + 1, items.size))
        publish()
    }

    private fun publish() {
        val q = _state.value.queue
        val idx = if (q.isEmpty()) -1 else active.currentMediaItemIndex.coerceIn(0, q.lastIndex)
        _state.value = _state.value.copy(index = idx, playing = wantPlay)
        val cur = q.getOrNull(idx)
        if (cur != null && cur.id != lastCountedId) {
            lastCountedId = cur.id
            store.countPlay(cur.id)
            refreshLyrics(null)
        }
    }

    private fun refreshLyrics(tracks: Tracks?) {
        val song = _state.value.queue.getOrNull(active.currentMediaItemIndex)
            ?.takeIf { it.id.toString() == active.currentMediaItem?.mediaId }
        if (song == null) { lyricsFor = -1; _lyrics.value = null; return }
        if (song.id != lyricsFor) {
            lyricsFor = song.id
            _lyrics.value = null
            scope.launch {
                val fromFile = withContext(Dispatchers.IO) { LyricsParser.sidecar(song) }
                if (fromFile != null && lyricsFor == song.id) _lyrics.value = fromFile
            }
        }
        if (tracks != null && _lyrics.value == null) LyricsParser.embedded(tracks)?.let { _lyrics.value = it }
    }

    private fun scheduleSleep(ms: Long) {
        _sleepAt.value = SystemClock.elapsedRealtime() + ms
        sleepJob = scope.launch {
            delay((ms - 5000).coerceAtLeast(0))
            masterJob?.cancel()
            rampMaster(0f, min(5000, ms))
            players.forEach { it.pause() }
            wantPlay = false
            _sleepAt.value = 0
            publish()
            save()
        }
    }

    private fun requestFocus() =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnGain = false; pause(fade = true) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeOnGain = wantPlay; pause(fade = false) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duck = 0.25f; applyVolumes() }
            AudioManager.AUDIOFOCUS_GAIN -> {
                duck = 1f
                applyVolumes()
                if (resumeOnGain) { resumeOnGain = false; play() }
            }
        }
    }

    private fun applyFx() {
        fx.apply(
            settings.eqEnabled.value, parseLevels(settings.eqLevels.value), settings.bassBoost.value,
            settings.loudness.value, settings.virtualizer.value, settings.reverb.value,
        )
        val send = fx.reverbSendId
        val aux = if (send != null) AuxEffectInfo(send, 1f) else AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
        players.forEach { it.setAuxEffectInfo(aux) }
    }

    fun parseLevels(raw: String): List<Int> {
        val parsed = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        return List(fx.bandCount) { parsed.getOrElse(it) { 0 } }
    }

    fun save() {
        val st = _state.value
        settings.queueIds.set(st.queue.joinToString(",") { it.id.toString() })
        settings.queueIndex.set(st.index.coerceAtLeast(0))
        settings.queuePos.set(active.currentPosition)
        settings.shuffle.set(st.shuffle)
        settings.repeat.set(st.repeat)
    }

    private fun restore(lib: LibraryData) {
        if (items.isNotEmpty()) return
        val songs = settings.queueIds.value.split(',').mapNotNull { it.toLongOrNull()?.let(lib.byId::get) }
        if (songs.isEmpty()) return
        val index = settings.queueIndex.value.coerceIn(0, songs.lastIndex)
        setQueue(songs)
        _state.value = _state.value.copy(shuffle = settings.shuffle.value, repeat = settings.repeat.value)
        lastCountedId = songs[index].id
        active.repeatMode = settings.repeat.value
        active.setMediaItems(items, index, settings.queuePos.value)
        active.prepare()
        publish()
        refreshLyrics(null)
    }

    /** What the media session (notification, lock screen, headset) talks to. */
    private inner class SessionPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() = this@DotEngine.play()
        override fun pause() = this@DotEngine.pause()
        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
        override fun seekToNext() = this@DotEngine.next()
        override fun seekToNextMediaItem() = this@DotEngine.next()
        override fun seekToPrevious() = this@DotEngine.previous()
        override fun seekToPreviousMediaItem() = this@DotEngine.previous()
        override fun seekTo(positionMs: Long) = this@DotEngine.seekTo(positionMs)
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == _state.value.index) this@DotEngine.seekTo(positionMs) else jumpTo(mediaItemIndex)
        }
        override fun stop() = this@DotEngine.pause(fade = false)
    }
}
