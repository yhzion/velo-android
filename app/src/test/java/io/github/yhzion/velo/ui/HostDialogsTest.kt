package io.github.yhzion.velo.ui

import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.remoteWithUrl
import io.github.yhzion.velo.ui.theme.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HostDialogsTest {

    @Test
    fun hostnamesAndFullHttpsUrlsFindTheSameSavedRemote() {
        val saved = Remote("remote:rex", "Workstation", "https://workstation.tailnet.example")
        val hostnames = listOf("workstation.tailnet.example", " WORKSTATION.tailnet.example:443/ ")
        val urls = listOf(saved.url, " HTTPS://WORKSTATION.tailnet.example:443/pair?x=1#top ")
        val addresses = (hostnames + urls).map { RemoteAddressInput(it) } +
            urls.map { RemoteAddressInput().scan(listOf(it)) }

        for (address in addresses) {
            assertEquals(saved.url, address.acceptedUrl)
            assertNull(address.problem)
            assertEquals(saved, remoteWithUrl(listOf(saved), checkNotNull(address.acceptedUrl)))
        }
    }

    @Test
    fun blankOrIncompleteHttpsInputDoesNotShowAnError() {
        for (text in listOf("", "  ", "https://", "ssh://")) {
            val address = RemoteAddressInput(text)
            assertNull(text, address.acceptedUrl)
            assertNull(text, address.problem)
        }
    }

    @Test
    fun aSingleLabelHostnameCanBeTyped() {
        assertEquals("https://workstation", RemoteAddressInput("Workstation").acceptedUrl)
    }

    @Test
    fun theDesktopsLocatorFormsCanBeTyped() {
        val forms = mapOf(
            "host.example.net:8443" to "https://host.example.net:8443",
            "https://198.51.100.17" to "https://198.51.100.17",
            "[2001:db8::1]" to "https://[2001:db8::1]",
            "http://127.0.0.1:7890" to "http://127.0.0.1:7890",
            "http://host.example.net" to "http://host.example.net",
            "host.example.net/path" to "https://host.example.net",
            "/tmp/rex forward/server.sock" to "/tmp/rex forward/server.sock",
            "unix:///tmp/rex.sock" to "/tmp/rex.sock",
            "ssh://alice@studio.example:2222" to "ssh://alice@studio.example:2222",
        )
        for ((text, url) in forms) {
            val address = RemoteAddressInput(text)
            assertEquals(text, url, address.acceptedUrl)
            assertNull(text, address.problem)
        }
    }

    @Test
    fun aRefusedAddressExplainsWhy() {
        assertEquals("Velo doesn't support ftp:// server addresses.", RemoteAddressInput("ftp://h").problem)
        assertEquals("Remove the password from the server address.", RemoteAddressInput("https://me:pw@h").problem)
        assertEquals("Missing SSH username in ssh://h. For example: ssh://user@h.", RemoteAddressInput("ssh://h").problem)
        assertEquals("Check the format of the server address.", RemoteAddressInput("https://h:").problem)
    }

    @Test
    fun aScanIsStillHttpsOnly() {
        val qr = "Scan a QR code that holds an HTTPS address"
        assertEquals(qr, RemoteAddressInput().scan(listOf("http://127.0.0.1:7890")).problem)
        assertEquals(qr, RemoteAddressInput().scan(listOf("/tmp/rex.sock")).problem)
        assertEquals(qr, RemoteAddressInput().scan(listOf("https://me:pw@h")).problem)
        assertEquals(qr, RemoteAddressInput().scan(listOf("ssh://alice@studio.example")).problem)
        assertEquals("https://workstation.example.net:8443", RemoteAddressInput().scan(listOf("https://workstation.example.net:8443")).acceptedUrl)
    }

    @Test
    fun invalidAddressesCannotBeSubmittedAndShowAnError() {
        for (text in listOf(
            "https://host.example.net:", "https://host.example.net:abc", "https://host.example.net:0",
            "https://host.example.net:65536", "ftp://host.example.net", "ssh://host.example.net",
            "https://user@host.example.net", "https:///host.example.net",
            "host example", "unix://tmp/rex.sock", "https://[2001:db8::1",
        )) {
            val typed = RemoteAddressInput(text)
            val scanned = RemoteAddressInput().scan(listOf(text))
            assertNull(text, typed.acceptedUrl)
            assertNotNull(text, typed.problem)
            assertNull(text, scanned.acceptedUrl)
            assertNotNull(text, scanned.problem)
        }
    }

    @Test
    fun aScanChoosesTheFirstValidQrAfterAnInvalidPayload() {
        val scanned = RemoteAddressInput().scan(listOf(
            "Just some text",
            "https://FIRST.example.net:443/pair",
            "https://second.example.net",
        ))

        assertEquals("https://first.example.net", scanned.text)
        assertEquals(scanned.text, scanned.acceptedUrl)
        assertNull(scanned.problem)
    }

    @Test
    fun aScanWithOnlyRejectedCodesRequiresAnEditOrRescan() {
        val typed = RemoteAddressInput("old.example.net")
        val rejected = typed.scan(listOf("Just some text", "new.example.net:443/"))

        assertEquals(typed.text, rejected.text)
        assertNull(rejected.acceptedUrl)
        assertEquals("Scan a QR code that holds an HTTPS address", rejected.problem)

        val edited = RemoteAddressInput("new.example.net:443/")
        assertEquals("https://new.example.net", edited.acceptedUrl)
        assertNull(edited.problem)

        val rescanned = rejected.scan(listOf("https://new.example.net:443/"))
        assertEquals("https://new.example.net", rescanned.acceptedUrl)
        assertNull(rescanned.problem)

        val corrected = RemoteAddressInput(rescanned.text.removePrefix("https://"))
        assertEquals(rescanned.acceptedUrl, corrected.acceptedUrl)
        assertNull(corrected.problem)
    }

    @Test
    fun aRemoteThatIsNotConnectedNamesItsPhaseInLowerCase() {
        assertNull(remoteRowNote("connected", removed = false))
        // The first attempt, also before the core has reported anything.
        assertEquals("connecting…", remoteRowNote("connecting", removed = false))
        assertEquals("connecting…", remoteRowNote("idle", removed = false))
        // A remote that was connected went away: the core retries.
        assertEquals("reconnecting…", remoteRowNote("reconnecting", removed = false))
        // The first attempt failed: a machine that is off, or one that refused.
        assertEquals("connection failed", remoteRowNote("error", removed = false))
        // A status this app does not know.
        assertEquals("offline", remoteRowNote("handshaking", removed = false))
    }

    @Test
    fun theRemovedActiveRemoteSaysRemovedWhateverItsConnectionSaid() {
        for (status in listOf("idle", "connected", "error")) assertEquals("Removed", remoteRowNote(status, removed = true))
    }

    @Test
    fun deviceSummaryShowsTheNodeNameFirstAddressAndState() {
        val running = TailnetState.Running("velo.tailnet.example", listOf("192.0.2.40", "2001:db8:1::1"))
        assertEquals(DeviceRow("velo.tailnet.example", "192.0.2.40 · Running", "Running", TailnetChip("Connected", Status.Ok)), deviceRow(running, paused = false))
    }

    @Test
    fun deviceSummaryKeepsItsNameWithoutAnAddressAndDoesNotShortenLongNames() {
        val name = "a-very-long-node-name-that-needs-ellipsis-on-a-narrow-sidebar.tailnet.example"
        assertEquals(DeviceRow(name, "Running", "Running", TailnetChip("Connected", Status.Ok)), deviceRow(TailnetState.Running(name, emptyList()), paused = false))
        assertEquals(DeviceRow(name, "Running", "Running", TailnetChip("Connected", Status.Ok)), deviceRow(TailnetState.Running(name, listOf("")), paused = false))
    }

    @Test
    fun deviceSummaryUsesTheFallbackForBlankNames() {
        for (name in listOf("", "  ")) {
            assertEquals(name, DeviceRow("This Device", "192.0.2.40 · Running", "Running", TailnetChip("Connected", Status.Ok)),
                deviceRow(TailnetState.Running(name, listOf("192.0.2.40")), paused = false))
        }
    }

    @Test
    fun deviceSummaryShowsUnknownAsAValidName() {
        for ((name, expected) in listOf("unknown" to "unknown", " Unknown " to "Unknown")) {
            assertEquals(name, DeviceRow(expected, "192.0.2.40 · Running", "Running", TailnetChip("Connected", Status.Ok)),
                deviceRow(TailnetState.Running(name, listOf("192.0.2.40")), paused = false))
        }
    }

    @Test
    fun deviceSummaryWithoutAnAddressShowsOnlyTheState() {
        for ((state, word) in listOf(
            TailnetState.Off to "Off", TailnetState.Starting to "Starting",
            TailnetState.NeedsLogin("https://login.example/abc") to "Needs login",
            TailnetState.LoggedOut to "Logged out",
            TailnetState.Error("the node is stopped") to "Error", TailnetState.Running("", emptyList()) to "Running",
        )) assertEquals(DeviceRow("This Device", word, word, tailnetChip(state, paused = false)), deviceRow(state, paused = false))
    }

    @Test
    fun theDeviceDotSaysWhatTheTailscalePageSaysForEachState() {
        for ((state, said) in listOf(
            TailnetState.Off to "Off", TailnetState.Starting to "Starting",
            TailnetState.NeedsLogin("https://login.example/abc") to "Needs login",
            TailnetState.LoggedOut to "Logged out",
            TailnetState.Error("the node is stopped") to "Error",
            TailnetState.Running("velo", listOf("192.0.2.40")) to "Connected",
        )) {
            assertEquals(said, deviceRow(state, paused = false).chip.text)
            for (paused in listOf(false, true)) assertEquals("$state paused=$paused", tailnetChip(state, paused), deviceRow(state, paused).chip)
        }
    }

    @Test
    fun theDeviceDotIsPausedWhileAndroidHoldsTheNetworkBack() {
        val running = TailnetState.Running("velo", listOf("192.0.2.40"))
        assertEquals(TailnetChip("Paused", Status.Warning), deviceRow(running, paused = true).chip)
        assertEquals(TailnetChip("Off", Status.Off), deviceRow(TailnetState.Off, paused = true).chip)
    }

    @Test
    fun theDeviceLinesWordIsTheChipsWordButARunningNodeSaysRunning() {
        // One table of words: the line says what the dot says, except that a running node that is not held back says Running (#134).
        for (state in listOf(
            TailnetState.Off, TailnetState.Starting, TailnetState.NeedsLogin("https://login.example/abc"), TailnetState.LoggedOut,
            TailnetState.Error("the node is stopped"), TailnetState.Running("velo", listOf("192.0.2.40")),
        )) for (paused in listOf(false, true)) {
            val word = if (state is TailnetState.Running && !paused) "Running" else tailnetChip(state, paused).text
            assertEquals("$state paused=$paused", word, deviceRow(state, paused).state)
            assertEquals("$state paused=$paused", word, deviceRow(state, paused).line.substringAfterLast(" · "))
        }
    }

    @Test
    fun theDeviceLineSaysPausedAsItsDotDoes() {
        // While Android holds the network back, the line under the name does not say Running beside a Paused dot (#127).
        assertEquals("192.0.2.40 · Paused", deviceRow(TailnetState.Running("velo", listOf("192.0.2.40")), paused = true).line)
        assertEquals("Paused", deviceRow(TailnetState.Running("velo", emptyList()), paused = true).line)
        assertEquals("Paused", deviceRow(TailnetState.Starting, paused = true).line)
        // An off node is not held back: its line says Off, as its dot does.
        assertEquals("Off", deviceRow(TailnetState.Off, paused = true).line)
    }

    @Test
    fun theRemotesDotHasTheTailscalePagesColourAndNoneWhileOff() {
        // The Remotes page's dot, the sidebar's and the Tailscale page's chip agree in every state (#126).
        for (state in listOf(
            TailnetState.Starting, TailnetState.NeedsLogin("https://login.example/abc"), TailnetState.LoggedOut,
            TailnetState.Error("the node is stopped"), TailnetState.Running("velo", listOf("192.0.2.40")),
        )) for (paused in listOf(false, true)) {
            assertEquals("$state paused=$paused", tailnetChip(state, paused).status, remotesDotStatus(state, paused))
            assertEquals("$state paused=$paused", deviceRow(state, paused).chip.status, remotesDotStatus(state, paused))
        }
        assertEquals(Status.Active, remotesDotStatus(TailnetState.Starting, paused = false))
        assertEquals(Status.Off, remotesDotStatus(TailnetState.LoggedOut, paused = false))
        for (paused in listOf(false, true)) assertNull(remotesDotStatus(TailnetState.Off, paused))
    }

    @Test
    fun theDeviceDotWhileStartingHasTheTailscalePagesColour() {
        assertEquals(TailnetChip("Starting", Status.Active), deviceRow(TailnetState.Starting, paused = false).chip)
    }

    @Test
    fun theStartScreenAsksForALoginUntilTheNodeIsOnTheTailnet() {
        assertEquals(StartStep.LogIn(null), startStep(TailnetState.Off))
        assertEquals(StartStep.LogIn("https://login.example/abc"), startStep(TailnetState.NeedsLogin("https://login.example/abc")))
        assertEquals(StartStep.LogIn(null), startStep(TailnetState.LoggedOut))
        assertEquals(StartStep.Starting, startStep(TailnetState.Starting))
        assertEquals(StartStep.Failed("the node is stopped"), startStep(TailnetState.Error("the node is stopped")))
        assertEquals(StartStep.Found, startStep(TailnetState.Running("velo.tailnet.example", listOf("192.0.2.40"))))
    }

    @Test
    fun aLoggedOutNodeSaysSoInsteadOfStarting() {
        assertEquals("Logged out of Tailscale.", tailnetStatusLine(TailnetState.LoggedOut, paused = false))
        assertEquals("Starting…", tailnetStatusLine(TailnetState.Starting, paused = false))
    }
}
