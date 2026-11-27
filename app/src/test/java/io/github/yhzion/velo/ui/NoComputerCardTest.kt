package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A search that found nothing says what to check on the computer and the tailnet (#66), at every UI size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class NoComputerCardTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theCardSaysToServeOnTheTailnetAndTurnOnHttps() {
        val model = StartCardModel(
            step = StartStep.Found, waitingFor = null, unopened = null, connectedAs = "Connected as velo-phone",
            found = emptyList(), note = FoundNote.NothingFound, searching = false,
            logIn = {}, loginPageAgain = { null }, tryAgain = {}, searchAgain = {}, connect = {}, getRex = { true },
        )
        var size by mutableStateOf(UiSize.Small)
        compose.setContent { VeloTheme(size = size) { StartScreenBody(model, onAddByQr = {}, onSettings = {}) } }
        for (each in UiSize.entries) {
            size = each
            compose.waitForIdle()
            val hint = compose.onNodeWithTag("tailnet-https-hint").performScrollTo().assertIsDisplayed()
            hint.assertTextEquals(
                "On the computer, run rex server tailscale on. The tailnet also needs MagicDNS and HTTPS certificates.")
            // The hint wraps inside the card rather than running past its edge.
            val card = compose.onNodeWithTag("no-computer-card").getBoundsInRoot()
            val bounds = hint.getBoundsInRoot()
            assertTrue("$each: $bounds inside $card", bounds.left >= card.left && bounds.right <= card.right)
        }
    }
}
