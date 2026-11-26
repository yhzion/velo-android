package io.github.yhzion.velo.fidelity

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.ui.SettingsCategory
import io.github.yhzion.velo.ui.SettingsPanel
import io.github.yhzion.velo.ui.SettingsRoute
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Remotes page's Tailscale row draws Tailscale's logo whether Velo's node runs or not; the status line tells which. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class RemotesTailscaleLogoTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theRowShowsTheSameLogoWhenTheNodeRunsAndWhenItIsOff() {
        var running by mutableStateOf(true)
        compose.setContent {
            val context = LocalContext.current
            val state = if (running) TailnetState.Running("velo-phone", listOf("100.101.12.7")) else TailnetState.Off
            // A new host for each state: the fake's state is a plain field, so the page is drawn afresh.
            val host = remember(running) { FakeSettingsHost(context).apply { tailnetState = state } }
            val layer = remember { OverlayLayer() }
            VeloTheme(UiSize.Medium) {
                OverlayLayerBox(layer, Modifier.fillMaxSize().background(Color.Black)) {
                    SettingsPanel(host, onClose = {}, start = SettingsRoute(SettingsCategory.Remotes))
                }
            }
        }
        val on = logoPixels()
        running = false
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val off = logoPixels()
        assertTrue("the logo draws nothing", on.toSet().size > 1)
        assertArrayEquals(on, off)
    }

    private fun logoPixels(): IntArray {
        val logo = compose.onNodeWithTag("remotes-tailscale-logo", useUnmergedTree = true).assertIsDisplayed()
        val map = logo.captureToImage().toPixelMap()
        return IntArray(map.width * map.height) { map[it % map.width, it / map.width].toArgb() }
    }
}
