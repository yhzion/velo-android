package io.github.yhzion.velo

import io.github.yhzion.velo.ui.HighlightPreset
import io.github.yhzion.velo.ui.PaneHighlight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAppearanceTest {
    @Test fun retiredColorSchemeIsNotReadWhenRestoringOtherSettings() {
        val prefs = FakePrefs()
        // Even a non-string at the retired key must not affect startup.
        prefs.edit().putInt("colorScheme", 1).putBoolean("keepScreenOn", true).apply()
        assertTrue(Settings(prefs).keepScreenOn)
    }

    @Test fun thePaneHighlightIsRestoredWithItsCustomColour() {
        val prefs = FakePrefs()
        assertEquals(PaneHighlight.Default, Settings(prefs).paneHighlight)
        Settings(prefs).savePaneHighlight(PaneHighlight(null, 0xF2A65A))
        Settings(prefs).savePaneHighlight(PaneHighlight(HighlightPreset.Cyan, 0xF2A65A))
        assertEquals(PaneHighlight(HighlightPreset.Cyan, 0xF2A65A), Settings(prefs).paneHighlight)
    }
}
