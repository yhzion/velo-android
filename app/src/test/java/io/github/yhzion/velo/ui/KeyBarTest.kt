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
        // WsOpenPanes, in dp: 32 for a key, CTRL 47; 548 inside the bar, 5 between two caps.
        val need = listOf(32, 47, 32, 32, 32, 32, 32, 32, 32)
        val caps = keyCaps(need, floor = 32, gap = 5, available = 548).widths
        // 508 for nine caps: 56 each, and the 4 left over to the first four.
        assertEquals(listOf(57, 57, 57, 57, 56, 56, 56, 56, 56), caps)
        assertEquals(548, caps.sum() + 5 * 8)
        // One key takes the whole bar.
        assertEquals(listOf(300), keyCaps(listOf(40), floor = 32, gap = 5, available = 300).widths)
        assertTrue(keyCaps(emptyList(), floor = 32, gap = 5, available = 100).widths.isEmpty())
    }

    @Test fun capsThatExactlyFitTheWidestLabelStillShareTheBar() {
        // Three caps of CTRL's 47 and two gaps of 5: 151.
        assertEquals(listOf(47, 47, 47), keyCaps(listOf(32, 47, 32), floor = 32, gap = 5, available = 151).widths)
        // Without a label wider than the least, the least decides.
        assertEquals(listOf(32, 32), keyCaps(listOf(20, 32), floor = 32, gap = 5, available = 69).widths)
    }

    @Test fun aBarTooNarrowForTheWidestLabelScrollsWithEveryCapAtThatLabel() {
        // One pixel short of three CTRLs: every cap at CTRL's width, wider together than the bar.
        val caps = keyCaps(listOf(32, 47, 32), floor = 32, gap = 5, available = 150).widths
        assertEquals(listOf(47, 47, 47), caps)
        assertTrue(caps.sum() + 5 * 2 > 150)
        // A need under the least still takes the least once the bar scrolls.
        assertEquals(listOf(32, 32), keyCaps(listOf(20, 20), floor = 32, gap = 5, available = 44).widths)
    }

    @Test fun aLabelGivesUpItsPaddingBeforeTheBarScrolls() {
        // The folded Fold8: CTRL needs 39 with 4 on each side; nine caps get 37 each, and CTRL keeps its 31.
        val need = listOf(32, 39, 32, 32, 32, 32, 32, 32, 32)
        assertEquals(List(9) { 37 }, keyCaps(need, floor = 32, gap = 5, available = 9 * 37 + 8 * 5, give = 8).widths)
        // Short of nine labels without the padding: every cap at CTRL's whole need, and the bar scrolls.
        assertEquals(List(9) { 39 }, keyCaps(need, floor = 32, gap = 5, available = 9 * 31 + 8 * 5 - 1, give = 8).widths)
        // Without the padding to give, the same bar scrolls.
        assertEquals(List(9) { 39 }, keyCaps(need, floor = 32, gap = 5, available = 9 * 37 + 8 * 5).widths)
    }

    @Test fun aNarrowBarNarrowsTheGapBeforeTheCapsGoBelowTheLeast() {
        // CTRL's label is 31 without its padding: nine caps keep the least 32 at a gap of 3.
        val need = listOf(32, 39, 32, 32, 32, 32, 32, 32, 32)
        val caps = keyCaps(need, floor = 32, gap = 5, available = 9 * 32 + 8 * 3, give = 8, leastGap = 2)
        assertEquals(KeyCaps(List(9) { 32 }, 3), caps)
        // Room between two gaps: the widest gap that fits, and the pixels it leaves to the first caps.
        assertEquals(KeyCaps(List(4) { 33 } + List(5) { 32 }, 3), keyCaps(need, floor = 32, gap = 5, available = 9 * 32 + 8 * 3 + 4, give = 8, leastGap = 2))
        // With room for the whole gap, the gap stays.
        assertEquals(5, keyCaps(need, floor = 32, gap = 5, available = 9 * 32 + 8 * 5, give = 8, leastGap = 2).gap)
    }

    @Test fun atTheLeastGapTheCapsGoBelowTheLeastDownToTheWidestLabel() {
        val need = listOf(32, 39, 32, 32, 32, 32, 32, 32, 32)
        // The bar phone: no room for nine caps of 32 at a gap of 2, so they share the bar at 31 each.
        assertEquals(KeyCaps(List(9) { 31 }, 2), keyCaps(need, floor = 32, gap = 5, available = 9 * 31 + 8 * 2, give = 8, leastGap = 2))
        val between = keyCaps(need, floor = 32, gap = 5, available = 9 * 32 + 8 * 2 - 1, give = 8, leastGap = 2)
        assertEquals(2, between.gap)
        assertEquals(9 * 32 + 8 * 2 - 1, between.widths.sum() + 8 * 2)
        assertTrue(between.widths.all { it in 31..32 })
    }

    @Test fun onlyABarTooNarrowForTheLabelsAtTheLeastGapScrolls() {
        val need = listOf(32, 39, 32, 32, 32, 32, 32, 32, 32)
        // A pixel short of nine labels of 31 at the least gap: every cap takes CTRL's whole need, at the whole gap.
        assertEquals(KeyCaps(List(9) { 39 }, 5), keyCaps(need, floor = 32, gap = 5, available = 9 * 31 + 8 * 2 - 1, give = 8, leastGap = 2))
        // One key has no gap to give up and takes the whole bar while its label fits.
        assertEquals(listOf(31), keyCaps(listOf(39), floor = 32, gap = 5, available = 31, give = 8, leastGap = 2).widths)
        assertEquals(listOf(39), keyCaps(listOf(39), floor = 32, gap = 5, available = 30, give = 8, leastGap = 2).widths)
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
