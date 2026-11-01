package com.dj.dot.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
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
import com.dj.dot.playback.AudioFx
import kotlin.math.roundToInt

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
    val speed by s.speed.flow.collectAsStateWithLifecycle()
    val minDur by s.minDurationSec.flow.collectAsStateWithLifecycle()
    val viz by s.visualizer.flow.collectAsStateWithLifecycle()
    val eqOn by s.eqEnabled.flow.collectAsStateWithLifecycle()
    val preset by s.eqPreset.flow.collectAsStateWithLifecycle()
    val scanning by dot.library.scanning.collectAsStateWithLifecycle()
    val lib by dot.library.data.collectAsStateWithLifecycle()

    var fileAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        fileAccess = Environment.isExternalStorageManager()
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
            ) { s.crossfadeMs.set(((it * 24).roundToInt() * 500)) }
        }
        item { SwitchRow("Fade when skipping", "Quick blend when you tap next, previous or a song", fadeSkip) { s.fadeOnSkip.set(it) } }
        if (fadeSkip) item {
            SliderRow("Skip fade", "%.1f S".format(skipFade / 1000f), null, skipFade / 4000f) {
                s.skipFadeMs.set(((it * 16).roundToInt() * 250).coerceAtLeast(250))
            }
        }
        item { SwitchRow("Fade on play / pause", "Soft fade in and out instead of a hard cut", fadePause) { s.fadeOnPause.set(it) } }

        item { Section("Sound") }
        item { NavRow("Equalizer", if (eqOn) preset else "Off") { app.push(Screen.Equalizer) } }
        item {
            SliderRow("Playback speed", "%.2fX".format(speed), "Tap the value to reset", (speed - 0.5f) / 1.5f, onLabelClick = { dot.engine.setSpeed(1f) }) {
                dot.engine.setSpeed(((0.5f + it * 1.5f) * 20).roundToInt() / 20f)
            }
        }

        item { Section("Look") }
        item { SwitchRow("Glyph visualizer", "Dot-matrix spectrum and beat pulse on Now Playing", viz) { s.visualizer.set(it) } }

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
        item { NavRow("Rescan library", if (scanning) "Scanning…" else "${lib.songs.size} songs") { dot.library.rescan() } }
        item {
            NavRow("Lyrics file access", if (fileAccess) "Allowed" else "Needed for .lrc files") {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
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
                Label("Version 1.0 · offline music player")
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
    val bass by s.bassBoost.flow.collectAsStateWithLifecycle()
    val loud by s.loudness.flow.collectAsStateWithLifecycle()
    val levels = remember(raw) { dot.engine.parseLevels(raw) }
    val span = maxOf(-fx.range.first, fx.range.last).toFloat()

    fun setLevels(l: List<Int>, name: String) {
        s.eqLevels.set(l.joinToString(","))
        s.eqPreset.set(name)
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            TopBar("Sound", app::pop) {
                Switch(on, { s.eqEnabled.set(it) }, colors = dotSwitchColors(), modifier = Modifier.padding(end = 12.dp))
            }
        }
        item { DotTitle("Equalizer", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), size = 34.sp) }
        if (!fx.available) item { Label("Equalizer isn't available on this device", Modifier.padding(20.dp), color = Dot.Red) }

        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AudioFx.presets) { (name, curve) ->
                    Pill(name, {
                        if (!on) s.eqEnabled.set(true)
                        setLevels(AudioFx.presetLevels(curve, fx.bandCount, fx.range), name)
                    }, selected = on && preset == name)
                }
                item { Pill("Custom", { if (!on) s.eqEnabled.set(true); s.eqPreset.set("Custom") }, selected = on && preset == "Custom") }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                levels.forEachIndexed { band, level ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Label("%+.0f".format(level / 100f), color = if (on) Dot.White else Dot.Muted)
                        Spacer(Modifier.height(8.dp))
                        VerticalDotSlider(
                            level / span,
                            { v ->
                                val mb = ((v * span) / 50).roundToInt() * 50
                                val next = levels.toMutableList().also { it[band] = mb.coerceIn(fx.range) }
                                if (!on) s.eqEnabled.set(true)
                                setLevels(next, "Custom")
                            },
                            Modifier.height(220.dp),
                            enabled = on,
                        )
                        Spacer(Modifier.height(8.dp))
                        Label(formatHz(fx.frequencies[band]))
                    }
                }
            }
        }

        item { DottedDivider(Modifier.padding(horizontal = 20.dp)) }
        item {
            SliderRow("Bass boost", "${bass / 10}%", "Extra low end on top of the EQ", bass / 1000f, live = true) {
                if (!on) s.eqEnabled.set(true)
                s.bassBoost.set((it * 1000).roundToInt())
            }
        }
        item {
            SliderRow("Loudness", "+%.1f DB".format(loud / 100f), "Lifts quiet tracks", loud / 1000f, live = true) {
                if (!on) s.eqEnabled.set(true)
                s.loudness.set((it * 1000).roundToInt())
            }
        }
        item {
            Row(Modifier.padding(20.dp)) {
                Pill("Reset", {
                    setLevels(List(fx.bandCount) { 0 }, "Flat")
                    s.bassBoost.set(0)
                    s.loudness.set(0)
                })
            }
        }
    }
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
        DotSlider(fraction, onChange, live = live)
    }
}
