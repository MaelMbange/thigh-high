package io.github.maelmbange.thighhigh.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Red of the delete action, the one color that never follows the cover. */
val DeleteRed = Color(0xFFE5534B)

/**
 * One color per kind of text, the same role on every screen. They follow the
 * cover of the track that is playing, through the theme.
 */
object TextColors {
    /** Track and download titles. */
    val title: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    /** The track that is playing, and the accent of the small controls. */
    val playing: Color @Composable get() = MaterialTheme.colorScheme.primary
    val artist: Color @Composable get() = MaterialTheme.colorScheme.secondary
    val genre: Color @Composable get() = MaterialTheme.colorScheme.tertiary
    /** Durations, times, statuses and hints. */
    val info: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

/** Black or white, whichever reads best on [background]. */
fun contentOn(background: Color): Color =
    if (background.luminance() > 0.4f) Color(0xFF101010) else Color.White

/**
 * The whole interface takes the colors of the playing track's cover (the
 * same palette as the full player and the terminal app): its main color for
 * accents and the highlighted track, the second for artists, the third for
 * genres. They fade over when the track changes.
 */
@Composable
fun ThighHighTheme(palette: CoverPalette = CoverPalette.Default, content: @Composable () -> Unit) {
    val spec = tween<Color>(600)
    val primary by animateColorAsState(palette.primary, spec, label = "primary")
    val secondary by animateColorAsState(palette.secondary, spec, label = "secondary")
    val tertiary by animateColorAsState(palette.tertiary, spec, label = "tertiary")
    val colors = darkColorScheme(
        primary = primary,
        onPrimary = contentOn(primary),
        secondary = secondary,
        onSecondary = contentOn(secondary),
        tertiary = tertiary,
        onTertiary = contentOn(tertiary),
        secondaryContainer = primary.copy(alpha = 0.25f),
        onSecondaryContainer = Color.White,
        error = DeleteRed,
        background = Color(0xFF121212),
        surface = Color(0xFF121212),
        surfaceContainer = Color(0xFF1E1E1E),
        onBackground = Color(0xFFEDEDED),
        onSurface = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFF9A9A9A),
    )
    MaterialTheme(colorScheme = colors, content = content)
}
