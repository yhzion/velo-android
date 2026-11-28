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
 * A screen reader reaches the caption's tile where it is on the screen, before the tab's name beside it (#205). Compose
 * gives a screen reader the order of the nodes it stops on (each one's traversal-before node), and TalkBack puts a
 * node left out of that order last: the tile's parts have tags, so it was not a leaf, and without merging them it was
 * left out, after the key bar. The test reads that order, as Compose writes it for a test in each node's extras, from
 * the first node no other names, in the zoomed tab (the issue's) and in a tab of several panes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class CaptionOrderTest(private val width: ScanWidth) {
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
    @Test fun theCaptionsTileComesJustBeforeItsTitle() {
        var current by mutableStateOf(Scenes.first())
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.semantics { testTagsAsResourceId = true }) { key(current) { FidelityScenes.getValue(current)() } }
        }
        for (scene in Scenes) {
            current = scene
            compose.waitForIdle()
            val order = compose.runOnIdle { readingOrder(view) }
            val at = order.indexOf("caption-tab-tile")
            assertEquals("$scene at $width: the tile, then the title, in the order:\n" + order.joinToString("\n"),
                listOf("caption-tab-tile", "pane-caption-title"), if (at < 0) emptyList() else order.subList(at, minOf(at + 2, order.size)))
        }
    }

    /** The tags of the nodes in the order Compose gives a screen reader (a node without a tag by its id). */
    private fun readingOrder(view: View): List<String> {
        val provider = view.accessibilityNodeProvider
        val before = mutableMapOf<Int, Int>()
        val tags = mutableMapOf<Int, String>()
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            tags[id] = info.viewIdResourceName ?: "#$id"
            info.extras.getInt(TraversalBefore, -1).takeIf { it >= 0 }?.let { before[id] = it }
            for (c in 0 until info.childCount) childId(info, c)?.let(::walk)
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        var node = (before.keys - before.values.toSet()).singleOrNull() ?: return emptyList()
        val order = mutableListOf(node)
        while (true) node = before[node]?.takeIf { it !in order }?.also(order::add) ?: break
        return order.map { tags[it] ?: "#$it" }
    }

    /** The virtual view id of [info]'s child at [index]; null for a view of its own (Speech's scans skip them too). */
    private fun childId(info: AccessibilityNodeInfo, index: Int): Int? {
        val id = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, index) as Long
        return (id ushr 32).toInt().takeIf { it != AccessibilityNodeProvider.HOST_VIEW_ID }
    }

    companion object {
        /** The extra in which Compose writes, for a test, the node a screen reader goes to after this one. */
        private const val TraversalBefore = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

        /** The zoomed tab of the issue, and a tab of several panes. */
        private val Scenes = listOf("workspace-zoom", "workspace-panes")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun widths() = ScanWidth.parameters()
    }
}
