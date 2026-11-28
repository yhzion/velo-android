package io.github.yhzion.velo.fidelity

import android.app.Application
import android.view.View
import androidx.compose.foundation.background
import io.github.yhzion.velo.ui.theme.VeloSegmented
import io.github.yhzion.velo.ui.PaneHighlightSettings
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.AttachControl
import io.github.yhzion.velo.ui.ComposerDialog
import io.github.yhzion.velo.ui.ComposerTarget
import io.github.yhzion.velo.ui.DetailLine
import io.github.yhzion.velo.ui.DetailRow
import io.github.yhzion.velo.ui.NEEDS_ANSWER_NOTE
import io.github.yhzion.velo.ui.PASSWORD_SAVED
import io.github.yhzion.velo.ui.SshDetail
import io.github.yhzion.velo.ui.SshDetailRow
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
import org.junit.Assert.assertFalse
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
 * and the SSH key actions (#149), SSH details' Forget, the sidebar's tab rows (#150), and Compose's
 * draft (#174), the slider rows (#200) and the remote rows' connection (#201). The radio buttons of a segmented
 * control and the highlight swatches carry their names themselves (#203), and the swatches say their places in order
 * (#204). Each reads the AccessibilityNodeInfo tree a service gets ([Speech]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi",
    shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
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

    /** The node tagged [tag] still takes a tap and keeps its [role] (if it has one) after its words are hidden. */
    private fun assertStillActs(tag: String, role: Role?) {
        val node = compose.onNodeWithTag(tag).let { if (role == null) it else it.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role)) }
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

    @Test fun eachOptionOfATapGestureMenuIsSaidOnce() {
        show {
            val context = LocalContext.current
            val host = remember {
                FakeSettingsHost(context).apply {
                    settings.saveTapGestures(true)
                    settings.saveDoubleTapKey(TapKey.Esc)
                }
            }
            val layer = remember { OverlayLayer() }
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
                    SettingsPanel(host, onClose = {}, start = SettingsRoute(SettingsCategory.Keyboard))
                }
            }
        }
        compose.onNodeWithTag("keyboard-double-tap-menu").performScrollTo().performClick()
        compose.waitForIdle()
        val options = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        // The drawn "Esc" is said as Escape, and never beside it; each option says its state too (#167). The key bar
        // also says Escape and Tab, so no option may say its words twice.
        for (option in listOf(listOf("Selected", "Escape"), listOf("Not selected", "Tab"), listOf("Not selected", "None")))
            assertTrue("$option said once, among $options", option in options)
        assertFalse("No option says a thing twice, among $options", options.any(Speech::saysTwice))
    }

    @Test fun theMinimizedWindowsMenuSaysItsDescriptionOnce() {
        scene("settings-appearance")
        assertEquals(listOf("Minimized windows: Bottom Left"), said("appearance-minimized-menu"))
        assertStillActs("appearance-minimized-menu", Role.DropdownList)
    }

    @Test fun anAlertsSoundMenuSaysItsDescriptionOnce() {
        scene("settings-notif")
        assertEquals(listOf("Sound: Attention"), said("alert-needs-response-sound-menu"))
        assertEquals(listOf("Sound: Success"), said("alert-work-completed-sound-menu"))
    }

    // #151: every menu says "<label>: <value>", as the keyboard's and Minimized windows' do.

    @Test fun theUpdatesChannelMenuSaysItsDescriptionOnce() {
        scene("settings-updates")
        assertEquals(listOf("Channel: Stable"), said("updates-channel-menu"))
        assertStillActs("updates-channel-menu", Role.DropdownList)
    }

    // #197: a row whose menu button says "<label>: <value>" says its label once: the label beside the button is no stop.

    @Test fun theChannelRowSaysItsLabelOnce() {
        scene("settings-updates")
        assertMenuRowSaysItsLabelOnce("updates-channel", "Channel: Stable", "Stable")
    }

    @Test fun theMinimizedWindowsRowSaysItsLabelOnce() {
        scene("settings-appearance")
        assertMenuRowSaysItsLabelOnce("appearance-minimized", "Minimized windows: Bottom Left", "Bottom Left")
    }

    @Test fun theDoubleTapRowSaysItsLabelOnceAndItsMenuOpens() {
        showTapGestureRows()
        assertMenuRowSaysItsLabelOnce("keyboard-double-tap", "Double tap: Tab", "Tab")
    }

    @Test fun theTripleTapRowSaysItsLabelOnceAndItsMenuOpens() {
        showTapGestureRows()
        // Each row in a test of its own: the first menu opened would stay open over the other row.
        assertMenuRowSaysItsLabelOnce("keyboard-triple-tap", "Triple tap: Enter", "Enter")
    }

    /** Keyboard settings with tap gestures on: double tap sends Tab, triple tap Enter. */
    private fun showTapGestureRows() {
        show {
            val context = LocalContext.current
            val host = remember {
                FakeSettingsHost(context).apply {
                    settings.saveTapGestures(true)
                    settings.saveDoubleTapKey(TapKey.Tab)
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
    }

    /** What each focus stop of the row tagged `<tag>-row` says, in order. */
    private fun rowStops(tag: String): List<List<String>> {
        val node = compose.onNodeWithTag("$tag-row").performScrollTo()
        compose.waitForIdle()
        val id = node.fetchSemanticsNode().id
        return compose.runOnIdle { Speech.focusableNodes(view, id).map { it.second } }
    }

    /**
     * The row tagged `<tag>-row` has one stop, its menu button, which says [said] ("<label>: <value>"): the label is
     * heard once. The button is still a dropdown that a tap opens, on a menu where [value] is the selected choice.
     */
    private fun assertMenuRowSaysItsLabelOnce(tag: String, said: String, value: String) {
        assertEquals(listOf(listOf(said)), rowStops(tag))
        assertStillActs("$tag-menu", Role.DropdownList)
        compose.onNodeWithTag("$tag-menu").performClick()
        compose.waitForIdle()
        val stops = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        assertTrue("$value selected in the open menu, among $stops", listOf("Selected", value) in stops)
    }

    // #204: the highlight colour's swatches, four to a row, say their places in reading order.

    @Test fun theHighlightSwatchesSayTheirPlacesInOrder() {
        scene("settings-appearance")
        val swatches = listOf("Accent", "Blue", "Green", "Yellow", "Cyan", "Magenta", "Text", "Custom")
        val group = compose.onNodeWithTag("appearance-highlight-swatches").performScrollTo().fetchSemanticsNode().id
        val collection = compose.runOnIdle { Speech.node(view, group).collectionInfo }
        assertEquals("one list of the swatches", listOf(1, swatches.size), listOf(collection.rowCount, collection.columnCount))
        for ((place, name) in swatches.withIndex()) {
            val tag = "appearance-highlight-${name.lowercase()}"
            val id = compose.onNodeWithTag(tag).fetchSemanticsNode().id
            val (item, said) = compose.runOnIdle { Speech.node(view, id).collectionItemInfo to Speech.saidAt(view, id) }
            assertEquals("$name is ${place + 1} of ${swatches.size}", listOf(0, place), listOf(item.rowIndex, item.columnIndex))
            assertTrue("$name is named, said $said", name in said)
        }
    }

    // #203: a radio button keeps its name while a header covers its words. TalkBack said "Selected, radio button, 1 of 2"
    // for a Pane style cell half under the page's header: Compose leaves out a node that a later one covers, and the
    // name was only in the cell's words. The button now carries its name, which Compose gives as a child as big as it.

    @Test fun aSegmentedRadioButtonHalfUnderAHeaderKeepsItsName() {
        var cover by mutableStateOf(0.dp)
        show {
            VeloTheme(UiSize.Medium) {
                Box(Modifier.fillMaxWidth()) {
                    VeloSegmented(listOf("Comfortable", "Compact"), "Comfortable", { it }, Modifier.testTag("styles"), optionTag = { "style-$it" }) {}
                    // The page's header, drawn over the top of the control as a scrolled page draws it.
                    Box(Modifier.fillMaxWidth().height(cover).background(Color.Black).semantics { heading() })
                }
            }
        }
        assertHalfCoveredRadioKeepsItsName("style-Comfortable", "Comfortable") { cover = it }
    }

    @Test fun aHighlightSwatchHalfUnderAHeaderKeepsItsName() {
        var cover by mutableStateOf(0.dp)
        show {
            val context = LocalContext.current
            val settings = remember { FakeSettingsHost(context).settings }
            VeloTheme(UiSize.Medium) {
                Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Column { PaneHighlightSettings(settings) }
                    Box(Modifier.fillMaxWidth().height(cover).background(Color.Black).semantics { heading() })
                }
            }
        }
        assertHalfCoveredRadioKeepsItsName("appearance-highlight-cyan", "Cyan") { cover = it }
    }

    /**
     * Covers the words of the radio button whose part is tagged [tag] down to their bottom with a header ([setCover] sets
     * its height), leaving the button's lower part in sight. The button still says [name], once, with its state.
     */
    private fun assertHalfCoveredRadioKeepsItsName(tag: String, name: String, setCover: (androidx.compose.ui.unit.Dp) -> Unit) {
        compose.waitForIdle()
        var radio = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        while (SemanticsProperties.Role !in radio.config) radio = checkNotNull(radio.parent) { "$tag has no radio button" }
        val words = compose.onNode(hasText(name), useUnmergedTree = true).fetchSemanticsNode()
        val density = compose.density
        val bottom = words.boundsInRoot.bottom
        assertTrue("the button reaches below its words", radio.boundsInRoot.bottom > bottom + 2)
        setCover(with(density) { (bottom + 1).toDp() })
        compose.waitForIdle()
        val said = compose.runOnIdle { Speech.saidAt(view, radio.id) }
        assertEquals("$name said once, among $said", 1, said.count { it == name })
        assertTrue("$tag still says its state, among $said", said.any { it == "Selected" || it == "Not selected" })
    }

    // #230: the Highlight color row's value is said by the chosen swatch alone.

    @Test fun theHighlightColorIsSaidOnce() {
        scene("settings-appearance")
        compose.onNodeWithTag("appearance-highlight-swatches").performScrollTo()
        val stops = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        assertFalse("Accent is no stop of its own, among $stops", listOf("Accent") in stops)
        assertEquals("Accent is said once, among $stops", 1, stops.count { "Accent" in it })
        assertTrue("the chosen swatch says it, among $stops", listOf("Selected", "Accent") in stops)
        assertTrue("the row's name is still a stop, among $stops", listOf("Highlight color") in stops)
    }

    // #200: a slider row says its name and its value on the slider alone: the words beside it are no stops.

    @Test fun theSoundVolumeRowSaysItsValueOnce() {
        scene("settings-notif")
        assertSliderRowSaysItsValueOnce("notifications-volume", "Sound volume", listOf("Activity sound volume", "100%"))
    }

    @Test fun theFontSizeRowSaysItsValueOnce() {
        scene("settings-appearance")
        assertSliderRowSaysItsValueOnce("appearance-font-size", "Font size", listOf("Font size", "13"))
    }

    /**
     * The slider tagged `<tag>-slider` says [said], its name and its value, and is still a slider. The row's [label] and
     * its shown value are no stops, so the stop before the slider is something else and the value is heard once.
     */
    private fun assertSliderRowSaysItsValueOnce(tag: String, label: String, said: List<String>) {
        assertEquals(said, said("$tag-slider"))
        val slider = compose.onNodeWithTag("$tag-slider").fetchSemanticsNode().id
        assertTrue("$tag-slider is still a slider", compose.runOnIdle { Speech.node(view, slider).rangeInfo != null })
        val stops = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        val value = said.last()
        assertFalse("$label is no stop of its own, among $stops", listOf(label) in stops)
        assertFalse("$value is no stop of its own, among $stops", listOf(value) in stops)
        assertEquals("$value is said once, among $stops", 1, stops.count { value in it })
    }

    // #167: About's Updates row says the release by its text, and no state says it again.

    @Test fun aboutsUpdatesRowSaysTheReleaseOnce() {
        scene("settings-about")
        assertEquals(listOf("Updates", "2.10.1 available", "Not seen yet"), said("about-updates-row"))
        assertEquals(null, stateOf("about-updates-row"))
        assertStillActs("about-updates-row", Role.Button)
        compose.onNodeWithTag("about-updates-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("updates-channel-menu").assertExists()
    }

    // #149: the rows.

    @Test fun anAlertRowSaysItsDescriptionOnce() {
        scene("settings-notif")
        // Speech.saidAt lists the row's own state first, then the words of its children (#167).
        assertEquals(listOf("On", "Background notification, Needs your response"), said("alert-needs-response-notification-row"))
        // The sound menu in the row is a control of its own, said apart.
        assertEquals(listOf("On", "Activity sound, Work completed"), said("alert-work-completed-sound-row"))
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

    /** Compose's draft is named by its label alone (#174): a description of the same word was said with it. */
    @Test fun theComposeDraftIsNamedOnce() {
        val state = UiState(status = "connected", server = "studio-mac", session = "session:dev", window = "build", focused = "block:build",
            windows = listOf(WindowInfo("build", layout = LayoutNode(block = "block:build"))))
        show {
            val conn = rememberFakeConnection(state)
            val target = remember(conn) { ComposerTarget(conn, "session:dev", "block:build", 1L, "studio-mac · build") }
            val layer = remember { OverlayLayer() }
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
                    ComposerDialog(target, notificationPending = false, isCurrent = { true }, onClose = {})
                }
            }
        }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        assertEquals(listOf("Draft"), compose.runOnIdle { Speech.saidAt(view, field) })
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

    @Test fun sshDetailsForgetIsSaidOnce() {
        val row = SshDetailRow(SshDetail.Password, listOf(DetailLine(PASSWORD_SAVED)), canForget = true)
        show { VeloTheme(UiSize.Medium) { DetailRow(row, remember { FocusRequester() }, takesFocus = false, onFocus = {}, forgetEnabled = true) {} } }
        compose.waitForIdle()
        val said = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        assertTrue("Forget said once, among $said", listOf(SshDetail.Password.forgetDescription) in said)
    }

    // #150: the sidebar's tab rows, beside it and docked.

    @Test fun aTabRowBesideThePanesSaysEachPartOnce() = assertTabRowsSayEachPartOnce("workspace-sidebar")

    @Test fun aDockedTabRowSaysEachPartOnce() = assertTabRowsSayEachPartOnce("workspace-docked")

    /**
     * The tile names the program and the panes, the name follows unless it is the program, and the second line says the
     * state and the folder. The row of the shown tab says it is selected (#167).
     */
    private fun assertTabRowsSayEachPartOnce(name: String) {
        scene(name)
        assertEquals(listOf("Claude Code", "Refactor the sync queue", "Working · 2s since report · ~/src/api"), said("tab-row-refactor-sync-queue"))
        assertEquals(listOf("Selected", "Codex, 2 panes", "Port the parser tests", "Working · 12s since report · ~/src/cli"), said("tab-row-port-parser-tests"))
        assertEquals(listOf("zsh, 4 panes", "~/src/web"), said("tab-row-zsh"))
        // The second line says the program works; the tile has no state to add, and the row none either.
        assertEquals(null, stateOf("tab-tile-refactor-sync-queue"))
        assertEquals(null, stateOf("tab-row-refactor-sync-queue"))
        assertStillActs("tab-row-zsh", null)
    }

    // #201: a remote row whose note says its connection says it once; the dot says it where no note does.

    @Test fun aRemoteRowSaysItsConnectionOnce() {
        show { VeloTheme(UiSize.Medium) { RemoteStatesSidebar() } }
        compose.waitForIdle()
        assertEquals(listOf("Selected", "Remote", "live", "Connected"), said("remote-row-live"))
        assertEquals(listOf("Remote", "failed", "connection failed"), said("remote-row-failed"))
        assertEquals(listOf("Remote", "again", "reconnecting…"), said("remote-row-again"))
        assertEquals(listOf("Remote", "away", "offline"), said("remote-row-away"))
        assertEquals(listOf("Remote", "dialing", "connecting…"), said("remote-row-dialing"))
        // The note asks for the login's answer; the dot still says where the connection is.
        assertEquals(listOf("Remote", "asking", NEEDS_ANSWER_NOTE, "Connecting…"), said("remote-row-asking"))
    }

    /** The state a service says for the node tagged [tag], read from its AccessibilityNodeInfo. */
    private fun stateOf(tag: String): String? {
        val id = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().id
        return compose.runOnIdle { Speech.node(view, id).stateDescription?.toString() }
    }

    /**
     * A selected row with an unread report and five or more panes, and a row of a remote that is not
     * connected: the tile says the panes as a count, it says the finish not yet seen as "Unread" since
     * the second line says "Completed", and the offline mark adds nothing to the line's "Disconnected".
     */
    @Test fun anUnreadSelectedRowAndAnOfflineRowSayEachPartOnce() {
        show { VeloTheme(UiSize.Medium) { RowCasesSidebar() } }
        compose.waitForIdle()
        assertEquals(listOf("Selected", "htop, 6 panes", "Unread", "Build the release", "Completed · 26s since report · ~/src/api"), said("tab-row-build"))
        assertEquals("Unread", stateOf("tab-tile-build"))
        // Compose gives a selected row that is no tab its state as a checked node: "Selected", once.
        assertEquals("Selected", stateOf("tab-row-build"))
        assertEquals(listOf("htop, 5 panes", "Watch the logs", "Disconnected · ~/var/log"), said("tab-row-watch"))
        assertEquals(null, stateOf("tab-tile-watch"))
    }

    /** A working row with a progress pie (#250): the row's words say the percent (#251), and the working dots give way to the pie. */
    @Test fun aWorkingRowWithProgressSaysThePercentOnceAndDropsTheDots() {
        show { VeloTheme(UiSize.Medium) { ProgressSidebar() } }
        compose.waitForIdle()
        // The percent is said with the second line, which the screen draws without it (#251); the tile has no state to
        // say again at each new percent.
        assertEquals(listOf("Selected", "Claude Code", "Index the files", "Working · 30% · 4s since report · ~/src/api"), said("tab-row-index"))
        assertEquals(null, stateOf("tab-tile-index"))
        // The screen draws the line without the percent.
        compose.onNodeWithText("Working · 4s since report · ~/src/api", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tab-tile-index-pie", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tab-tile-index-logo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("tab-working-index", useUnmergedTree = true).assertDoesNotExist()
        // Without a progress, the logo and the dots stay.
        compose.onNodeWithTag("tab-tile-plain-logo", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tab-working-plain", useUnmergedTree = true).assertExists()
        assertEquals(null, stateOf("tab-tile-plain"))
    }
}
