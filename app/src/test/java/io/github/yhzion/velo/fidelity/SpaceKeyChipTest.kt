package io.github.yhzion.velo.fidelity

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.ui.SettingsCategory
import io.github.yhzion.velo.ui.SettingsPanel
import io.github.yhzion.velo.ui.SettingsRoute
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Keyboard page offers Space among the Editing keys (#213); it is off until picked, and a tap puts it before Backspace. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class SpaceKeyChipTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theSpaceChipIsAnEditingKeyThatATapAddsBeforeBackspace() {
        lateinit var settings: Settings
        compose.setContent {
            val context = LocalContext.current
            val host = remember { FakeSettingsHost(context).also { settings = it.settings } }
            val layer = remember { OverlayLayer() }
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
                    SettingsPanel(host, onClose = {}, start = SettingsRoute(SettingsCategory.Keyboard))
                }
            }
        }
        compose.waitForIdle()
        val before = settings.keyBar
        assertTrue("a saved bar has no Space until it is picked", "Space" !in before)
        val chip = compose.onNodeWithTag("keyboard-chip-space").performScrollTo()
        chip.assertContentDescriptionEquals("Space").assertIsNotSelected()
        // Drawn among the Editing chips: below the Editing caption and above the Navigation one.
        val top = chip.fetchSemanticsNode().boundsInRoot.top
        val editing = compose.onNodeWithTag("keyboard-chip-group-editing").fetchSemanticsNode().boundsInRoot.bottom
        val navigation = compose.onNodeWithTag("keyboard-chip-group-navigation").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Space is under Editing ($editing ≤ $top < $navigation)", top >= editing && top < navigation)
        chip.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("keyboard-chip-space").assertIsSelected()
        // The bar's preview draws its cap, SPACE, said as Space.
        compose.onNodeWithTag("key-space", useUnmergedTree = true).assertExists()
        assertEquals(before.dropLast(1) + "Space" + "Backspace", settings.keyBar)
    }
}
