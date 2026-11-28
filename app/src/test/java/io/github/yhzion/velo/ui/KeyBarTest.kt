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
        for (key in listOf("Tab", "Enter", "Alt", "Shift", "Left", "Down", "Up", "Right", "Home", "End", "Space", "Backspace"))
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
        // The unfolded Fold8 upright, in dp: a pane column of 753, 741 inside the strip's 6 on each side; 36 for a key, CTRL 39, 6 between two caps.
        val need = listOf(36, 39, 36, 36, 36, 36, 36, 36, 36)
        val caps = keyCaps(need, floor = 36, gap = 6, available = 741, upright = true)
        // 693 for nine caps: 77 each, nothing left over, in one row.
        assertEquals(KeyCaps(List(9) { 77 }, 6), caps)
        // Pixels the share cannot split go to the first caps.
        assertEquals(listOf(78, 78, 77, 77, 77, 77, 77, 77, 77), keyCaps(need, floor = 36, gap = 6, available = 743).widths)
        // One key takes the whole bar.
        assertEquals(listOf(300), keyCaps(listOf(40), floor = 36, gap = 6, available = 300).widths)
        assertTrue(keyCaps(emptyList(), floor = 36, gap = 6, available = 100).widths.isEmpty())
    }

    @Test fun aCapOfTheLeastWidthStillTakesOneRow() {
        val need = List(9) { 36 }
        // Nine caps of 36 and eight gaps of 6: 372.
        assertEquals(KeyCaps(List(9) { 36 }, 6), keyCaps(need, floor = 36, gap = 6, available = 372, upright = true))
        // CTRL needs 47 with 4 on each side: it gives up the padding and keeps one row while its 39 fit, not a pixel less.
        val ctrl = listOf(36, 47) + List(7) { 36 }
        assertEquals(KeyCaps(List(9) { 39 }, 6), keyCaps(ctrl, floor = 36, gap = 6, available = 9 * 39 + 8 * 6, give = 8, upright = true))
        assertEquals(2, keyCaps(ctrl, floor = 36, gap = 6, available = 9 * 39 + 8 * 6 - 1, give = 8, upright = true).rows)
    }

    @Test fun belowTheLeastCapAnUprightBarTakesTwoRows() {
        // The bar phone upright (393 wide): the strip is 325, 313 inside it, under nine caps of 36.
        val need = listOf(36, 39, 36, 36, 36, 36, 36, 36, 36)
        val caps = keyCaps(need, floor = 36, gap = 6, available = 313, give = 8, upright = true)
        // Five caps in the first row and four in the second, all of one width: 289 for five caps.
        assertEquals(KeyCaps(List(4) { 58 } + 57, 6, rows = 2), caps)
        assertEquals(5, caps.columns)
        assertFalse(caps.scrolls)
        // A pixel under nine caps of 36 is enough.
        assertEquals(2, keyCaps(List(9) { 36 }, floor = 36, gap = 6, available = 371, upright = true).rows)
        // Ten keys split five and five.
        assertEquals(5, keyCaps(List(10) { 36 }, floor = 36, gap = 6, available = 371, upright = true).columns)
    }

    @Test fun belowTheLeastCapAWideBarScrollsWithEveryCapAtTheWidestNeed() {
        val need = listOf(36, 39, 36, 36, 36, 36, 36, 36, 36)
        val caps = keyCaps(need, floor = 36, gap = 6, available = 371, give = 8, upright = false)
        assertEquals(KeyCaps(List(9) { 39 }, 6, scrolls = true), caps)
        assertTrue(caps.widths.sum() + 6 * 8 > 371)
        // Upright, but too narrow even for two rows: one row that scrolls.
        assertTrue(keyCaps(need, floor = 36, gap = 6, available = 5 * 36 + 4 * 6 - 1, upright = true).scrolls)
        // One key has no second row to go to.
        assertEquals(KeyCaps(listOf(39), 6, scrolls = true), keyCaps(listOf(39), floor = 36, gap = 6, available = 30, upright = true))
    }

    @Test fun eachKeyIsNamedForTestsAsTheBoardNamesIt() {
        assertEquals(listOf("key-slash", "key-ctrl", "key-esc", "key-tab", "key-left", "key-down", "key-up", "key-right", "key-backspace"),
            Settings.DEFAULT_KEY_BAR.split(',').map(::keyTestTag))
    }

    /** #213: Space is typed, an Editing key sent by its keycode, so an armed Ctrl, Alt or Shift goes with it. */
    @Test fun spaceIsAnEditingKeySentByItsKeycodeThatDoesNotRepeat() {
        assertTrue("Space" in Settings.ALL_KEYS)
        assertEquals(KeyGroup.Editing, keyGroup("Space"))
        assertEquals(android.view.KeyEvent.KEYCODE_SPACE, KEY_CODES["Space"])
        // Sent with its character, or the core would type nothing for it without Ctrl or Alt.
        assertEquals(' '.code, barKeyChar(KEY_CODES.getValue("Space")))
        for (key in KEY_CODES.keys - "Space") assertEquals(key, 0, barKeyChar(KEY_CODES.getValue(key)))
        assertFalse("a held Space types one space", "Space" in REPEAT_KEYS)
        assertEquals("SPACE", keyName("Space"))
        assertEquals("Space", spokenKeyName("Space"))
        assertEquals("key-space", keyTestTag("Space"))
        assertEquals("space", keyTagId("Space"))
        assertEquals(listOf("/", "Ctrl", "Esc", "Tab", "Left", "Down", "Up", "Right", "Space", "Backspace"),
            toggleKey(Settings.DEFAULT_KEY_BAR.split(','), "Space"))
        // A saved bar has no Space until it is picked.
        assertFalse("Space" in Settings.DEFAULT_KEY_BAR.split(','))
    }
}
