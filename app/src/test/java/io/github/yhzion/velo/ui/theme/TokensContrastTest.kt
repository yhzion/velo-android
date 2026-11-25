package io.github.yhzion.velo.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x relative luminance of an opaque sRGB colour. */
private fun luminance(c: Color): Double {
    fun linear(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
}

/** WCAG 2.x contrast ratio, 1 (the same) to 21 (black on white). */
private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

/** One colour on another, by name, so a failure says which pair. */
private class Pair(val what: String, val on: String, val fg: Color, val bg: Color)

/** A surface group as it is drawn: a name and the fills its content can sit on. */
private class Drawn(val name: String, val c: SurfaceColors, val surfaces: List<Color>, val textOnGlass: Boolean, val rows: Boolean = true)

class TokensContrastTest {
    private val appearances = listOf("dark" to VeloTheme.colors, "reference light" to ReferencePalettes.light)

    /** Every ninth of each channel: 729 colours, the corners of the colour cube among them. */
    private val anyColour: List<Color> = buildList {
        for (r in 0..8) for (g in 0..8) for (b in 0..8) add(Color(r / 8f, g / 8f, b / 8f))
    }

    /**
     * The five groups of an appearance, and the raised card, which keeps the colours of the sheet
     * or the sidebar it stands on. An overlay shows a blur of the terminals through its fill, and
     * a terminal can show any colour: its surface is taken over every colour of [anyColour], as
     * the material the app draws ([VeloColors.overlayOver]).
     */
    private fun groups(colors: VeloColors): List<Drawn> = listOfNotNull(
        Drawn("backdrop", colors.backdrop, listOf(colors.backdrop.surface), textOnGlass = false),
        Drawn("sidebar", colors.sidebar, listOf(colors.sidebar.surface), textOnGlass = true),
        Drawn("chrome", colors.chrome, listOf(colors.chrome.surface), textOnGlass = true),
        Drawn("overlay", colors.overlay, anyColour.map { colors.overlayOver(it) }.distinct(), textOnGlass = true),
        Drawn("sheet", colors.sheet, listOf(colors.sheet.surface), textOnGlass = true),
        // A card holds no selected row; the light reference's sidebar is black, so a card stands on it only in Dark.
        Drawn("card on the sheet", colors.sheet.copyOn(colors.raised), listOf(colors.raised), textOnGlass = true, rows = false),
        if (colors.dark) Drawn("card on the sidebar", colors.sidebar.copyOn(colors.raised), listOf(colors.raised), textOnGlass = true, rows = false) else null,
    )

    /** The group's colours on [surface]: a card is drawn with the colours of what it stands on. */
    private fun SurfaceColors.copyOn(surface: Color) = SurfaceColors(
        surface, text, secondary, disabled, glass, glassEdge, onGlass, glassSelected, selected, divider,
        accent, onAccent, ok, warning, danger, dangerText, onDanger,
    )

    /** Every place text is drawn: body, secondary and destructive text on a surface, on glass and on a selected row. */
    private fun textPairs(g: Drawn): List<Pair> = buildList {
        val c = g.c
        for (s in g.surfaces) {
            add(Pair("text", "surface", c.text, s))
            add(Pair("secondary", "surface", c.secondary, s))
            add(Pair("dangerText", "surface", c.dangerText, s))
        }
        add(Pair("text", "selected", c.text, c.selected))
        add(Pair("secondary", "selected", c.secondary, c.selected))
        add(Pair("onAccent", "accent", c.onAccent, c.accent))
        add(Pair("onDanger", "danger", c.onDanger, c.danger))
        if (g.textOnGlass) {
            add(Pair("onGlass", "glass", c.onGlass, c.glass))
            add(Pair("onGlass", "glassSelected", c.onGlass, c.glassSelected))
            add(Pair("secondary", "glass", c.secondary, c.glass))
        }
    }

    /** Every icon or mark that says something by itself: the state colours, and the glyph of an icon-only control. */
    private fun markPairs(g: Drawn): List<Pair> = buildList {
        val c = g.c
        val states = listOf("accent" to c.accent, "ok" to c.ok, "warning" to c.warning, "danger" to c.danger)
        for ((name, color) in states) {
            for (s in g.surfaces) add(Pair(name, "surface", color, s))
            add(Pair(name, "selected", color, c.selected))
            if (g.textOnGlass) add(Pair(name, "glass", color, c.glass))
        }
        add(Pair("onGlass", "glass", c.onGlass, c.glass))
        add(Pair("onGlass", "glassSelected", c.onGlass, c.glassSelected))
    }

    /** The pairs below [least], each once with its worst ratio (an overlay has hundreds of surfaces). */
    private fun failures(least: Double, pairs: (Drawn) -> List<Pair>): List<String> = buildList {
        for ((appearance, colors) in appearances) for (g in groups(colors)) {
            val worst = LinkedHashMap<String, Double>()
            for (p in pairs(g)) {
                val ratio = contrast(p.fg, p.bg)
                if (ratio < least) worst.merge("$appearance ${g.name}: ${p.what} on ${p.on}", ratio, ::minOf)
            }
            for ((pair, ratio) in worst) add("$pair is %.2f".format(ratio))
        }
    }

    @Test fun theContrastFunctionAgreesWithKnownRatios() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.001)
        assertEquals(1.0, contrast(Color(0xFF336FB1), Color(0xFF336FB1)), 0.001)
        // #767676 is the lightest grey that passes 4.5 on white; #777777 is the first that fails.
        assertEquals(4.54, contrast(Color(0xFF767676), Color.White), 0.005)
        assertEquals(4.48, contrast(Color(0xFF777777), Color.White), 0.005)
    }

    @Test fun everyTextReachesFourPointFiveOnWhatItIsDrawnOn() {
        assertEquals(emptyList<String>(), failures(4.5, ::textPairs))
    }

    @Test fun everyMarkThatCarriesMeaningReachesThree() {
        assertEquals(emptyList<String>(), failures(3.0, ::markPairs))
    }

    @Test fun aParagraphOnACardOrADialogIsReadable() {
        for ((appearance, colors) in appearances) {
            val ratio = contrast(colors.textOnRaised, colors.raised)
            assertTrue("$appearance textOnRaised on raised is %.2f".format(ratio), ratio >= 4.5)
        }
    }

    @Test fun aCardStandsOutOfTheSheetByItsEdge() {
        // The card's fill is a step above the sheet; its border, the divider, is what draws its shape.
        for ((appearance, colors) in appearances) {
            val ratio = maxOf(contrast(colors.raised, colors.sheet.surface), contrast(colors.sheet.divider, colors.raised))
            assertTrue("$appearance raised %.3f".format(ratio), ratio >= 1.1)
        }
    }

    @Test fun aSelectedRowShowsOnItsSurface() {
        // The Duo's own pill is this faint: 1.12 on the sidebar. Below 1.1 it cannot be seen.
        for ((appearance, colors) in appearances) for (g in groups(colors).filter { it.rows }) for (s in g.surfaces) {
            val ratio = contrast(g.c.selected, s)
            assertTrue("$appearance ${g.name}: selected on its surface is %.3f".format(ratio), ratio >= 1.1)
        }
    }

    @Test fun aSelectedRowIsOnOneSideOfAnOverlayWhateverIsBehindIt() {
        // Lighter than the overlay over one colour and darker over another would mean that it vanishes somewhere between.
        for ((appearance, colors) in appearances) {
            val sides = anyColour.map { luminance(colors.overlay.selected) > luminance(colors.overlayOver(it)) }
            assertEquals("$appearance overlay", 1, sides.distinct().size)
        }
    }

    @Test fun anOverlayIsItsMeasuredColourOverTheGroundItWasMeasuredOn() {
        // The sheet of the iOS app reads #2E323E over a dimmed terminal and #ECEDF1 over a dimmed grey: the anchors.
        for ((appearance, colors) in appearances) {
            val scrim = colors.scrim.alpha
            fun undimmed(v: Float) = v / (1 - scrim)
            val behind = Color(undimmed(colors.overlayAnchor.red), undimmed(colors.overlayAnchor.green), undimmed(colors.overlayAnchor.blue))
            assertTrue(appearance, contrast(colors.overlayOver(behind), colors.overlay.surface) < 1.02)
        }
    }

    @Test fun whatIsBehindAnOverlayShowsInIt() {
        // A blur that cannot be seen is no blur. Red, green and blue behind an overlay give fills
        // that differ by 15 % of a channel or more (at a 10 % share of the picture they differed by 6 % and
        // a capture showed next to nothing); black and white give two fills as well.
        fun apart(a: Color, b: Color) = maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue))
        for ((appearance, colors) in appearances) {
            val fills = listOf(Color.Red, Color.Green, Color.Blue).map { colors.overlayOver(it) }
            for (a in fills.indices) for (b in a + 1 until fills.size) {
                assertTrue("$appearance: fills $a and $b are %.3f apart".format(apart(fills[a], fills[b])), apart(fills[a], fills[b]) >= 0.15f)
            }
            val grey = apart(colors.overlayOver(Color.Black), colors.overlayOver(Color.White))
            assertTrue("$appearance: over black and over white are %.3f apart".format(grey), grey >= 0.04f)
        }
    }

    @Test fun aGlassControlShowsOnItsSurfaceByItsFillOrItsEdge() {
        for ((appearance, colors) in appearances) for (g in groups(colors)) for (s in g.surfaces) {
            val ratio = maxOf(contrast(g.c.glass, s), contrast(g.c.glassEdge, s))
            assertTrue("$appearance ${g.name}: glass on its surface is %.3f".format(ratio), ratio >= 1.25)
        }
    }

    @Test fun aSelectedCellShowsInsideItsGlass() {
        for ((appearance, colors) in appearances) for (g in groups(colors)) {
            val ratio = contrast(g.c.glassSelected, g.c.glass)
            assertTrue("$appearance ${g.name}: glassSelected on glass is %.3f".format(ratio), ratio >= 1.1)
        }
    }

    @Test fun disabledTextIsDimmerThanSecondaryAndStillThere() {
        for ((appearance, colors) in appearances) for (g in groups(colors)) for (s in g.surfaces) {
            val disabled = contrast(g.c.disabled, s)
            assertTrue("$appearance ${g.name}: disabled %.2f".format(disabled), disabled < contrast(g.c.secondary, s) && disabled >= 1.8)
        }
    }

    @Test fun theSidebarAndTheBackdropAreDarkInBothAppearances() {
        // Measured: in every image of Rex for iOS, the light ones included, they are black or near-black.
        // Its text is light: the Duo's own reads #D5DDE4 in the light clip, a luminance of 0.71.
        for ((appearance, colors) in appearances) for (c in listOf(colors.sidebar, colors.backdrop)) {
            assertTrue(appearance, luminance(c.surface) < 0.01 && luminance(c.text) > 0.6)
        }
    }

    @Test fun appChromeAndOverlaysAreDarkUnlikeTheLightReference() {
        for (c in listOf(ReferencePalettes.light.chrome, ReferencePalettes.light.overlay)) assertTrue(luminance(c.surface) > 0.8)
        for (c in listOf(VeloTheme.colors.chrome, VeloTheme.colors.overlay)) assertTrue(luminance(c.surface) < 0.05)
    }

    @Test fun materialComponentsThatRemainAreReadableOnEverySurface() {
        // A switch, a slider, a chip, a text button and a snackbar draw with these pairs of the scheme.
        val failures = buildList {
            for ((appearance, colors) in appearances) for (g in groups(colors)) {
                val m = materialColors(g.c, colors.scrim)
                val pairs = listOf(
                    Pair("onSurface", "surface", m.onSurface, m.surface),
                    Pair("onBackground", "background", m.onBackground, m.background),
                    Pair("onSurfaceVariant", "surfaceVariant", m.onSurfaceVariant, m.surfaceVariant),
                    Pair("onSurfaceVariant", "surface", m.onSurfaceVariant, m.surface),
                    Pair("onSurface", "surfaceContainer", m.onSurface, m.surfaceContainer),
                    Pair("onSurface", "surfaceContainerHigh", m.onSurface, m.surfaceContainerHigh),
                    Pair("onSurface", "surfaceContainerHighest", m.onSurface, m.surfaceContainerHighest),
                    Pair("onSurfaceVariant", "surfaceContainerHighest", m.onSurfaceVariant, m.surfaceContainerHighest),
                    Pair("onPrimaryContainer", "primaryContainer", m.onPrimaryContainer, m.primaryContainer),
                    Pair("onErrorContainer", "errorContainer", m.onErrorContainer, m.errorContainer),
                    Pair("onPrimary", "primary", m.onPrimary, m.primary),
                    Pair("onSecondaryContainer", "secondaryContainer", m.onSecondaryContainer, m.secondaryContainer),
                    Pair("onError", "error", m.onError, m.error),
                    Pair("error", "surface", m.error, m.surface),
                    Pair("inverseOnSurface", "inverseSurface", m.inverseOnSurface, m.inverseSurface),
                )
                for (p in pairs) {
                    val ratio = contrast(p.fg, p.bg)
                    if (ratio < 4.5) add("$appearance ${g.name}: ${p.what} on ${p.on} is %.2f".format(ratio))
                }
                // Marks without text: a switch's track, a slider's, a field's outline.
                for (p in listOf(Pair("primary", "surface", m.primary, m.surface), Pair("outline", "surface", m.outline, m.surface))) {
                    val ratio = contrast(p.fg, p.bg)
                    if (ratio < 3.0) add("$appearance ${g.name}: ${p.what} on ${p.on} is %.2f".format(ratio))
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun theFocusBorderReachesThreeOnTheWorkspaceInBothAppearances() {
        // The border is the only mark of the pane that takes the keys. It is drawn over the terminal at
        // the pane's edge, next to the backdrop, so its composite is held against both. The terminal's
        // default background is the renderer's clear colour before a palette arrives: black, `clear_rgb`
        // in native/src/term/Renderer.zig (a palette the server sends replaces it; a light one is not covered).
        val terminalDefaultBackground = Color(0xFF000000)
        fun over(top: Color, under: Color) = Color(
            top.red * top.alpha + under.red * (1 - top.alpha),
            top.green * top.alpha + under.green * (1 - top.alpha),
            top.blue * top.alpha + under.blue * (1 - top.alpha),
        )
        val failures = buildList {
            for ((appearance, colors) in appearances) {
                val border = over(colors.paneFocus, terminalDefaultBackground)
                for ((on, surface) in listOf("backdrop surface" to colors.backdrop.surface, "terminal default background" to terminalDefaultBackground)) {
                    val ratio = contrast(border, surface)
                    if (ratio < 3.0) add("$appearance: paneFocus on $on is %.2f".format(ratio))
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun theScrimDimsWithoutHiding() {
        for ((appearance, colors) in appearances) {
            assertTrue(appearance, colors.scrim.alpha in 0.15f..0.6f)
        }
    }
}
