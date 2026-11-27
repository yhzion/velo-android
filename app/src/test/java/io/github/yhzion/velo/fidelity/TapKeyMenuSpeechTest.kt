package io.github.yhzion.velo.fidelity

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.yhzion.velo.ui.SettingsCategory
import io.github.yhzion.velo.ui.SettingsPanel
import io.github.yhzion.velo.ui.SettingsRoute
import io.github.yhzion.velo.ui.TapKey
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Keyboard page's tap-gesture menus call Esc "Escape" to a screen reader, as the key chips and the key bar do (#125). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class TapKeyMenuSpeechTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theMenuAndItsChoicesSayEscape() {
        compose.setContent {
            val context = LocalContext.current
            val host = remember {
                FakeSettingsHost(context).apply {
                    settings.saveTapGestures(true)
                    settings.saveDoubleTapKey(TapKey.Esc)
                    settings.saveTripleTapKey(TapKey.Enter)
                }
            }
            val layer = remember { OverlayLayer() }
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
                    SettingsPanel(host, onClose = {}, start = SettingsRoute(SettingsCategory.Keyboard))
                }
            }
        }
        compose.waitForIdle()
        val menu = compose.onNodeWithTag("keyboard-double-tap-menu").performScrollTo()
        menu.assertContentDescriptionEquals("Double tap: Escape")
        menu.performClick()
        compose.waitForIdle()
        // The menu's Esc choice: its drawn word stays Esc, a screen reader says Escape.
        compose.onNode(hasText("Esc") and isSelectable()).assertContentDescriptionEquals("Escape")
    }
}
