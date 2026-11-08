package com.blockparty.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Neutral "paper" palette so players' drawings stay the visual focus. */
object Paper {
    val Background = Color(0xFFF3EFE6)
    val Ground = Color(0xFFE6E0D2)
    val Lot = Color(0xFFEDE8DC)
    val LockedLot = Color(0xFFE0DACB)
    val Sidewalk = Color(0xFFD8D2C4)
    val Road = Color(0xFFBDB8AE)
    val RoadLine = Color(0xFFF3EFE6)
    val Floor = Color(0xFFF7F4EC)
    val FloorLine = Color(0xFFE3DDCF)
    val Wall = Color(0xFFFAF8F3)
    val WallSide = Color(0xFFE6E1D6)
    val BackWall = Color(0xFFEFEBE1)
    val BackWallSide = Color(0xFFE4DFD3)
    val Roof = Color(0xFFCFC8B8)
    val Ink = Color(0xFF5B564C)
    val Accent = Color(0xFFE8735A)
    val Valid = Color(0xFF5DAA68)
    val Invalid = Color(0xFFD9534F)
    val Coin = Color(0xFFD9A21B)
    val Star = Color(0xFFE8A33D)
}

private val colors = lightColorScheme(
    primary = Color(0xFFB4533E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF3B0A02),
    secondary = Color(0xFF4F6F8F),
    secondaryContainer = Color(0xFFD5E4F5),
    background = Paper.Background,
    surface = Color(0xFFFBF8F2),
    surfaceVariant = Color(0xFFEAE4D8),
    surfaceContainer = Color(0xFFF1ECE2),
    surfaceContainerHigh = Color(0xFFEDE7DC),
    onSurface = Color(0xFF2E2B26),
    onSurfaceVariant = Paper.Ink,
    outline = Color(0xFF8F897D),
)

@Composable
fun BlockPartyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
