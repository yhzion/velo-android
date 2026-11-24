package io.github.yhzion.velo

import io.github.yhzion.velo.core.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `tailnet-status.json`: a hand-authored `tailscale status --json` example,
 * wrapped as [TailnetNative.statusJson] returns it. The workstation is online;
 * the build agent is active but offline.
 */
private fun fixture(): String =
    TailnetDiscoveryTest::class.java.classLoader!!.getResource("tailnet-status.json")!!.readText()

class TailnetDiscoveryTest {
    @Test fun onlyTheOnlinePeerIsDiscovered() {
        val peers = parsePeers(fixture())
        assertEquals(listOf(TailnetPeer("workstation", "workstation.tailnet.example")), peers)
    }

    @Test fun theFixtureIsRunning() = assertEquals(
        TailnetState.Running("velo.tailnet.example", listOf("192.0.2.40", "2001:db8:1::1")),
        parseTailnetStatus(fixture()),
    )

    @Test fun peersWithoutADnsNameOrOfflineAreSkippedAndSorted() {
        val text = """{"status":{"Peer":{
            "a":{"HostName":"Zeta","DNSName":"zeta.discovery.example.","Online":true},
            "b":{"HostName":"alpha","DNSName":"alpha.discovery.example.","Online":true},
            "c":{"HostName":"gone","DNSName":"gone.discovery.example.","Online":false},
            "d":{"HostName":"nodns","DNSName":"","Online":true},
            "e":{"DNSName":"bare.discovery.example.","Online":true}}}}"""
        assertEquals(
            listOf(TailnetPeer("alpha", "alpha.discovery.example"), TailnetPeer("bare", "bare.discovery.example"), TailnetPeer("Zeta", "zeta.discovery.example")),
            parsePeers(text),
        )
    }

    @Test fun noPeersInAStoppedOrUnreadableStatus() {
        assertEquals(emptyList<TailnetPeer>(), parsePeers("{}"))
        assertEquals(emptyList<TailnetPeer>(), parsePeers("not json"))
    }

    @Test fun llmsTxtNamingRexIsARexServer() {
        assertTrue(isRexLlmsTxt("# Rex\n\nRex is a terminal multiplexer…"))
        assertFalse(isRexLlmsTxt("<html>Welcome to nginx</html>"))
    }

    @Test fun onlyServersThatAreNotSavedAreFound() {
        val saved = listOf(Remote("remote:w", "workstation", "https://workstation.tailnet.example"))
        val found = listOf(
            DiscoveredServer("workstation", "https://workstation.tailnet.example"),
            DiscoveredServer("lab-laptop", "https://lab-laptop.tailnet.example"),
            DiscoveredServer("studio", "https://studio.tailnet.example"),
        )
        // The workstation is saved; the laptop and studio are not.
        assertEquals(listOf(found[1], found[2]), notYetRows(found, saved))
        // With nothing saved (the start screen) every running server is offered.
        assertEquals(found, notYetRows(found, emptyList()))
    }

    @Test fun aSavedRemoteMatchesHoweverEitherUrlIsSpelled() {
        val workstation = DiscoveredServer("workstation", "https://workstation.tailnet.example")
        for (spelling in listOf("https://WORKSTATION.tailnet.example:443/", "workstation.tailnet.example", "https://workstation.tailnet.example/llms.txt")) {
            assertEquals(spelling, emptyList<DiscoveredServer>(), notYetRows(listOf(workstation), listOf(Remote("remote:w", "w", spelling))))
        }
        val shouting = DiscoveredServer("WORKSTATION", "https://WORKSTATION.tailnet.example:443")
        assertEquals(emptyList<DiscoveredServer>(), notYetRows(listOf(shouting), listOf(Remote("remote:w", "w", "https://workstation.tailnet.example"))))
        // Another machine whose name only starts alike is still found.
        val other = DiscoveredServer("workstation2", "https://workstation2.tailnet.example")
        assertEquals(listOf(other), notYetRows(listOf(other), listOf(Remote("remote:w", "w", "https://workstation.tailnet.example"))))
    }
}

class SweepDueTest {
    @Test fun dueOnARunningNodeAfterTheGap() = assertTrue(sweepDue(running = true, paused = false, lastSweepMs = 0, nowMs = 60_000, gapMs = 60_000))
    @Test fun notBeforeTheGap() = assertFalse(sweepDue(running = true, paused = false, lastSweepMs = 10_000, nowMs = 69_999, gapMs = 60_000))
    @Test fun notWhileAndroidWithholdsTheNetwork() = assertFalse(sweepDue(running = true, paused = true, lastSweepMs = 0, nowMs = 600_000, gapMs = 5_000))
    @Test fun notOnANodeThatIsNotRunning() = assertFalse(sweepDue(running = false, paused = false, lastSweepMs = 0, nowMs = 600_000, gapMs = 5_000))
}
