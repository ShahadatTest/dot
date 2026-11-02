package com.dj.dot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dj.dot.DotApp
import com.dj.dot.data.PlaylistStore
import com.dj.dot.playback.AudioFx
import com.dj.dot.playback.GlyphMode
import com.dj.dot.playback.Vibe
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val dot = DotApp.instance
    val s = dot.settings
    val app = LocalAppState.current
    val context = LocalContext.current

    val crossfade by s.crossfadeMs.flow.collectAsStateWithLifecycle()
    val skipFade by s.skipFadeMs.flow.collectAsStateWithLifecycle()
    val fadeSkip by s.fadeOnSkip.flow.collectAsStateWithLifecycle()
    val fadePause by s.fadeOnPause.flow.collectAsStateWithLifecycle()
    val beatFade by s.beatAlignedFade.flow.collectAsStateWithLifecycle()
    val speed by s.speed.flow.collectAsStateWithLifecycle()
    val minDur by s.minDurationSec.flow.collectAsStateWithLifecycle()
    val hideWhatsApp by s.hideWhatsApp.flow.collectAsStateWithLifecycle()
    val viz by s.visualizer.flow.collectAsStateWithLifecycle()
    val eqOn by s.eqEnabled.flow.collectAsStateWithLifecycle()
    val effect by s.soundEffect.flow.collectAsStateWithLifecycle()
    val artStyle by s.artStyle.flow.collectAsStateWithLifecycle()
    val stripStyle by s.stripStyle.flow.collectAsStateWithLifecycle()
    val glyphMode by s.glyphMode.flow.collectAsStateWithLifecycle()
    val shake by s.shakeForRandom.flow.collectAsStateWithLifecycle()
    val playOnConnect by s.playOnConnect.flow.collectAsStateWithLifecycle()
    val bedside by s.bedsideWhenCharging.flow.collectAsStateWithLifecycle()
    val scanning by dot.library.scanning.collectAsStateWithLifecycle()
    val lib by dot.library.data.collectAsStateWithLifecycle()

    val lyricsFolders by s.lyricsFolders.flow.collectAsStateWithLifecycle()
    val pickLyricsFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep read access across restarts; nothing outside this folder becomes readable.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val all = (s.lyricsFolderUris().map { it.toString() } + uri.toString()).distinct()
        s.lyricsFolders.set(all.joinToString("\n"))
        dot.engine.reloadLyrics()
    }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        unrestricted = isUnrestricted(context)
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopBar("Settings", app::pop) }
        item { DotTitle("Settings", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), size = 34.sp) }

        item { Section("Transitions") }
        item {
            SliderRow(
                "Crossfade", if (crossfade == 0) "OFF" else "%.1f S".format(crossfade / 1000f),
                "Blend the end of one song into the next",
                crossfade / 12000f,
                steps = 24,
            ) { s.crossfadeMs.set(((it * 24).roundToInt() * 500)) }
        }
        if (crossfade > 0) item { SwitchRow("Start blends on the beat", "Waits for the next beat before crossfading", beatFade) { s.beatAlignedFade.set(it) } }
        item { SwitchRow("Fade when skipping", "Quick blend when you tap next, previous or a song", fadeSkip) { s.fadeOnSkip.set(it) } }
        if (fadeSkip) item {
            SliderRow("Skip fade", "%.1f S".format(skipFade / 1000f), null, skipFade / 4000f, steps = 16) {
                s.skipFadeMs.set(((it * 16).roundToInt() * 250).coerceAtLeast(250))
            }
        }
        item { SwitchRow("Play when headphones connect", "Bluetooth, wired or USB; resumes your queue", playOnConnect) { s.playOnConnect.set(it) } }
        item { SwitchRow("Fade on play / pause", "Soft fade in and out instead of a hard cut", fadePause) { s.fadeOnPause.set(it) } }

        item { Section("Sound") }
        item { NavRow("Sound effects & equalizer", if (eqOn) effect else "Hi-Fi") { app.push(Screen.Equalizer) } }
        item {
            SliderRow("Playback speed", "%.2fX".format(speed), "Tap the value to reset", (speed - 0.5f) / 1.5f, steps = 30, onLabelClick = { dot.engine.setSpeed(1f) }) {
                dot.engine.setSpeed(((0.5f + it * 1.5f) * 20).roundToInt() / 20f)
            }
        }

        item { Section("Look") }
        item { SwitchRow("Glyph visualizer", "Dot-matrix spectrum and beat pulse on Now Playing", viz) { s.visualizer.set(it) } }
        if (viz) item { PickerRow("Visualizer style", "Or tap the visualizer to switch", StripStyle.entries.map { it.label }, stripStyle) { s.stripStyle.set(it) } }
        item { PickerRow("Cover style", "Or tap the album art to switch", ArtStyle.entries.map { it.label }, artStyle) { s.artStyle.set(it) } }
        item { SwitchRow("Bedside clock when charging", "After a minute untouched on the charger", bedside) { s.bedsideWhenCharging.set(it) } }
        item { NavRow("Open bedside clock", "Big dot clock with what's playing") { app.bedside = true } }
        if (dot.glyph.supported) item {
            val unavailable by dot.glyph.unavailable.collectAsStateWithLifecycle()
            PickerRow(
                "Glyph lights",
                if (unavailable) GLYPH_UNAVAILABLE else "The light strip on the back reacts to your music",
                GlyphMode.entries.map { it.label }, glyphMode,
            ) { s.glyphMode.set(it) }
        }

        item { Section("Gestures") }
        item { SwitchRow("Shake for a random song", "Shake the phone back and forth a few times", shake) { s.shakeForRandom.set(it) } }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                listOf(
                    "Double-tap the art" to "Like the song",
                    "Swipe the art sideways" to "Next or previous",
                    "Swipe up on the player" to "Open the queue",
                    "Swipe down on the player" to "Close it",
                    "Tap the art or visualizer" to "Switch styles",
                ).forEach { (gesture, action) ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(gesture, color = Dot.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(action, color = Dot.Muted, style = MonoSmall)
                    }
                }
            }
        }

        item { Section("Library") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Skip short audio", color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Hides voice notes and ringtones shorter than this", color = Dot.Muted, style = MonoSmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 15, 30, 60).forEach { sec ->
                        Pill(if (sec == 0) "All" else "${sec}s", { s.minDurationSec.set(sec); dot.library.rescan() }, selected = minDur == sec)
                    }
                }
            }
        }
        item {
            SwitchRow("Hide WhatsApp audio", "Voice notes and audio received on WhatsApp", hideWhatsApp) {
                s.hideWhatsApp.set(it)
                dot.library.rescan()
            }
        }
        item { BackupRows() }
        item { NavRow("Rescan library", if (scanning) "Scanning…" else "${lib.songs.size} songs") { dot.library.rescan() } }
        item {
            val count = lyricsFolders.lines().count { it.isNotBlank() }
            NavRow("Lyrics folders", if (count == 0) "Pick the folders with your .lrc files" else "$count folder${if (count == 1) "" else "s"} · tap to add another") {
                runCatching { pickLyricsFolder.launch(null) }
            }
        }
        if (lyricsFolders.isNotBlank()) item {
            NavRow("Forget lyrics folders", "Dot stops reading .lrc files from them") {
                s.lyricsFolderUris().forEach { uri ->
                    runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                s.lyricsFolders.set("")
                dot.engine.reloadLyrics()
            }
        }

        item { Section("Background") }
        item {
            NavRow("Keep playing in background", if (unrestricted) "Unrestricted" else "Tap so Nothing OS never stops playback") {
                if (!unrestricted) requestUnrestricted(context)
            }
        }

        item { Section("About") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                DotTitle("Dot", size = 28.sp)
                Label("Version ${com.dj.dot.BuildConfig.VERSION_NAME} · offline music player")
            }
        }
    }
}

