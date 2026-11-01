package com.dj.dot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    /** Spectrum bars in dots: the Visuals and Glyph button on Now Playing. */
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
}

@Composable
fun DotIcon(glyph: DotGlyph, modifier: Modifier = Modifier, color: Color = Dot.White, size: Dp = 22.dp) {
    val tint by animateColorAsState(color, tween(220), label = "tint")
    Canvas(modifier.size(size)) {
        val n = maxOf(glyph.cols, glyph.rows.size)
        val step = this.size.minDimension / n
        val ox = (this.size.width - step * glyph.cols) / 2 + step / 2
        val oy = (this.size.height - step * glyph.rows.size) / 2 + step / 2
        for ((x, y) in glyph.points) drawCircle(tint, step * 0.38f, Offset(ox + x * step, oy + y * step))
    }
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
