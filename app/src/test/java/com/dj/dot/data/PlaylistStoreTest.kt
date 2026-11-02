package com.dj.dot.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PlaylistStoreTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        dir = Files.createTempDirectory("dot-store").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun song(id: Long, title: String = "Song $id", artist: String = "Artist") = Song(
        id = id, title = title, artist = artist, album = "Album", albumId = 1, duration = 200_000, track = id.toInt(),
        year = 2024, path = "/music/$title.mp3", folder = "/music", dateAdded = 0,
    )

    private fun library(vararg songs: Song) = LibraryData.build(songs.toList())

    private fun libraryJson(playlist: String = "Mix", songs: List<Long> = listOf(1, 2)) = JSONObject()
        .put("playlists", JSONArray().put(JSONObject().put("id", "p1").put("name", playlist).put("songs", JSONArray(songs))))
        .put("favourites", JSONArray(listOf(2)))
        .toString()

    /** Saves are debounced and written on a background thread; wait until the file has [text]. */
    private fun awaitFile(name: String, text: String) {
        val f = File(dir, name)
        repeat(100) {
            if (f.exists() && f.readText().contains(text)) return
            Thread.sleep(50)
        }
        fail("$name never contained $text")
    }

    @Test fun savesAndLoadsPlaylists() {
        val store = PlaylistStore(dir, scope)
        store.create("Road trip", listOf(3, 4))
        store.toggleFavourite(4)
        awaitFile("library.json", "Road trip")

        val again = PlaylistStore(dir, scope)
        assertEquals("Road trip", again.playlists.value.single().name)
        assertEquals(listOf(3L, 4L), again.playlists.value.single().songIds)
        assertEquals(setOf(4L), again.favourites.value)
    }

    @Test fun damagedFileFallsBackToPreviousSession() {
        File(dir, "library.json").writeText(libraryJson("Good"))
        // First session: the first save keeps the good file as the previous copy.
        PlaylistStore(dir, scope).toggleFavourite(9)
        awaitFile("library.json.prev", "Good")

        File(dir, "library.json").writeText("{\"playlists\": [ truncated")
        val store = PlaylistStore(dir, scope)
        assertEquals("Good", store.playlists.value.single().name)
    }

    @Test fun unreadableFileIsKeptAsideNotLost() {
        val broken = "{\"playlists\": [ truncated"
        File(dir, "library.json").writeText(broken)
        val store = PlaylistStore(dir, scope)
        assertTrue(store.playlists.value.isEmpty())
        assertEquals(broken, File(dir, "library.json.corrupt").readText())
    }

    @Test fun oneBadSectionDoesNotDropTheRest() {
        val json = JSONObject(libraryJson("Kept")).put("counts", JSONObject().put("1", "lots")).toString()
        File(dir, "library.json").writeText(json)
        val store = PlaylistStore(dir, scope)
        assertEquals("Kept", store.playlists.value.single().name)
        assertEquals(setOf(2L), store.favourites.value)
    }

    @Test fun removeAtIgnoresAStaleIndex() {
        val store = PlaylistStore(dir, scope)
        val id = store.create("Mix", listOf(1, 2, 3))
        store.removeAt(id, 1, songId = 3)
        assertEquals(listOf(1L, 2L, 3L), store.playlists.value.single().songIds)
        store.removeAt(id, 1, songId = 2)
        assertEquals(listOf(1L, 3L), store.playlists.value.single().songIds)
    }

    private fun backup(block: JSONObject.() -> Unit = {}) = JSONObject()
        .put("app", "dot").put("version", 1)
        .put("playlists", JSONArray().put(JSONObject().put("name", "Backed up").put("songs", JSONArray(listOf("/music/Song 1.mp3")))))
        .put("favourites", JSONArray(listOf("/music/Song 2.mp3")))
        .put("counts", JSONObject().put("/music/Song 1.mp3", 5))
        .put("daily", JSONObject().put("2026-11-01", 60_000))
        .apply(block)
        .toString()

    @Test fun restoreReplacesEverythingFromAValidBackup() {
        val lib = library(song(1), song(2))
        val store = PlaylistStore(dir, scope)
        store.create("Old")
        val parsed = PlaylistStore.parseBackup(backup(), lib)
        store.restore(parsed)
        assertEquals("Backed up", store.playlists.value.single().name)
        assertEquals(listOf(1L), store.playlists.value.single().songIds)
        assertEquals(setOf(2L), store.favourites.value)
        assertEquals(mapOf(1L to 5), store.playCounts.value)
        assertEquals(2, parsed.matched)
        awaitFile("library.json.before-restore", "Old")
    }

    @Test fun restoreMatchesByTitleAndArtistWhenPathsDiffer() {
        val moved = song(7, title = "Song 1").copy(path = "/sdcard/Song 1.mp3")
        val json = backup { put("songs", JSONObject().put("/music/Song 1.mp3", JSONArray().put("Song 1").put("Artist"))) }
        val parsed = PlaylistStore.parseBackup(json, library(moved))
        assertEquals(listOf(7L), parsed.playlists.single().songIds)
    }

    @Test fun malformedBackupIsRejectedBeforeAnythingChanges() {
        val lib = library(song(1), song(2))
        val bad = listOf(
            "not json",
            JSONObject().put("app", "other").toString(),
            backup { put("counts", JSONObject().put("/music/Song 1.mp3", "five")) },
            backup { put("counts", JSONObject().put("/music/Song 1.mp3", -1)) },
            backup { put("daily", JSONObject().put("yesterday", 10)) },
            backup { put("hours", JSONArray(List(25) { 0 })) },
        )
        for (json in bad) {
            val threw = runCatching { PlaylistStore.parseBackup(json, lib) }.isFailure
            assertTrue("accepted: $json", threw)
        }
    }

    @Test fun longPlaylistNamesAreTrimmed() {
        val json = backup { put("playlists", JSONArray().put(JSONObject().put("name", "x".repeat(5000)).put("songs", JSONArray()))) }
        val parsed = PlaylistStore.parseBackup(json, library(song(1)))
        assertEquals(200, parsed.playlists.single().name.length)
        assertFalse(parsed.playlists.single().name.isBlank())
    }
}
