package io.github.yhzion.velo.fidelity

import android.app.Application
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.yhzion.velo.ui.AttachControl
import io.github.yhzion.velo.ui.ComposerAttachment
import io.github.yhzion.velo.ui.ComposerDraft
import io.github.yhzion.velo.ui.SettingsCategory
import io.github.yhzion.velo.ui.SettingsPanel
import io.github.yhzion.velo.ui.SettingsRoute
import io.github.yhzion.velo.ui.TapKey
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

/**
 * Controls whose description sits over visible words say the description alone to a screen
 * reader: the menu buttons (#148), and the alert rows, the key-order keys, Compose's attachment
 * and the SSH key actions (#149). Each reads the AccessibilityNodeInfo tree a service gets ([Speech]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class ReadOnceTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private lateinit var view: View

    private fun show(content: @Composable () -> Unit) = compose.setContent { view = LocalView.current; content() }

    private fun scene(name: String) = show { FidelityScenes.getValue(name)() }

    /** What the node tagged [tag] says, scrolled into view first. */
    private fun said(tag: String): List<String> {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        compose.waitForIdle()
        val id = node.fetchSemanticsNode().id
        return compose.runOnIdle { Speech.saidAt(view, id) }
    }

    /** The node tagged [tag] still takes a tap and keeps its [role] after its words are hidden. */
    private fun assertStillActs(tag: String, role: Role) {
        val node = compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))
        val info = compose.runOnIdle { Speech.node(view, node.fetchSemanticsNode().id) }
        assertTrue("$tag takes a tap", info.isClickable)
    }

    // #148: each kind of menu.

    @Test fun aTapGestureMenuSaysItsDescriptionOnce() {
        show {
            val context = LocalContext.current
            val host = remember {
                FakeSettingsHost(context).apply {
                    settings.saveTapGestures(true)
                    settings.saveDoubleTapKey(TapKey.Esc)
                    settings.saveTripleTapKey(TapKey.Enter)
                }
            }
            val layer = remember { OverlayLayer() }
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
                    SettingsPanel(host, onClose = {}, start = SettingsRoute(SettingsCategory.Keyboard))
                }
            }
        }
        assertEquals(listOf("Double tap: Escape"), said("keyboard-double-tap-menu"))
        assertEquals(listOf("Triple tap: Enter"), said("keyboard-triple-tap-menu"))
        assertStillActs("keyboard-double-tap-menu", Role.DropdownList)
    }

    @Test fun theMinimizedWindowsMenuSaysItsDescriptionOnce() {
        scene("settings-appearance")
        assertEquals(listOf("Minimized windows: Bottom Left"), said("appearance-minimized-menu"))
        assertStillActs("appearance-minimized-menu", Role.DropdownList)
    }

    @Test fun anAlertsSoundMenuSaysItsDescriptionOnce() {
        scene("settings-notif")
        assertEquals(listOf("Sound, Attention"), said("alert-needs-response-sound-menu"))
        assertEquals(listOf("Sound, Success"), said("alert-work-completed-sound-menu"))
    }

    // #149: the rows.

    @Test fun anAlertRowSaysItsDescriptionOnce() {
        scene("settings-notif")
        assertEquals(listOf("Background notification, Needs your response"), said("alert-needs-response-notification-row"))
        // The sound menu in the row is a control of its own, said apart.
        assertEquals(listOf("Activity sound, Work completed"), said("alert-work-completed-sound-row"))
        val id = compose.onNodeWithTag("alert-work-completed-notification-row").fetchSemanticsNode().id
        val info = compose.runOnIdle { Speech.node(view, id) }
        assertTrue("the row is still a switch", info.isCheckable && info.isChecked)
    }

    @Test fun aKeyInTheKeyOrderIsNamedOnce() {
        scene("settings-key-order")
        assertEquals(listOf("Escape"), said("keyboard-order-key-esc"))
        assertEquals(listOf("Control"), said("keyboard-order-key-ctrl"))
        assertEquals(listOf("Slash"), said("keyboard-order-key-slash"))
        assertEquals(listOf("Tab"), said("keyboard-order-key-tab"))
    }

    @Test fun theAttachmentSaysItsFileOnce() {
        val draft = ComposerDraft()
        show { VeloTheme(UiSize.Medium) { AttachControl(draft, enabled = true) {} } }
        compose.waitForIdle()
        assertEquals(listOf("Attach…"), compose.runOnIdle { Speech.focusableNodes(view).single().second })
        draft.attachment = ComposerAttachment("report.pdf", byteArrayOf(1))
        compose.waitForIdle()
        assertEquals(listOf("Attachment report.pdf"), compose.runOnIdle { Speech.focusableNodes(view).single().second })
    }

    @Test fun eachSshKeyActionIsSaidOnce() {
        scene("settings-ssh")
        assertEquals(listOf("Copy install command for this phone's key"), said("ssh-copy-install-command"))
        assertEquals(listOf("Copy this phone's key"), said("ssh-copy-key"))
        assertEquals(listOf("Share install command for this phone's key"), said("ssh-share-key"))
        assertEquals(listOf("Remove imported key Uf/c48n6"), said("ssh-imported-key-remove-2"))
        assertStillActs("ssh-copy-key", Role.Button)
        // The imported key's menu holds the phone key's buttons as items.
        compose.onNodeWithTag("ssh-imported-key-name-2", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        val items = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        for (item in listOf("Copy imported key Uf/c48n6", "Copy install command for imported key Uf/c48n6", "Share install command for imported key Uf/c48n6"))
            assertTrue("$item said once, among $items", listOf(item) in items)
    }
}
