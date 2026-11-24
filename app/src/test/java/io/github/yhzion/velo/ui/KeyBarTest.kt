package io.github.yhzion.velo.ui

import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.ui.theme.Feel
import org.junit.Assert.*
import org.junit.Test

class KeyBarTest {
    @Test fun aModifierFeelsOnAndOffAndAnyOtherKeyFeelsAsAKey() {
        assertEquals(Feel.ToggleOn, barKeyFeel("Ctrl", armed = emptySet()))
        assertEquals(Feel.ToggleOff, barKeyFeel("Ctrl", armed = setOf("Ctrl")))
        // Another modifier that is on does not make this one's tap turn it off.
        assertEquals(Feel.ToggleOn, barKeyFeel("Alt", armed = setOf("Ctrl")))
        assertEquals(Feel.Key, barKeyFeel("Esc", armed = setOf("Ctrl")))
    }

    @Test fun unknownLabelsKeepTheirCase() {
        assertEquals("a", keyName("a"))
        assertEquals("Ctrl+x", keyName("Ctrl+x"))
        assertEquals("enter", keyName("enter"))
    }

    @Test fun upgradesKeepTheOrderThatWasVisibleIncludingUnknownKeys() {
        assertEquals(listOf("Ctrl", "Esc", "Tab", "Left", "Down", "Up", "Right"),
            readKeyOrder("Esc,Tab,Ctrl,Left,Down,Up,Right", ordered = false))
        assertEquals(listOf("Ctrl", "xY", "Esc", "Up"), readKeyOrder("Up,xY,Ctrl,Esc", false))
        assertEquals(emptyList<String>(), readKeyOrder("", false))
    }

    @Test fun movingAcrossGroupsTogglingAndReloadingPreserveTheChosenOrder() {
        val moved = moveKey(listOf("Up", "Esc", "Ctrl", "xY"), "Esc", -1)
        assertEquals(listOf("Esc", "Up", "Ctrl", "xY"), moved)
        assertEquals(moved, readKeyOrder(moved.joinToString(","), true))
        assertEquals(listOf("Up", "Esc", "Ctrl", "xY"), moveKey(moved, "Esc", 1))
        assertEquals(moved, moveKey(moved, "Esc", -1))
        assertEquals(moved, moveKey(moved, "xY", 1))
        assertEquals(moved, moveKey(moved, "Missing", 1))
        assertEquals(listOf("Up", "Ctrl", "xY", "Esc"), toggleKey(toggleKey(moved, "Esc"), "Esc"))
        assertEquals(listOf("Esc", "Up", "Ctrl"), toggleKey(moved, "xY"))
        assertTrue(readKeyOrder("", true).isEmpty())
    }

    @Test fun addingKeysPlacesSlashFirstAndBackspaceLastWithoutReorderingOtherKeys() {
        val bar = listOf("Up", "Esc")
        val ends = toggleKey(toggleKey(bar, "Backspace"), "/")
        assertEquals(listOf("/", "Up", "Esc", "Backspace"), ends)
        assertEquals(listOf("/", "Up", "Esc", "Tab", "Backspace"), toggleKey(ends, "Tab"))
        val custom = moveKey(ends, "Backspace", -1)
        assertEquals(custom + "Tab", toggleKey(custom, "Tab"))
        assertEquals("/", Settings.DEFAULT_KEY_BAR.substringBefore(','))
        assertEquals("Backspace", Settings.DEFAULT_KEY_BAR.substringAfterLast(','))
    }

    @Test fun widthsFillTheBarAndPreserveLargeLabelsAndTouchTargets() {
        assertEquals(listOf(100, 100, 100), keyWidths(listOf(50, 75, 50), 300))
        assertEquals(listOf(65, 120, 66), keyWidths(listOf(50, 120, 50), 251))
        assertEquals(listOf(50, 120, 50), keyWidths(listOf(50, 120, 50), 170))
        assertEquals(listOf(301), keyWidths(listOf(50), 301))
        assertTrue(keyWidths(emptyList(), 100).isEmpty())
    }
}
