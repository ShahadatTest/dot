package com.dj.dot.data

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val track: Int,
    val year: Int,
    val path: String,
    val folder: String,
    val dateAdded: Long,
    val uri: Uri,
)

@Immutable
data class Album(val id: Long, val title: String, val artist: String, val year: Int, val songs: List<Song>)

@Immutable
data class Artist(val name: String, val songs: List<Song>, val albumCount: Int)

@Immutable
data class Folder(val path: String, val name: String, val songs: List<Song>)

@Immutable
data class Playlist(val id: String, val name: String, val songIds: List<Long>)

@Immutable
data class LibraryData(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val ready: Boolean = false,
) {
    val byId: Map<Long, Song> by lazy { songs.associateBy { it.id } }

    companion object {
        private val byText = String.CASE_INSENSITIVE_ORDER

        fun build(all: List<Song>): LibraryData {
            val songs = all.sortedWith(compareBy(byText) { it.title })
            val albums = all.groupBy { it.albumId }.map { (id, list) ->
                val sorted = list.sortedWith(compareBy<Song> { it.track }.thenBy(byText) { it.title })
                val artists = list.map { it.artist }.distinct()
                Album(id, sorted.first().album, if (artists.size == 1) artists[0] else "Various artists", list.maxOf { it.year }, sorted)
            }.sortedWith(compareBy(byText) { it.title })
            val artists = all.groupBy { it.artist }.map { (name, list) ->
                Artist(
                    name,
                    list.sortedWith(compareBy<Song, String>(byText) { it.album }.thenBy { it.track }),
                    list.distinctBy { it.albumId }.size,
                )
            }.sortedWith(compareBy(byText) { it.name })
            val folders = all.groupBy { it.folder }.map { (path, list) ->
                Folder(path, path.substringAfterLast('/').ifEmpty { path }, list.sortedWith(compareBy(byText) { it.path }))
            }.sortedWith(compareBy(byText) { it.name })
            return LibraryData(songs, albums, artists, folders, ready = true)
        }
    }
}
