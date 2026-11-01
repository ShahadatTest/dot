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
                .toString()
            launch(Dispatchers.IO) {
                val tmp = File(file.parentFile, "library.json.tmp")
                tmp.writeText(root)
                tmp.renameTo(file)
            }
        }
    }
}
