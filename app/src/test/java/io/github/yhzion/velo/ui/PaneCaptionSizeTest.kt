package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.TextUnit
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The caption's title (pane-caption-title) is in the board's caption type at the chosen UI size, and nothing
 * else (#46). No pinch is exercised here: `PaneCaption` takes no terminal text size, so its signature makes one
 * impossible to pass, and a pinch in the focused pane can change only the pane's text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaneCaptionSizeTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private var size by mutableStateOf(UiSize.Medium)

    private fun titleFontSize(): TextUnit {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("pane-caption-title", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first().layoutInput.style.fontSize
    }

    @Test fun theTitleTakesTheCaptionTypeAtEachUiSize() {
        var expected = TextUnit.Unspecified
        compose.setContent {
            VeloTheme(size = size) {
                expected = VeloTheme.type.caption.fontSize
                PaneCaption(null, connected = true, ActivityReadState(), host = "velo-phone")
            }
        }
        for (choice in UiSize.entries) {
            size = choice
            compose.waitForIdle()
            assertEquals("at $choice", expected, titleFontSize())
        }
    }
}
