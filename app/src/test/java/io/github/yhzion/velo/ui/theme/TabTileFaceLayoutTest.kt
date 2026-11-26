package io.github.yhzion.velo.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tile laid out by its face (`byFace`, the caption under the panes) keeps its face inside the slot it reports,
 * left to right and right to left: the face sits at the box's start, so the box is placed relative to the direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class TabTileFaceLayoutTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var panes by mutableStateOf(1)

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()

    @Test fun theFaceStaysInsideTheTilesSlotInEitherDirection() {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                VeloTheme {
                    Box(Modifier.testTag("slot")) { TabTile(TileBrand.Zsh, panes = panes, size = 18.dp, tag = "t", byFace = true) }
                }
            }
        }
        for (d in LayoutDirection.entries) for (n in listOf(1, 2, 4)) {
            direction = d
            panes = n
            compose.waitForIdle()
            val slot = bounds("slot")
            val face = bounds("t-face")
            val tolerance = 0.5f
            assertTrue("$d, $n panes: face $face, slot $slot", face.left.value >= slot.left.value - tolerance)
            assertTrue("$d, $n panes: face $face, slot $slot", face.right.value <= slot.right.value + tolerance)
            assertTrue("$d, $n panes: face $face, slot $slot", face.top.value >= slot.top.value - tolerance)
            assertTrue("$d, $n panes: face $face, slot $slot", face.bottom.value <= slot.bottom.value + tolerance)
        }
    }
}
