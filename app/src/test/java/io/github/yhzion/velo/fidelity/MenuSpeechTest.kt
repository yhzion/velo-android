package io.github.yhzion.velo.fidelity

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No option of a menu that a registered scene ([FidelityScenes]) opens says a thing twice to a screen
 * reader (#167): DoubleSpeechTest scans the scenes as drawn, with their menus shut but the one a board
 * draws open. This test opens each menu, one at a time on a fresh scene, and scans what the menu adds by
 * the same rule ([Speech.saysTwice]).
 *
 * A menu is opened by its button ([Openers]): a menu button (each SelectMenu: the tap-gesture,
 * Minimized windows, sound and channel menus), a sidebar session's or remote's More button, a saved
 * remote's Actions, an imported SSH key's row, and a long press on a tab's row (the VeloMenus). From
 * #174 also the rail's connection mark (its popover) and Pane menu (the focused pane's menu), the
 * pane menu's Paste of two lines (its question), Compose's attach control, a long press on a
 * chip of the overview's session strip, and a zoomed tab's minimap (its picker). The scenes are drawn over an overlay layer, so a menu opens
 * in the same window and its nodes are read.
 *
 * A menu that scrolls is scanned at each page it scrolls to ([ScrollSweep], #276).
 *
 * Each test runs at each [ScanWidth] (#175); its name says which. Unfolded, the scenes draw their wide
 * layouts, with openers of their own ([OnlyUnfolded]); a scene that draws a menu open there draws it
 * shut here ([LocalMenusShut]), and the menu is opened by its button as at the phone's width. The
 * kind check, which needs no width, is in [SpeechCheckTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class MenuSpeechTest(private val width: ScanWidth) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Before fun drawAtTheWidth() = width.apply()

    /**
     * A kind of button that opens a menu: the nodes it finds, the name a menu it opens goes by ([label]
     * of the button's tag, or the kind's name), and how it opens one: false when it has nothing to
     * open there (a button two kinds find is still checked by the other).
     */
    private class Opener(
        val name: String, val finds: SemanticsMatcher, val label: (String?) -> String = { it ?: name },
        val open: (SemanticsNodeInteraction) -> Boolean,
    )

    private val tap: (SemanticsNodeInteraction) -> Boolean = { it.performClick(); true }

    private val Openers = listOf(
        Opener("menu button", SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList), open = tap),
        Opener("More or Actions button", tagMatches(Regex("(session|remote|saved-remote)-menu-.+")), open = tap),
        Opener("imported key row", SemanticsMatcher("opens Copy or share") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Copy or share") == true
        }, open = tap),
        Opener("long press on a tab row", tagMatches(Regex("tab-row-.+")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)) {
            it.performSemanticsAction(SemanticsActions.OnLongClick)
            true
        },
        // The rail (#174): the connection mark's popover, and Pane menu, the focused pane's menu.
        Opener("rail button", tagMatches(Regex("rail-(connection|pane-menu)")), open = tap),
        // The pane menu's Paste of a clip of two lines, which asks first: its question takes the menu's place.
        Opener("paste prompt", tagMatches(Regex("rail-pane-menu")), label = { "paste prompt of $it" }, open = ::askToPaste),
        // The Compose dialog's attach control: "Attach…", or the attached file's name.
        Opener("attach control", SemanticsMatcher("attaches a file") {
            SemanticsActions.OnClick in it.config && (it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "Attach…" } == true ||
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { d -> d.startsWith("Attachment ") } == true)
        }, open = tap),
        // A zoomed tab's minimap in the caption, whose tap opens its picker of panes.
        Opener("minimap", tagMatches(Regex("caption-minimap")), open = tap),
        // A chip of the overview's session strip.
        Opener("long press on a session chip", SemanticsMatcher("opens the session's menu") {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Show session menu"
        }) {
            it.performSemanticsAction(SemanticsActions.OnLongClick)
            true
        },
    )

    /**
     * Opens the pane menu by its [button], then taps its Paste with two lines on the clipboard; false
     * when there is no Paste to tap: the menu did not open (its own opener says so), or Paste is off
     * because the remote is down, which pastes nothing and so asks nothing.
     */
    private fun askToPaste(button: SemanticsNodeInteraction): Boolean {
        button.performClick()
        compose.waitForIdle()
        // Each button is used on a fresh draw of its scene, so a menu left open here is gone for the next.
        val paste = hasText("Paste") and hasClickAction() and isEnabled()
        if (compose.onAllNodes(paste).fetchSemanticsNodes().isEmpty()) return false
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Velo", "make\nmake test"))
        compose.onNode(paste).performClick()
        return true
    }

    /** Whether [node] sits in a list that scrolls, so it can be scrolled into view. */
    private fun inScroll(node: SemanticsNodeInteraction): Boolean =
        generateSequence(node.fetchSemanticsNode().parent) { it.parent }.any { SemanticsActions.ScrollBy in it.config }

    private fun tagMatches(tag: Regex) = SemanticsMatcher("test tag ~ $tag") { it.config.getOrNull(SemanticsProperties.TestTag)?.matches(tag) == true }

    /**
     * The buttons a scene draws under a sheet, so a tap on them opens nothing: the saved remotes'
     * Actions under Add remote. Every other button must open a menu.
     */
    private val Covered = setOf("settings-add-remote" to "saved-remote-menu-studio-mac", "settings-add-remote" to "saved-remote-menu-build-server")

    /** The menus each width must open: the kinds the issue names, by a tag's start or the kind's name. */
    private val Required = listOf("keyboard-double-tap-menu", "keyboard-triple-tap-menu", "appearance-minimized-menu", "alert-needs-response-sound-menu",
        "updates-channel-menu", "tab-row-", "session-menu-", "remote-menu-", "saved-remote-menu-", "imported key row")

    /** The scenes that draw the rail: each opens its popover and the pane menu, and the connected ones the paste prompt (#174, #188). */
    private val RailScenes = listOf("workspace-terminal", "workspace-sidebar", "workspace-docked", "workspace-panes", "workspace-pane-menu", "workspace-offline",
        "workspace-wheel", "workspace-overview", "workspace-drum", "workspace-pane-move", "workspace-zoom",
        "workspace-overview-grid", "workspace-pane-move-alone", "workspace-move-undo", "workspace-zoom-covered", "workspace-pane-picker")

    /**
     * Openers each width draws in one scene: the sidebar scene shows the remote's More button, as the
     * docked one does; the rail's scenes their rail; the Compose dialog, and the draft with a file,
     * the attach control; the overview and its grid their session strip's chips; and the zoomed tabs their minimap (#174,
     * #188), whose picker lists the pane under a floating one in a row of its own.
     */
    private val InScene = listOf("workspace-sidebar" to "remote-menu-", "workspace-docked" to "remote-menu-") +
        RailScenes.flatMap { listOf(it to "rail-connection", it to "rail-pane-menu") } +
        RailScenes.filter { it != "workspace-offline" }.map { it to "paste prompt of rail-pane-menu" } +
        listOf("workspace-compose" to "attach control", "compose-attachment" to "attach control", "workspace-overview" to "long press on a session chip",
            "workspace-zoom" to "caption-minimap", "workspace-overview-grid" to "long press on a session chip", "workspace-zoom-covered" to "caption-minimap")

    /**
     * Openers only the unfolded width draws, by scene and kind: settings' home shows Appearance beside
     * its list, and the workspace scenes draw the "Open" boards' data, with the dev session's last two
     * tabs (docker-compose, htop) listed.
     */
    private val OnlyUnfolded = listOf("settings-home" to "appearance-minimized-menu") +
        listOf("workspace-sidebar", "workspace-docked").flatMap { listOf(it to "tab-row-docker-compose", it to "tab-row-htop") }

    /** The (scene, kind) menus that [width] must open. */
    private fun requiredAt(width: ScanWidth): List<Pair<String?, String>> =
        Required.map { null to it } + InScene + if (width == ScanWidth.Unfolded) OnlyUnfolded else emptyList()

    @Test fun noOptionOfAMenuInAnySceneSaysAThingTwice() {
        val opened = openEachMenu(FidelityScenes)
        val twiceSaid = opened.flatMap { menu -> menu.options.filter(Speech::saysTwice).map { "${menu.scene}, ${menu.by}: \"${it.joinToString(" | ")}\"" } }
        assertTrue("Said twice in a menu at $width (scene, button, words):\n" + twiceSaid.joinToString("\n"), twiceSaid.isEmpty())
        // Every button opens a menu, but those under a sheet ([Covered]), so none is skipped unseen.
        val empty = opened.filter { it.options.isEmpty() && (it.scene to it.button) !in Covered }
        assertTrue("Opened nothing at $width (scene, button):\n" + empty.joinToString("\n"), empty.isEmpty())
        // A covered button is still drawn and still opens nothing, so the list names no button that is gone or opens a menu.
        val stale = Covered.filter { (scene, button) -> opened.none { it.scene == scene && it.button == button && it.options.isEmpty() } }
        assertTrue("Covered but not drawn, or opens a menu, at $width (scene, button): $stale", stale.isEmpty())
        // Each kind of menu the issue names was opened, so the scan is not empty by a missed button.
        val openers = opened.filter { it.options.isNotEmpty() }.map { "${it.scene}, ${it.by}" }
        for ((scene, must) in requiredAt(width))
            assertTrue("A menu opened by $must${scene?.let { " in $it" } ?: ""} at $width, among:\n" + openers.joinToString("\n"),
                opened.any { it.options.isNotEmpty() && it.isBy(must) && (scene == null || it.scene == scene) })
    }

    /**
     * Each menu the [scenes] open: for each scene, each enabled node an [Openers] kind finds is used on
     * a fresh draw of the scene, and the focusable nodes it adds are the menu's options.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun openEachMenu(scenes: Map<String, @Composable () -> Unit>): List<Opened> {
        var current by mutableStateOf(scenes.keys.first())
        var draw by mutableIntStateOf(0)
        lateinit var view: View
        var drawn = 0 to 0
        var window = 0 to 0
        var density = 1f
        compose.setContent {
            view = LocalView.current
            drawn = LocalConfiguration.current.let { it.screenWidthDp to it.screenHeightDp }
            // Some layouts measure the window, not the configuration, so the window (whole px, rounded down) must match too.
            window = LocalWindowInfo.current.containerSize.let { it.width to it.height }
            density = LocalDensity.current.density
            val layer = remember { OverlayLayer() }
            // Tags as resource ids name each button; the layer keeps a menu in this window.
            OverlayLayerBox(layer, Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                CompositionLocalProvider(LocalMenusShut provides true) {
                    Box(Modifier.fillMaxSize()) { key(current, draw) { scenes.getValue(current)() } }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("The scenes are drawn at $width", width.w to width.h, drawn)
        assertEquals("The window is $width", (width.w * density).toInt() to (width.h * density).toInt(), window)
        val opened = mutableListOf<Opened>()
        for (name in scenes.keys) {
            current = name
            draw++
            compose.waitForIdle()
            for (opener in Openers) {
                val count = compose.onAllNodes(opener.finds and isEnabled()).fetchSemanticsNodes().size
                for (index in 0 until count) {
                    draw++
                    compose.waitForIdle()
                    val button = compose.onAllNodes(opener.finds and isEnabled())[index]
                    // Scrolled first, so the nodes the menu adds are its own and none the scroll brought in.
                    if (inScroll(button)) button.performScrollTo()
                    compose.waitForIdle()
                    val label = opener.label(button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag))
                    val before = compose.runOnIdle { Speech.focusableNodes(view).map { Speech.virtualId(it.first) }.toSet() }
                    val listsBefore = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
                        .fetchSemanticsNodes(atLeastOneRootRequired = false).map { it.id }.toSet()
                    if (!opener.open(button)) continue
                    compose.waitForIdle()
                    // A node new since the tap is the menu's: a row's words may change meanwhile ("2s since report"), so each
                    // node is heard once, as first heard. A menu longer than its window is heard at each page it scrolls to
                    // (#276); the scene's lists under it stay as they are.
                    val added = linkedMapOf<Int, List<String>>()
                    ScrollSweep(compose, view) { it !in listsBefore }.sweep {
                        for ((info, words) in Speech.focusableNodes(view))
                            if (Speech.virtualId(info) !in before && words.isNotEmpty()) added.putIfAbsent(Speech.virtualId(info), words)
                    }
                    opened += Opened(name, label, if (label.startsWith(opener.name)) label else "$label (${opener.name})", added.values.toList())
                }
            }
        }
        return opened
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun widths() = ScanWidth.parameters()
    }
}

/** One menu opened: where, by what (its tag, or the kind of button), and what each option it adds says. */
internal class Opened(val scene: String, val button: String, val by: String, val options: List<List<String>>) {
    /** Whether a button of the kind [must] opened it: a tag that starts with it, or the one kind with no tag by its name. */
    fun isBy(must: String) = button.startsWith(must) || by == must

    override fun toString() = "$scene, $by: " + if (options.isEmpty()) "(opened nothing)" else options.joinToString(" / ") { it.joinToString(" | ") }
}
