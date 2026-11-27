package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.InputMode
import io.github.yhzion.velo.ui.theme.VeloPalettes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The key bar's caps (#142) drawn: one width across the bar, taps and holds sent as before, the
 * armed modifier selected and a paused bar taking no tap.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the labels measure as on a phone, which decides whether the caps fit.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w821dp-h1088dp-420dpi")
class KeyBarCapsTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val keys = Settings.DEFAULT_KEY_BAR.split(',')
    private val sent = mutableListOf<Pair<String, Boolean>>()
    private var enabled by mutableStateOf(true)
    private lateinit var inputMode: InputModeManager

    private fun show(width: Dp, armed: Set<String> = emptySet()) {
        compose.setContent {
            inputMode = LocalInputModeManager.current
            VeloTheme(UiSize.Medium) {
                KeyBar(keys, armed, Modifier.width(width), enabled = enabled) { key, repeat -> sent += key to repeat }
            }
        }
        compose.waitForIdle()
    }

    // The tag is on the cap, inside the key that takes the taps.
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
    private fun key(name: String) = compose.onNodeWithTag(keyTestTag(name), useUnmergedTree = true).onParent()

    @Test fun theFoldedFold8BarHoldsEveryKeyAtOneWidth() {
        // WsClosedTerminal: the key bar is 390.5 wide.
        show(390.5.dp)
        val bar = bounds("key-bar")
        val caps = keys.map { bounds(keyTestTag(it)) }
        assertTrue("${caps.last().right} > ${bar.right}", caps.last().right <= bar.right)
        val widths = caps.map { it.right - it.left }
        for (w in widths) assertEquals(widths.first().value, w.value, 0.5f)
    }

    @Test fun aTapSendsTheKeyAndAHoldRepeatsIt() {
        show(560.dp)
        key("Esc").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Esc" to false), sent)
        sent.clear()
        compose.mainClock.autoAdvance = false
        key("Left").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        key("Left").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Left" to false, sent.first())
        assertTrue("$sent", sent.size > 2 && sent.drop(1).all { it == ("Left" to true) })
    }

    @Test fun anArmedModifierIsSelectedAndAPausedBarTakesNoTap() {
        show(560.dp, armed = setOf("Ctrl"))
        key("Ctrl").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        enabled = false
        compose.waitForIdle()
        key("Esc").performClick()
        compose.waitForIdle()
        assertTrue("$sent", sent.isEmpty())
    }

    @Test fun theKeyWithTheKeyboardsFocusIsAStepLighter() {
        show(560.dp)
        val cap = compose.onNodeWithTag(keyTestTag("Esc"), useUnmergedTree = true)
        fun fill() = cap.captureToImage().toPixelMap().let { it[it.width / 2, 4] }
        assertEquals(VeloPalettes.dark.chrome.glass, fill())
        // A hardware keyboard's Tab moves the focus: the keys take it only out of touch mode.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        key("Esc").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertEquals(VeloPalettes.dark.chrome.glassSelected, fill())
    }
}
