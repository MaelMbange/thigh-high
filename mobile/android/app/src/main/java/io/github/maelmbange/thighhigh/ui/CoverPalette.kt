package io.github.maelmbange.thighhigh.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.scale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Three colors taken from a cover, from most to least prominent. A Kotlin
 * port of the core crate's `cover::palette`, so both apps pick the same colors.
 */
data class CoverPalette(val primary: Color, val secondary: Color, val tertiary: Color) {
    companion object {
        /** Used until a track with cover art plays (same as the terminal app). */
        val Default = CoverPalette(rgb(200, 80, 200), rgb(80, 180, 255), rgb(255, 180, 80))

        /** Minimum hue difference, in degrees, for two colors to count as distinct. */
        private const val DISTINCT_HUE = 40f

        fun from(bitmap: Bitmap): CoverPalette {
            val small = bitmap.scale(64, 64)
            val pixels = IntArray(64 * 64)
            small.getPixels(pixels, 0, 64, 0, 0, 64, 64)

            // Group similar colors, weighting vivid ones above greys, blacks and whites.
            class Group(var weight: Float = 0f, var r: Float = 0f, var g: Float = 0f, var b: Float = 0f)
            val groups = HashMap<Int, Group>()
            for (p in pixels) {
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                val (_, s, v) = hsv(r, g, b)
                val w = 0.05f + s * v
                val group = groups.getOrPut(((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)) { Group() }
                group.weight += w
                group.r += r * w
                group.g += g * w
                group.b += b * w
            }
            val total = groups.values.sumOf { it.weight.toDouble() }.toFloat()
            val ranked = groups.values
                .map { it.weight to vivid((it.r / it.weight).toInt(), (it.g / it.weight).toInt(), (it.b / it.weight).toInt()) }
                .sortedByDescending { it.first }
            val primary = ranked.firstOrNull()?.second ?: return Default

            val grey = { c: Rgb -> hsv(c).second < 0.12f }
            val picked = mutableListOf(primary)
            for ((weight, color) in ranked.drop(1)) {
                if (picked.size == 3) break
                val distinct = picked.all { hueDistance(it, color) >= DISTINCT_HUE }
                if (weight >= total * 0.02f && !grey(color) && distinct) picked += color
            }
            fun derive(degrees: Float, fallback: Color) =
                if (grey(primary)) fallback else rotate(primary, degrees).toColor()
            return CoverPalette(
                primary = primary.toColor(),
                secondary = picked.getOrNull(1)?.toColor() ?: derive(150f, Default.secondary),
                tertiary = picked.getOrNull(2)?.toColor() ?: derive(-150f, Default.tertiary),
            )
        }
    }
}

private data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun toColor() = rgb(r, g, b)
}

private fun rgb(r: Int, g: Int, b: Int) = Color(r, g, b)

private fun hsv(c: Rgb) = hsv(c.r, c.g, c.b)

private fun hsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val mx = max(rf, max(gf, bf))
    val mn = min(rf, min(gf, bf))
    val d = mx - mn
    val hue = when {
        d == 0f -> 0f
        mx == rf -> 60f * (((gf - bf) / d).mod(6f))
        mx == gf -> 60f * ((bf - rf) / d + 2f)
        else -> 60f * ((rf - gf) / d + 4f)
    }
    val saturation = if (mx == 0f) 0f else d / mx
    return Triple(hue, saturation, mx)
}

private fun fromHsv(hue: Float, saturation: Float, value: Float): Rgb {
    val c = value * saturation
    val x = c * (1f - abs((hue / 60f).mod(2f) - 1f))
    val m = value - c
    val (r, g, b) = when (hue.toInt()) {
        in 0 until 60 -> Triple(c, x, 0f)
        in 60 until 120 -> Triple(x, c, 0f)
        in 120 until 180 -> Triple(0f, c, x)
        in 180 until 240 -> Triple(0f, x, c)
        in 240 until 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun byte(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
    return Rgb(byte(r), byte(g), byte(b))
}

/** Keeps the hue but makes the color bright, and saturated unless it was grey. */
private fun vivid(r: Int, g: Int, b: Int): Rgb {
    val (h, s, v) = hsv(r, g, b)
    return fromHsv(h, if (s < 0.12f) s else max(s, 0.55f), max(v, 0.85f))
}

private fun hueDistance(a: Rgb, b: Rgb): Float {
    val d = abs(hsv(a).first - hsv(b).first)
    return min(d, 360f - d)
}

private fun rotate(c: Rgb, degrees: Float): Rgb {
    val (h, s, v) = hsv(c)
    return fromHsv((h + degrees).mod(360f), s, v)
}
