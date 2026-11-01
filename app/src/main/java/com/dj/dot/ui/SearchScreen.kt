package com.dj.dot.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp

@Composable
fun SearchScreen() {
    val dot = DotApp.instance
    val app = LocalAppState.current
    val lib by dot.library.data.collectAsStateWithLifecycle()
    val state by dot.engine.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val q = query.trim()
    val songs = remember(q, lib) {
        if (q.isEmpty()) emptyList()
        else lib.songs.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
    }
    val albums = remember(q, lib) { if (q.isEmpty()) emptyList() else lib.albums.filter { it.title.contains(q, true) || it.artist.contains(q, true) } }
    val artists = remember(q, lib) { if (q.isEmpty()) emptyList() else lib.artists.filter { it.name.contains(q, true) } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            DotIconBtn(DotIcons.Back, app::pop, size = 16.dp)
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                if (query.isEmpty()) Text("SEARCH", color = Dot.Muted, fontFamily = MonoFamily, fontSize = 18.sp, letterSpacing = 1.5.sp)
                BasicTextField(
                    query, { query = it },
                    Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    textStyle = TextStyle(color = Dot.White, fontFamily = MonoFamily, fontSize = 18.sp),
                    cursorBrush = SolidColor(Dot.Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            if (query.isNotEmpty()) DotIconBtn(DotIcons.Close, { query = "" }, tint = Dot.Muted, size = 13.dp)
        }
        DottedDivider(Modifier.padding(horizontal = 20.dp))

        when {
            q.isEmpty() -> EmptyState("Find anything", "Songs, albums and artists")
            songs.isEmpty() && albums.isEmpty() && artists.isEmpty() -> EmptyState("Nothing", "No matches for \"$q\"")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (artists.isNotEmpty()) {
                    item { Label("Artists", Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp), color = Dot.Red) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(artists.take(20), key = { it.name }) { a ->
                                Column(
                                    Modifier.width(84.dp).clickable { app.push(Screen.Artist(a.name)) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Art(a.songs.first(), Modifier.size(76.dp), px = 228, shape = CircleShape)
                                    Spacer(Modifier.height(6.dp))
                                    Text(a.name, color = Dot.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item { Label("Albums", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp), color = Dot.Red) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(albums.take(20), key = { it.id }) { al ->
                                Column(Modifier.width(120.dp).clickable { app.push(Screen.Album(al.id)) }) {
                                    Art(al.songs.first(), Modifier.size(120.dp), px = 360, shape = RoundedCornerShape(12.dp))
                                    Spacer(Modifier.height(6.dp))
                                    Text(al.title, color = Dot.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Label(al.artist)
                                }
                            }
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item { Label("Songs · ${songs.size}", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp), color = Dot.Red) }
                    itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                        SongRow(s, s.id == state.current?.id, onClick = { dot.engine.playSongs(songs, i) }, onMore = { app.menu = SongMenu(s) })
                    }
                }
            }
        }
    }
}
