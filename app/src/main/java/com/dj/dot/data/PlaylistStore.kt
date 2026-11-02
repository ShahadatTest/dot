package com.dj.dot.data

import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Playlists, favourites and play counts, stored as one small JSON file. */
class PlaylistStore(private val dir: File, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(dir, "library.json"))
    /** The file as it was when this session started, kept in case the main one gets damaged. */
    private val previous = AtomicFile(File(dir, "library.json.prev"))
    private var keptPrevious = false
    /** One writer at a time, so a slow write can never overlap the next one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

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
        // Fall back to the copy kept from the previous session if the main file is unreadable.
        val loaded = read(file) ?: read(previous)
        if (loaded == null && file.baseFile.exists()) {
            // Keep whatever is there aside instead of letting the next save write over it.
            runCatching { file.baseFile.copyTo(File(dir, "library.json.corrupt"), overwrite = true) }
        }
        loaded?.let(::load)
    }

    private fun read(f: AtomicFile): JSONObject? =
        if (!f.baseFile.exists()) null else runCatching { JSONObject(String(f.readFully())) }.getOrNull()

    /** Each section is read on its own, so one bad field doesn't cost the rest. */
    private fun load(root: JSONObject) {
        runCatching {
            val pls = root.optJSONArray("playlists") ?: JSONArray()
            _playlists.value = (0 until pls.length()).mapNotNull { i ->
                runCatching {
                    val o = pls.getJSONObject(i)
                    val ids = o.getJSONArray("songs")
                    Playlist(o.getString("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
                }.getOrNull()
            }
        }
        runCatching {
            val fav = root.optJSONArray("favourites") ?: JSONArray()
            _favourites.value = (0 until fav.length()).map { fav.getLong(it) }.toSet()
        }
        runCatching {
            val counts = root.optJSONObject("counts") ?: JSONObject()
            _playCounts.value = counts.keys().asSequence().associate { it.toLong() to counts.getInt(it) }
        }
        runCatching {
            val listen = root.optJSONObject("listen") ?: JSONObject()
            _listenMs.value = listen.keys().asSequence().associate { it.toLong() to listen.getLong(it) }
        }
        runCatching {
            val daily = root.optJSONObject("daily") ?: JSONObject()
            _dailyMs.value = daily.keys().asSequence().associate { it to daily.getLong(it) }
        }
        runCatching { root.optJSONArray("hours")?.let { h -> _hourMs.value = List(24) { h.optLong(it) } } }
        trackingSince = root.optLong("since", trackingSince)
        runCatching {
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
    fun add(id: String, songIds: List<Long>) = edit(id) { p ->
        val existing = p.songIds.toHashSet()
        p.copy(songIds = p.songIds + songIds.filter { existing.add(it) })
    }
    /** Removes the entry at [index], but only if it is still [songId], so a stale index can't hit another song. */
    fun removeAt(id: String, index: Int, songId: Long) = edit(id) { p ->
        if (p.songIds.getOrNull(index) != songId) p else p.copy(songIds = p.songIds.filterIndexed { i, _ -> i != index })
    }
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

    /** Forgets every learned tempo so songs are measured again. */
    fun clearBpm() {
        _bpm.value = emptyMap()
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

    /**
     * A portable backup. Songs are keyed by file path with title and artist alongside, because
     * MediaStore ids change after a reset or on a new phone.
     */
    fun exportJson(lib: LibraryData): String {
        val used = HashSet<Long>()
        fun key(id: Long): String? = lib.byId[id]?.path?.also { used += id }
        val root = JSONObject()
            .put("app", "dot").put("version", 1).put("exported", System.currentTimeMillis())
            .put("playlists", JSONArray().apply {
                _playlists.value.forEach { p -> put(JSONObject().put("name", p.name).put("songs", JSONArray(p.songIds.mapNotNull(::key)))) }
            })
            .put("favourites", JSONArray(_favourites.value.mapNotNull(::key)))
            .put("counts", JSONObject().apply { _playCounts.value.forEach { (id, n) -> key(id)?.let { put(it, n) } } })
            .put("listen", JSONObject().apply { _listenMs.value.forEach { (id, ms) -> key(id)?.let { put(it, ms) } } })
            .put("bpm", JSONObject().apply { _bpm.value.forEach { (id, b) -> key(id)?.let { put(it, b) } } })
            .put("daily", JSONObject().apply { _dailyMs.value.forEach { (k, v) -> put(k, v) } })
            .put("hours", JSONArray(_hourMs.value))
            .put("since", trackingSince)
        root.put("songs", JSONObject().apply {
            used.forEach { id -> lib.byId[id]?.let { put(it.path, JSONArray().put(it.title).put(it.artist)) } }
        })
        return root.toString()
    }

    /** A backup that has been fully read and checked, ready to replace the current data in one step. */
    class Backup(
        val playlists: List<Playlist>,
        val favourites: Set<Long>,
        val counts: Map<Long, Int>,
        val listen: Map<Long, Long>,
        val bpm: Map<Long, Int>,
        val daily: Map<String, Long>?,
        val hours: List<Long>?,
        val since: Long?,
        val matched: Int,
    )

    /**
     * Replaces playlists, favourites and stats with a parsed [backup]. The data it replaces is kept in
     * `library.json.before-restore` so a restore can be undone by hand.
     */
    fun restore(backup: Backup) {
        val before = toJson()
        scope.launch(io + NonCancellable) { runCatching { AtomicFile(File(dir, "library.json.before-restore")).write(before.toByteArray()) } }
        _playlists.value = backup.playlists
        _favourites.value = backup.favourites
        _playCounts.value = backup.counts
        _listenMs.value = backup.listen
        _bpm.value = backup.bpm
        backup.daily?.let { _dailyMs.value = it }
        backup.hours?.let { _hourMs.value = it }
        backup.since?.let { trackingSince = it }
        save()
    }

    companion object {
        /** Real backups are a few hundred KB at most; anything far bigger isn't one. */
        const val MAX_BACKUP_BYTES = 5L * 1024 * 1024
        private const val MAX_NAME = 200
        private const val MAX_DAYS = 20_000
        private val dayKey = Regex("""\d{4}-\d{2}-\d{2}""")

        /**
         * Reads and checks a backup without touching any state. Throws if the file isn't a Dot backup
         * or holds values Dot would never write.
         */
        fun parseBackup(json: String, lib: LibraryData): Backup {
            val root = JSONObject(json)
            require(root.optString("app") == "dot") { "Not a Dot backup" }
            val byPath = lib.songs.associateBy { it.path }
            val byMeta = lib.songs.associateBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            val meta = root.optJSONObject("songs") ?: JSONObject()
            val matched = HashSet<Long>()
            fun idOf(path: String): Long? {
                val song = byPath[path] ?: meta.optJSONArray(path)?.let { m -> byMeta["${m.optString(0).lowercase()}|${m.optString(1).lowercase()}"] }
                return song?.id?.also { matched += it }
            }
            fun ids(arr: JSONArray?) = (0 until (arr?.length() ?: 0)).mapNotNull { idOf(arr!!.getString(it)) }
            fun <T> map(obj: JSONObject?, read: (JSONObject, String) -> T): Map<Long, T> =
                obj?.keys()?.asSequence()?.mapNotNull { k -> idOf(k)?.let { it to read(obj, k) } }?.toMap() ?: emptyMap()
            fun nonNegative(v: Long): Long { require(v >= 0) { "Negative value" }; return v }

            val pls = root.optJSONArray("playlists") ?: JSONArray()
            val playlists = (0 until pls.length()).map { i ->
                val o = pls.getJSONObject(i)
                val name = o.getString("name").trim().take(MAX_NAME).ifEmpty { "Playlist" }
                Playlist(UUID.randomUUID().toString(), name, ids(o.optJSONArray("songs")).distinct())
            }
            val daily = root.optJSONObject("daily")?.let { d ->
                require(d.length() <= MAX_DAYS) { "Too many days" }
                d.keys().asSequence().associateWith { k ->
                    require(dayKey.matches(k)) { "Bad day $k" }
                    nonNegative(d.getLong(k))
                }
            }
            val hours = root.optJSONArray("hours")?.let { h ->
                require(h.length() <= 24) { "Bad hours" }
                List(24) { nonNegative(h.optLong(it)) }
            }
            return Backup(
                playlists = playlists,
                favourites = ids(root.optJSONArray("favourites")).toSet(),
                counts = map(root.optJSONObject("counts")) { o, k -> nonNegative(o.getInt(k).toLong()).toInt() },
                listen = map(root.optJSONObject("listen")) { o, k -> nonNegative(o.getLong(k)) },
                bpm = map(root.optJSONObject("bpm")) { o, k -> o.getInt(k).also { require(it in 30..300) { "Bad tempo" } } },
                daily = daily,
                hours = hours,
                since = root.optLong("since", -1L).takeIf { it > 0 },
                matched = matched.size,
            )
        }
    }

    private inline fun edit(id: String, crossinline f: (Playlist) -> Playlist) {
        _playlists.value = _playlists.value.map { if (it.id == id) f(it) else it }
        save()
    }

    private fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val json = toJson()
            withContext(io + NonCancellable) {
                if (!keptPrevious) {
                    keptPrevious = true
                    if (read(file) != null) runCatching { previous.write(file.readFully()) }
                }
                runCatching { file.write(json.toByteArray()) }
            }
        }
    }

    private fun toJson(): String = JSONObject()
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
}

/** Writes through a temp file that is synced to disk before it replaces the old one. */
internal fun AtomicFile.write(bytes: ByteArray) {
    val out = startWrite()
    try {
        out.write(bytes)
        finishWrite(out)
    } catch (e: Throwable) {
        failWrite(out)
        throw e
    }
}
