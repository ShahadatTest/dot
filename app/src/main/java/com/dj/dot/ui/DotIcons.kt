package com.dj.dot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A dot-matrix icon: each 'X' in [rows] is a lit dot. */
@Immutable
class DotGlyph(vararg rows: String) {
    val rows: List<String> = rows.toList()
    val cols: Int = rows.maxOf { it.length }
    val points: List<Pair<Int, Int>> = rows.flatMapIndexed { y, row -> row.mapIndexedNotNull { x, c -> if (c == 'X') x to y else null } }
}

object DotIcons {
    val Play = DotGlyph(
        "...........",
        "...X.......",
        "...XX......",
        "...XXX.....",
        "...XXXX....",
        "...XXXXX...",
        "...XXXX....",
        "...XXX.....",
        "...XX......",
        "...X.......",
        "...........",
    )
    val Pause = DotGlyph(
        "...........",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "..XX...XX..",
        "...........",
    )
    val Next = DotGlyph(
        "...........",
        "..X.....X..",
        "..XX....X..",
        "..XXX...X..",
        "..XXXX..X..",
        "..XXXXX.X..",
        "..XXXX..X..",
        "..XXX...X..",
        "..XX....X..",
        "..X.....X..",
        "...........",
    )
    val Previous = DotGlyph(*Next.rows.map { it.reversed() }.toTypedArray())
    val Shuffle = DotGlyph(
        "X.....XXX",
        ".X.....XX",
        "..X...X.X",
        "...X.X...",
        "....X....",
        "...X.X...",
        "..X...X.X",
        ".X.....XX",
        "X.....XXX",
    )
    val Repeat = DotGlyph(
        "......X..",
        "XXXXXXXX.",
        "X.....X..",
        "X........",
        "X.......X",
        "........X",
        "..X.....X",
        ".XXXXXXXX",
        "..X......",
    )
    val RepeatOne = DotGlyph(
        "......X..",
        "XXXXXXXX.",
        "X.....X..",
        "X...X....",
        "X...X...X",
        "....X...X",
        "..X.....X",
        ".XXXXXXXX",
        "..X......",
    )
    val Heart = DotGlyph(
        ".XX...XX.",
        "XXXX.XXXX",
        "XXXXXXXXX",
        "XXXXXXXXX",
        ".XXXXXXX.",
        "..XXXXX..",
        "...XXX...",
        "....X....",
    )
    val HeartOutline = DotGlyph(
        ".XX...XX.",
        "X..X.X..X",
        "X...X...X",
        "X.......X",
        ".X.....X.",
        "..X...X..",
        "...X.X...",
        "....X....",
    )
    val Lyrics = DotGlyph(
        "XXXXXXXXX",
        ".........",
        "XXXXXX...",
        ".........",
        "XXXXXXXXX",
        ".........",
        "XXXX.....",
    )
    val Queue = DotGlyph(
        "XXXXXXXXX",
        ".........",
        "XXXXXXXXX",
        ".........",
        "XXXXX.X..",
        "......XX.",
        "XXXXX.XXX",
        "......XX.",
        "......X..",
    )
    val Moon = DotGlyph(
        "...XXX...",
        ".XXX.....",
        ".XX......",
        "XX.......",
        "XX.......",
        "XX.....X.",
        ".XX...XX.",
        ".XXXXXX..",
        "...XXX...",
    )
    val ChevronDown = DotGlyph(
        "X.......X",
        ".X.....X.",
        "..X...X..",
        "...X.X...",
        "....X....",
    )
    val Back = DotGlyph(
        "...X",
        "..X.",
        ".X..",
        "X...",
        ".X..",
        "..X.",
        "...X",
    )
    val Equalizer = DotGlyph(
        ".X..X..X.",
        ".X..X..X.",
        "XXX.X..X.",
        ".X..X..X.",
        ".X.XXX.X.",
        ".X..X..X.",
        ".X..X.XXX",
        ".X..X..X.",
        ".X..X..X.",
    )
    val Visuals = DotGlyph(
        "....X....",
        "....X....",
        "..X.X....",
        "..X.X.X..",
        "X.X.X.X..",
        "X.X.X.X.X",
        "X.X.X.X.X",
        "X.X.X.X.X",
        "X.X.X.X.X",
    )
    val Search = DotGlyph(
        "..XXX....",
        ".X...X...",
        "X.....X..",
        "X.....X..",
        "X.....X..",
        ".X...X...",
        "..XXXX...",
        "......X..",
        ".......XX",
    )
    val Settings = DotGlyph(
        "..X......",
        "XXXXXXXXX",
        "..X......",
        ".........",
        "......X..",
        "XXXXXXXXX",
        "......X..",
        ".........",
        "...X.....",
        "XXXXXXXXX",
        "...X.....",
    )
    val More = DotGlyph("X", ".", "X", ".", "X")
    val Close = DotGlyph(
        "X.....X",
        ".X...X.",
        "..X.X..",
        "...X...",
        "..X.X..",
        ".X...X.",
        "X.....X",
    )
    val Plus = DotGlyph(
        "...X...",
        "...X...",
        "...X...",
        "XXXXXXX",
        "...X...",
        "...X...",
        "...X...",
    )
    val Up = DotGlyph(
        "....X....",
        "...X.X...",
        "..X...X..",
        ".X.....X.",
        "X.......X",
    )
    val Stats = DotGlyph(
        "......XX.",
        "......XX.",
        "...XX.XX.",
        "...XX.XX.",
        "XX.XX.XX.",
        "XX.XX.XX.",
        "XX.XX.XX.",
    )
    val Party = DotGlyph(
        "XXX...XXX",
        "X.......X",
        "X.......X",
        ".........",
        "....X....",
        ".........",
        "X.......X",
        "X.......X",
        "XXX...XXX",
    )
    val Share = DotGlyph(
        "....X....",
        "...XXX...",
        "..X.X.X..",
        "....X....",
        "....X....",
        "X...X...X",
        "X.......X",
        "XXXXXXXXX",
    )
}

