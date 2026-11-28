package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
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
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bar phone on its side (Velvet, 895×393 dp at 440 dpi) with Gboard up, about 249 dp (#232): the caption under
 * the panes gives way, so that the pane card keeps more than a line or two, and the key bar stays. With the caption
 * the pane kept 16 dp (50.5 in the compact style). A screen reader finds no part of the hidden caption, and the
 * caption, its tab name with it, comes back with the keyboard down, or where a smaller keyboard leaves room for it.
 * While it is hidden, the pane that takes the keys says its words after its own (#260); shown, the caption says them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w895dp-h393dp-land-440dpi",
    shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class LandscapeKeyboardCaptionTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private lateinit var view: View
    private var conn: HostConnection? = null
    private var app: AppController? = null

    private val state = UiState(status = "connected", server = "velo", session = "s", focused = "a",
        windows = listOf(WindowInfo("w1", label = "build", layout = LayoutNode(block = "a"))))

    @After fun destroy() {
        conn?.destroy()
        app?.destroy()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** The workspace at the app's default size, Medium, in the pane [style], the bar's keys the default ones. */
    private fun workspace(style: String, state: UiState = this.state) {
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), state).also { conn = it }
        val app = AppController(ApplicationProvider.getApplicationContext()).also { this.app = it }
        app.settings.savePaneStyle(style)
        compose.setContent {
            view = LocalView.current
            VeloTheme(UiSize.Medium) {
                Workspace(app, c, remember { TerminalInputView(view.context) }, FoldInfo(), null, remember { PaneNotificationLaunches() },
                    settingsOpen = false, onSettings = {}, onTailscale = {})
            }
        }
        settle()
    }

    /** The keyboard [height] dp tall, as the window tells the view: the input method's inset; 0 is the keyboard down. */
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

    private fun height(b: DpRect) = (b.bottom - b.top).value
    private fun pane() = height(compose.onNodeWithTag("pane-1").getBoundsInRoot())
    private fun captionShown() = compose.onAllNodesWithTag("pane-caption").fetchSemanticsNodes().isNotEmpty()
    /** What a screen reader hears for the pane that takes the keys: its view's description. */
    private fun paneSaid() = panesSaid().single()
    /** What a screen reader hears for each pane, in the order of the views. */
    private fun panesSaid() = compose.runOnIdle { paneViews().map { it.contentDescription?.toString() } }
    private fun paneViews() = (view as ViewGroup).descendants.filterIsInstance<PaneSurfaceView>().toList()

    /** A screen reader's focus comes to the [n]th pane view, as [View.requestAccessibilityFocus] tells it. */
    private fun readerFocusTo(n: Int) = compose.runOnIdle {
        shadowOf(view.context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        paneViews()[n].sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    }

    /** And goes, as [View.clearAccessibilityFocus] tells it. */
    private fun readerFocusOff(n: Int) = compose.runOnIdle {
        paneViews()[n].sendAccessibilityEventUnchecked(AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED))
    }

    private fun twoPanes(label: String, focused: String = "a") = state.copy(focused = focused, windows = listOf(WindowInfo("w1", label = label,
        layout = LayoutNode(dir = "row", first = LayoutNode(block = "a"), second = LayoutNode(block = "b")))))

    /** The state as the server sends it again, with the titles. */
    private var generation = 1L
    private fun serverSends(next: UiState) {
        compose.runOnIdle { conn!!.onState(++generation, true, Json { encodeDefaults = true }.encodeToString(UiState.serializer(), next)) }
        settle()
    }

    /** With Gboard up the pane keeps at least [least] dp, the caption is gone, to a screen reader too, and the key bar stays. */
    private fun theCaptionGivesWay(style: String, least: Float) {
        workspace(style)
        assertTrue("the caption shows with the keyboard down", captionShown())
        keyboard(249)
        assertFalse("the caption gives way", captionShown())
        for (part in listOf("pane-caption-title", "caption-tab-tile"))
            assertTrue("a screen reader finds no $part", compose.onAllNodesWithTag(part, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue("the pane keeps ${pane()} dp, at least $least", pane() >= least)
        assertEquals("the key bar stays, one row", 56f, height(compose.onNodeWithTag("key-bar").getBoundsInRoot()), 1f)
        // The pane that takes the keys says what the caption said (#260).
        assertEquals("Pane 1, input target, velo · build, Terminal", paneSaid())
        keyboard(0)
        assertTrue("the caption comes back with the keyboard down", captionShown())
        compose.onNodeWithTag("pane-caption-title", useUnmergedTree = true).assertTextEquals("velo · build")
        assertEquals("the caption says it again, the pane no more", "Pane 1, input target", paneSaid())
    }

    // Comfortable: 16 dp with the caption; its 40 and the gap of 8 above it go to the pane.
    @Test fun comfortable() = theCaptionGivesWay("comfortable", 63f)

    // Compact: 50.5 dp with the caption; its line and the compact gap go to the pane.
    @Test fun compact() = theCaptionGivesWay("compact", 78f)

    // Comfortable, with the caption: the pane keeps 393 − 8 − 8 − (8 + 40) − (8 + 56) less the keyboard, 265 − it.

    /** On either side of the least height: a keyboard of 144 dp leaves the pane 121 dp under the caption, one of 147 dp 118. */
    @Test fun theCaptionGoesUnderTheLeastHeight() {
        workspace("comfortable")
        keyboard(144)
        assertTrue("the caption stays", captionShown())
        assertEquals(121f, pane(), 1f)
        assertEquals("shown, the caption is heard once, on its own", "Pane 1, input target", paneSaid())
        keyboard(147)
        assertFalse("the caption gives way", captionShown())
        assertEquals("the pane holds the caption's room", 118f + 48f, pane(), 1f)
        assertEquals("Pane 1, input target, velo · build, Terminal", paneSaid())
    }

    /**
     * Hidden with the keyboard up, the caption comes back where a smaller keyboard leaves the pane the least height
     * and the margin with it (130 dp: 135), and not short of the margin (140 dp: 125), however long it waits there.
     */
    @Test fun aSmallerKeyboardBringsTheCaptionBack() {
        workspace("comfortable")
        keyboard(249)
        assertFalse(captionShown())
        keyboard(140)
        repeat(5) { settle() }
        assertFalse("short of the margin, it stays hidden", captionShown())
        assertEquals(125f + 48f, pane(), 1f)
        keyboard(130)
        assertTrue("with the margin, it comes back", captionShown())
        assertEquals(135f, pane(), 1f)
        assertEquals("Pane 1, input target", paneSaid())
        repeat(5) { settle() }
        assertTrue("and stays", captionShown())
    }

    /**
     * Two panes side by side: only the one that takes the keys says the caption's words, and a new name of the tab,
     * which comes with the state with the titles, reaches it while the caption is hidden.
     */
    @Test fun onlyTheInputTargetSaysTheCaptionAndANewNameReachesIt() {
        workspace("comfortable", twoPanes("build"))
        keyboard(249)
        assertFalse(captionShown())
        assertEquals(listOf("Pane 1, input target, velo · build, Terminal, 2 panes", "Pane 2"), panesSaid())
        serverSends(twoPanes("deploy"))
        assertEquals(listOf("Pane 1, input target, velo · deploy, Terminal, 2 panes", "Pane 2"), panesSaid())
    }

    /**
     * A screen reader reads a new description of the node it is on again: an agent's tab, named by a title that changes
     * many times a second, was read again each time (42 times in 10 s on the Velvet). While its focus is on the pane, the
     * caption's words stay; the new name is heard when the focus comes again.
     */
    @Test fun whileAScreenReaderIsOnThePaneTheWordsStay() {
        workspace("comfortable", twoPanes("build"))
        keyboard(249)
        readerFocusTo(0)
        for (name in listOf("agent a 1", "agent b 2", "agent c 3")) {
            serverSends(twoPanes(name))
            assertEquals("Pane 1, input target, velo · build, Terminal, 2 panes", panesSaid()[0])
        }
        readerFocusOff(0)
        assertEquals("the focus gone, the new name", "Pane 1, input target, velo · agent c 3, Terminal, 2 panes", panesSaid()[0])
        readerFocusTo(0)
        serverSends(twoPanes("agent d 4"))
        assertEquals("and that one is heard when the focus comes again", "Pane 1, input target, velo · agent c 3, Terminal, 2 panes", panesSaid()[0])
    }

    /** A change of the input target is read as before, the screen reader's focus on the pane or not; the words go with it. */
    @Test fun theInputTargetChangesAtOnceUnderTheFocus() {
        workspace("comfortable", twoPanes("build"))
        keyboard(249)
        readerFocusTo(0)
        readerFocusTo(1)
        serverSends(twoPanes("build", focused = "b"))
        assertEquals(listOf("Pane 1", "Pane 2, input target"), panesSaid())
        // The pane the focus is on takes the words when the focus comes again.
        readerFocusOff(1)
        assertEquals("Pane 2, input target, velo · build, Terminal, 2 panes", panesSaid()[1])
    }

    @Test fun theRuleComesBackOnlyWhereTheCaptionFits() {
        // Not with the keyboard down, however short the panes.
        assertFalse(captionGivesWay(squeezed = false, gaveWay = false, pane = 16f, caption = 48f))
        assertFalse(captionGivesWay(squeezed = false, gaveWay = true, pane = 64f, caption = 48f))
        // Shown: it goes under the least height, and stays at it.
        assertTrue(captionGivesWay(squeezed = true, gaveWay = false, pane = 16f, caption = 48f))
        assertFalse(captionGivesWay(squeezed = true, gaveWay = false, pane = 120f, caption = 48f))
        // Hidden: the pane holds the caption's room; it comes back only where the pane keeps the least height with it.
        assertTrue(captionGivesWay(squeezed = true, gaveWay = true, pane = 150f, caption = 48f))
        // And the margin over it.
        assertTrue(captionGivesWay(squeezed = true, gaveWay = true, pane = 168f, caption = 48f))
        assertFalse(captionGivesWay(squeezed = true, gaveWay = true, pane = 176f, caption = 48f))
    }
}
