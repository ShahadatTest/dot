@file:OptIn(ExperimentalTextApi::class)

package com.dj.dot.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dj.dot.R

object Dot {
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF0D0D0D)
    val Surface2 = Color(0xFF171717)
    val Line = Color(0xFF262626)
    val Faint = Color(0xFF2E2E2E)
    val Muted = Color(0xFF8A8A8A)
    val White = Color(0xFFF2F2F2)
    val Red = Color(0xFFD71921)
}

/** Round dot-matrix display face. */
val DotoFamily = FontFamily(
    Font(
        R.font.doto,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900), FontVariation.Setting("ROND", 100f)),
    ),
)

val MonoFamily = FontFamily(
    Font(R.font.space_mono, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val MonoLabel = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, letterSpacing = 1.2.sp, lineHeight = 14.sp)
val MonoSmall = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp, letterSpacing = 0.3.sp, lineHeight = 16.sp)

@Composable
fun DotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Dot.Red,
            onPrimary = Dot.White,
            secondary = Dot.White,
            onSecondary = Dot.Black,
            background = Dot.Black,
            onBackground = Dot.White,
            surface = Dot.Black,
            onSurface = Dot.White,
            surfaceVariant = Dot.Surface2,
            onSurfaceVariant = Dot.Muted,
            surfaceContainer = Dot.Surface2,
            surfaceContainerHigh = Dot.Surface2,
            surfaceContainerLow = Dot.Surface,
            outline = Dot.Line,
            outlineVariant = Dot.Line,
        ),
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
            labelSmall = MonoLabel,
            labelMedium = MonoLabel,
        ),
        content = content,
    )
}
