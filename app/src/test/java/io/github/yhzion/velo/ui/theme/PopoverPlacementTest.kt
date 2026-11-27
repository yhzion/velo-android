package io.github.yhzion.velo.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntRect
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
 * A popover's place for its anchor (#182). [VeloPopover] with `keepsAnchorClear` never covers its anchor; without it,
 * as every popover but the minimap's picker opens, it is placed as before ([menuPosition]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w894dp-h393dp-420dpi")
class PopoverPlacementTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    /** A button near the screen's bottom with a popover higher than the room above it, open. */
    private fun show(keepsAnchorClear: Boolean) = compose.setContent {
        VeloTheme {
            val layer = remember { OverlayLayer() }
            var at by remember { mutableStateOf<IntRect?>(null) }
            OverlayLayerBox(layer, Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.testTag("button").size(48.dp).onGloballyPositioned { at = it.onScreen() })
                    Spacer(Modifier.height(8.dp))
                }
                VeloPopover(true, onDismissRequest = {}, modifier = Modifier.testTag("popover"), anchor = { at },
                    keepsAnchorClear = keepsAnchorClear) {
                    Box(Modifier.size(200.dp, 600.dp))
                }
            }
        }
    }

    @Test fun aPopoverIsPlacedAsBeforeAndMayCoverItsAnchor() {
        // Too high for either side: as [menuPosition] says, at the window's bottom margin, over the button.
        show(keepsAnchorClear = false)
        val button = compose.onNodeWithTag("button").getBoundsInRoot()
        val popover = compose.onNodeWithTag("popover").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("the popover ${popover.top}..${popover.bottom} over the button ${button.top}..${button.bottom}",
            popover.top < button.top && popover.bottom > button.top && popover.bottom <= root.bottom)
    }

    @Test fun aPopoverThatKeepsItsAnchorClearFitsTheRoomAboveIt() {
        show(keepsAnchorClear = true)
        val button = compose.onNodeWithTag("button").getBoundsInRoot()
        val popover = compose.onNodeWithTag("popover").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("the popover ${popover.top}..${popover.bottom} above the button from ${button.top}",
            popover.top >= root.top && popover.bottom <= button.top)
    }
}
