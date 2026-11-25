package io.github.yhzion.velo.fidelity

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app side of the geometry layer: tagged nodes come back in dp, in tree order, with their text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class TaggedNodesTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun boxesAreInDpFromTheRoot() {
        compose.setContent {
            Column(Modifier.padding(start = 16.dp, top = 40.dp)) {
                Box(Modifier.size(100.dp, 60.dp).testTag("card")) {
                    Text("Updates", Modifier.padding(top = 8.dp).testTag("label"))
                }
                Box(Modifier.size(361.dp, 1.dp).testTag("divider"))
                Box(Modifier.size(361.dp, 1.dp).testTag("divider"))
            }
        }
        val nodes = compose.taggedNodes()
        assertEquals(listOf("card", "label", "divider", "divider"), nodes.map { it.tag })
        val card = nodes[0]
        assertEquals(16f, card.x, 0.5f)
        assertEquals(40f, card.y, 0.5f)
        assertEquals(100f, card.w, 0.5f)
        assertEquals(60f, card.h, 0.5f)
        assertEquals("Updates", nodes[1].text)
        assertEquals(48f, nodes[1].y, 0.5f)
        assertEquals(100f, nodes[2].y, 0.5f)
        assertEquals(101f, nodes[3].y, 0.5f)
    }
}
