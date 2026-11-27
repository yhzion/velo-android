package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.mediumAt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The links at the bottom of the start screen stay whole: when the window is too low for the card and the links (the
 * empty search on the bar phone from the Medium size, more steps on the folded Fold8 at Large), the page scrolls on
 * to the links and their bottom margin, Settings' update dot inside the window (#86). Every step, every UI size, on
 * each device shape. Native graphics, so text is measured and wraps as on a device: the legacy mode gives text almost
 * no width, and the card would then fit the window where it does not on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class StartFooterTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Config(qualifiers = "w393dp-h894dp-420dpi")
    @Test fun theLinksStayWholeOnTheBarPhone() = linksStayWhole(scrolls = "start-2-empty at Medium")

    @Config(qualifiers = "w475dp-h751dp-420dpi")
    @Test fun theLinksStayWholeOnTheFoldedFold8() = linksStayWhole(scrolls = "start-2-empty at Medium")

    @Config(qualifiers = "w821dp-h1088dp-420dpi")
    @Test fun theLinksStayWholeOnTheUnfoldedFold8() = linksStayWhole()

    /** Each step at each size has its links whole once scrolled to; the page [scrolls] (a step at a size) must scroll to them. */
    private fun linksStayWhole(scrolls: String? = null) {
        var size by mutableStateOf(UiSize.Small)
        var step by mutableStateOf(Steps.keys.first())
        compose.setContent {
            VeloTheme(size = size) { StartScreenBody(Steps.getValue(step), onAddByQr = {}, onSettings = {}, settingsBadge = true) }
        }
        for (each in UiSize.entries) for (name in Steps.keys) {
            size = each
            step = name
            compose.waitForIdle()
            val at = "$name at $each"
            val before = compose.onNodeWithTag("start-action").getUnclippedBoundsInRoot()
            compose.onNodeWithTag("start-footer").performScrollTo()
            val screen = compose.onNodeWithTag("screen-start").getUnclippedBoundsInRoot()
            val footer = compose.onNodeWithTag("start-footer").getUnclippedBoundsInRoot()
            val action = compose.onNodeWithTag("start-action").getUnclippedBoundsInRoot()
            val settings = compose.onNodeWithTag("start-settings-link").getUnclippedBoundsInRoot()
            val dot = compose.onNodeWithTag("start-settings-update-dot", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val margin = mediumAt(each, 20.dp)
            // Half a pixel at 420 dpi, for the rounding of the layout to whole pixels.
            val slack = 0.2.dp
            // The case the issue found: the links lie below the window until the page scrolls to them.
            if (at == scrolls) assertTrue("$at: the page scrolls to the links", action.top < before.top - slack)
            assertTrue("$at: the links $footer below the card $action", footer.top >= action.bottom - slack)
            assertTrue("$at: the links $footer inside the window $screen", footer.top >= screen.top - slack && footer.bottom <= screen.bottom + slack)
            assertTrue("$at: Settings $settings keeps its $margin margin above the window's edge ${screen.bottom}",
                settings.bottom + margin <= screen.bottom + slack)
            assertTrue("$at: the update dot $dot inside the window $screen", dot.top >= screen.top && dot.bottom <= screen.bottom &&
                dot.left >= screen.left && dot.right <= screen.right)
        }
    }

    private companion object {
        val Computers = listOf(DiscoveredServer("studio-mac", "macOS · 100.101.4.20"), DiscoveredServer("build-server", "Linux · 100.101.9.3"))

        fun model(step: StartStep, waitingFor: String? = null, found: List<DiscoveredServer> = emptyList(),
                  note: FoundNote = FoundNote.None, searching: Boolean = false, opening: String? = null) = StartCardModel(
            step = step, waitingFor = waitingFor, unopened = null,
            connectedAs = if (step == StartStep.Found) "Connected as velo-phone" else null,
            found = found, note = note, searching = searching,
            logIn = {}, loginPageAgain = { null }, tryAgain = {}, searchAgain = {}, connect = {}, getRex = { true },
            opening = opening, openingProgress = 0.62f,
        )

        /** The StartKit board's five steps, as the fidelity scenes draw them. */
        val Steps = linkedMapOf(
            "start-1" to model(StartStep.LogIn(null)),
            "start-1-wait" to model(StartStep.LogIn("https://login.tailscale.com/a/sample"), waitingFor = "https://login.tailscale.com/a/sample"),
            "start-2" to model(StartStep.Found, found = Computers, searching = true),
            "start-2-empty" to model(StartStep.Found, note = FoundNote.NothingFound),
            "start-3" to model(StartStep.Found, opening = "studio-mac"),
        )
    }
}
