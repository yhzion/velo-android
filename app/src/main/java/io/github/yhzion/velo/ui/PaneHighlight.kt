package io.github.yhzion.velo.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The colours the edge of the pane that takes the keys can take: Rex for Mac's choices
 * (Active Pane Highlight Color: accent, blue, green, yellow, cyan, magenta, terminal text), in
 * its order. The Mac takes them from the terminal theme's palette; here they are fixed, since
 * only the native core knows a remote's palette. No red and no orange: those say "failed" and
 * "waiting" elsewhere in the app. Each keeps [MinHighlightContrast] on black at the pane strength.
 */
enum class HighlightPreset(val label: String, val rgb: Int, val saved: String) {
    /** The palette's own colour (chrome's accent), the edge the app has always drawn. */
    Accent("Accent", 0x7FA6F5, "accent"),
    Blue("Blue", 0x0A84FF, "blue"),
    Green("Green", 0x30D158, "green"),
    Yellow("Yellow", 0xFFD60A, "yellow"),
    Cyan("Cyan", 0x64D2FF, "cyan"),
    Magenta("Magenta", 0xDA77F2, "magenta"),
    /** The terminal's text colour on the Mac; here chrome's text. */
    Text("Text", 0xF5F8FE, "text"),
}

/**
 * Settings → Appearance → Active pane: a [preset], or the [custom] colour when [preset] is
 * null. The custom colour stays while a preset is chosen, so choosing Custom again brings it
 * back. Colours are 0xRRGGBB.
 */
data class PaneHighlight(val preset: HighlightPreset?, val custom: Int?) {
    init {
        require(preset != null || custom != null) { "a custom highlight needs its colour" }
    }

    val isCustom: Boolean get() = preset == null

    /** The chosen colour. */
    val rgb: Int get() = preset?.rgb ?: custom!!

    /** The colour that replaces the palette's tokens; null for [HighlightPreset.Accent], which keeps them as they are. */
    val override: Int? get() = if (preset == HighlightPreset.Accent) null else rgb

    /** The value of the `paneHighlight` preference. */
    val saved: String get() = preset?.saved ?: CUSTOM

    /** Custom, starting from the kept custom colour, else from the colour shown now. */
    fun toCustom(): PaneHighlight = PaneHighlight(null, custom ?: rgb)

    companion object {
        private const val CUSTOM = "custom"
        val Default = PaneHighlight(HighlightPreset.Accent, null)

        /** The saved [choice] and custom colour; anything unreadable is the default. */
        fun read(choice: String?, custom: String?): PaneHighlight {
            val colour = custom?.let(::parseHex)
            val preset = HighlightPreset.entries.find { it.saved == choice }
            return when {
                preset != null -> PaneHighlight(preset, colour)
                choice == CUSTOM && colour != null -> PaneHighlight(null, colour)
                else -> Default.copy(custom = colour)
            }
        }
    }
}

/** The edge's strength: the palette's paneFocus alpha (70 %), kept for every colour. */
const val PaneHighlightAlpha = 0xB3 / 255f

/** The least contrast an edge keeps on the black around and behind panes: WCAG's 3:1 for parts of the interface. */
const val MinHighlightContrast = 3f

/** "#RRGGBB" or "RRGGBB", any case, spaces around it allowed; null otherwise. */
fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return digits.toInt(16)
}

/** "#RRGGBB" in capitals. */
fun hexOf(rgb: Int): String = "#" + (rgb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

/** A colour as hue (0 to 360), saturation and value (0 to 1): the axes of the custom picker. */
data class Hsv(val h: Float, val s: Float, val v: Float) {
    fun rgb(): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(n: Float) = ((n + m) * 255f).roundToInt().coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}

fun hsvOf(rgb: Int): Hsv {
    val r = (rgb shr 16 and 0xFF) / 255f
    val g = (rgb shr 8 and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val d = max - minOf(r, g, b)
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Hsv((h + 360f) % 360f, if (max == 0f) 0f else d / max, max)
}

private fun luminance(rgb: Int, scale: Float = 1f): Float {
    fun channel(shift: Int): Float {
        val n = (rgb shr shift and 0xFF) / 255f * scale
        return if (n <= 0.04045f) n / 12.92f else ((n + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * channel(16) + 0.7152f * channel(8) + 0.0722f * channel(0)
}

/** The contrast of the edge drawn in [rgb] at [PaneHighlightAlpha] over black, against black. */
fun highlightContrast(rgb: Int): Float = (luminance(rgb, PaneHighlightAlpha) + 0.05f) / 0.05f

fun isLowContrast(rgb: Int): Boolean = highlightContrast(rgb) < MinHighlightContrast

/** A terminal grid: columns by rows. */
data class GridFit(val cols: Int, val rows: Int)

/**
 * The grid a pane of [surfaceW] × [surfaceH] px holds at [fontPx], as the core fits it
 * (Pane.zig `cellSizeLocked` and `fitCount`): a cell is ceil(px × 0.6) wide and
 * ceil(px × [lineHeight]) high, and [padPx] is left at both ends. Never less than 1 × 1.
 */
fun gridFit(surfaceW: Int, surfaceH: Int, fontPx: Float, lineHeight: Float, padPx: Float): GridFit {
    val cellW = maxOf(1, ceil(fontPx * 0.6f).toInt())
    val cellH = maxOf(1, ceil(fontPx * lineHeight.coerceIn(1f, 2f)).toInt())
    fun fit(surface: Int, cell: Int): Int {
        val usable = surface - 2 * padPx.coerceIn(0f, 256f)
        return if (usable < cell) 1 else floor(usable / cell).toInt()
    }
    return GridFit(fit(surfaceW, cellW), fit(surfaceH, cellH))
}
