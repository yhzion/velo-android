package io.github.yhzion.velo

import io.github.yhzion.velo.HardwareKeyRoute.Forward
import io.github.yhzion.velo.HardwareKeyRoute.PassOn
import io.github.yhzion.velo.HardwareKeyRoute.Swallow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where [MainActivity.dispatchKeyEvent] sends a key of the activity's window. */
class HardwareKeysTest {

    private fun route(
        fromHardware: Boolean = true, systemKey: Boolean = false, terminalOwnsFocus: Boolean = true,
        panesPaused: Boolean = false, otherEditorFocused: Boolean = false,
    ) = hardwareKeyRoute(fromHardware, systemKey, terminalOwnsFocus, panesPaused, otherEditorFocused)

    @Test
    fun aKeyOfAHardwareKeyboardGoesToThePaneOnScreen() {
        // The workspace draws its panes and its terminal input has the focus: Tab, the arrows,
        // Enter and Escape are the terminal's.
        assertEquals(Forward, route())
    }

    @Test
    fun theSoftKeyboardAndOtherVirtualDevicesAreLeftToTheFocusedView() {
        // The soft keyboard types through the input view's own connection; these keys take the
        // window's normal path to the focused view.
        assertEquals(PassOn, route(fromHardware = false))
        assertEquals(PassOn, route(fromHardware = false, terminalOwnsFocus = false, panesPaused = true))
    }

    @Test
    fun aSystemKeyReachesTheSystem() {
        // Back, Home, the volume keys, mute, power and the media keys have no bytes for the terminal.
        assertEquals(PassOn, route(systemKey = true))
        assertEquals(PassOn, route(systemKey = true, terminalOwnsFocus = false, panesPaused = true))
    }

    @Test
    fun withoutTheTerminalsFocusNoKeyIsTakenFromTheScreenShown() {
        // Settings, the Tailscale screen, pairing and the start screen draw no pane; nor does
        // the workspace while the sidebar covers its panes, and a dialog or a menu takes the
        // keys itself. Enter there is not the shell's.
        assertEquals(PassOn, route(terminalOwnsFocus = false))
    }

    @Test
    fun aTextFieldOfTheWindowWithTheFocusGetsTheKeys() {
        // The sidebar's Filter: what is typed there is a search, not a command, also while the
        // panes beside the sidebar are paused.
        assertEquals(PassOn, route(otherEditorFocused = true))
        assertEquals(PassOn, route(terminalOwnsFocus = false, panesPaused = true, otherEditorFocused = true))
    }

    @Test
    fun aKeyForPanesWhoseConnectionIsDownGoesNowhere() {
        // Neither to the terminal nor to the window: Tab and the arrows would move the focus off the
        // terminal input, and Enter would press the control that got it. The panes then need no tap
        // when the connection is back.
        assertEquals(Swallow, route(terminalOwnsFocus = false, panesPaused = true))
    }

    @Test
    fun everyCombinationHasOneRoute() {
        val forward = mutableListOf<List<Boolean>>()
        val swallow = mutableListOf<List<Boolean>>()
        val flags = listOf(false, true)
        for (fromHardware in flags) for (systemKey in flags) for (terminalOwnsFocus in flags)
            for (panesPaused in flags) for (otherEditorFocused in flags) {
                val row = listOf(fromHardware, systemKey, terminalOwnsFocus, panesPaused, otherEditorFocused)
                when (hardwareKeyRoute(fromHardware, systemKey, terminalOwnsFocus, panesPaused, otherEditorFocused)) {
                    Forward -> forward += row
                    Swallow -> swallow += row
                    PassOn -> {}
                }
            }
        assertEquals(listOf(listOf(true, false, true, false, false)), forward)
        // Paused panes take nothing, also if the terminal owned the focus; the second row cannot
        // occur, since a workspace whose connection is down accepts no input.
        assertEquals(listOf(listOf(true, false, false, true, false), listOf(true, false, true, true, false)), swallow)
    }
}
