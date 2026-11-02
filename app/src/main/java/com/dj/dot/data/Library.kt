package com.dj.dot.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Indexes local audio from MediaStore and re-indexes when files change. */
class Library(private val context: Context, private val scope: CoroutineScope, private val settings: Settings) {
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data
    val scanning = MutableStateFlow(false)

    private var observer: ContentObserver? = null
    private var scanJob: Job? = null

    private val cacheFile = AtomicFile(File(context.filesDir, "library_cache.json"))

    fun start() {
        if (observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = rescan(debounceMs = 1500)
            }
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, o)
            observer = o
            scope.launch { openFromCache() }
        }
    }

    /**
     * Shows the last known library straight away, then re-reads MediaStore only if Android
     * reports a change since that snapshot (or the filters changed).
     */
    private suspend fun openFromCache() {
        val cached = withContext(Dispatchers.IO) { readCache() }
        if (cached != null) _data.value = withContext(Dispatchers.Default) { LibraryData.build(cached.second) }
        if (cached == null || cached.first != snapshotKey()) rescan()
    }

    fun rescan(debounceMs: Long = 0) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(debounceMs)
            scanning.value = true
            val key = snapshotKey()
            val songs = withContext(Dispatchers.IO) { query() }
            _data.value = withContext(Dispatchers.Default) { LibraryData.build(songs) }
            scanning.value = false
            withContext(Dispatchers.IO) { writeCache(key, songs) }
        }
    }

    /** MediaStore's change counter plus the scan filters; equal keys mean nothing changed. */
    private fun snapshotKey(): String {
        val generation = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { MediaStore.getExternalVolumeNames(context).sumOf { MediaStore.getGeneration(context, it) } }.getOrDefault(-1L)
        } else -1L
        return "$generation|${settings.minDurationSec.value}|${settings.hideWhatsApp.value}"
    }

    private fun readCache(): Pair<String, List<Song>>? = runCatching {
        val root = JSONObject(String(cacheFile.readFully()))
        val arr = root.getJSONArray("songs")
        val songs = (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            val id = a.getLong(0)
            val path = a.getString(8)
            Song(
                id = id, title = a.getString(1), artist = a.getString(2), album = a.getString(3),
                albumId = a.getLong(4), duration = a.getLong(5), track = a.getInt(6), year = a.getInt(7),
                path = path, folder = path.substringBeforeLast('/', ""), dateAdded = a.getLong(9),
                uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
            )
        }
        root.getString("key") to songs
    }.getOrNull()

    private fun writeCache(key: String, songs: List<Song>) = runCatching {
        val arr = JSONArray()
        songs.forEach { s ->
            arr.put(JSONArray().put(s.id).put(s.title).put(s.artist).put(s.album).put(s.albumId)
                .put(s.duration).put(s.track).put(s.year).put(s.path).put(s.dateAdded))
        }
        cacheFile.write(JSONObject().put("key", key).put("songs", arr).toString().toByteArray())
    }

    private fun query(): List<Song> {
        val minMs = settings.minDurationSec.value * 1000L
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val out = ArrayList<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.DURATION} >= ?", arrayOf(minMs.toString()), null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val hideWhatsApp = settings.hideWhatsApp.value
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = c.getString(iData).orEmpty()
                val fileName = path.substringAfterLast('/').substringBeforeLast('.')
                if (hideWhatsApp && isWhatsApp(path, fileName)) continue
                out += Song(
                    id = id,
                    title = TagCleaner.clean(c.getString(iTitle).orEmpty()).ifEmpty { TagCleaner.clean(fileName) }.ifEmpty { fileName },
                    artist = c.getString(iArtist).clean("Unknown artist"),
                    album = c.getString(iAlbum).clean("Unknown album"),
                    albumId = c.getLong(iAlbumId),
                    duration = c.getLong(iDur),
                    track = c.getInt(iTrack) % 1000,
                    year = c.getInt(iYear),
                    path = path,
                    folder = path.substringBeforeLast('/', ""),
                    dateAdded = c.getLong(iAdded),
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                )
            }
        }
        return out
    }

    private fun String?.clean(fallback: String) =
        if (isNullOrBlank() || this == "<unknown>") fallback else TagCleaner.clean(this).ifEmpty { fallback }

    private val whatsAppName = Regex("""^(AUD|PTT)-\d{8}-WA\d+.*""", RegexOption.IGNORE_CASE)

    /** WhatsApp voice notes and shared audio, wherever they were saved. */
    private fun isWhatsApp(path: String, fileName: String) =
        whatsAppName.matches(fileName) || path.contains("/WhatsApp", ignoreCase = true)
}
