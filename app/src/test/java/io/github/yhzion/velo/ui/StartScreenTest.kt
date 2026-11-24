package io.github.yhzion.velo.ui

import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.TailnetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartScreenTest {
    @Test fun aWantedLoginOpensTheNodesLoginPage() {
        assertEquals(TailnetLoginStep.Open("https://login.example/1"), tailnetLoginStep(wanted = true, TailnetState.NeedsLogin("https://login.example/1")))
    }

    @Test fun aWantedLoginWaitsWhileTheNodeIsOnItsWayToALoginPage() {
        assertEquals(TailnetLoginStep.Keep, tailnetLoginStep(wanted = true, TailnetState.Starting))
        assertEquals(TailnetLoginStep.Keep, tailnetLoginStep(wanted = true, TailnetState.LoggedOut))
    }

    @Test fun aWantedLoginEndsWhenTheNodeWillShowNoLoginPageForIt() {
        // A node that went straight to Running, was turned off or failed: a later Log out must not open the browser unasked.
        assertEquals(TailnetLoginStep.Drop, tailnetLoginStep(wanted = true, TailnetState.Running("phone", emptyList())))
        assertEquals(TailnetLoginStep.Drop, tailnetLoginStep(wanted = true, TailnetState.Off))
        assertEquals(TailnetLoginStep.Drop, tailnetLoginStep(wanted = true, TailnetState.Error("AlreadyStarted")))
    }

    @Test fun withoutAWantedLoginNothingOpens() {
        val states = listOf(
            TailnetState.Off, TailnetState.Starting, TailnetState.NeedsLogin("https://login.example/1"), TailnetState.LoggedOut,
            TailnetState.Running("phone", emptyList()), TailnetState.Error("AlreadyStarted"),
        )
        for (state in states) assertEquals(state.toString(), TailnetLoginStep.Keep, tailnetLoginStep(wanted = false, state))
    }

    private val page = "https://login.example/1"

    /** A browser that records what it opened, or has none ([IllegalArgumentException], as Compose's handler throws). */
    private class Browser(val present: Boolean = true) : androidx.compose.ui.platform.UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) {
            if (!present) throw IllegalArgumentException("no browser")
            opened += uri
        }
    }

    /** The card a login in [login] shows with the node in [state]. */
    private fun card(login: TailnetLogin, state: TailnetState) = loginCardView(login.waitingFor, startStep(state) as StartStep.LogIn)

    @Test fun openingThePageWaitsUntilCancelAndTheSamePageOpensAgain() {
        val login = TailnetLogin(startNode = {})
        val browser = Browser()
        val state = TailnetState.NeedsLogin(page)
        login.open(browser, page)
        login.follow(state)
        assertEquals(LoginCardView.Waiting, card(login, state))
        assertEquals(1, startStepNumber(startStep(state)))
        login.cancel()
        // The node keeps its page, so nothing brings the wait back until the user opens the page again.
        login.follow(state)
        assertEquals(LoginCardView.Ask, card(login, state))
        login.open(browser, (startStep(state) as StartStep.LogIn).url!!)
        assertEquals(LoginCardView.Waiting, card(login, state))
        assertEquals(listOf(page, page), browser.opened)
    }

    @Test fun aLoginPageTheUserDidNotOpenShowsNoWait() {
        // A node that brings a login page by itself (turned on in Settings) has opened no browser.
        val login = TailnetLogin(startNode = {})
        login.follow(TailnetState.NeedsLogin(page))
        assertEquals(LoginCardView.Ask, card(login, TailnetState.NeedsLogin(page)))
    }

    @Test fun theWaitEndsWhenTheNodeLogsIn() {
        val login = TailnetLogin(startNode = {})
        login.open(Browser(), page)
        val running = TailnetState.Running("phone", emptyList())
        login.follow(running)
        assertNull(login.waitingFor)
        assertEquals(StartStep.Found, startStep(running))
    }

    @Test fun theWaitEndsWhenTheNodeLeavesItsLoginPageAnyOtherWay() {
        for (state in listOf(TailnetState.Off, TailnetState.Starting, TailnetState.LoggedOut, TailnetState.Error("AlreadyStarted"))) {
            val login = TailnetLogin(startNode = {})
            login.open(Browser(), page)
            login.follow(state)
            assertNull(state.toString(), login.waitingFor)
            // A later login page, after the node is turned on again, must not show a wait for a page that was never opened.
            login.follow(TailnetState.NeedsLogin("https://login.example/2"))
            assertNull(state.toString(), login.waitingFor)
        }
    }

    @Test fun aNewPageTheScreenNeverSawLeaveTheOldOneShowsNoWait() {
        // The user opened page 1 and left the start screen; meanwhile the node stopped and came back with page 2.
        // The screen comes back to page 2 alone, never having seen the states between: still no wait for it.
        val login = TailnetLogin(startNode = {})
        login.open(Browser(), page)
        val second = TailnetState.NeedsLogin("https://login.example/2")
        assertEquals(LoginCardView.Ask, card(login, second))
        login.follow(second)
        assertNull(login.waitingFor)
    }

    @Test fun withoutABrowserThePageIsShownAndNotWaitedFor() {
        val login = TailnetLogin(startNode = {})
        login.open(Browser(present = false), page)
        assertNull(login.waitingFor)
        assertEquals(page, login.unopened)
        login.follow(TailnetState.NeedsLogin(page))
        assertEquals(page, login.unopened)
        // The node no longer has that page: its address is no use any more.
        login.follow(TailnetState.Off)
        assertNull(login.unopened)
    }

    @Test fun loggingInWithNoPageStartsTheNode() {
        var starts = 0
        val login = TailnetLogin(startNode = { starts++ })
        login.start()
        assertEquals(1, starts)
        assertTrue(login.wanted)
    }

    @Test fun aWaitWithNoLoginPageLeftShowsTheLoginCard() {
        // The frame between the node leaving its page and the wait ending: nothing to wait for, so the login button.
        assertEquals(LoginCardView.Ask, loginCardView(page, StartStep.LogIn(null)))
    }

    @Test fun openingTheLoginPageAgainAsksForANewOneOnceTheNodeHasNone() {
        assertEquals(page, loginPageAgain(TailnetState.NeedsLogin(page)))
        for (state in listOf(TailnetState.Off, TailnetState.LoggedOut, TailnetState.Starting, TailnetState.Error("x"), TailnetState.Running("phone", emptyList())))
            assertNull(state.toString(), loginPageAgain(state))
    }

    @Test fun nothingFoundBeforeAnySweepEndedIsStillLooking() {
        val note = foundNote(found = 0, paused = false, sweepsFinished = 0)
        assertEquals(FoundNote.Looking, note)
        assertEquals("Looking for computers that run Rex…", note.text)
    }

    @Test fun nothingFoundAfterASweepSaysSo() {
        val note = foundNote(found = 0, paused = false, sweepsFinished = 1)
        assertEquals(FoundNote.NothingFound, note)
        assertEquals("No computer that runs Rex was found on this tailnet.", note.text)
        assertEquals(FoundNote.NothingFound, foundNote(found = 0, paused = false, sweepsFinished = 7))
    }

    @Test fun pausedWinsOverBothOtherTexts() {
        assertEquals(FoundNote.Paused, foundNote(found = 0, paused = true, sweepsFinished = 0))
        assertEquals(FoundNote.Paused, foundNote(found = 0, paused = true, sweepsFinished = 3))
        assertEquals("Paused while Velo is in the background", FoundNote.Paused.text)
    }

    @Test fun serversFoundNeedNoText() {
        for (paused in listOf(false, true)) for (sweeps in listOf(0, 1, 5)) {
            val note = foundNote(found = 2, paused = paused, sweepsFinished = sweeps)
            assertEquals(FoundNote.None, note)
            assertNull(note.text)
        }
    }

    @Test fun onlyAFinishedSweepOffersSearchAgain() {
        assertEquals(false, FoundNote.Looking.offersSearchAgain)
        assertEquals(false, FoundNote.Paused.offersSearchAgain)
        assertEquals(false, FoundNote.None.offersSearchAgain)
        assertEquals(true, FoundNote.NothingFound.offersSearchAgain)
    }

    @Test fun theNodeIsNamedOnlyWhileItRunsWithAName() {
        val name = "velo.tailnet.example"
        val line = connectedAsLine(TailnetState.Running(name, listOf("192.0.2.40")))
        assertTrue(line!!.startsWith("Connected as "))
        assertTrue(line.endsWith(name))   // the whole MagicDNS name: the row cuts it, not the text
        assertNull(connectedAsLine(TailnetState.Running("", emptyList())))   // no "Connected as " with nothing after it
        assertNull(connectedAsLine(TailnetState.Off))
        assertNull(connectedAsLine(TailnetState.Starting))
        assertNull(connectedAsLine(TailnetState.NeedsLogin("https://login.example")))
        assertNull(connectedAsLine(TailnetState.Error("x")))
    }

    @Test fun theHeaderCountsOnlyComputersThatAreThere() {
        assertNull(computersCount(0))   // no "· 0" while nothing is found yet
        assertEquals("· 1", computersCount(1))
        assertEquals("· 12", computersCount(12))
    }

    @Test fun eachConnectNamesItsOwnComputer() {
        val first = connectLabel(DiscoveredServer("studio", "http://192.0.2.2:7777"))
        val second = connectLabel(DiscoveredServer("laptop", "http://192.0.2.3:7777"))
        assertTrue(first.endsWith("studio"))
        assertTrue(second.endsWith("laptop"))
        assertNotEquals(first, second)   // two Connects must not read the same to TalkBack
    }

    @Test fun theCameraMessageIsTheWordingTheIssueGives() {
        assertEquals("Camera access is off. Allow it in Settings to scan a code.", CAMERA_DENIED_MESSAGE)
    }

    @Test fun eachPhoneAndFoldPostureGetsItsLayout() {
        assertEquals(StartLayout.Narrow, startLayout(412f, 892f))   // a bar phone, upright
        assertEquals(StartLayout.Narrow, startLayout(555f, 876f))   // the folded Fold8
        assertEquals(StartLayout.Low, startLayout(892f, 412f))      // a bar phone, sideways
        assertEquals(StartLayout.Low, startLayout(876f, 555f))      // the folded Fold8, sideways
        assertEquals(StartLayout.Band, startLayout(1088f, 821f))    // the unfolded Fold8, sideways
        assertEquals(StartLayout.TopSteps, startLayout(821f, 1088f)) // the unfolded Fold8, upright
    }

    @Test fun theBandNeedsBothItsWidthAndItsHeight() {
        assertEquals(StartLayout.Band, startLayout(840f, 600f))
        assertEquals(StartLayout.TopSteps, startLayout(839f, 600f))
        // Wide but too low for the band, and compact: the low layout, so the card still scrolls beside the steps.
        assertEquals(StartLayout.Low, startLayout(1200f, 599f))
    }

    @Test fun theCompactLimitIsTheWorkspaces() {
        for ((w, h) in listOf(600f to 700f, 700f to 600f, 599f to 900f, 900f to 599f))
            assertEquals("$w x $h", !isCompact(w, h), startLayout(w, h) == StartLayout.Band || startLayout(w, h) == StartLayout.TopSteps)
    }

    @Test fun aSidewaysWindowTooNarrowForTheColumnAndACardIsNarrow() {
        // The low column takes 280 dp; beside a smaller card a button would have no width left (a split-screen window).
        assertEquals(StartLayout.Narrow, startLayout(320f, 300f))
        assertEquals(StartLayout.Narrow, startLayout(500f, 500f))
        assertEquals(StartLayout.Narrow, startLayout(StartLowMinWidthDp - 1f, 400f))
        assertEquals(StartLayout.Low, startLayout(StartLowMinWidthDp, 400f))
    }

    @Test fun theStepIsPickingAComputerOnlyOnceTheNodeIsOnTheTailnet() {
        assertEquals(2, startStepNumber(StartStep.Found))
        for (step in listOf(StartStep.LogIn(null), StartStep.LogIn("https://login.example/1"), StartStep.Starting, StartStep.Failed("x")))
            assertEquals(step.toString(), 1, startStepNumber(step))
        assertEquals("Step 1 of 3: Log in to Tailscale", startStepsDescription(1))
        assertEquals("Step 2 of 3: Pick a computer", startStepsDescription(2))
    }
}
