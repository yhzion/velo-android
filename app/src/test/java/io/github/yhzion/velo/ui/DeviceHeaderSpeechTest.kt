package io.github.yhzion.velo.ui

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.Speech
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The This Device row tells a screen reader the node's state once, in the dot's word, in every
 * state (#134). It reads what an accessibility service gets for the row ([Speech]): the words and
 * states of the row's node and of its descendants in the Compose view's AccessibilityNodeInfo tree.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class DeviceHeaderSpeechTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theRowSaysItsNameItsAddressAndTheDotsWordOnce() {
        var device by mutableStateOf(deviceRow(TailnetState.Off, paused = false))
        lateinit var view: View
        compose.setContent { view = LocalView.current; VeloTheme(UiSize.Medium) { DeviceHeader(device, onClick = {}) } }
        val states = listOf(
            TailnetState.Off, TailnetState.Starting, TailnetState.NeedsLogin("https://login.example/abc"), TailnetState.LoggedOut,
            TailnetState.Error("the node is stopped"), TailnetState.Running("", emptyList()),
            TailnetState.Running("velo", emptyList()), TailnetState.Running("velo", listOf("192.0.2.40")),
        )
        for (state in states) for (paused in listOf(false, true)) {
            device = deviceRow(state, paused)
            compose.waitForIdle()
            val row = compose.onNodeWithTag("this-device-row").fetchSemanticsNode().id
            val words = compose.runOnIdle { Speech.saidAt(view, row) }
            // One description that says a part twice ("velo, velo") is said twice too (#167).
            assertFalse("$state paused=$paused: $words", Speech.saysTwice(words))
            val said = words.joinToString(", ")
            val running = state as? TailnetState.Running
            val expected = listOfNotNull("This Device", running?.name?.ifEmpty { null }, running?.ips?.firstOrNull(),
                tailnetChip(state, paused).text).joinToString(", ")
            assertEquals("$state paused=$paused", expected, said)
        }
    }
}
