package com.dj.dot.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Playlists, favourites and play counts, stored as one small JSON file. */
class PlaylistStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "library.json")

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists
    private val _favourites = MutableStateFlow<Set<Long>>(emptySet())
    val favourites: StateFlow<Set<Long>> = _favourites
    private val _playCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val playCounts: StateFlow<Map<Long, Int>> = _playCounts

    /** Milliseconds actually listened, per song, per day (yyyy-MM-dd) and per hour of day. */
    private val _listenMs = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val listenMs: StateFlow<Map<Long, Long>> = _listenMs
    private val _dailyMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val dailyMs: StateFlow<Map<String, Long>> = _dailyMs
    private val _hourMs = MutableStateFlow(List(24) { 0L })
    val hourMs: StateFlow<List<Long>> = _hourMs
    /** Tempo learned the first time each song plays, for the mood mixes. */
    private val _bpm = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val bpm: StateFlow<Map<Long, Int>> = _bpm
    var trackingSince: Long = System.currentTimeMillis()
        private set

    private var saveJob: Job? = null

    init {
        runCatching {
            val root = JSONObject(file.readText())
            val pls = root.optJSONArray("playlists") ?: JSONArray()
            _playlists.value = (0 until pls.length()).map { i ->
                val o = pls.getJSONObject(i)
                val ids = o.getJSONArray("songs")
                Playlist(o.getString("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
            }
            val fav = root.optJSONArray("favourites") ?: JSONArray()
            _favourites.value = (0 until fav.length()).map { fav.getLong(it) }.toSet()
            val counts = root.optJSONObject("counts") ?: JSONObject()
            _playCounts.value = counts.keys().asSequence().associate { it.toLong() to counts.getInt(it) }
            val listen = root.optJSONObject("listen") ?: JSONObject()
            _listenMs.value = listen.keys().asSequence().associate { it.toLong() to listen.getLong(it) }
            val daily = root.optJSONObject("daily") ?: JSONObject()
            _dailyMs.value = daily.keys().asSequence().associate { it to daily.getLong(it) }
            root.optJSONArray("hours")?.let { h -> _hourMs.value = List(24) { h.optLong(it) } }
            trackingSince = root.optLong("since", trackingSince)
            val tempo = root.optJSONObject("bpm") ?: JSONObject()
            _bpm.value = tempo.keys().asSequence().associate { it.toLong() to tempo.getInt(it) }
        }
    }

    fun create(name: String, songIds: List<Long> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        _playlists.value = _playlists.value + Playlist(id, name.trim().ifEmpty { "Playlist" }, songIds)
        save()
        return id
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    fun delete(id: String) { _playlists.value = _playlists.value.filterNot { it.id == id }; save() }
    fun add(id: String, songIds: List<Long>) = edit(id) { p -> p.copy(songIds = p.songIds + songIds.filterNot { it in p.songIds }) }
    fun removeAt(id: String, index: Int) = edit(id) { p -> p.copy(songIds = p.songIds.filterIndexed { i, _ -> i != index }) }
    fun move(id: String, from: Int, to: Int) = edit(id) { p ->
        val l = p.songIds.toMutableList()
        if (from in l.indices && to in l.indices) l.add(to, l.removeAt(from))
        p.copy(songIds = l)
    }

    fun toggleFavourite(songId: Long) {
        val f = _favourites.value
        _favourites.value = if (songId in f) f - songId else f + songId
        save()
    }

    fun countPlay(songId: Long) {
        val c = _playCounts.value
        _playCounts.value = c + (songId to (c[songId] ?: 0) + 1)
        save()
    }

    fun setBpm(songId: Long, bpm: Int) {
        _bpm.value = _bpm.value + (songId to bpm)
        save()
    }

    fun addListen(songId: Long, ms: Long) {
        if (ms <= 0) return
        val now = java.util.Calendar.getInstance()
        val day = "%04d-%02d-%02d".format(now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH))
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        _listenMs.value = _listenMs.value + (songId to (_listenMs.value[songId] ?: 0L) + ms)
        _dailyMs.value = _dailyMs.value + (day to (_dailyMs.value[day] ?: 0L) + ms)
        _hourMs.value = _hourMs.value.toMutableList().also { it[hour] += ms }
        save()
    }

    private inline fun edit(id: String, crossinline f: (Playlist) -> Playlist) {
        _playlists.value = _playlists.value.map { if (it.id == id) f(it) else it }
        save()
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val root = JSONObject()
                .put("playlists", JSONArray().apply {
                    _playlists.value.forEach { p ->
                        put(JSONObject().put("id", p.id).put("name", p.name).put("songs", JSONArray(p.songIds)))
                    }
                })
                .put("favourites", JSONArray(_favourites.value.toList()))
                .put("counts", JSONObject().apply { _playCounts.value.forEach { (k, v) -> put(k.toString(), v) } })
                .put("listen", JSONObject().apply { _listenMs.value.forEach { (k, v) -> put(k.toString(), v) } })
                .put("daily", JSONObject().apply { _dailyMs.value.forEach { (k, v) -> put(k, v) } })
                .put("hours", JSONArray(_hourMs.value))
                .put("since", trackingSince)
                .put("bpm", JSONObject().apply { _bpm.value.forEach { (k, v) -> put(k.toString(), v) } })
                .toString()
            launch(Dispatchers.IO) {
                val tmp = File(file.parentFile, "library.json.tmp")
                tmp.writeText(root)
                tmp.renameTo(file)
            }
        }
    }
}