private fun isUnrestricted(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun requestUnrestricted(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }
}

@Composable
fun EqualizerScreen() {
    val dot = DotApp.instance
    val s = dot.settings
    val fx = dot.engine.fx
    val app = LocalAppState.current
    val on by s.eqEnabled.flow.collectAsStateWithLifecycle()
    val raw by s.eqLevels.flow.collectAsStateWithLifecycle()
    val preset by s.eqPreset.flow.collectAsStateWithLifecycle()
    val effect by s.soundEffect.flow.collectAsStateWithLifecycle()
    val bass by s.bassBoost.flow.collectAsStateWithLifecycle()
    val loud by s.loudness.flow.collectAsStateWithLifecycle()
    val surround by s.virtualizer.flow.collectAsStateWithLifecycle()
    val reverb by s.reverb.flow.collectAsStateWithLifecycle()
    val levelling by s.levelling.flow.collectAsStateWithLifecycle()
    val balance by s.balance.flow.collectAsStateWithLifecycle()
    val mono by s.mono.flow.collectAsStateWithLifecycle()
    val vibe by s.vibe.flow.collectAsStateWithLifecycle()
    val levels = remember(raw) { dot.engine.parseLevels(raw) }
    val span = maxOf(-fx.range.first, fx.range.last).toFloat()

    /** Any manual tweak turns sound on and marks the mode as custom. */
    fun tweak(block: () -> Unit) {
        if (!on) s.eqEnabled.set(true)
        block()
        s.soundEffect.set("Custom")
    }

    fun applyEffect(e: AudioFx.SoundEffect) {
        val curve = AudioFx.presets.first { it.first == e.eq }.second
        s.eqEnabled.set(e.name != "Hi-Fi")
        s.eqLevels.set(AudioFx.presetLevels(curve, fx.bandCount, fx.range).joinToString(","))
        s.eqPreset.set(e.eq)
        s.bassBoost.set(e.bass)
        s.virtualizer.set(e.surround)
        s.reverb.set(e.reverb)
        s.loudness.set(e.loudness)
        s.soundEffect.set(e.name)
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            TopBar("Sound", app::pop) {
                Switch(on, { s.eqEnabled.set(it) }, colors = dotSwitchColors(), modifier = Modifier.padding(end = 12.dp))
            }
        }
        item { DotTitle("Sound", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), size = 34.sp) }

        item { Section("Effects") }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AudioFx.effects.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { e ->
                            val selected = effect == e.name && (on || e.name == "Hi-Fi")
                            EffectCard(e.name, e.note, selected, Modifier.weight(1f)) { applyEffect(e) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item { HeadphonesCard() }

        item { Section("Balance") }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Left / right", color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Label(balanceLabel(balance), color = Dot.White, modifier = Modifier.clickable { s.balance.set(0) })
                }
                Text("Tap the value to centre", color = Dot.Muted, style = MonoSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("L", color = Dot.White)
                    DotSlider(
                        (balance + 100) / 200f, { s.balance.set(((it * 40).roundToInt() * 5) - 100) },
                        Modifier.weight(1f).padding(horizontal = 8.dp), live = true, steps = 40,
                        bubble = { balanceLabel(((it * 40).roundToInt() * 5) - 100) },
                    )
                    Label("R", color = Dot.White)
                }
            }
        }
        item { SwitchRow("Mono", "Both ears get the full song; good with one earbud", mono) { s.mono.set(it) } }

        item { Section("Vibes") }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Change the whole feel of a track. Works with any sound mode.", color = Dot.Muted, style = MonoSmall)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Vibe.entries.size) { i ->
                        Pill(Vibe.entries[i].label, { s.vibe.set(i) }, selected = vibe == i, accent = vibe == i && i != 0)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (Vibe.entries.getOrElse(vibe) { Vibe.Off }) {
                        Vibe.Off -> " "
                        Vibe.Slowed -> "Slower, deeper, in a big room"
                        Vibe.Nightcore -> "Faster and higher, full energy"
                        Vibe.EightD -> "The song circles your head. Use earphones"
                        Vibe.Karaoke -> "Removes most centred vocals. Sing along"
                    },
                    color = Dot.White, style = MonoSmall,
                )
            }
        }

        item { Section("Equalizer") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AudioFx.presets) { (name, curve) ->
                    Pill(name, {
                        tweak {
                            s.eqLevels.set(AudioFx.presetLevels(curve, fx.bandCount, fx.range).joinToString(","))
                            s.eqPreset.set(name)
                        }
                    }, selected = on && preset == name)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                levels.forEachIndexed { band, level ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Label("%+.0f".format(level / 100f), color = if (on) Dot.White else Dot.Muted)
                        Spacer(Modifier.height(8.dp))
                        VerticalDotSlider(
                            level / span,
                            { v ->
                                val mb = (((v * span) / 50).roundToInt() * 50).coerceIn(fx.range)
                                tweak {
                                    s.eqLevels.set(levels.toMutableList().also { it[band] = mb }.joinToString(","))
                                    s.eqPreset.set("Custom")
                                }
                            },
                            Modifier.height(200.dp),
                            enabled = on,
                        )
                        Spacer(Modifier.height(8.dp))
                        Label(formatHz(fx.frequencies[band]))
                    }
                }
            }
        }

        item { Section("Enhancements") }
        item {
            SliderRow("Bass boost", "${bass / 10}%", "Extra low end on top of the EQ", bass / 1000f) {
                tweak { s.bassBoost.set((it * 1000).roundToInt()) }
            }
        }
        item {
            SliderRow(
                "Panoramic", "${surround / 10}%",
                "3D surround, best on earphones",
                surround / 1000f,
            ) { tweak { s.virtualizer.set((it * 1000).roundToInt()) } }
        }
        item {
            SwitchRow("Volume levelling", "Quiet and loud tracks play at a similar level", levelling) { s.levelling.set(it) }
        }
        item {
            SliderRow("Loudness", "+%.1f DB".format(loud / 100f), "Lifts quiet tracks; the limiter stops clipping", loud / 1000f) {
                tweak { s.loudness.set((it * 1000).roundToInt()) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Reverb", color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Room ambience around the music", color = Dot.Muted, style = MonoSmall)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AudioFx.reverbs.size) { i ->
                        Pill(AudioFx.reverbs[i].first, { tweak { s.reverb.set(i) } }, selected = on && reverb == i)
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(20.dp)) {
                Pill("Reset", { applyEffect(AudioFx.effects.first()) })
            }
        }
    }
}

