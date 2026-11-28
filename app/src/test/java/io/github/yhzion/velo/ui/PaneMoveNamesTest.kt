package io.github.yhzion.velo.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.fidelity.CorePackage
import io.github.yhzion.velo.fidelity.ShadowVeloCore
import io.github.yhzion.velo.fidelity.fakeConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #234: a pane move's capsule and its places' actions name a tab as its card does, from the connection's live state. The
 * tab's program reports a title; the steady state the move's overlay is drawn from has none, so it said the program alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class PaneMoveNamesTest {
    private val shell = WindowInfo("t1", layout = LayoutNode(block = "a"))
    private val vim = WindowInfo("t2", layout = LayoutNode(block = "c"))
    private val state = UiState(status = "connected", server = "velo", session = "s", windows = listOf(shell, vim), window = "t1",
        blocks = mapOf("a" to BlockInfo(program = "/bin/zsh"), "c" to BlockInfo(title = "vim README.md", program = "vim")))
    private var conn: HostConnection? = null

    @After fun destroy() {
        conn?.destroy()
    }

    @Test fun aMovesTabsAreNamedAsTheirCardsAre() {
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), state).also { conn = it }
        shadowOf(Looper.getMainLooper()).idle()
        // The card's name, and what the overlay's own state says.
        assertEquals("vim README.md", tabTitle(c.state, vim))
        assertEquals("vim", tabTitle(c.steady, vim))

        val names = moveTabNames(c)
        assertEquals(mapOf("t1" to tabTitle(c.state, shell), "t2" to "vim README.md"), names)
        val beside = MovePlace.Beside("t2", "c", PaneEdge.Left, PaneRect("c", 0f, 0f, 1f, 1f))
        assertEquals("Left of Pane 1 · vim README.md", placeCaption(beside, { "Pane 1" }, names::getValue, null))
        assertEquals("Move to a new tab · tab “vim README.md” closes", placeCaption(MovePlace.NewTab, { "Pane 1" }, names::getValue, "t2"))
    }
}
