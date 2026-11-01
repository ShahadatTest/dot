package com.dj.dot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.data.Song

@Composable
fun AlbumScreen(id: Long) {
    val lib by DotApp.instance.library.data.collectAsStateWithLifecycle()
    val album = lib.albums.firstOrNull { it.id == id } ?: return Missing()
    val meta = listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString(), "${album.songs.size} songs").joinToString(" · ")
    SongListScreen(album.title, meta, album.songs, cover = album.songs.first(), trackNumbers = true)
}

@Composable
fun ArtistScreen(name: String) {
    val lib by DotApp.instance.library.data.collectAsStateWithLifecycle()
    val artist = lib.artists.firstOrNull { it.name == name } ?: return Missing()
    SongListScreen(artist.name, "${artist.songs.size} songs · ${artist.albumCount} albums", artist.songs, cover = artist.songs.first(), coverShape = CircleShape)
}

@Composable
fun FolderScreen(path: String) {
    val lib by DotApp.instance.library.data.collectAsStateWithLifecycle()
    val folder = lib.folders.firstOrNull { it.path == path } ?: return Missing()
    SongListScreen(folder.name, "${folder.songs.size} songs", folder.songs, cover = null)
}

@Composable
fun PlaylistScreen(id: String) {
    val dot = DotApp.instance
    val lib by dot.library.data.collectAsStateWithLifecycle()
    val playlists by dot.store.playlists.collectAsStateWithLifecycle()
    val favs by dot.store.favourites.collectAsStateWithLifecycle()
    val counts by dot.store.playCounts.collectAsStateWithLifecycle()
    val app = LocalAppState.current

    when (id) {
        SMART_FAVOURITES -> {
            val songs = remember(lib, favs) { lib.songs.filter { it.id in favs } }
            SongListScreen("Favourites", "${songs.size} songs", songs, cover = songs.firstOrNull())
        }
        SMART_RECENT -> {
            val songs = remember(lib) { lib.songs.sortedByDescending { it.dateAdded }.take(100) }
            SongListScreen("Recently added", "${songs.size} songs", songs, cover = songs.firstOrNull())
        }
        SMART_TOP -> {
            val songs = remember(lib, counts) {
                counts.entries.filter { it.value > 0 }.sortedByDescending { it.value }.mapNotNull { lib.byId[it.key] }.take(100)
            }
            SongListScreen("Most played", "${songs.size} songs", songs, cover = songs.firstOrNull())
        }
        else -> {
            val playlist = playlists.firstOrNull { it.id == id } ?: return Missing()
            val songs = remember(lib, playlist) { playlist.songIds.mapNotNull { lib.byId[it] } }
            var renaming by remember { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            SongListScreen(
                playlist.name, "${songs.size} songs", songs, cover = songs.firstOrNull(), playlistId = playlist.id,
                actions = {
                    Box {
                        IconBtn(Icons.Rounded.MoreVert, { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = true })
                            DropdownMenuItem({ Text("Delete", color = Dot.Red) }, { menu = false; dot.store.delete(playlist.id); app.pop() })
                        }
                    }
                },
            )
            if (renaming) NameDialog("Rename playlist", playlist.name, onDismiss = { renaming = false }) {
                dot.store.rename(playlist.id, it)
                renaming = false
            }
        }
    }
}

@Composable
private fun Missing() {
    val app = LocalAppState.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("", app::pop)
        EmptyState("Gone", "This item is no longer in your library.")
    }
}

@Composable
private fun SongListScreen(
    title: String,
    meta: String,
    songs: List<Song>,
    cover: Song?,
    coverShape: Shape = RoundedCornerShape(20.dp),
    trackNumbers: Boolean = false,
    playlistId: String? = null,
    actions: @Composable () -> Unit = {},
) {
    val dot = DotApp.instance
    val app = LocalAppState.current
    val state by dot.engine.state.collectAsStateWithLifecycle()
    val currentId = state.current?.id

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { TopBar("", app::pop, actions) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (cover != null) Art(cover, Modifier.size(220.dp), px = 660, shape = coverShape)
                else IconTile(null, text = title.take(1))
                Spacer(Modifier.height(18.dp))
                DotTitle(title, size = 30.sp, align = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Label(meta, align = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Play", { dot.engine.playSongs(songs, 0) }, accent = true, icon = Icons.Rounded.PlayArrow)
                    Pill("Shuffle", { dot.engine.playSongs(songs, shuffleAll = true) }, icon = Icons.Rounded.Shuffle)
                }
                Spacer(Modifier.height(14.dp))
                DottedDivider()
                Spacer(Modifier.height(6.dp))
            }
        }
        if (songs.isEmpty()) item { EmptyState("Empty", "Add songs from any list using the ⋮ menu.") }
        itemsIndexed(songs, key = { i, s -> "$i-${s.id}" }) { i, s ->
            SongRow(
                s, s.id == currentId,
                onClick = { dot.engine.playSongs(songs, i) },
                onMore = { app.menu = SongMenu(s, playlistId, i) },
                leading = if (trackNumbers) {
                    {
                        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                            Label(if (s.track > 0) "%02d".format(s.track) else "—", color = if (s.id == currentId) Dot.Red else Dot.Muted)
                        }
                    }
                } else null,
            )
        }
    }
}
