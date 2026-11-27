package io.github.yhzion.velo.fidelity

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.yhzion.velo.ui.WheelName
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
 * No control in a registered scene ([FidelityScenes]) says the same words twice to a screen reader
 * (#147). A control whose own description sits over visible texts is read twice: Compose gives the
 * description as a child next to the texts (#120, #124, #127, #134). The test reads what an
 * accessibility service gets, the AccessibilityNodeInfo tree of the Compose view's node provider,
 * as DeviceHeaderSpeechTest does.
 *
 * Each focusable node says its own words and states and those of the descendants that are not
 * focusable themselves. The node says a thing twice by [Speech.saysTwice]: a part comes again, as it
 * is or by a short or full name of one thing (#167), or one of its words is said inside another.
 * MenuSpeechTest opens the scenes' menus and scans their options the same way.
 *
 * The scenes of the screens no board draws (#174: the rail's window wheel, the overview, its drum, a pane move and a
 * zoomed tab's minimap) must say what [Heard] lists, so that a scene which did not draw its screen fails. The drum
 * says nothing, so its cards' name tags in the unmerged tree show it was drawn.
 *
 * The scan runs at each [ScanWidth], the phone's and the unfolded Fold8's (#175), and its name says which.
 * Unfolded, a scene draws its wide layout, and settings-remotes draws studio-mac's menu open. The
 * checks that need no width are in [SpeechCheckTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class DoubleSpeechTest(private val width: ScanWidth) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Before fun drawAtTheWidth() = width.apply()

    /** What one focusable node says: its own words first, then its descendants'. */
    private class Said(val node: String, val parts: List<String>, val actions: Int = 0) {
        override fun toString() = "$node: \"${parts.joinToString(" | ")}\""
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun noControlInAnySceneSaysAThingTwice() {
        val names = FidelityScenes.keys.toList()
        var current by mutableStateOf(names.first())
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
            // Tags as resource ids name the node a failure points at; nothing else in the tree changes.
            Box(Modifier.semantics { testTagsAsResourceId = true }) { key(current) { FidelityScenes.getValue(current)() } }
        }
        compose.waitForIdle()
        assertEquals("The scenes are drawn at $width", width.w to width.h, drawn)
        assertEquals("The window is $width", (width.w * density).toInt() to (width.h * density).toInt(), window)
        val twiceSaid = mutableListOf<String>()
        val heard = mutableMapOf<String, List<Said>>()
        val drumTags = mutableListOf<String>()
        for (name in names) {
            current = name
            compose.waitForIdle()
            val said = compose.runOnIdle { utterances(view) }
            heard[name] = said
            // The drum's cards say nothing, so their names' tags, kept in the unmerged tree, show the drum was drawn.
            if (name == "workspace-drum") drumTags += compose.taggedNodes().map { it.tag }
            for (s in said.filter { Speech.saysTwice(it.parts) }) twiceSaid += "$name, $s"
        }
        assertTrue("Said twice to a screen reader at $width (scene, node, words):\n" + twiceSaid.joinToString("\n"), twiceSaid.isEmpty())
        // The screens no board draws were drawn and heard (#174), so the scan is not empty by a screen that did not draw.
        for (must in Heard) {
            val said = heard[must.scene].orEmpty()
            assertTrue("${must.scene} ${if (must.heard) "says" else "never says"} ${must.what} at $width, among:\n" + said.joinToString("\n"),
                said.any(must.test) == must.heard)
        }
        assertTrue("workspace-drum draws its cards' names (card-name-*) at $width, among tags:\n" + drumTags.joinToString("\n"),
            drumTags.any { it.startsWith("card-name-") })
    }

    /** What a [scene] says, or with [heard] false never says: [what], found by [test]. */
    private class MustHear(val scene: String, val what: String, val heard: Boolean = true, val test: (Said) -> Boolean)

    /**
     * What each scene of a screen no board draws (#174) says at every width: the rail's window wheel its slider with the
     * window in front, the zoomed tab its minimap with the pane it marks, the overview a tab's card and its session
     * strip, and a pane move its capsule and Cancel. No pane is named here: the scan reads pane names as they are. The
     * drum's cards say nothing, for the rail's slider names its tabs: a card would say a tab again.
     */
    private val Heard = listOf(
        MustHear("workspace-wheel", "the window wheel's slider and its window") { it.parts.size == 2 && it.parts[0] == WheelName },
        MustHear("workspace-zoom", "the minimap and the pane it marks") { it.node == "caption-minimap" && it.parts.size == 2 },
        MustHear("workspace-overview", "a tab's card, not selected") { it.parts.size == 2 && it.parts[1] == "Not selected" && !it.parts[0].startsWith("Session ") },
        MustHear("workspace-overview", "the session strip's remote") { it.parts.size == 1 && it.parts[0].startsWith("Remote ") },
        MustHear("workspace-pane-move", "the move's capsule") { it.parts.size == 1 && it.parts[0].startsWith("Choose where") },
        MustHear("workspace-pane-move", "the move's Cancel") { it.parts == listOf("Cancel") },
        // A pane's place to pick says the pane's name alone and offers its places as actions; the name is not pinned.
        MustHear("workspace-pane-move", "a pane's place to pick, with its places") { it.parts.size == 1 && it.actions > 0 && !it.node.startsWith("rail-") },
        MustHear("workspace-drum", "a card's selection", heard = false) { it.parts.lastOrNull() in setOf("Selected", "Not selected") },
    )

    /** Each focusable node that says something, with the words it says. */
    private fun utterances(view: View): List<Said> =
        Speech.focusableNodes(view).filter { it.second.isNotEmpty() }.map { (info, said) -> Said(nodeName(info), said, customActions(info)) }

    /** How many custom actions [info] offers: their ids are resource ids, above the standard actions' bits. */
    private fun customActions(info: AccessibilityNodeInfo): Int = info.actionList.count { it.id > 0xFFFFFF }

    private fun nodeName(info: AccessibilityNodeInfo): String =
        info.viewIdResourceName ?: Rect().also(info::getBoundsInScreen).let { "node at ${it.toShortString()}" }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun widths() = ScanWidth.parameters()
    }
}
