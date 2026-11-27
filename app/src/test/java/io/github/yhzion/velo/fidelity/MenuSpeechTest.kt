package io.github.yhzion.velo.fidelity

import android.app.Application
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
 * remote's Actions, an imported SSH key's row, and a long press on a tab's row (the VeloMenus). The
 * scenes are drawn over an overlay layer, so a menu opens in the same window and its nodes are read.
 *
 * Each test runs at each [ScanWidth] (#175); its name says which. Unfolded, the scenes draw their wide
 * layouts, with openers of their own ([OnlyUnfolded]); a scene that draws a menu open there draws it
 * shut here ([LocalMenusShut]), and the menu is opened by its button as at the phone's width. The
 * kind check, which needs no width, is in [SpeechCheckTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class MenuSpeechTest(private val width: ScanWidth) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Before fun drawAtTheWidth() = width.apply()

    /** A kind of button that opens a menu: the nodes it finds, and how it opens one. */
    private class Opener(val name: String, val finds: SemanticsMatcher, val open: (SemanticsNodeInteraction) -> Unit)

    private val tap: (SemanticsNodeInteraction) -> Unit = { it.performClick() }

    private val Openers = listOf(
        Opener("menu button", SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList), tap),
        Opener("More or Actions button", tagMatches(Regex("(session|remote|saved-remote)-menu-.+")), tap),
        Opener("imported key row", SemanticsMatcher("opens Copy or share") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Copy or share") == true
        }, tap),
        Opener("long press on a tab row", tagMatches(Regex("tab-row-.+")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)) {
            it.performSemanticsAction(SemanticsActions.OnLongClick)
        },
    )

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

    /** Openers each width draws in one scene: the sidebar scene shows the remote's More button, as the docked one does. */
    private val InScene = listOf("workspace-sidebar" to "remote-menu-", "workspace-docked" to "remote-menu-")

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
                    val label = button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TestTag) ?: opener.name
                    val before = compose.runOnIdle { Speech.focusableNodes(view).map { Speech.virtualId(it.first) }.toSet() }
                    opener.open(button)
                    compose.waitForIdle()
                    // A node new since the tap is the menu's: a row's words may change meanwhile ("2s since report").
                    val added = compose.runOnIdle { Speech.focusableNodes(view) }
                        .filter { Speech.virtualId(it.first) !in before && it.second.isNotEmpty() }.map { it.second }
                    opened += Opened(name, label, if (label == opener.name) label else "$label (${opener.name})", added)
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
