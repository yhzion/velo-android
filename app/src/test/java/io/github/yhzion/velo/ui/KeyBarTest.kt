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

    @Test fun eachKeyHasOneSpokenNameAndAWordKeepsItsOwn() {
        for ((key, said) in listOf(
            "Ctrl" to "Control", "Esc" to "Escape", "PgUp" to "Page Up", "PgDn" to "Page Down",
            "-" to "Minus", "|" to "Pipe", "/" to "Slash", "~" to "Tilde",
        )) assertEquals(key, said, spokenKeyName(key))
        for (key in listOf("Tab", "Enter", "Alt", "Shift", "Left", "Down", "Up", "Right", "Home", "End", "Backspace"))
            assertEquals(key, spokenKeyName(key))
        // A key saved by an older Velo that is no longer offered is said as it is written.
        assertEquals("xY", spokenKeyName("xY"))
    }

    @Test fun everyOfferedKeyIsSaidInWords() {
        for (key in Settings.ALL_KEYS) spokenKeyName(key).let { assertTrue(key, it.isNotBlank() && it.all { c -> c.isLetter() || c == ' ' }) }
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

    @Test fun everyCapTakesTheSameShareOfTheBar() {
        // WsOpenPanes, in dp: 32 for a key, CTRL 47; 548 inside the bar, 5 between two caps.
        val need = listOf(32, 47, 32, 32, 32, 32, 32, 32, 32)
        val caps = keyCapWidths(need, floor = 32, gap = 5, available = 548)
        // 508 for nine caps: 56 each, and the 4 left over to the first four.
        assertEquals(listOf(57, 57, 57, 57, 56, 56, 56, 56, 56), caps)
        assertEquals(548, caps.sum() + 5 * 8)
        // One key takes the whole bar.
        assertEquals(listOf(300), keyCapWidths(listOf(40), floor = 32, gap = 5, available = 300))
        assertTrue(keyCapWidths(emptyList(), floor = 32, gap = 5, available = 100).isEmpty())
    }

    @Test fun capsThatExactlyFitTheWidestLabelStillShareTheBar() {
        // Three caps of CTRL's 47 and two gaps of 5: 151.
        assertEquals(listOf(47, 47, 47), keyCapWidths(listOf(32, 47, 32), floor = 32, gap = 5, available = 151))
        // Without a label wider than the least, the least decides.
        assertEquals(listOf(32, 32), keyCapWidths(listOf(20, 32), floor = 32, gap = 5, available = 69))
    }

    @Test fun aBarTooNarrowForTheWidestLabelScrollsWithEveryCapAtThatLabel() {
        // One pixel short of three CTRLs: every cap at CTRL's width, wider together than the bar.
        val caps = keyCapWidths(listOf(32, 47, 32), floor = 32, gap = 5, available = 150)
        assertEquals(listOf(47, 47, 47), caps)
        assertTrue(caps.sum() + 5 * 2 > 150)
        // A need under the least still takes the least.
        assertEquals(listOf(32, 32), keyCapWidths(listOf(20, 20), floor = 32, gap = 5, available = 60))
    }

    @Test fun aLabelGivesUpItsPaddingBeforeTheBarScrolls() {
        // The folded Fold8: CTRL needs 39 with 4 on each side; nine caps get 37 each, and CTRL keeps its 31.
        val need = listOf(32, 39, 32, 32, 32, 32, 32, 32, 32)
        assertEquals(List(9) { 37 }, keyCapWidths(need, floor = 32, gap = 5, available = 9 * 37 + 8 * 5, give = 8))
        // Below the least, the padding does not help: every cap at CTRL's whole need, and the bar scrolls.
        assertEquals(List(9) { 39 }, keyCapWidths(need, floor = 32, gap = 5, available = 9 * 32 + 8 * 5 - 1, give = 8))
        // Without the padding to give, the same bar scrolls.
        assertEquals(List(9) { 39 }, keyCapWidths(need, floor = 32, gap = 5, available = 9 * 37 + 8 * 5))
    }

    @Test fun eachKeyIsNamedForTestsAsTheBoardNamesIt() {
        assertEquals(listOf("key-slash", "key-ctrl", "key-esc", "key-tab", "key-left", "key-down", "key-up", "key-right", "key-backspace"),
            Settings.DEFAULT_KEY_BAR.split(',').map(::keyTestTag))
    }
}
