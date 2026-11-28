package io.github.yhzion.velo.ui.theme

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A row's second line says words it does not draw, such as a progress pie's percent (#251), and draws the same. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class TabListRowWordsTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theSaidWordsChangeWhatIsHeardNotWhatIsDrawn() {
        val line = "Working · 4s since report · ~/src/api"
        compose.setContent {
            VeloTheme(UiSize.Medium) {
                Column(Modifier.width(320.dp)) {
                    TabListRow("Index the files", {}, subtitle = line, subtitleSaid = "Working · 30% · 4s since report · ~/src/api", id = "said", tile = {})
                    TabListRow("Index the files", {}, subtitle = line, id = "drawn", tile = {})
                }
            }
        }
        compose.waitForIdle()
        fun heard(id: String) = compose.onNodeWithTag("tab-status-$id", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString(" ")
        assertEquals("Working · 30% · 4s since report · ~/src/api", heard("said"))
        assertEquals(line, heard("drawn"))
        val said = compose.onNodeWithTag("tab-status-said", useUnmergedTree = true).captureToImage().toPixelMap()
        val drawn = compose.onNodeWithTag("tab-status-drawn", useUnmergedTree = true).captureToImage().toPixelMap()
        assertEquals(drawn.width to drawn.height, said.width to said.height)
        for (y in 0 until drawn.height) for (x in 0 until drawn.width) assertEquals("($x,$y)", drawn[x, y], said[x, y])
    }
}