/**
 * Draws a [DotGlyph] centred in its box. When the glyph changes, its dots slide from the old
 * shape into the new one (extra dots grow in or shrink away), like the Glyph matrix redrawing.
 */
@Composable
fun DotIcon(glyph: DotGlyph, modifier: Modifier = Modifier, color: Color = Dot.White, size: Dp = 22.dp) {
    val tint by animateColorAsState(color, tween(220), label = "tint")
    var from by remember { mutableStateOf(glyph) }
    var to by remember { mutableStateOf(glyph) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(glyph) {
        if (glyph !== to) {
            from = to
            to = glyph
            morph.snapTo(0f)
            morph.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f))
        }
    }
    Canvas(modifier.size(size)) {
        val t = morph.value
        val a = layout(from, this.size.width, this.size.height)
        val b = layout(to, this.size.width, this.size.height)
        if (t >= 1f || from === to) {
            for (p in b.points) drawCircle(tint, b.radius, p)
            return@Canvas
        }
        // Pair every dot of the bigger shape with one of the smaller, in reading order.
        val n = maxOf(a.points.size, b.points.size)
        for (i in 0 until n) {
            val pa = a.points[i * a.points.size / n]
            val pb = b.points[i * b.points.size / n]
            val pos = Offset(pa.x + (pb.x - pa.x) * t, pa.y + (pb.y - pa.y) * t)
            drawCircle(tint, a.radius + (b.radius - a.radius) * t.coerceIn(0f, 1f), pos)
        }
    }
}

private class Laid(val points: List<Offset>, val radius: Float)

private fun layout(glyph: DotGlyph, w: Float, h: Float): Laid {
    val n = maxOf(glyph.cols, glyph.rows.size)
    val step = minOf(w, h) / n
    val ox = (w - step * glyph.cols) / 2 + step / 2
    val oy = (h - step * glyph.rows.size) / 2 + step / 2
    val pts = glyph.points.sortedWith(compareBy({ it.first }, { it.second })).map { (x, y) -> Offset(ox + x * step, oy + y * step) }
    return Laid(pts, step * 0.38f)
}

/** Shrinks a little while pressed and springs back, for any clickable. */
fun Modifier.pressable(interaction: MutableInteractionSource, pressedScale: Float = 0.86f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.5f, stiffness = if (pressed) 1200f else 500f),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun DotIconBtn(
    glyph: DotGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dot.White,
    size: Dp = 20.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(44.dp)
            .pressable(interaction)
            .clip(CircleShape)
            .clickable(interaction, ripple(bounded = true), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DotIcon(glyph, color = tint, size = size)
    }
}
