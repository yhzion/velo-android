package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.mediumAt
import io.github.yhzion.velo.ui.theme.tileLayers
import io.github.yhzion.velo.ui.theme.veloSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tab's tile in the caption under the panes (#45): laid out by its face, the face's centre on
 * the title's line and the board's 8 between the farthest part the tile draws and the title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class PaneCaptionTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private var size by mutableStateOf(UiSize.Medium)
    private var panes by mutableStateOf(1)
    private var compact by mutableStateOf(false)

    private fun row(panes: Int): TabRow {
        val tab = TabInfo("w", label = "build", program = "zsh", panes = panes)
        val state = UiState(status = "connected", sessions = listOf(SessionInfo("s", tabs = listOf(tab))))
        return sessionGroups(state, false, emptySet(), false, "").single().rows.single()
    }

    private fun show() = compose.setContent {
        VeloTheme(size = size) { PaneCaption(row(panes), connected = true, ActivityReadState(), host = "studio", compact = compact) }
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    @Test fun theFaceSitsOnTheTitlesLine() {
        show()
        for (s in UiSize.entries) {
            size = s
            compose.waitForIdle()
            val face = bounds("caption-tab-tile-face")
            val title = bounds("pane-caption-title")
            val faceCentre = (face.top + face.bottom) / 2
            val titleCentre = (title.top + title.bottom) / 2
            assertEquals("$s", titleCentre.value, faceCentre.value, 0.5f)
        }
    }

    @Test fun theBoardsEightLiesBetweenTheTileAndTheTitle() {
        show()
        for (s in UiSize.entries) for (n in listOf(1, 2, 4)) {
            size = s
            panes = n
            compose.waitForIdle()
            // The farthest part drawn right: the face alone, or the farthest layer behind it.
            val right = if (tileLayers(n) == 1) bounds("caption-tab-tile-face").right
            else maxOf(bounds("caption-tab-tile-face").right, bounds("caption-tab-tile-layer-${tileLayers(n)}").right)
            val gap = bounds("pane-caption-title").left - right
            assertEquals("$s, $n panes", mediumAt(s, 8.dp).value, gap.value, 0.5f)
        }
    }

    /**
     * The compact caption lies only gapCompact below the pane, and its row keeps the tile's box high, as before
     * #45. With the face centred, the farthest layer of a four-pane tab reaches 0.10 to 0.17 dp past the gap:
     * under one pixel, on the dimmest layer, so up to half a dp past the pane's bottom is allowed. A row tall
     * enough to hold every layer would make the compact caption 8 higher.
     */
    @Test fun inTheCompactStyleNoLayerReachesThePaneAbove() {
        compact = true
        panes = 4
        show()
        for (s in UiSize.entries) {
            size = s
            compose.waitForIdle()
            val pane = bounds("pane-caption").top - veloSizes(s).gapCompact
            val layer = bounds("caption-tab-tile-layer-4")
            assertTrue("$s: layer top ${layer.top} more than 0.5 dp past the pane's bottom $pane", layer.top >= pane - 0.5.dp)
            // The face still sits on the title's line.
            val face = bounds("caption-tab-tile-face")
            val title = bounds("pane-caption-title")
            assertEquals("$s", ((title.top + title.bottom) / 2).value, ((face.top + face.bottom) / 2).value, 0.5f)
        }
    }

    @Test fun aWorkingTabsProgressIsAPieAndItsPercentIsSaidWithTheTitle() {
        // The caption under the panes draws the pie as the sidebar row does, without the dots (#251).
        val tab = TabInfo("w", label = "build", program = "claude", activity = "working", block = "b1", progress = 30)
        val row = sessionGroups(UiState(status = "connected", sessions = listOf(SessionInfo("s", tabs = listOf(tab)))), false, emptySet(), false, "")
            .single().rows.single()
        compose.setContent { VeloTheme { PaneCaption(row, connected = true, ActivityReadState(), host = "studio") } }
        compose.waitForIdle()
        compose.onNodeWithTag("caption-tab-tile-pie", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("caption-tab-tile-logo", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("pane-caption-working", useUnmergedTree = true).assertDoesNotExist()
        fun said(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
        // The dots' word moves to the tile; the percent is said with the title, which draws without it.
        assertEquals(TabIndicator.Working.description, said("caption-tab-tile")[SemanticsProperties.StateDescription])
        assertEquals("studio · build · 30%", said("pane-caption-title")[SemanticsProperties.Text].joinToString(" "))
    }
}
