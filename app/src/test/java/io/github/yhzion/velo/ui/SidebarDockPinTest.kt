package io.github.yhzion.velo.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sidebar's pin (sidebar-dock): drawn only where the window can dock the sidebar (the unfolded
 * Fold8 upright), an on/off switch that docks it beside the panes or undocks it, as the palette does.
 * The scene wires the pin the way the workspace does: [sidebarFit] for the window, [dockSwitch] for
 * the pin and [toggleSidebarDock] for its tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SidebarDockPinTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val settings = Settings(RuntimeEnvironment.getApplication().getSharedPreferences("dock-pin", Context.MODE_PRIVATE))
    private var open by mutableStateOf(true)
    private var size by mutableStateOf(UiSize.Medium)

    private fun show() {
        compose.setContent {
            VeloTheme(size = size) {
                val gap = VeloTheme.sizes.gap
                val fit = sidebarFit(settings.sidebarDocked, gap, gap)
                Row(Modifier.fillMaxSize()) {
                    if (open) Sidebar(DeviceRow("velo-phone", "Running", "Running"), emptyList(), sidebarSlot(fit.place),
                        actions { open = toggleSidebarDock(settings.sidebarDocked, settings::saveSidebarDocked) },
                        docked = dockSwitch(fit.dockable, settings.sidebarDocked))
                    if (!open || fit.place == SidebarPlace.Beside) Box(Modifier.testTag("panes").weight(1f).fillMaxHeight())
                }
            }
        }
    }

    @Config(qualifiers = "w393dp-h894dp-420dpi")
    @Test fun theBarPhoneHasNoPin() {
        show()
        compose.onNodeWithTag("sidebar-toggle").assertExists()
        compose.onNodeWithTag("sidebar-dock").assertDoesNotExist()
    }

    @Config(qualifiers = "w475dp-h751dp-420dpi")
    @Test fun theFoldedScreenHasNoPin() {
        show()
        compose.onNodeWithTag("sidebar-toggle").assertExists()
        compose.onNodeWithTag("sidebar-dock").assertDoesNotExist()
    }

    @Config(qualifiers = "w751dp-h475dp-420dpi")
    @Test fun theFoldedScreenSidewaysHasNoPin() {
        show()
        compose.onNodeWithTag("sidebar-dock").assertDoesNotExist()
    }

    @Config(qualifiers = "w1088dp-h821dp-420dpi")
    @Test fun theUnfoldedScreenSidewaysHasNoPin() {
        // The sidebar sits beside the panes there anyway: nothing to choose.
        show()
        compose.onNodeWithTag("sidebar-dock").assertDoesNotExist()
    }

    @Config(qualifiers = "w821dp-h1088dp-420dpi")
    @Test fun theUnfoldedScreenUprightHasThePinBesideTheToggle() {
        show()
        val toggle = compose.onNodeWithTag("sidebar-toggle").getBoundsInRoot()
        val pin = compose.onNodeWithTag("sidebar-dock").getBoundsInRoot()
        // As large as the toggle, at least the touch target, on its right in the same row.
        assertEquals(toggle.right - toggle.left, pin.right - pin.left)
        assertEquals(toggle.bottom - toggle.top, pin.bottom - pin.top)
        assertTrue(pin.right - pin.left >= 48.dp)
        assertEquals(toggle.top, pin.top)
        assertTrue(pin.left > toggle.right)
    }

    @Config(qualifiers = "w821dp-h1088dp-420dpi")
    @Test fun thePinIsASwitchNamedDockSidebar() {
        show()
        compose.onNodeWithTag("sidebar-dock")
            .assertContentDescriptionEquals("Dock Sidebar")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
    }

    @Config(qualifiers = "w821dp-h1088dp-420dpi")
    @Test fun aTapDocksTheSidebarBesideThePanesAndAnotherUndocksAndClosesIt() {
        show()
        // Undocked, the open sidebar covers the panes.
        compose.onNodeWithTag("panes").assertDoesNotExist()

        compose.onNodeWithTag("sidebar-dock").performClick()
        assertTrue(settings.sidebarDocked)
        assertTrue(open)
        compose.onNodeWithTag("panes").assertExists()
        compose.onNodeWithTag("sidebar-dock").assertIsOn()
        // The choice is kept: a new start reads it.
        assertTrue(Settings(RuntimeEnvironment.getApplication().getSharedPreferences("dock-pin", Context.MODE_PRIVATE)).sidebarDocked)

        compose.onNodeWithTag("sidebar-dock").performClick()
        assertFalse(settings.sidebarDocked)
        assertFalse(open)
        compose.onNodeWithTag("sidebar").assertDoesNotExist()
        compose.onNodeWithTag("panes").assertExists()

        // Opened again, it covers the panes and the pin is off.
        open = true
        compose.onNodeWithTag("panes").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-dock").assertIsOff()
    }

    @Config(qualifiers = "w821dp-h1088dp-420dpi")
    @Test fun theToolbarHoldsThePinAtEverySize() {
        show()
        for (each in UiSize.entries) {
            size = each
            compose.waitForIdle()
            val pin = compose.onNodeWithTag("sidebar-dock").getBoundsInRoot()
            val actions = compose.onNodeWithTag("sidebar-actions").getBoundsInRoot()
            val toolbar = compose.onNodeWithTag("sidebar-toolbar").getBoundsInRoot()
            // The capsule keeps its three controls and stays clear of the pin, inside the toolbar.
            assertTrue("$each", actions.right - actions.left >= 48.dp * 3)
            assertTrue("$each", pin.right <= actions.left)
            assertTrue("$each", actions.right <= toolbar.right)
        }
    }

    private fun actions(toggleDock: () -> Unit) = SidebarActions(
        toggleSidebar = { open = false }, openSettings = {}, openPalette = {}, openDevice = {},
        selectRemote = {}, newSession = {}, renameRemote = {}, removeRemote = {}, sshDetails = {}, addRemote = {},
        found = emptyList(), addFound = {},
        selectSession = { _, _ -> }, newTab = { _, _ -> }, renameSession = { _, _ -> }, closeSession = { _, _ -> },
        selectTab = { _, _ -> }, renameTab = { _, _ -> }, moveTab = { _, _, _ -> }, closeTab = { _, _ -> },
        toggleDock = toggleDock,
    )
}
