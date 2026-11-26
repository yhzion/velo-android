package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.tileBrand
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
 * The titles on the rail's drum (#48): each draws its tab's tile, by the brand its program has in the sidebar, laid
 * out by its face above the name, and the tile and two lines of name stay inside the title's height.
 */
@RunWith(RobolectricTestRunner::class)
// Text is measured with real fonts: the name's lines are counted.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class RailWheelTilesTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val windows = listOf(
        WheelWindow("c", "Assistant", "claude"),
        WheelWindow("x", "Review the patch before it lands on main", "codex"),
        WheelWindow("v", "notes.md", "vim"),
        WheelWindow("s", "build box", "ssh"),
        WheelWindow("n", "editor", "nvim"),
    )

    private var size by mutableStateOf(UiSize.Medium)

    /**
     * The strip with its wheel held open on [shown], as a long press opens it. The held wheel turns frame by frame,
     * so the clock is moved by hand: the test never waits for it to rest.
     */
    private fun holdWheel(shown: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VeloTheme(size = size) {
                RailScrubber(windows, shown = shown, scrolls = false, turns = true, haptics = false,
                    pane = { null }, touchEpoch = { 0 }, pressed = { false }, onScroll = { _, _ -> false },
                    onOpen = { windows.map { it.id } }, onTurn = { _, _ -> }, onPick = {}, onSelect = {},
                    modifier = Modifier.size(60.dp, 600.dp).testTag("strip"))
            }
        }
        compose.onNodeWithTag("strip").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
    }

    @Test fun eachTitleDrawsItsTabsTileByTheBrandItHasInTheSidebar() {
        holdWheel("x")
        for (window in windows) compose.assertTileOf("wheel-title-${window.id}-tile", tileBrand(window.icon))
        // vim and ssh have no brand of their own: the terminal's tile, as in the sidebar and the caption.
        compose.assertTileOf("wheel-title-v-tile", TileBrand.Terminal)
        compose.assertTileOf("wheel-title-s-tile", TileBrand.Terminal)
    }

    @Test fun theTileAndTwoLinesOfNameStayInsideTheTitle() {
        holdWheel("x")
        for (s in UiSize.entries) {
            size = s
            compose.mainClock.advanceTimeByFrame()
            val face = compose.bounds("wheel-title-x-tile-face")
            val name = compose.bounds("wheel-title-x-name")
            assertEquals("$size", 2, compose.textLayout("wheel-title-x-name").lineCount)
            // The title's parts are centred in it: together no higher than it, they stay inside it.
            assertTrue("$size: the face and the name take ${name.bottom - face.top}", name.bottom - face.top <= TitleHeight)
            assertTrue("$size: the face ${face.bottom} runs into the name ${name.top}", face.bottom <= name.top)
            // Laid out by its face, a tile of one pane is centred over the name, as the old mark was.
            assertEquals("$size", ((name.left + name.right) / 2).value, ((face.left + face.right) / 2).value, 0.5f)
        }
    }
}