@Composable
private fun EffectCard(name: String, note: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Dot.White else Dot.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotTitle(name, Modifier.weight(1f), size = 18.sp, color = if (selected) Dot.Black else Dot.White)
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) Dot.Red else Dot.Faint))
        }
        Spacer(Modifier.height(4.dp))
        Text(note, color = if (selected) Dot.Black.copy(alpha = 0.6f) else Dot.Muted, style = MonoSmall, maxLines = 1)
    }
}

/** Back up to, and restore from, a file the user picks. */
@Composable
private fun BackupRows() {
    val dot = DotApp.instance
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = dot.store.exportJson(dot.library.data.value)
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }.isSuccess
            }
            toast(if (ok) "Backed up" else "Couldn't save the backup")
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingRestore = uri }

    NavRow("Back up", "Playlists, favourites and stats to a file") {
        save.launch("dot-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.json")
    }
    NavRow("Restore", "Replace them with a backup") { open.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }

    pendingRestore?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            containerColor = Dot.Surface2,
            title = { DotTitle("Restore?", size = 22.sp) },
            text = { Text("Your current playlists, favourites and stats will be replaced by the backup.", color = Dot.Muted, style = MonoSmall) },
            confirmButton = {
                Pill("Restore", {
                    pendingRestore = null
                    scope.launch {
                        val lib = dot.library.data.value
                        // Read with a cap and parse off the main thread; nothing changes unless the whole file checks out.
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching {
                                val bytes = context.contentResolver.openInputStream(uri)!!.use { readCapped(it, PlaylistStore.MAX_BACKUP_BYTES + 1) }
                                if (bytes.size > PlaylistStore.MAX_BACKUP_BYTES) return@runCatching null
                                PlaylistStore.parseBackup(String(bytes), lib)
                            }
                        }
                        result
                            .onSuccess { backup ->
                                if (backup == null) toast("That file is too big to be a Dot backup")
                                else { dot.store.restore(backup); toast("Restored · ${backup.matched} songs matched") }
                            }
                            .onFailure { toast("That file isn't a Dot backup") }
                    }
                }, accent = true)
            },
            dismissButton = { Pill("Cancel", { pendingRestore = null }) },
        )
    }
}

