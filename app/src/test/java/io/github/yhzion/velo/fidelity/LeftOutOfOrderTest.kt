package io.github.yhzion.velo.fidelity

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No node in a registered scene ([FidelityScenes]) says something without a place in a screen reader's order (#235).
 * Compose gives a screen reader the order of the nodes it stops on, the ones it marks screen-reader focusable (leaves and
 * merging nodes); a node with a description whose parts carry test tags is neither, so it is left out, and TalkBack
 * reaches it last, out of its place (#205's caption tile, CaptionOrderTest). A clickable or focusable node is no stop in
 * that order either. So a node that says something must be such a stop or be said by one above it; a merging node
 * hidden from accessibility hides all it holds ([Speech.hidesAll]). The scan runs with a screen reader on, as Compose
 * works out the order only then, at each [ScanWidth].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class LeftOutOfOrderTest(private val width: ScanWidth) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    /** A screen reader is on before anything is composed: only then does Compose work out the order. */
    @Before fun screenReaderOn() {
        width.apply()
        val manager = ApplicationProvider.getApplicationContext<Application>().getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun everyNodeThatSaysSomethingHasItsPlace() {
        val names = FidelityScenes.keys.toList()
        var current by mutableStateOf(names.first())
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            // Tags as resource ids name the node a failure points at.
            Box(Modifier.semantics { testTagsAsResourceId = true }) { key(current) { FidelityScenes.getValue(current)() } }
        }
        val leftOut = mutableListOf<String>()
        for (name in names) {
            current = name
            compose.waitForIdle()
            leftOut += compose.runOnIdle { leftOut(view) }.map { "$name, $it" }
        }
        assertTrue("Said, but out of a screen reader's order at $width (scene, node, words):\n" + leftOut.joinToString("\n"), leftOut.isEmpty())
    }

    /**
     * The start steps stay a polite live region that says their description, so a step that changes is announced:
     * merging the list (#235) must not lose it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun theStartStepsAreStillAnnounced() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.semantics { testTagsAsResourceId = true }) { FidelityScenes.getValue("start-2")() }
        }
        val steps = compose.runOnIdle { nodes(view).single { it.viewIdResourceName == "start-steps" } }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, steps.liveRegion)
        // Merging, Compose puts the description on a child of its own; the node says it, as a screen reader reads it.
        assertEquals(listOf("Step 2 of 3: Pick a computer"), compose.runOnIdle { Speech.said(view, steps) })
        assertTrue("a stop of its own", steps.isScreenReaderFocusable)
    }

    /** Each node in [view] that says something, is no stop and is under none, with what it says. */
    private fun leftOut(view: View): List<String> {
        val provider = view.accessibilityNodeProvider ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(id: Int, underStop: Boolean) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            if (Speech.hidesAll(info)) return
            val stop = info.isImportantForAccessibility && info.isScreenReaderFocusable
            val words = Speech.words(info)
            if (!stop && !underStop && words.isNotEmpty()) found += "${info.viewIdResourceName ?: "#$id"}: \"${words.joinToString(" | ")}\""
            for (c in 0 until info.childCount) Speech.childVirtualId(info, c)?.let { walk(it, underStop || stop) }
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID, false)
        return found
    }

    /** Every node in [view]. */
    private fun nodes(view: View): List<AccessibilityNodeInfo> {
        val provider = view.accessibilityNodeProvider ?: return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            all += info
            for (c in 0 until info.childCount) Speech.childVirtualId(info, c)?.let(::walk)
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        return all
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun widths() = ScanWidth.parameters()
    }
}
