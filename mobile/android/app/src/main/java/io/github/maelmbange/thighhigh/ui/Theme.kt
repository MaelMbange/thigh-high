package io.github.maelmbange.thighhigh.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Catppuccin Mocha colors the interface uses. */
object Catppuccin {
    val Lavender = Color(0xFFB4BEFE)
    val Mauve = Color(0xFFCBA6F7)
    val Sky = Color(0xFF89DCEB)
    val Peach = Color(0xFFFAB387)
    val Green = Color(0xFFA6E3A1)
    val Red = Color(0xFFF38BA8)
    val Text = Color(0xFFCDD6F4)
    val Subtext0 = Color(0xFFA6ADC8)
    val Surface1 = Color(0xFF45475A)
    val Crust = Color(0xFF11111B)
}

/**
 * One color per kind of text, the same on every screen: the list, the mini
 * player, the full player and the download screen.
 */
object TextColors {
    /** Track and download titles. */
    val title = Catppuccin.Lavender
    /** The track that is playing, and the accent of the small controls. */
    val playing = Catppuccin.Mauve
    val artist = Catppuccin.Sky
    val genre = Catppuccin.Peach
    /** Durations, times, statuses and hints. */
    val info = Catppuccin.Subtext0
}

private val Colors = darkColorScheme(
    primary = Catppuccin.Mauve,
    onPrimary = Catppuccin.Crust,
    secondary = Catppuccin.Mauve,
    onSecondary = Catppuccin.Crust,
    secondaryContainer = Catppuccin.Surface1,
    onSecondaryContainer = Catppuccin.Text,
    error = Catppuccin.Red,
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceContainer = Color(0xFF1E1E1E),
    onBackground = Catppuccin.Text,
    onSurface = Catppuccin.Text,
    onSurfaceVariant = Catppuccin.Subtext0,
)

@Composable
fun ThighHighTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
