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

    @Test fun keysKeepTheirOwnWidthWhileTheyFit() {
        // WsOpenPanes, in dp: 32 for a key, CTRL 47; 548 inside the bar.
        val natural = listOf(32, 47, 32, 32, 32, 32, 32, 32, 32)
        assertEquals(natural, keyCapWidths(natural, floor = 32, available = 548))
        assertEquals(natural, keyCapWidths(natural, floor = 32, available = natural.sum()))
    }

    @Test fun aKeyWiderThanTheLeastGivesUpRoomBeforeTheBarScrolls() {
        // WsBarTerminal: 297 inside the bar; CTRL alone is wider than 32 and gives up the 6 that are missing.
        val natural = listOf(32, 47, 32, 32, 32, 32, 32, 32, 32)
        assertEquals(listOf(32, 41, 32, 32, 32, 32, 32, 32, 32), keyCapWidths(natural, floor = 32, available = 297))
        // Two wide keys share the shortage by their widths, never below the least.
        assertEquals(listOf(32, 58, 42), keyCapWidths(listOf(32, 70, 50), floor = 32, available = 132))
        assertEquals(listOf(32, 32, 32), keyCapWidths(listOf(32, 70, 50), floor = 32, available = 96))
        // Fewer than the least for every key: the bar scrolls.
        assertNull(keyCapWidths(listOf(32, 70, 50), floor = 32, available = 95))
        assertTrue(keyCapWidths(emptyList(), floor = 32, available = 100)!!.isEmpty())
    }

    @Test fun theRoomLeftGoesBetweenTheKeysAndEveryKeyTakesTheTapsUpToHalfway() {
        // WsOpenPanes: nine keys, 251 left over: 31.375 between two keys. The first and the last key
        // reach the bar's ends; each of the others half of the room on each side.
        val caps = listOf(32, 47, 32, 32, 32, 32, 32, 32, 32)
        val cells = keyCells(caps, 548)
        assertEquals(548, cells.sum())
        val gap = (548 - caps.sum()) / 8f
        assertEquals(caps.first() + gap / 2, cells.first().toFloat(), 1f)
        assertEquals(caps.last() + gap / 2, cells.last().toFloat(), 1f)
        for (i in 1 until caps.lastIndex) assertEquals("$i", caps[i] + gap, cells[i].toFloat(), 1f)
        // No room left: the cells are the keys.
        assertEquals(caps, keyCells(caps, caps.sum()))
        // One key takes the bar.
        assertEquals(listOf(300), keyCells(listOf(40), 300))
        assertTrue(keyCells(emptyList(), 300).isEmpty())
    }

    @Test fun eachKeyIsNamedForTestsAsTheBoardNamesIt() {
        assertEquals(listOf("key-slash", "key-ctrl", "key-esc", "key-tab", "key-left", "key-down", "key-up", "key-right", "key-backspace"),
            Settings.DEFAULT_KEY_BAR.split(',').map(::keyTestTag))
    }
}
