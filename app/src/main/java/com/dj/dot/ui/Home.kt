package com.dj.dot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.data.Song
import kotlinx.coroutines.launch

private val tabs = listOf("Songs", "Albums", "Artists", "Folders", "Playlists")
private val sortNames = listOf("Title", "Artist", "Recent")

@Composable
fun HomeScreen() {
    val app = LocalAppState.current
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                DotTitle("Dot", size = 40.sp)
                Box(Modifier.padding(start = 4.dp, bottom = 11.dp).size(8.dp).clip(CircleShape).background(Dot.Red))
            }
            IconBtn(Icons.Rounded.Search, { app.push(Screen.Search) })
            IconBtn(Icons.Rounded.Settings, { app.push(Screen.Settings) })
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(tabs.size) { i ->
                Pill(tabs[i], { scope.launch { pager.animateScrollToPage(i) } }, selected = pager.currentPage == i)
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), key = { it }) { page ->
            when (page) {
                0 -> SongsTab()
                1 -> AlbumsTab()
                2 -> ArtistsTab()
                3 -> FoldersTab()
                else -> PlaylistsTab()
            }
        }
    }
}

@Composable
private fun SongsTab() {
    val dot = DotApp.instance
    val app = LocalAppState.current
    val lib by dot.library.data.collectAsStateWithLifecycle()
    val sort by dot.settings.songSort.flow.collectAsStateWithLifecycle()
    val state by dot.engine.state.collectAsStateWithLifecycle()
    val songs = remember(lib, sort) {
        when (sort) {
            1 -> lib.songs.sortedWith(compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }.thenBy { it.track })
            2 -> lib.songs.sortedByDescending { it.dateAdded }
            else -> lib.songs
        }
    }
    val currentId = state.current?.id

    when {
        !lib.ready -> EmptyState("Scanning", "Looking for music on your phone")
        songs.isEmpty() -> EmptyState("No music", "Copy songs into the Music folder and they'll show up here.")
        else -> Box(Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("${songs.size} songs", Modifier.weight(1f))
                        Pill(sortNames[sort], { dot.settings.songSort.set((sort + 1) % sortNames.size) })
                        Spacer(Modifier.width(8.dp))
                        Pill("Shuffle", { dot.engine.playSongs(songs, shuffleAll = true) }, accent = true, icon = Icons.Rounded.Shuffle)
                    }
                }
                itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                    SongRow(s, s.id == currentId, onClick = { dot.engine.playSongs(songs, i) }, onMore = { app.menu = SongMenu(s) })
                }
            }
            FastScroller(
                listState, songs.size, itemOffset = 1,
                labelFor = { i ->
                    val song = songs[i]
                    when (sort) {
                        1 -> initial(song.artist)
                        2 -> monthLabel.format(java.util.Date(song.dateAdded * 1000))
                        else -> initial(song.title)
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private val monthLabel = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault())

private fun initial(text: String): String {
    val c = text.trimStart().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (c.isLetter()) c.toString() else "#"
}

@Composable
private fun AlbumsTab() {
    val app = LocalAppState.current
    val lib by DotApp.instance.library.data.collectAsStateWithLifecycle()
    if (lib.ready && lib.albums.isEmpty()) return EmptyState("No albums", "Albums appear once music is found.")
    LazyVerticalGrid(
        GridCells.Adaptive(156.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(lib.albums, key = { it.id }) { album ->
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { app.push(Screen.Album(album.id)) }) {
                Art(album.songs.first(), Modifier.fillMaxWidth().aspectRatio(1f), px = 420, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                Text(album.title, color = Dot.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Label(album.artist)
            }
        }
    }
}

@Composable
private fun ArtistsTab() {
    val app = LocalAppState.current
    val lib by DotApp.instance.library.data.collectAsStateWithLifecycle()
    if (lib.ready && lib.artists.isEmpty()) return EmptyState("No artists", "Artists appear once music is found.")
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(lib.artists, key = { it.name }) { artist ->
                ListRow(
                    title = artist.name,
                    subtitle = "${artist.songs.size} songs · ${artist.albumCount} albums",
                    onClick = { app.push(Screen.Artist(artist.name)) },
                ) { Art(artist.songs.first(), Modifier.size(52.dp), px = 156, shape = CircleShape) }
            }
        }
        FastScroller(listState, lib.artists.size, itemOffset = 0, labelFor = { initial(lib.artists[it].name) }, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun FoldersTab() {
    val app = LocalAppState.current
    val lib by DotApp.instance.library.data.collectAsStateWithLifecycle()
    if (lib.ready && lib.folders.isEmpty()) return EmptyState("No folders", "Folders appear once music is found.")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(lib.folders, key = { it.path }) { folder ->
            ListRow(
                title = folder.name,
                subtitle = "${folder.songs.size} songs · ${folder.path.removePrefix("/storage/emulated/0")}",
                onClick = { app.push(Screen.Folder(folder.path)) },
            ) { IconTile(Icons.Rounded.Folder) }
        }
    }
}

@Composable
private fun PlaylistsTab() {
    val dot = DotApp.instance
    val app = LocalAppState.current
    val playlists by dot.store.playlists.collectAsStateWithLifecycle()
    val favs by dot.store.favourites.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Pill("New playlist", { creating = true }, accent = true, icon = Icons.Rounded.Add)
            }
        }
        item { ListRow("Favourites", "${favs.size} songs", { app.push(Screen.Playlist(SMART_FAVOURITES)) }) { IconTile(Icons.Rounded.Favorite, red = true) } }
        item { ListRow("Recently added", "Latest 100", { app.push(Screen.Playlist(SMART_RECENT)) }) { IconTile(Icons.Rounded.History) } }
        item { ListRow("Most played", "Your top 100", { app.push(Screen.Playlist(SMART_TOP)) }) { IconTile(Icons.AutoMirrored.Rounded.TrendingUp) } }
        if (playlists.isNotEmpty()) item { Label("Your playlists", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)) }
        items(playlists, key = { it.id }) { p ->
            ListRow(p.name, "${p.songIds.size} songs", { app.push(Screen.Playlist(p.id)) }) { IconTile(null, text = p.name.take(1)) }
        }
    }
    if (creating) NameDialog("New playlist", "", onDismiss = { creating = false }) { dot.store.create(it); creating = false }
}

const val SMART_FAVOURITES = "smart:favourites"
const val SMART_RECENT = "smart:recent"
const val SMART_TOP = "smart:top"

@Composable
fun ListRow(title: String, subtitle: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Dot.Muted, style = MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun IconTile(icon: ImageVector?, red: Boolean = false, text: String = "") {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(if (red) Dot.Red else Dot.Surface2), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, tint = Dot.White, modifier = Modifier.size(24.dp))
        else DotTitle(text, size = 22.sp)
    }
}
