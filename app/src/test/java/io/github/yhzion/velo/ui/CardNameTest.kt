package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.tileDotRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The names under the overview's cards (#48): each draws its tab's tile laid out by its face, by the brand it has in
 * the sidebar, with the marks the caption under the panes draws at its end. Every card's tile is in full colour, and
 * its dot is ringed in the backdrop the names lie on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class CardNameTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private var size by mutableStateOf(UiSize.Medium)

    private fun row(tab: TabInfo): TabRow {
        val state = UiState(status = "connected", sessions = listOf(SessionInfo("s", tabs = listOf(tab))))
        return sessionGroups(state, false, emptySet(), false, "").single().rows.single()
    }

    /**
     * [rows]' names on the backdrop, each centred in a card's room [room] high, as the row and the grid lay them out,
     * or beside a drum card for a null [room].
     */
    private fun show(rows: List<TabRow>, room: Dp? = GridLabelRoom, bright: (TabRow) -> Boolean = { false }) =
        compose.setContent {
            VeloTheme(size = size) {
                Column(Modifier.background(VeloTheme.colors.backdrop.surface)) {
                    for (r in rows) Box(
                        Modifier.testTag("room-${r.tab}").width(240.dp).then(if (room != null) Modifier.height(room) else Modifier)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CardName(r, connected = true, ActivityReadState(), bright = bright(r), room = room)
                    }
                }
            }
        }

    @Test fun everyNameDrawsItsTabsTileByTheBrandItHasInTheSidebar() {
        val programs = mapOf("c" to "claude", "x" to "codex", "o" to "omp", "d" to "docker", "v" to "vim", "s" to "ssh")
        show(programs.map { (id, program) -> row(TabInfo(id, program = program)) })
        compose.assertTileOf("card-name-c-tile", TileBrand.Claude)
        compose.assertTileOf("card-name-x-tile", TileBrand.Codex)
        compose.assertTileOf("card-name-o-tile", TileBrand.Omp)
        compose.assertTileOf("card-name-d-tile", TileBrand.Docker)
        // vim and ssh have no brand of their own: the terminal's tile, called by the program's name.
        compose.assertTileOf("card-name-v-tile", TileBrand.Terminal, description = "vim")
        compose.assertTileOf("card-name-s-tile", TileBrand.Terminal, description = "ssh")
    }

    @Test fun aWaitingTabShowsTheWarningDotAsInTheSidebarAndAWorkingOneItsDots() {
        show(listOf(row(TabInfo("w", program = "claude", activity = "blocked", block = "w1")),
            row(TabInfo("b", program = "claude", activity = "working", block = "b1"))))
        // The report not yet seen is the tile's dot; that it waits, the dot at the name's end.
        compose.drawn("card-name-w-tile-badge").assertExists()
        val dot = compose.drawn("card-name-w-attention").captureToImage().toPixelMap()
        assertEquals(VeloTheme.colors.backdrop.warning.toArgb(), dot[dot.width / 2, dot.height / 2].toArgb())
        compose.drawn("card-name-b-working").assertExists()
        compose.drawn("card-name-b-attention").assertDoesNotExist()
    }

    @Test fun aWorkingTabsProgressIsAPieWithoutDotsAsInTheSidebar() {
        // The overview card's name and the caption under the panes draw the tab as its sidebar row does (#251).
        show(listOf(row(TabInfo("p", program = "claude", activity = "working", block = "p1", progress = 30)),
            row(TabInfo("b", program = "claude", activity = "working", block = "b1"))))
        compose.drawn("card-name-p-tile-pie").assertExists()
        compose.drawn("card-name-p-tile-logo").assertDoesNotExist()
        compose.drawn("card-name-p-working").assertDoesNotExist()
        // The dots' words give way with them: the tile says the program works.
        val said = compose.drawn("card-name-p-tile").fetchSemanticsNode().config
        assertEquals(TabIndicator.Working.description, said[SemanticsProperties.StateDescription])
        compose.drawn("card-name-b-tile-logo").assertExists()
        compose.drawn("card-name-b-working").assertExists()
    }

    @Test fun everyCardsTileIsInFullColourAndItsDotIsRingedInTheBackdrop() {
        val done = row(TabInfo("u", program = "claude", activity = "done", block = "u1"))
        val dim = row(TabInfo("d", program = "claude", activity = "done", block = "d1"))
        show(listOf(done, dim), bright = { it == done })
        compose.drawn("card-name-u-tile-badge").assertExists()
        // The dimmer name keeps its tile as bright as the selected one's, as a sidebar row does: the fill beside the
        // logo is the same colour, but for the anti-aliasing of a face that lies a fraction of a pixel lower.
        val bright = fill("card-name-u-tile-face")
        val dimmer = fill("card-name-d-tile-face")
        for (shift in listOf(16, 8, 0)) {
            assertTrue("$bright, $dimmer", abs((bright shr shift and 0xFF) - (dimmer shr shift and 0xFF)) <= 3)
        }
        // On the ring, between the dot and the face: the backdrop's colour, not the face's or another surface's.
        val tile = compose.drawn("card-name-u-tile").captureToImage().toPixelMap()
        val density = compose.density.density
        val dot = tileDotRect(CaptionTile)
        val ring = dot.width / 2 + 1f
        val x = ((dot.center.x - ring / sqrt(2f)) * density).roundToInt()
        val y = ((dot.center.y + ring / sqrt(2f)) * density).roundToInt()
        assertEquals(VeloTheme.colors.backdrop.surface.toArgb(), tile[x, y].toArgb())
    }

    @Test fun theCountOfFivePanesStaysInTheGridsRoomAndTheLayersBelowTheCard() {
        show(listOf(row(TabInfo("p", program = "claude", panes = 6))))
        for (s in UiSize.entries) {
            size = s
            compose.waitForIdle()
            // The room's top is the card's bottom.
            val room = compose.bounds("room-p")
            val count = compose.bounds("card-name-p-tile-pane-count")
            val layer = compose.bounds("card-name-p-tile-layer-4")
            assertTrue("$s: the count's bottom ${count.bottom} past the room's ${room.bottom}", count.bottom <= room.bottom)
            assertTrue("$s: the farthest layer's top ${layer.top} over the card above ${room.top}", layer.top >= room.top)
        }
    }

    @Test fun theFaceSitsOnTheNamesLineAndTheDrumsNameKeepsItsSize() {
        show(listOf(row(TabInfo("r", program = "codex", title = "Review"))), room = null)
        val face = compose.bounds("card-name-r-tile-face")
        val title = compose.bounds("card-name-r-title")
        assertEquals(((title.top + title.bottom) / 2).value, ((face.top + face.bottom) / 2).value, 0.5f)
        assertEquals(DrumLabelText, compose.textLayout("card-name-r-title").layoutInput.style.fontSize)
    }

    /** The colour of the face tagged [tag] beside its logo: a tenth of the way in from its left, halfway down. */
    private fun fill(tag: String): Int {
        val map = compose.drawn(tag).captureToImage().toPixelMap()
        return map[map.width / 10, map.height / 2].toArgb()
    }
}