/** Connected earphones with their reported battery, or a button to allow reading it. */
@Composable
private fun HeadphonesCard() {
    val dot = DotApp.instance
    val hp = dot.headphones
    val info by hp.connected.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(hp.hasPermission()) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        allowed = granted
        if (granted) hp.start()
    }
    Section("Earphones")
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Dot.Surface2).padding(16.dp),
    ) {
        val current = info
        when {
            !allowed -> {
                Text("See your earphones' battery", color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Needs the Nearby devices permission", color = Dot.Muted, style = MonoSmall)
                Spacer(Modifier.height(10.dp))
                Pill("Allow", { if (android.os.Build.VERSION.SDK_INT >= 31) ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }, accent = true)
            }
            current == null -> {
                Text("No Bluetooth earphones connected", color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Connect them and their battery shows here", color = Dot.Muted, style = MonoSmall)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        DotTitle(current.name, size = 20.sp)
                        Text(
                            if (current.battery != null) "Battery reported by the earphones" else "These earphones don't report battery to Android",
                            color = Dot.Muted, style = MonoSmall,
                        )
                    }
                    current.battery?.let { DotTitle("$it%", size = 26.sp, color = if (it <= 20) Dot.Red else Dot.White) }
                }
                current.battery?.let { level ->
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                        val n = 20
                        val step = size.width / n
                        for (i in 0 until n) {
                            val lit = i < (level * n + 50) / 100
                            drawCircle(
                                if (!lit) Dot.Faint else if (level <= 20) Dot.Red else Dot.White,
                                3.dp.toPx(), androidx.compose.ui.geometry.Offset(step * i + step / 2, size.height / 2),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Separate left, right and case levels are only available to system apps.", color = Dot.Muted, style = MonoSmall)
            }
        }
    }
}

private fun balanceLabel(b: Int) = when {
    b == 0 -> "Centre"
    b < 0 -> "L ${-b}%"
    else -> "R $b%"
}

private fun formatHz(hz: Int) = if (hz >= 1000) "%.1fK".format(hz / 1000f).replace(".0K", "K") else "$hz"

@Composable
private fun dotSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Dot.White,
    checkedTrackColor = Dot.Red,
    checkedBorderColor = Dot.Red,
    uncheckedThumbColor = Dot.Muted,
    uncheckedTrackColor = Dot.Surface2,
    uncheckedBorderColor = Dot.Line,
)

@Composable
private fun PickerRow(title: String, subtitle: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Dot.Muted, style = MonoSmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, label -> Pill(label, { onSelect(i) }, selected = selected == i) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Label(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp), color = Dot.Red)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Dot.Muted, style = MonoSmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = dotSwitchColors())
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(value, color = Dot.Muted, style = MonoSmall)
        }
        Label("→", color = Dot.Muted)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    subtitle: String?,
    fraction: Float,
    live: Boolean = true,
    steps: Int = 20,
    onLabelClick: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Dot.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null) Text(subtitle, color = Dot.Muted, style = MonoSmall)
            }
            Label(value, color = Dot.White, modifier = if (onLabelClick != null) Modifier.clickable(onClick = onLabelClick) else Modifier)
        }
        Spacer(Modifier.height(4.dp))
        DotSlider(fraction, onChange, live = live, steps = steps, bubble = { value })
    }
}

/** Reads at most [limit] bytes, so a huge file can't be pulled into memory. */
private fun readCapped(input: java.io.InputStream, limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Shown when the phone won't open a Glyph session for Dot. */
const val GLYPH_UNAVAILABLE = "The light strip didn't respond. On a test build, turn on Glyph debug mode in developer options"
