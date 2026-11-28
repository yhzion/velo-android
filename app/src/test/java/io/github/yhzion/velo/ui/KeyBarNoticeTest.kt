package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pane column's notices over a key bar of two rows (#218 with #192): on the upright bar phone the default keys
 * take two rows, 106 high, and the notices that the workspace places measure the bar's real top, so they stay
 * above it, with the keyboard down and with it up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi",
    shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class KeyBarNoticeTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private lateinit var view: View
    private var conn: HostConnection? = null
    private var app: AppController? = null

    private val state = UiState(status = "connected", server = "velo", session = "s",
        windows = listOf(WindowInfo("w1", layout = LayoutNode(block = "a"))))
    private val missing = "The remote has no file at /data/local/nosuch/missing-file.txt."

    @After fun destroy() {
        conn?.destroy()
        app?.destroy()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** The workspace at the app's default size, Medium, the bar's keys the default ones. */
    private fun workspace(): HostConnection {
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), state).also { conn = it }
        val app = AppController(ApplicationProvider.getApplicationContext()).also { this.app = it }
        compose.setContent {
            view = LocalView.current
            VeloTheme(UiSize.Medium) {
                Workspace(app, c, remember { TerminalInputView(view.context) }, FoldInfo(), null, remember { PaneNotificationLaunches() },
                    settingsOpen = false, onSettings = {}, onTailscale = {})
            }
        }
        settle()
        return c
    }

    /** The keyboard up, as the window tells the view: the input method's inset, [height] dp tall. */
    private fun keyboardUp(height: Int) = compose.runOnIdle {
        val px = (height * view.resources.displayMetrics.density).toInt()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun height(b: DpRect) = (b.bottom - b.top).value

    /** The key bar takes two rows, and every notice drawn lies above its top. */
    private fun assertTheNoticesStayAboveATwoRowBar() {
        val bar = compose.onNodeWithTag("key-bar").getBoundsInRoot()
        assertEquals("two rows: 6 + 44 + 6 + 44 + 6", 106f, height(bar), 1f)
        val notices = compose.onAllNodesWithContentDescription("Error: $missing").fetchSemanticsNodes().map { it.boundsInRoot } +
            compose.onAllNodesWithTag("toast-message", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("a notice shows", notices.isNotEmpty())
        val density = compose.density.density
        for (n in notices) assertTrue("notice $n ends above the key bar's top ${bar.top}", n.bottom / density <= bar.top.value + 0.5f)
    }

    @Test fun twoNoticesStayAboveATwoRowKeyBar() {
        val c = workspace()
        c.uploading = "Uploading report.pdf…"
        c.error = missing
        settle()
        assertTheNoticesStayAboveATwoRowBar()
        compose.onNodeWithContentDescription("Error: $missing").assertExists()
    }

    @Test fun withTheKeyboardUpTheNoticesStayAboveATwoRowKeyBar() {
        val c = workspace()
        // Gboard on the bar phone upright: about 374 dp of 894.
        keyboardUp(374)
        settle()
        c.uploading = "Uploading report.pdf…"
        c.error = missing
        settle()
        assertTheNoticesStayAboveATwoRowBar()
    }
}
