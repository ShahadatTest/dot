@file:OptIn(ExperimentalMaterial3Api::class)

package com.dj.dot.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.data.Song

@Composable
fun SongMenuSheet(menu: SongMenu, onDismiss: () -> Unit) {
    val dot = DotApp.instance
    val app = LocalAppState.current
    val context = LocalContext.current
    val favs by dot.store.favourites.collectAsStateWithLifecycle()
    val lib by dot.library.data.collectAsStateWithLifecycle()
    val song = menu.song
    val fav = song.id in favs

    fun done(action: () -> Unit) { action(); onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dot.Surface2,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(song, Modifier.size(56.dp), px = 168, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Dot.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Label(song.artist)
                }
            }
            Spacer(Modifier.height(12.dp))
            DottedDivider()
            Spacer(Modifier.height(4.dp))
            SheetItem("Play next", Icons.Rounded.PlaylistPlay) { done { dot.engine.playNext(listOf(song)) } }
            SheetItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { done { dot.engine.addToQueue(listOf(song)) } }
            SheetItem("Add to playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { done { app.addToPlaylist = listOf(song) } }
            SheetItem(if (fav) "Remove from favourites" else "Add to favourites", if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) {
                done { dot.store.toggleFavourite(song.id) }
            }
            SheetItem("Go to album", Icons.Rounded.Album) { done { app.push(Screen.Album(song.albumId)) } }
            if (lib.artists.any { it.name == song.artist }) {
                SheetItem("Go to artist", Icons.Rounded.Person) { done { app.push(Screen.Artist(song.artist)) } }
            }
            SheetItem("Share", Icons.Rounded.Share) {
                done {
                    val send = Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM, song.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, song.title))
                }
            }
            if (menu.playlistId != null && !menu.playlistId.startsWith("smart:") && menu.index >= 0) {
                SheetItem("Remove from playlist", Icons.Rounded.Delete, red = true) {
                    done { dot.store.removeAt(menu.playlistId, menu.index) }
                }
            }
        }
    }
}

@Composable
fun AddToPlaylistSheet(songs: List<Song>, onDismiss: () -> Unit) {
    val dot = DotApp.instance
    val context = LocalContext.current
    val playlists by dot.store.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Dot.Surface2,
    ) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            DotTitle("Add to playlist", size = 24.sp)
            Spacer(Modifier.height(12.dp))
            SheetItem("New playlist", Icons.Rounded.Add) { creating = true }
            playlists.forEach { p ->
                SheetItem(p.name, Icons.AutoMirrored.Rounded.QueueMusic) {
                    dot.store.add(p.id, songs.map { it.id })
                    Toast.makeText(context, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        }
    }
    if (creating) NameDialog("New playlist", "", onDismiss = { creating = false }) { name ->
        dot.store.create(name, songs.map { it.id })
        Toast.makeText(context, "Created $name", Toast.LENGTH_SHORT).show()
        creating = false
        onDismiss()
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Dot.Surface2,
        title = { DotTitle(title, size = 22.sp) },
        text = {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dot.Black).padding(14.dp)) {
                if (text.isEmpty()) Text("NAME", color = Dot.Muted, fontFamily = MonoFamily, fontSize = 16.sp)
                BasicTextField(
                    text, { text = it },
                    Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    textStyle = TextStyle(color = Dot.White, fontFamily = MonoFamily, fontSize = 16.sp),
                    cursorBrush = SolidColor(Dot.Red),
                )
            }
        },
        confirmButton = { Pill("Save", { if (text.isNotBlank()) onConfirm(text) }, accent = true) },
        dismissButton = { Pill("Cancel", onDismiss) },
    )
}
