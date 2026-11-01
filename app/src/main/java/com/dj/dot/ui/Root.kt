package com.dj.dot.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.data.Song
import kotlinx.coroutines.delay

sealed interface Screen {
    val key: String
    data object Home : Screen { override val key = "home" }
    data object Search : Screen { override val key = "search" }
    data object Settings : Screen { override val key = "settings" }
    data object Equalizer : Screen { override val key = "eq" }
    data object Wrapped : Screen { override val key = "wrapped" }
    data class Album(val id: Long) : Screen { override val key = "album/$id" }
    data class Artist(val name: String) : Screen { override val key = "artist/$name" }
    data class Folder(val path: String) : Screen { override val key = "folder/$path" }
    data class Playlist(val id: String) : Screen { override val key = "playlist/$id" }
}

/** A song menu opened from a list; [playlistId]/[index] allow "remove from playlist". */
data class SongMenu(val song: Song, val playlistId: String? = null, val index: Int = -1)

class AppState {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var nowPlaying by mutableStateOf(false)
    var menu by mutableStateOf<SongMenu?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)

    fun push(screen: Screen) {
        nowPlaying = false
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalAppState = staticCompositionLocalOf<AppState> { error("AppState not provided") }

private fun audioPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudio(context: Context) =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

@Composable
fun DotRoot() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAudio(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasAudio(context)
    }
    LaunchedEffect(granted) { if (granted) DotApp.instance.library.start() }

    if (!granted) {
        PermissionScreen {
            val perms = buildList {
                add(audioPermission())
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(perms.toTypedArray())
        }
    } else {
        MainUi()
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Dot.Black).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            DotTitle("Dot", size = 88.sp)
            Box(Modifier.padding(start = 6.dp, bottom = 22.dp).size(16.dp).clip(CircleShape).background(Dot.Red))
        }
        Spacer(Modifier.height(12.dp))
        Label("Your music. Offline. Nothing else.", align = TextAlign.Center)
        Spacer(Modifier.height(48.dp))
        Pill("Allow music access", onAllow, accent = true)
    }
}

@Composable
private fun MainUi() {
    val app = remember { AppState() }
    val engine = DotApp.instance.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val holder = rememberSaveableStateHolder()

    CompositionLocalProvider(LocalAppState provides app) {
        Box(Modifier.fillMaxSize().background(Dot.Black)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = app.stack.last(),
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "screens",
                    ) { screen ->
                        holder.SaveableStateProvider(screen.key) { ScreenContent(screen) }
                    }
                }
                state.current?.let { song ->
                    MiniPlayer(song, state.playing, onOpen = { app.nowPlaying = true })
                }
                Spacer(Modifier.navigationBarsPadding())
            }

            AnimatedVisibility(
                visible = app.nowPlaying && state.current != null,
                enter = slideInVertically(tween(320)) { it },
                exit = slideOutVertically(tween(260)) { it },
            ) {
                NowPlayingScreen(onClose = { app.nowPlaying = false })
            }

            app.menu?.let { SongMenuSheet(it, onDismiss = { app.menu = null }) }
            app.addToPlaylist?.let { AddToPlaylistSheet(it, onDismiss = { app.addToPlaylist = null }) }
        }

        BackHandler(enabled = app.nowPlaying || app.stack.size > 1) {
            if (app.nowPlaying) app.nowPlaying = false else app.pop()
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen()
        Screen.Search -> SearchScreen()
        Screen.Settings -> SettingsScreen()
        Screen.Equalizer -> EqualizerScreen()
        Screen.Wrapped -> WrappedScreen()
        is Screen.Album -> AlbumScreen(screen.id)
        is Screen.Artist -> ArtistScreen(screen.name)
        is Screen.Folder -> FolderScreen(screen.path)
        is Screen.Playlist -> PlaylistScreen(screen.id)
    }
}

/** Polls the playhead while composed; cheap enough at a few Hz. */
@Composable
fun rememberProgress(intervalMs: Long = 250): Pair<Long, Long> {
    val engine = DotApp.instance.engine
    val value by produceState(engine.position() to engine.duration()) {
        while (true) {
            value = engine.position() to engine.duration()
            delay(intervalMs)
        }
    }
    return value
}

@Composable
private fun MiniPlayer(song: Song, playing: Boolean, onOpen: () -> Unit) {
    val engine = DotApp.instance.engine
    val (pos, dur) = rememberProgress(500)
    Column(
        Modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Dot.Surface2)
            .clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(song, Modifier.size(44.dp), px = 144, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = Dot.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = Dot.Muted, style = MonoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DotIconBtn(if (playing) DotIcons.Pause else DotIcons.Play, engine::toggle, size = 22.dp)
            DotIconBtn(DotIcons.Next, engine::next, size = 20.dp)
        }
        val fraction = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).height(2.dp).background(Dot.Faint)) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Dot.Red))
        }
    }
}
