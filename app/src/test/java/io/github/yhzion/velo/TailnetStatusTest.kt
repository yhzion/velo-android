package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetStatusTest {
    @Test fun stoppedNodeIsOff() = assertEquals(TailnetState.Off, parseTailnetStatus("{}"))

    @Test fun needsLoginCarriesTheLoginUrl() = assertEquals(
        TailnetState.NeedsLogin("https://login.example/abc123"),
        parseTailnetStatus("""{"status":{"BackendState":"NeedsLogin","AuthURL":"https://login.example/abc123","Self":null}}"""),
    )

    @Test fun needsLoginBeforeTheUrlIsStarting() = assertEquals(
        TailnetState.Starting,
        parseTailnetStatus("""{"status":{"BackendState":"NeedsLogin","AuthURL":""}}"""),
    )

    private val running = """{"status":{"BackendState":"Running","TailscaleIPs":["192.0.2.9"],"Self":{"HostName":"velo"}}}"""
    private val loginWithoutUrl = """{"status":{"BackendState":"NeedsLogin","AuthURL":""}}"""
    private val loginWithUrl = """{"status":{"BackendState":"NeedsLogin","AuthURL":"https://login.example/abc123"}}"""

    @Test fun theFirstStartWithoutAnAddressIsStarting() =
        assertEquals(TailnetState.Starting, parseTailnetStatus(loginWithoutUrl, loggedInBefore = false))

    @Test fun aNodeThatRanAndNeedsLoginAgainWithoutAnAddressIsLoggedOut() {
        var loggedIn = false
        val first = parseTailnetStatus(running, loggedIn)
        assertEquals(TailnetState.Running("velo", listOf("192.0.2.9")), first)
        loggedIn = loggedInSince(loggedIn, first)
        assertEquals(TailnetState.LoggedOut, parseTailnetStatus(loginWithoutUrl, loggedIn))
    }

    @Test fun aNodeThatNeededLoginWithAnAddressAndThenLostItIsLoggedOut() {
        val first = parseTailnetStatus(loginWithUrl, loggedInBefore = false)
        assertEquals(TailnetState.LoggedOut, parseTailnetStatus(loginWithoutUrl, loggedInSince(false, first)))
    }

    @Test fun needsLoginWithAnAddressIsNeedsLoginWhateverCameBefore() {
        for (before in listOf(false, true))
            assertEquals(TailnetState.NeedsLogin("https://login.example/abc123"), parseTailnetStatus(loginWithUrl, before))
    }

    @Test fun onlyRunningAndNeedsLoginWithAnAddressCountAsLoggedIn() {
        assertEquals(true, loggedInSince(false, TailnetState.Running("n", emptyList())))
        assertEquals(true, loggedInSince(false, TailnetState.NeedsLogin("https://login.example/abc123")))
        for (now in listOf(TailnetState.Off, TailnetState.Starting, TailnetState.Error("x")))
            assertEquals(now.toString(), false, loggedInSince(false, now))
    }

    @Test fun beingLoggedOutDoesNotForgetThatTheNodeWasLoggedIn() {
        assertEquals(true, loggedInSince(true, TailnetState.LoggedOut))
        assertEquals(true, loggedInSince(true, TailnetState.Starting))
    }

    @Test fun runningHasNameAndAddresses() = assertEquals(
        TailnetState.Running("velo.tailnet.example", listOf("192.0.2.9", "2001:db8:1::9")),
        parseTailnetStatus(
            """{"status":{"BackendState":"Running","TailscaleIPs":["192.0.2.9","2001:db8:1::9"],""" +
                """"Self":{"HostName":"velo","DNSName":"velo.tailnet.example."}}}""",
        ),
    )

    @Test fun aNodeStillStartingIsStarting() = assertEquals(
        TailnetState.Starting,
        parseTailnetStatus("""{"status":{"BackendState":"NoState"}}"""),
    )

    @Test fun anUnreadableStatusIsAnError() = assertEquals(
        TailnetState.Error("context deadline exceeded"),
        parseTailnetStatus("""{"statusError":"context deadline exceeded","status":null}"""),
    )
}

class LogoutFailedStateTest {
    @Test fun aLogoutAfterTheNodeStoppedIsOff() =
        assertEquals(TailnetState.Off, logoutFailedState("the node is not running", "{}"))

    @Test fun aLogoutThatFailsOnARunningNodeIsAnError() = assertEquals(
        TailnetState.Error("Log out failed: context deadline exceeded"),
        logoutFailedState("context deadline exceeded", """{"status":{"BackendState":"Running"}}"""),
    )
}

class RedialTest {
    private val running = TailnetState.Running("velo.tailnet.example", listOf("192.0.2.9"))
    private val login = TailnetState.NeedsLogin("https://login.example/abc123")

    @Test fun aNodeThatComesToRunRedialsEveryConnection() {
        for (was in listOf(TailnetState.Off, TailnetState.Starting, login, TailnetState.Error("x")))
            assertEquals(was.toString(), Redial.All, redialOn(was, running))
    }

    @Test fun aRunningNodeWithNewAddressesRedialsNothing() =
        assertEquals(Redial.None, redialOn(running, running.copy(ips = listOf("192.0.2.10"))))

    @Test fun aNodeThatStopsStartingWithoutRunningLetsTheWaitingOnesDial() {
        for (now in listOf(login, TailnetState.Error("the node is stopped"), TailnetState.Off))
            assertEquals(now.toString(), Redial.Waiting, redialOn(TailnetState.Starting, now))
    }

    @Test fun aNodeThatStartsOrLeavesRunningRedialsNothing() {
        assertEquals(Redial.None, redialOn(TailnetState.Off, TailnetState.Starting))
        assertEquals(Redial.None, redialOn(running, TailnetState.Starting))
        assertEquals(Redial.None, redialOn(running, TailnetState.Off))
        assertEquals(Redial.None, redialOn(login, TailnetState.Error("x")))
    }
}
