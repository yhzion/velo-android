package io.github.yhzion.velo.ui

import android.view.KeyEvent
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareKeyboardTest {
    @Test fun paneMenuOpensOnReleaseWheneverTheWorkspaceOffersIt() {
        val shortcut = PaneMenuShortcut()
        var opens = 0
        val open: () -> Unit = { opens++ }

        assertTrue(shortcut.dispatch(1, 10, KeyEvent.ACTION_DOWN, 0, true, false, open))
        assertTrue(shortcut.dispatch(1, 10, KeyEvent.ACTION_DOWN, 1, true, false, open))
        assertEquals(0, opens)
        // Ctrl and Shift may already be up when F10 is released.
        assertTrue(shortcut.dispatch(1, 10, KeyEvent.ACTION_UP, 0, false, false, open))
        assertEquals(1, opens)
        assertFalse(shortcut.dispatch(1, 10, KeyEvent.ACTION_UP, 0, false, false, open))
    }

    @Test fun paneMenuRespectsWorkspaceAvailabilityAndCancellation() {
        val shortcut = PaneMenuShortcut()
        val open: () -> Unit = { error("Pane menu should stay closed") }

        assertFalse(shortcut.dispatch(1, 10, KeyEvent.ACTION_DOWN, 0, true, false, null))
        assertFalse(shortcut.dispatch(1, 20, KeyEvent.ACTION_DOWN, 0, false, false, open))
        assertTrue(shortcut.dispatch(1, 30, KeyEvent.ACTION_DOWN, 0, true, false, open))
        assertTrue(shortcut.dispatch(1, 30, KeyEvent.ACTION_UP, 0, true, false, null))
        assertTrue(shortcut.dispatch(1, 40, KeyEvent.ACTION_DOWN, 0, true, false, open))
        assertTrue(shortcut.dispatch(1, 40, KeyEvent.ACTION_UP, 0, true, true, open))
    }

    @Test fun physicalShiftLeavesScreenCtrlAndAltArmedUntilTheLetter() {
        for (sticky in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_ALT_ON)) {
            var armed = sticky
            val consume: () -> Unit = { armed = 0 }

            withHardwareStickyMeta(armed, isModifier = true, consume) { meta ->
                assertEquals(0, meta)
            }
            assertEquals(sticky, armed)
            withHardwareStickyMeta(armed, isModifier = false, consume) { meta ->
                assertEquals(sticky, meta)
                assertEquals(sticky, armed)
            }
            assertEquals(0, armed)
            withHardwareStickyMeta(armed, isModifier = false, consume) { meta ->
                assertEquals(0, meta)
            }
        }
    }

    private fun leaf(block: String) = LayoutNode(block = block)
    private val row = WindowInfo("w1", layout = LayoutNode(dir = "row", first = leaf("a"), second = leaf("b")))

    private fun chord(
        window: WindowInfo? = row,
        focused: String? = "a",
        direction: PaneDirection = PaneDirection.Right,
        accepting: Boolean = true,
        overlayOpen: Boolean = false,
        connected: Boolean = true,
        rtl: Boolean = false,
        busy: Boolean = false,
    ) = movePaneChord(accepting, overlayOpen, connected, window, focused, direction, rtl, busy)

    @Test fun movePaneDirectionMapsTheFourArrows() {
        assertEquals(PaneDirection.Left, movePaneDirection(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PaneDirection.Right, movePaneDirection(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PaneDirection.Up, movePaneDirection(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PaneDirection.Down, movePaneDirection(KeyEvent.KEYCODE_DPAD_DOWN))
        assertNull(movePaneDirection(KeyEvent.KEYCODE_A))
        assertNull(movePaneDirection(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun theChordModifiersAreTheDocumentedOnes() {
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, PaneShortcutModifiers)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON, MovePaneModifiers)
    }

    @Test fun onlyCtrlAltShiftMakesTheMoveChord() {
        val ctrl = KeyEvent.META_CTRL_ON
        val alt = KeyEvent.META_ALT_ON
        val shift = KeyEvent.META_SHIFT_ON
        assertTrue(metaStateIs(MovePaneModifiers, MovePaneModifiers))
        for (wrong in listOf(0, shift, ctrl, alt, ctrl or shift, ctrl or alt, alt or shift)) {
            assertFalse("meta $wrong", metaStateIs(wrong, MovePaneModifiers))
        }
        // Ctrl+Shift, the other shortcuts' chord, is not the move chord, and the reverse.
        assertFalse(metaStateIs(PaneShortcutModifiers, MovePaneModifiers))
        assertFalse(metaStateIs(MovePaneModifiers, PaneShortcutModifiers))
        assertTrue(metaStateIs(PaneShortcutModifiers, PaneShortcutModifiers))
    }

    @Test fun theChordsFollowTheFrameworkRule() {
        for (chord in listOf(PaneShortcutModifiers, MovePaneModifiers)) {
            // A fourth modifier, Sym or Fn is another chord: the key goes to the terminal.
            assertFalse(metaStateIs(chord or KeyEvent.META_META_ON, chord))
            assertFalse(metaStateIs(chord or KeyEvent.META_SYM_ON, chord))
            assertFalse(metaStateIs(chord or KeyEvent.META_FUNCTION_ON, chord))
            // Lock keys do not change the chord.
            for (lock in listOf(KeyEvent.META_CAPS_LOCK_ON, KeyEvent.META_NUM_LOCK_ON, KeyEvent.META_SCROLL_LOCK_ON)) {
                assertTrue(metaStateIs(chord or lock, chord))
            }
            // A keyboard that reports a side bit of each modifier of the chord, with the generic flag.
            val left = (if (chord and KeyEvent.META_CTRL_ON != 0) KeyEvent.META_CTRL_LEFT_ON else 0) or
                (if (chord and KeyEvent.META_ALT_ON != 0) KeyEvent.META_ALT_LEFT_ON else 0) or
                (if (chord and KeyEvent.META_SHIFT_ON != 0) KeyEvent.META_SHIFT_RIGHT_ON else 0)
            assertTrue(metaStateIs(chord or left, chord))
        }
        val sides = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertTrue(metaStateIs(sides, MovePaneModifiers))
        assertFalse(metaStateIs(KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON, MovePaneModifiers))
        assertTrue(metaStateIs(KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON, PaneShortcutModifiers))
    }

    @Test fun theMoveChordIsOfferedOnlyWhileTheMoveCanHappen() {
        assertTrue(chord())
        assertTrue(chord(focused = "b", direction = PaneDirection.Left))
    }

    @Test fun theMoveChordIsNotOfferedAtALayoutEdge() {
        assertFalse(chord(direction = PaneDirection.Left))
        assertFalse(chord(direction = PaneDirection.Up))
        assertFalse(chord(direction = PaneDirection.Down))
        assertFalse(chord(focused = "b", direction = PaneDirection.Right))
        val lone = WindowInfo("w1", layout = leaf("a"))
        for (d in PaneDirection.entries) assertFalse(chord(window = lone, direction = d))
    }

    @Test fun theMoveChordFollowsTheDrawnDirectionInRtl() {
        assertFalse(chord(direction = PaneDirection.Right, rtl = true))
        assertTrue(chord(direction = PaneDirection.Left, rtl = true))
    }

    @Test fun theMoveChordIsNotOfferedWhileTheWorkspaceCannotTakeIt() {
        assertFalse(chord(accepting = false))
        assertFalse(chord(overlayOpen = true))
        assertFalse(chord(connected = false))
        assertFalse(chord(window = null))
        assertFalse(chord(focused = null))
        assertFalse(chord(focused = "missing"))
    }

    @Test fun theMoveChordIsNotOfferedForAZoomedOrFloatingPane() {
        val zoomed = row.copy(zoomed = "a")
        assertFalse(chord(window = zoomed))
        val floating = row.copy(floating = listOf(FloatingLayer("f")))
        assertFalse(chord(window = floating, focused = "f"))
        assertFalse(chord(window = floating, focused = "f", direction = PaneDirection.Left))
    }

    @Test fun anArrowThatHasNoMoveReachesTheTerminal() {
        val shortcut = PaneMenuShortcut()
        // No action is offered: the arrow down is not claimed, and neither is its key up.
        assertFalse(shortcut.dispatch(1, 10, KeyEvent.ACTION_DOWN, 0, true, false, null))
        assertFalse(shortcut.dispatch(1, 10, KeyEvent.ACTION_UP, 0, true, false, null))
    }

    @Test fun aMoveRunsOnTheKeyUpAndIsNotClaimedWithoutTheChord() {
        val shortcut = PaneMenuShortcut()
        var moves = 0
        val move: () -> Unit = { moves++ }
        assertFalse(shortcut.dispatch(1, 5, KeyEvent.ACTION_DOWN, 0, false, false, move))
        assertTrue(shortcut.dispatch(1, 10, KeyEvent.ACTION_DOWN, 0, true, false, move))
        assertEquals(0, moves)
        assertTrue(shortcut.dispatch(1, 10, KeyEvent.ACTION_UP, 0, true, false, move))
        assertEquals(1, moves)
    }

    @Test fun theShortcutKnowsTheKeyItHolds() {
        val shortcut = PaneMenuShortcut()
        assertFalse(shortcut.holds(1, 10))
        assertTrue(shortcut.dispatch(1, 10, KeyEvent.ACTION_DOWN, 0, true, false, {}))
        assertTrue(shortcut.holds(1, 10))
        assertFalse(shortcut.holds(1, 11))
        assertFalse(shortcut.holds(2, 10))
        shortcut.dispatch(1, 10, KeyEvent.ACTION_UP, 0, true, false, {})
        assertFalse(shortcut.holds(1, 10))
    }
}
