package com.dj.dot.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Indexes local audio from MediaStore and re-indexes when files change. */
class Library(private val context: Context, private val scope: CoroutineScope, private val settings: Settings) {
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data
    val scanning = MutableStateFlow(false)

    private var observer: ContentObserver? = null
    private var scanJob: Job? = null

    fun start() {
        if (observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = rescan(debounceMs = 1500)
            }
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, o)
            observer = o
            rescan()
        }
    }

    fun rescan(debounceMs: Long = 0) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(debounceMs)
            scanning.value = true
            _data.value = withContext(Dispatchers.IO) { LibraryData.build(query()) }
            scanning.value = false
        }
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
