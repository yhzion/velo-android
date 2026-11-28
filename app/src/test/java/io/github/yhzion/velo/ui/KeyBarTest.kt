package io.github.yhzion.velo.ui

import android.view.KeyEvent
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
            "-" to "Minus", "|" to "Pipe", "/" to "Slash", "~" to "Tilde", "BackTab" to "Shift Tab",
        )) assertEquals(key, said, spokenKeyName(key))
        for (key in listOf("Tab", "Enter", "Alt", "Shift", "Left", "Down", "Up", "Right", "Home", "End", "Backspace", "Delete", "Paste"))
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
        assertEquals(listOf("key-backtab", "key-delete", "key-pgup", "key-pgdn", "key-paste"),
            listOf("BackTab", "Delete", "PgUp", "PgDn", "Paste").map(::keyTestTag))
    }

    // #219: Shift+Tab, Delete and Paste.

    @Test fun shiftTabIsTabWithShiftAndDeleteIsForwardDelete() {
        assertEquals(KeyEvent.KEYCODE_TAB, KEY_CODES["BackTab"])
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, KEY_CODES["Delete"])
        // Paste sends no key: the clipboard goes instead.
        assertNull(KEY_CODES[PasteKey])
        assertEquals("Paste", PasteKey)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(shift, keyMeta("BackTab"))
        for (key in KEY_CODES.keys - "BackTab") assertEquals(key, 0, keyMeta(key))
    }

    @Test fun anArmedCtrlOrAltJoinsShiftTabAndAnArmedShiftChangesNothing() {
        // What the workspace sends: the key's own meta, with the armed modifiers added ([TerminalInputView.sendKey]).
        fun sent(armed: Set<String>) = keyMeta("BackTab") or stickyMeta(armed)
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val alt = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        assertEquals(shift, sent(emptySet()))
        assertEquals(shift, sent(setOf("Shift")))
        assertEquals(shift or ctrl, sent(setOf("Ctrl")))
        assertEquals(shift or alt, sent(setOf("Alt")))
        assertEquals(shift or ctrl or alt, sent(setOf("Ctrl", "Alt", "Shift")))
    }

    @Test fun theNewKeysAreEditingAndPagesStayNavigation() {
        for (key in listOf("BackTab", "Delete", "Paste")) assertEquals(key, KeyGroup.Editing, keyGroup(key))
        for (key in listOf("PgUp", "PgDn")) assertEquals(key, KeyGroup.Navigation, keyGroup(key))
    }

    @Test fun deletionRepeatsAndShiftTabPasteAndPagesDoNot() {
        assertTrue("Delete" in REPEAT_KEYS)
        assertTrue("Backspace" in REPEAT_KEYS)
        for (key in listOf("BackTab", "Paste", "PgUp", "PgDn", "Tab", "Enter")) assertFalse(key, key in REPEAT_KEYS)
    }

    @Test fun theNewKeysAreOfferedButNotOnANewBar() {
        for (key in listOf("BackTab", "Delete", "Paste", "PgUp", "PgDn")) assertTrue(key, key in Settings.ALL_KEYS)
        assertEquals("/,Ctrl,Esc,Tab,Left,Down,Up,Right,Backspace", Settings.DEFAULT_KEY_BAR)
        // A bar saved before stays as it was: none of them is added.
        assertEquals(listOf("Ctrl", "Esc"), readKeyOrder("Ctrl,Esc", ordered = true))
    }

    @Test fun aPickedNewKeyGoesBeforeABackspaceAtTheEnd() {
        val bar = Settings.DEFAULT_KEY_BAR.split(',')
        for (key in listOf("BackTab", "Delete", "Paste")) {
            val picked = toggleKey(bar, key)
            assertEquals(key, bar.dropLast(1) + key + "Backspace", picked)
            assertEquals(key, bar, toggleKey(picked, key))
        }
        // Without Backspace at the end, the key goes last.
        assertEquals(listOf("Up", "Delete"), toggleKey(listOf("Up"), "Delete"))
        assertEquals(listOf("Up", "Backspace", "Esc", "Paste"), toggleKey(listOf("Up", "Backspace", "Esc"), "Paste"))
    }
}
