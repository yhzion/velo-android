package io.github.yhzion.velo.ui.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Mixed tabs' tiles as drawn (#217): each layer behind the face is drawn in its own pane's brand, straight above the
 * face. The capture is also written to `app/build/reports/tile-stack.png`, to hold beside Rex for Mac.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h894dp-420dpi")
class TabTileStackDrawTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val c = TileBrand.Claude
    private val o = TileBrand.OpenCode
    private val x = TileBrand.Codex
    private val t = TileBrand.Terminal

    /** The mixed tabs of #217's Done when: 2, 3, 4, 5 and 8 panes, bash in front, then a Claude Code face. */
    private val tabs = listOf(
        Triple(t, 2, listOf(c)),
        Triple(t, 3, listOf(c, o)),
        Triple(t, 4, listOf(c, o, x)),
        Triple(t, 5, listOf(o, c, x, c)),
        Triple(t, 8, listOf(o, c, x, c, o, c, c)),
        Triple(c, 4, listOf(t, TileBrand.Neovim, TileBrand.Docker)),
    )

    private val size = 44f

    @Test fun eachLayerShowsItsPanesBrandAboveTheFace() {
        compose.setContent {
            VeloTheme {
                Column(Modifier.testTag("sheet").background(VeloTheme.surface.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for ((i, tab) in tabs.withIndex()) {
                            val (face, panes, others) = tab
                            TabTile(face, panes = panes, behind = tileBehind(face, others, panes), size = VeloTheme.medium(size.dp), tag = "t$i")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for ((i, tab) in tabs.withIndex()) {
                            val (face, panes, others) = tab
                            TabTile(face, panes = panes, behind = tileBehind(face, others, panes), tag = "s$i")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("sheet").captureToImage()
        File("build/reports").mkdirs()
        File("build/reports/tile-stack.png").outputStream().use { sheet.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }

        val density = compose.density.density
        /** The colour of [layer]'s top edge above the one in front of it, in the middle, of tile `t<i>`. */
        fun edge(i: Int, layer: Int): Triple<Int, Int, Int> {
            val map = compose.onNodeWithTag("t$i", useUnmergedTree = true).captureToImage().toPixelMap()
            val l = tileBackLayers(tabs[i].second, size).single { it.number == layer }
            val r = tileLayerRect(l, size)
            val p = map[(r.center.x * density).roundToInt(), ((r.top + tileStep(size) / 2) * density).roundToInt()]
            return Triple((p.red * 255).roundToInt(), (p.green * 255).roundToInt(), (p.blue * 255).roundToInt())
        }
        fun warm(p: Triple<Int, Int, Int>) = p.first > p.third + 25
        fun grey(p: Triple<Int, Int, Int>) = maxOf(p.first, p.second, p.third) - minOf(p.first, p.second, p.third) < 16
        // bash in front: Claude Code's orange just behind it, not bash's glass.
        assertTrue("${edge(0, 2)}", warm(edge(0, 2)))
        assertTrue("${edge(2, 2)}", warm(edge(2, 2)))
        // OpenCode's and Codex's layers are not orange.
        assertTrue("${edge(2, 3)}", !warm(edge(2, 3)))
        assertTrue("${edge(2, 4)}", !warm(edge(2, 4)))
        // A Claude Code face over bash: the glass, not the face's orange.
        assertTrue("${edge(5, 2)}", grey(edge(5, 2)))
    }
}
