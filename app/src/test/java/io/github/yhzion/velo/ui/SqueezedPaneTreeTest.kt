package io.github.yhzion.velo.ui

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.descendants
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.PaneNotificationLaunches
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.fidelity.CorePackage
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.ShadowVeloCore
import io.github.yhzion.velo.fidelity.fakeConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Velvet on its side (895×393 dp at 440 dpi) with Gboard up, about 249 dp, and a tab of one pane on the left and six
 * stacked on the right (#272): each right-hand pane keeps a line or less, and a screen reader still finds every one of
 * them, the input target among them. Compose leaves out of the accessibility tree a node the nodes above it cover; the
 * resize handles' 32 dp hit areas covered every pane under 25 dp between two of them (12.5 dp at an end), so with the
 * keyboard up only Pane 1 was left. The handles now say their names and take their action on the gap between the panes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w895dp-h393dp-land-440dpi",
    shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class SqueezedPaneTreeTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private lateinit var view: View
    private var conn: HostConnection? = null
    private var app: AppController? = null

    @After fun destroy() {
        conn?.destroy()
        app?.destroy()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** Six panes stacked: column(b, column(c, …)) with ratios that make them even. */
    private fun stack(blocks: List<String>): LayoutNode =
        if (blocks.size == 1) LayoutNode(block = blocks[0])
        else LayoutNode(dir = "column", ratio = 1f / blocks.size, first = LayoutNode(block = blocks[0]), second = stack(blocks.drop(1)))

    private fun state(focused: String) = UiState(status = "connected", server = "velo", session = "s", focused = focused,
        windows = listOf(WindowInfo("w1", label = "build", layout = LayoutNode(dir = "row", first = LayoutNode(block = "a"),
            second = stack(listOf("b", "c", "d", "e", "f", "g"))))))

    private fun workspace(size: UiSize, style: String, focused: String) {
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), state(focused)).also { conn = it }
        val app = AppController(ApplicationProvider.getApplicationContext()).also { this.app = it }
        app.settings.savePaneStyle(style)
        compose.setContent {
            view = LocalView.current
            VeloTheme(size) {
                Workspace(app, c, remember { TerminalInputView(view.context) }, FoldInfo(), null, remember { PaneNotificationLaunches() },
                    settingsOpen = false, onSettings = {}, onTailscale = {})
            }
        }
        settle()
    }

    private fun keyboard(height: Int) {
        compose.runOnIdle {
            val px = (height * view.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.ime(), height > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        settle()
    }

    /** The accessibility view ids of the real views the Compose tree holds as children: what a screen reader reaches. */
    private fun viewsInTree(): Set<Int> {
        val provider = view.accessibilityNodeProvider ?: return emptySet()
        val found = mutableSetOf<Int>()
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            for (c in 0 until info.childCount) {
                // A child's id is a long: the virtual view id in its high half, and for a real view (an AndroidView's
                // holder) the host's id there and the view's accessibility id in its low half. getChildId is hidden.
                val child = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, c) as Long
                val virtual = (child ushr 32).toInt()
                if (virtual == AccessibilityNodeProvider.HOST_VIEW_ID) found += child.toInt() else walk(virtual)
            }
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        return found
    }

    private fun paneViews() = (view as ViewGroup).descendants.filterIsInstance<PaneSurfaceView>().toList()
    private fun accessibilityId(v: View) = View::class.java.getMethod("getAccessibilityViewId").invoke(v) as Int

    /** What a screen reader finds of the panes, in the order of the views: what each says, or null for a pane it does not find. */
    private fun panesFound(): List<String?> = compose.runOnIdle {
        shadowOf(view.context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val inTree = viewsInTree()
        // The AndroidView's holder is the child the Compose tree names; the pane's view is its one child.
        paneViews().map { p -> if (accessibilityId(p.parent as View) in inTree) p.contentDescription?.toString() else null }
    }

    /** Each pane's height in px, in the order of the views. */
    private fun paneHeights() = compose.runOnIdle { paneViews().map { it.height } }

    private val caption = "velo · build, Terminal, 7 panes"

    @Test fun everyPaneTheKeyboardSqueezesStaysInTheTree() {
        workspace(UiSize.Small, "comfortable", "d")
        val all = listOf("Pane 1", "Pane 2", "Pane 3", "Pane 4, input target", "Pane 5", "Pane 6", "Pane 7")
        assertEquals("the keyboard down", all, panesFound())
        keyboard(249)
        // As the Velvet showed it: a line or less for each right-hand pane, the least 4 px.
        val heights = paneHeights()
        assertTrue("squeezed: $heights", heights.drop(1).all { it in 1..30 })
        assertEquals("the keyboard up", all.map { if (it == "Pane 4, input target") "$it, $caption" else it }, panesFound())
    }

    /** At Medium the input target keeps 16 px under the keyboard, and the panes at the foot none: a pane of no size is not found (#278). */
    @Test fun theInputTargetStaysAtMedium() {
        workspace(UiSize.Medium, "comfortable", "d")
        keyboard(249)
        val found = panesFound()
        assertEquals("Pane 4, input target, $caption", found[3])
        for ((i, h) in paneHeights().withIndex()) assertEquals("pane ${i + 1}, $h px", h > 0, found[i] != null)
    }

    /** The handles a screen reader finds, in the tree's order (the split left and right last): what each says, its bounds and node. */
    private fun handles() = compose.runOnIdle {
        shadowOf(view.context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val provider = view.accessibilityNodeProvider
        val found = mutableListOf<Triple<String, Rect, AccessibilityNodeInfo>>()
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            val said = info.contentDescription?.toString()
            if (said != null && said.startsWith("Resize panes")) found += Triple(said, Rect().also(info::getBoundsInScreen), info)
            for (c in 0 until info.childCount) {
                val child = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, c) as Long
                (child ushr 32).toInt().takeIf { it != AccessibilityNodeProvider.HOST_VIEW_ID }?.let(::walk)
            }
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        found
    }

    private fun paneRects() = compose.runOnIdle { paneViews().map { Rect().also { r -> it.getGlobalVisibleRect(r) } } }
    private fun px(dp: Float) = dp * view.resources.displayMetrics.density

    private val handleNames = List(5) { "Resize panes above and below" } + "Resize panes left and right"

    /** Panes of their usual height: each handle is found on its whole 32 dp hit area, as before #272. */
    @Test fun theHandlesKeepTheirHitAreaWhereNoPaneIsSqueezed() {
        workspace(UiSize.Small, "comfortable", "d")
        val handles = handles()
        assertEquals(handleNames, handles.map { it.first })
        for ((said, bounds, info) in handles) {
            val along = if (said.endsWith("left and right")) bounds.width() else bounds.height()
            assertEquals("$said on $bounds", px(32f), along.toFloat(), 1f)
            assertTrue("$said can be set", info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))
        }
    }

    /**
     * The keyboard up: the handles between the squeezed panes are found on the gap alone, on no pane, and keep their
     * names and action; the one between the left pane and the stack, beside panes of their full width, keeps its 32 dp.
     */
    @Test fun theHandlesGiveWayToSqueezedPanes() = handlesGiveWay("comfortable")
    @Test fun theHandlesGiveWayToSqueezedPanesCompact() = handlesGiveWay("compact")

    private fun handlesGiveWay(style: String) {
        run {
            workspace(UiSize.Small, style, "d")
            keyboard(249)
            val panes = paneRects()
            val handles = handles()
            assertEquals(style, handleNames, handles.map { it.first })
            assertEquals("$style: left and right", px(32f), handles.last().second.width().toFloat(), 1f)
            for ((said, bounds, info) in handles.dropLast(1)) {
                assertTrue("$style: $said on $bounds is on no pane of $panes", panes.none { Rect(it).intersect(bounds) })
                assertTrue("$style: $said can be set", info.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS))
            }
            assertTrue("$style: every pane found", panesFound().all { it != null })
            // The keyboard down, each takes its whole hit area again.
            keyboard(0)
            for ((said, bounds, _) in handles()) {
                val along = if (said.endsWith("left and right")) bounds.width() else bounds.height()
                assertEquals("$style, the keyboard down: $said on $bounds", px(32f), along.toFloat(), 1f)
            }
        }
    }

    /** The least pane that touches a split's gap on each side, along its axis, as [LayoutTree] lays the panes out. */
    @Test fun theEdgePanesOfANode() {
        val gap = 8.dp
        val leaf = LayoutNode(block = "x")
        assertEquals(100.dp, leaf.edgePaneExtent(column = true, end = true, 100.dp, gap))
        // A stack of two, 108 dp tall: 25 dp and 75 dp, the gap between them.
        val stack = LayoutNode(dir = "column", ratio = 0.25f, first = LayoutNode(block = "a"), second = LayoutNode(block = "b"))
        assertEquals(25f, stack.edgePaneExtent(column = true, end = false, 108.dp, gap).value, 0.01f)
        assertEquals(75f, stack.edgePaneExtent(column = true, end = true, 108.dp, gap).value, 0.01f)
        // Side by side, both touch a gap below them: the lesser counts.
        val row = LayoutNode(dir = "row", first = leaf, second = stack)
        assertEquals(75f, row.edgePaneExtent(column = true, end = true, 108.dp, gap).value, 0.01f)
        assertEquals(25f, row.edgePaneExtent(column = false, end = true, 58.dp, gap).value, 0.01f)
        assertTrue(handleOnGapOnly(12.dp, before = 24.dp, after = 80.dp))
        assertFalse(handleOnGapOnly(12.dp, before = 25.dp, after = 80.dp))
    }
}
