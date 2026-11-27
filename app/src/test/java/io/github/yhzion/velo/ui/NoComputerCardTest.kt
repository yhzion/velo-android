package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
                "On the computer, run `rex server tailscale on`. The tailnet also needs MagicDNS and HTTPS certificates.")
            // The hint sits inside the card, between the first step and Get Rex for Mac. Unclipped bounds, because the
            // page scrolls and a neighbour can lie partly outside the window at the Large size.
            val card = compose.onNodeWithTag("no-computer-card").getUnclippedBoundsInRoot()
            val step = compose.onNodeWithTag("no-computer-hint").getUnclippedBoundsInRoot()
            val getRex = compose.onNodeWithTag("get-rex-button").getUnclippedBoundsInRoot()
            val bounds = hint.getUnclippedBoundsInRoot()
            assertTrue("$each: $bounds inside $card", bounds.top >= card.top && bounds.bottom <= card.bottom &&
                bounds.left >= card.left && bounds.right <= card.right)
            assertTrue("$each: after the first step", bounds.top >= step.bottom)
            assertTrue("$each: before Get Rex for Mac", bounds.bottom <= getRex.top)
        }
    }

    /**
     * A tailnet member who is not the server node's owner gets 403 for `/llms.txt`, so the search lists no server
     * (#72): the step about someone else's computer also says what its owner must run, in the same sentence, so the
     * card gains no paragraph and Search again stays near the first screen.
     */
    @Test fun theShareStepSaysWhatTheOwnerMustRun() {
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
            val share = compose.onNodeWithTag("share-hint").performScrollTo().assertIsDisplayed()
            share.assertTextEquals(
                "Or ask the owner to share their computer with you in Tailscale and to run " +
                    "`rex server tailscale on --allow-tailnet-members`.")
            compose.onNodeWithTag("member-access-hint").assertDoesNotExist()
            val card = compose.onNodeWithTag("no-computer-card").getUnclippedBoundsInRoot()
            val getRex = compose.onNodeWithTag("get-rex-button").getUnclippedBoundsInRoot()
            val again = compose.onNodeWithTag("search-again-button").getUnclippedBoundsInRoot()
            val bounds = share.getUnclippedBoundsInRoot()
            assertTrue("$each: $bounds inside $card", bounds.top >= card.top && bounds.bottom <= card.bottom &&
                bounds.left >= card.left && bounds.right <= card.right)
            assertTrue("$each: after Get Rex for Mac", bounds.top >= getRex.bottom)
            assertTrue("$each: before Search again", bounds.bottom <= again.top)
        }
    }
}
