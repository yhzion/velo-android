package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The unfolded screen half-folded on its side (tabletop, #261), the hinge across the middle, with a keyboard of 300 dp,
 * the board's: the panes keep the hinge's height, and the caption and the key bar share what the keyboard leaves
 * under them. Where that is too little for both, the caption gives way and the key bar stays whole, as on a short
 * window (#232). Before, the key bar, measured last, was cut: 832×750 dp left 75 dp under the panes, the key bar
 * kept 19 dp of its 56 (40 in the compact style); 1088×821 left 111, and it kept 55.
 * Hiding the caption gives the panes nothing there: they stay as high as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class TabletopKeyboardCaptionTest {
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

    /** The workspace on a [w]×[h] dp screen at 420 dpi, half-folded with the hinge across its middle, in the pane [style]. */
    private fun tabletop(w: Int, h: Int, style: String) {
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-land-420dpi")
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), state).also { conn = it }
        val app = AppController(ApplicationProvider.getApplicationContext()).also { this.app = it }
        app.settings.savePaneStyle(style)
        compose.setContent {
            view = LocalView.current
            val hinge = (h / 2f * view.resources.displayMetrics.density).toInt()
            VeloTheme(UiSize.Medium) {
                Workspace(app, c, remember { TerminalInputView(view.context) }, FoldInfo(tabletop = true, hingeTopPx = hinge), null,
                    remember { PaneNotificationLaunches() }, settingsOpen = false, onSettings = {}, onTailscale = {})
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
    private fun keyBar() = height(compose.onNodeWithTag("key-bar").getBoundsInRoot())
    private fun captionShown() = compose.onAllNodesWithTag("pane-caption").fetchSemanticsNodes().isNotEmpty()
    private fun paneSaid() = compose.runOnIdle { (view as ViewGroup).descendants.filterIsInstance<PaneSurfaceView>().single().contentDescription?.toString() }

    /** With the keyboard up the caption gives way, the key bar is whole, and the panes keep the hinge's height. */
    private fun theCaptionGivesWay(w: Int, h: Int, style: String) {
        tabletop(w, h, style)
        assertTrue("the caption shows with the keyboard down", captionShown())
        val before = pane()
        keyboard(300)
        assertFalse("the caption gives way", captionShown())
        assertEquals("the key bar is whole", 56.4f, keyBar(), 0.5f)
        assertEquals("the panes keep the hinge's height", before, pane(), 0.5f)
        assertEquals("the pane that takes the keys says the caption's words", "Pane 1, input target, velo · build, Terminal", paneSaid())
        keyboard(0)
        assertTrue("the caption comes back with the keyboard down", captionShown())
        assertEquals("Pane 1, input target", paneSaid())
    }

    @Test fun theSmallerScreen() = theCaptionGivesWay(832, 750, "comfortable")

    @Test fun theSmallerScreenCompact() = theCaptionGivesWay(832, 750, "compact")

    // 111 dp under the panes: the caption's 48 and the key bar's 64 are 1.5 more.
    @Test fun theLargerScreen() = theCaptionGivesWay(1088, 821, "comfortable")

    /** Where both fit, the caption stays, and the pane says nothing of it: it is heard there already. */
    @Test fun whereBothFitTheCaptionStays() {
        tabletop(1088, 821, "compact")
        keyboard(300)
        assertTrue("the caption stays", captionShown())
        assertEquals("the key bar is whole", 56.4f, keyBar(), 0.5f)
        assertEquals("Pane 1, input target", paneSaid())
    }

    /**
     * Hidden, the caption comes back where a smaller keyboard leaves room for it and the margin over the key bar
     * (240 dp: about 135 under the panes), and not short of the margin (260 dp: about 115).
     */
    @Test fun aSmallerKeyboardBringsTheCaptionBack() {
        tabletop(832, 750, "comfortable")
        keyboard(300)
        assertFalse(captionShown())
        keyboard(260)
        repeat(5) { settle() }
        assertFalse("short of the margin, it stays hidden", captionShown())
        keyboard(240)
        assertTrue("with the margin, it comes back", captionShown())
        assertEquals("the key bar stays whole", 56.4f, keyBar(), 0.5f)
        repeat(5) { settle() }
        assertTrue("and stays", captionShown())
    }

    @Test fun theRuleUnderTheHinge() {
        // Not with the keyboard down, however little is left.
        assertFalse(captionGivesWayUnderHinge(squeezed = false, gaveWay = false, left = 0f, caption = 48f))
        assertFalse(captionGivesWayUnderHinge(squeezed = false, gaveWay = true, left = 0f, caption = 48f))
        // Shown: it goes where nothing is left over, and stays where a pixel is.
        assertTrue(captionGivesWayUnderHinge(squeezed = true, gaveWay = false, left = 0f, caption = 48f))
        assertFalse(captionGivesWayUnderHinge(squeezed = true, gaveWay = false, left = 0.4f, caption = 48f))
        // Hidden: it comes back only where what is left holds it and the margin.
        assertTrue(captionGivesWayUnderHinge(squeezed = true, gaveWay = true, left = 50f, caption = 48f))
        assertFalse(captionGivesWayUnderHinge(squeezed = true, gaveWay = true, left = 56f, caption = 48f))
    }
}
