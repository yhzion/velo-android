package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneHighlightTest {
    @Test fun presetsAreTheMacChoicesInItsOrder() {
        assertEquals(
            listOf("Accent", "Blue", "Green", "Yellow", "Cyan", "Magenta", "Text"),
            HighlightPreset.entries.map { it.label },
        )
        assertEquals(0x7FA6F5, HighlightPreset.Accent.rgb)
    }

    @Test fun everyPresetKeepsThreeToOneOnBlackAtThePaneStrength() {
        for (preset in HighlightPreset.entries) {
            assertTrue("${preset.label} ${highlightContrast(preset.rgb)}", !isLowContrast(preset.rgb))
        }
    }

    @Test fun aDarkColourIsLowContrast() {
        assertTrue(isLowContrast(0x4B3B8F))
        assertFalse(isLowContrast(0xF2A65A))
    }

    @Test fun hexAcceptsSixDigitsWithOrWithoutTheHash() {
        assertEquals(0x7FA6F5, parseHex("#7FA6F5"))
        assertEquals(0x7FA6F5, parseHex(" 7fa6f5 "))
        assertNull(parseHex("#7FA6F"))
        assertNull(parseHex("#7FA6F5A"))
        assertNull(parseHex("#GGGGGG"))
        assertNull(parseHex(""))
        assertEquals("#0A84FF", hexOf(0x0A84FF))
    }

    @Test fun hsvRoundTripsThroughRgb() {
        for (rgb in listOf(0x7FA6F5, 0x0A84FF, 0xFFD60A, 0x000000, 0xFFFFFF, 0xDA77F2)) {
            assertEquals(hexOf(rgb), hexOf(hsvOf(rgb).rgb()))
        }
        val red = hsvOf(0xFF0000)
        assertEquals(0f, red.h, 0.01f)
        assertEquals(1f, red.s, 0.01f)
        assertEquals(1f, red.v, 0.01f)
        assertEquals(0x00FF00, Hsv(120f, 1f, 1f).rgb())
    }

    @Test fun theDefaultChangesNothing() {
        assertEquals(PaneHighlight.Default, PaneHighlight.read(null, null))
        assertNull(PaneHighlight.Default.override)
        assertEquals(0x0A84FF, PaneHighlight(HighlightPreset.Blue, null).override)
    }

    @Test fun theCustomColourIsKeptWhileAPresetIsChosen() {
        val saved = PaneHighlight(HighlightPreset.Green, 0xF2A65A)
        val restored = PaneHighlight.read(saved.saved, hexOf(saved.custom!!))
        assertEquals(saved, restored)
        assertEquals(0x30D158, restored.rgb)
        assertEquals(PaneHighlight(null, 0xF2A65A), PaneHighlight.read("custom", "#F2A65A"))
        assertEquals(0xF2A65A, PaneHighlight(null, 0xF2A65A).rgb)
    }

    @Test fun anUnreadableChoiceFallsBackToTheDefault() {
        assertEquals(PaneHighlight.Default, PaneHighlight.read("purple", null))
        // "custom" without a readable colour has nothing to show.
        assertEquals(PaneHighlight.Default, PaneHighlight.read("custom", "nope"))
    }

    @Test fun choosingCustomTheFirstTimeStartsFromTheCurrentColour() {
        assertEquals(PaneHighlight(null, 0x0A84FF), PaneHighlight(HighlightPreset.Blue, null).toCustom())
        assertEquals(PaneHighlight(null, 0xF2A65A), PaneHighlight(HighlightPreset.Blue, 0xF2A65A).toCustom())
    }

    @Test fun gridFitMatchesTheCoreRule() {
        // Pane.zig: cell = ceil(px × 0.6) by ceil(px × line height); cells that fit less the padding at both ends.
        assertEquals(GridFit(100, 30), gridFit(600, 360, fontPx = 10f, lineHeight = 1.2f, padPx = 0f))
        assertEquals(GridFit(96, 28), gridFit(600, 360, fontPx = 10f, lineHeight = 1.2f, padPx = 10f))
        assertEquals(95, gridFit(596, 360, fontPx = 10f, lineHeight = 1.2f, padPx = 10.5f).cols)
        assertEquals(GridFit(100, 24), gridFit(600, 360, fontPx = 10f, lineHeight = 1.5f, padPx = 0f))
        assertEquals(GridFit(1, 1), gridFit(0, 0, fontPx = 10f, lineHeight = 1.2f, padPx = 0f))
    }
}
