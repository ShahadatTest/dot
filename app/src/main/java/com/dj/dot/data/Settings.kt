package com.dj.dot.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("dot", Context.MODE_PRIVATE)

    inner class Pref<T>(
        private val key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) {
        private val state = MutableStateFlow(sp.read(key, default))
        val flow: StateFlow<T> = state
        val value: T get() = state.value
        fun set(v: T) {
            state.value = v
            sp.edit().apply { write(key, v) }.apply()
        }
    }

    private fun int(k: String, d: Int) = Pref(k, d, { a, b -> getInt(a, b) }, { a, b -> putInt(a, b) })
    private fun long(k: String, d: Long) = Pref(k, d, { a, b -> getLong(a, b) }, { a, b -> putLong(a, b) })
    private fun bool(k: String, d: Boolean) = Pref(k, d, { a, b -> getBoolean(a, b) }, { a, b -> putBoolean(a, b) })
    private fun float(k: String, d: Float) = Pref(k, d, { a, b -> getFloat(a, b) }, { a, b -> putFloat(a, b) })
    private fun string(k: String, d: String) = Pref(k, d, { a, b -> getString(a, b) ?: b }, { a, b -> putString(a, b) })

    // Playback
    val crossfadeMs = int("crossfade_ms", 6000)
    val skipFadeMs = int("skip_fade_ms", 1200)
    val fadeOnSkip = bool("fade_skip", true)
    val fadeOnPause = bool("fade_pause", true)
    val beatAlignedFade = bool("beat_fade", true)
    val speed = float("speed", 1f)

    // Sound
    val eqEnabled = bool("eq_on", false)
    val eqLevels = string("eq_levels", "")
    val eqPreset = string("eq_preset", "Flat")
    val bassBoost = int("bass", 0)
    val loudness = int("loudness", 0)
    val virtualizer = int("virtualizer", 0)
    val reverb = int("reverb", 0)
    val soundEffect = string("sound_effect", "Hi-Fi")
    val levelling = bool("levelling", false)
    val balance = int("balance", 0)
    val mono = bool("mono", false)
    val vibe = int("vibe", 0)

    // Library & UI
    val minDurationSec = int("min_duration", 30)
    val hideWhatsApp = bool("hide_whatsapp", true)
    val songSort = int("song_sort", 0)
    val visualizer = bool("visualizer", true)
    val stripStyle = int("strip_style", 0)
    val artStyle = int("art_style", 0)
    val glyphMode = int("glyph_mode", 1)
    val shakeForRandom = bool("shake_random", true)
    val playOnConnect = bool("play_on_connect", true)
    val bedsideWhenCharging = bool("bedside_charging", true)
    val glyphBrightness = int("glyph_brightness", 100)
    val glyphSensitivity = int("glyph_sensitivity", 100)
    val glyphBeatFlash = bool("glyph_beat_flash", true)

    /** Per-song lyrics timing shift in ms; positive shows lines later. */
    fun lyricsOffset(songId: Long): Int = sp.getInt("lrc_off_$songId", 0)
    fun setLyricsOffset(songId: Long, ms: Int) = sp.edit().putInt("lrc_off_$songId", ms).apply()

    // Restored session
    val queueIds = string("q_ids", "")
    val queueIndex = int("q_index", 0)
    val queuePos = long("q_pos", 0)
    val shuffle = bool("shuffle", false)
    val repeat = int("repeat", 0)
}
