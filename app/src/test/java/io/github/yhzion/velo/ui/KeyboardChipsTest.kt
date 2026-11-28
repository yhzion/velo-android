package io.github.yhzion.velo.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Keyboard settings offer Shift+Tab, Delete and Paste (#219), and Page Up and Down, as chips drawn with their icons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class KeyboardChipsTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val settings = Settings(RuntimeEnvironment.getApplication().getSharedPreferences("keyboard-chips", Context.MODE_PRIVATE))

    private fun show() {
        compose.setContent { VeloTheme(UiSize.Medium) { KeyboardSettings(settings) } }
        compose.waitForIdle()
    }

    private fun icon(chip: String) =
        compose.onNode(hasTestTag(KeyIconTag) and hasAnyAncestor(hasTestTag("keyboard-chip-$chip")), useUnmergedTree = true)

    @Test fun theNewChipsAreOfferedWithTheirIconsAndOff() {
        show()
        for ((chip, said) in listOf("backtab" to "Shift Tab", "delete" to "Delete", "paste" to "Paste",
            "page-up" to "Page Up", "page-down" to "Page Down")) {
            compose.onNodeWithTag("keyboard-chip-$chip").assertContentDescriptionEquals(said).assertIsNotSelected()
            icon(chip).assertExists()
        }
        // A key named in a word keeps its word.
        icon("esc").assertDoesNotExist()
    }

    @Test fun aPickedChipGoesOnTheBarBeforeBackspace() {
        show()
        compose.onNodeWithTag("keyboard-chip-delete").performClick()
        compose.waitForIdle()
        assertEquals(Settings.DEFAULT_KEY_BAR.split(',').dropLast(1) + "Delete" + "Backspace", settings.keyBar)
    }
}
