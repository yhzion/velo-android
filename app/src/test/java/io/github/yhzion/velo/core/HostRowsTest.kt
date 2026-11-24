package io.github.yhzion.velo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val workstation = Remote("remote:w", "workstation", "https://workstation.tailnet.example")
private val mac = Remote("remote:m", "mac", "https://lab-laptop.tailnet.example")
private val pc = Remote("remote:p", "pc", "https://pc.tailnet.example")

class HostRowsTest {

    @Test
    fun rowsAreTheSavedRemotesInTheOrderSaved() {
        assertEquals(listOf(mac, workstation, pc), remoteRows(listOf(mac, workstation, pc), removed = null, before = emptySet()))
        assertEquals(emptyList<Remote>(), remoteRows(emptyList(), removed = null, before = emptySet()))
    }

    @Test
    fun theRemovedActiveRemoteKeepsItsPlace() {
        // It stood second of three, after the workstation.
        assertEquals(listOf(workstation, mac, pc), remoteRows(listOf(workstation, pc), removed = mac, before = setOf(workstation.id)))
        // It stood first.
        assertEquals(listOf(mac, workstation), remoteRows(listOf(workstation), removed = mac, before = emptySet()))
        // It stood last.
        assertEquals(listOf(workstation, pc, mac), remoteRows(listOf(workstation, pc), removed = mac, before = setOf(workstation.id, pc.id)))
    }

    @Test
    fun removingARemoteAboveTheKeptRowDoesNotMoveIt() {
        // Saved: workstation, mac, pc. The mac was removed while active; then the workstation goes.
        // The mac's row stays above the pc: a stored position (1) would put it below.
        assertEquals(listOf(mac, pc), remoteRows(listOf(pc), removed = mac, before = setOf(workstation.id)))
        // A remote saved meanwhile goes to the end, below the kept row's old neighbours.
        val nas = Remote("remote:n", "nas", "https://nas.tailnet.example")
        assertEquals(listOf(workstation, mac, pc, nas), remoteRows(listOf(workstation, pc, nas), removed = mac, before = setOf(workstation.id)))
    }

    @Test
    fun aRemovedRemoteSavedAgainIsOneRow() {
        val again = Remote("remote:m2", "mac again", "Lab-Laptop.tailnet.example:443/")

        assertEquals(listOf(workstation, again), remoteRows(listOf(workstation, again), removed = mac, before = emptySet()))
    }

    @Test
    fun aRemoteIsFoundByItsUrlHoweverItIsSpelled() {
        val saved = listOf(workstation, mac)

        assertEquals(workstation, remoteWithUrl(saved, "https://workstation.tailnet.example"))
        assertEquals(workstation, remoteWithUrl(saved, "WORKSTATION.tailnet.example:443/"))
        assertEquals(workstation, remoteWithUrl(saved, "  https://Workstation.tailnet.example/llms.txt?x=1 "))
        assertEquals(mac, remoteWithUrl(saved, "https://lab-laptop.tailnet.example:443"))
        assertNull(remoteWithUrl(saved, "https://workstation2.tailnet.example"))
        assertNull(remoteWithUrl(saved, "https://workstation.tailnet.example.other.example"))
    }

    @Test
    fun aRemoteSavedWithoutALabelIsNamedAfterItsMachine() {
        assertEquals("lab-laptop", remoteLabel("https://Lab-Laptop.tailnet.example:443/"))
        assertEquals("workstation", remoteLabel("workstation.tailnet.example"))
        assertEquals("Remote", remoteLabel(""))
    }

    @Test
    fun normalizationVariantsAreOneMachine() {
        assertEquals("https://x.example", normalizeEndpoint("https://X.example"))
        assertEquals("https://x.example", normalizeEndpoint("X.EXAMPLE:443"))
        assertEquals("https://x.example", normalizeEndpoint("HTTPS://x.example/"))
        assertEquals(hostKey("https://x.example/rex"), hostKey("https://X.example:443/llms.txt"))
    }

    @Test
    fun theDesktopsLocatorFormsAreUsableAndNormalized() {
        assertEquals("https://workstation.example.net:8443", usableUrl("  HTTPS://Workstation.Example.net:8443/ "))
        assertEquals("https://workstation.tailnet.example", usableUrl("https://workstation.tailnet.example:443"))
        assertEquals("https://workstation.tailnet.example", usableUrl("  HTTPS://Workstation.tailnet.example:443/llms.txt?x=1#y\n"))
        assertEquals("https://workstation.example.net", usableUrl("workstation.example.net"))
        assertEquals("https://workstation.example.net:8443", usableUrl("workstation.example.net:8443"))
        assertEquals("https://198.51.100.29", usableUrl("https://198.51.100.29"))
        assertEquals("https://[2001:db8::1]:8443", usableUrl("https://[2001:DB8::1]:8443"))
        assertEquals("https://[2001:db8::1]", usableUrl("https://[2001:db8::1]:443"))
        assertEquals("https://[2001:db8::1]", usableUrl("[2001:db8::1]"))
        assertEquals("http://127.0.0.1", usableUrl("http://127.0.0.1:80"))
        assertEquals("http://127.0.0.1:7890", usableUrl("http://127.0.0.1:7890"))
        assertEquals("http://host", usableUrl("http://host"))
        assertEquals("/tmp/Rex.sock", usableUrl("unix:///tmp/Rex.sock"))
        assertEquals("/tmp/rex forward/server.sock", usableUrl("/tmp/rex forward/server.sock"))
        assertEquals("/tmp/rex.sock", usableUrl("  /tmp/rex.sock\n"))
        // A port is its number: the core reads `0443` as 443.
        assertEquals("https://host", usableUrl("https://host:0443"))
        assertEquals("https://" + "a".repeat(253), usableUrl("https://" + "a".repeat(253)))
    }

    @Test
    fun aSavedRemoteKeepsItsKey() {
        assertEquals("https://workstation.tailnet.example", hostKey("https://workstation.tailnet.example"))
        assertEquals("/tmp/Rex.sock", hostKey("/tmp/Rex.sock"))
        assertEquals(hostKey("http://a.example"), hostKey("HTTP://A.example:80/"))
    }

    @Test
    fun aRemoteSavedWithoutALabelIsNamedAfterItsAddress() {
        assertEquals("rex", remoteLabel("/data/user/0/io.github.yhzion.velo/files/rex.sock"))
        assertEquals("server", remoteLabel("/tmp/rex forward/server.sock"))
        assertEquals("198.51.100.29", remoteLabel("https://198.51.100.29:8443"))
        assertEquals("studio", remoteLabel("http://studio.example:7890"))
        assertEquals("2001:db8::1", remoteLabel("https://[2001:db8::1]:8443"))
        assertEquals("Remote", remoteLabel("/"))
    }

    @Test
    fun normalizationKeepsASocketPathAsItIs() {
        assertEquals("/tmp/Rex.sock", normalizeEndpoint("  /tmp/Rex.sock "))
        assertEquals("http://x.example", normalizeEndpoint("HTTP://X.example:80"))
    }

    @Test
    fun checkLocatorNamesWhyALocatorIsRefused() {
        fun problem(raw: String) = checkLocator(raw).problem
        for (raw in listOf("", "   ", "\n")) assertEquals(raw, LocatorProblem.Empty, problem(raw))
        assertEquals(LocatorProblem.Whitespace, problem("https://a b"))
        assertEquals(LocatorProblem.Whitespace, problem("a b"))
        assertEquals(LocatorProblem.Password, problem("https://me:pw@host"))
        assertEquals(LocatorProblem.Password, problem("ssh://me:pw@host"))
        assertEquals(LocatorProblem.NoHost, problem("https://"))
        assertEquals(LocatorProblem.NoHost, problem("https://:443"))
        assertEquals(LocatorProblem.NoHost, problem("https://[]"))
        assertEquals(LocatorProblem.NoUser, problem("ssh://host"))
        assertEquals(LocatorProblem.NoUser, problem("ssh://@host:2222"))
        assertEquals(LocatorProblem.NoHost, problem("ssh://"))
        assertEquals(LocatorProblem.NoHost, problem("ssh://:2222"))
        assertEquals(LocatorProblem.NoHost, problem("ssh://me@"))
        assertEquals(LocatorProblem.NotAbsolute, problem("unix://tmp/rex.sock"))
        assertEquals(LocatorProblem.NotAbsolute, problem("unix://"))
        assertEquals(LocatorProblem.UnsupportedScheme, problem("ftp://host"))
        assertEquals(LocatorProblem.UnsupportedScheme, problem("wss://host"))
        for (raw in listOf(
            "https://host:0", "https://host:65536", "https://host:x", "https://host:", "https://host:80:80",
            "https://[::1", "https://[::1]x", "https://[::1]:", "https://[nothex::1]", "https://[1234]",
            "https://me@host", "http://me@host", "ssh://me@host/path", "ssh://me@host?x=1",
            // As the core: a bad host or port is named before a missing user.
            "ssh://bad!host", "ssh://[", "ssh://h:99999",
            // The SSH pool's rule: a user or host that would read as an ssh option.
            "ssh://-oProxyCommand=x@host", "ssh://me@-host", "ssh://me%41@host",
            // And a user a shell would read as more than a word: its fix (`rex server install`) is pasted into one.
            "ssh://a\$(rm\$IFS-rf\$IFS~)@evil.example", "ssh://a`id`@h", "ssh://a;b@h", "ssh://a'b@h", "ssh://~a@h",
            "ssh://a*@h", "ssh://a&b@h", "ssh://a(b)@h", "ssh://a!b@h", "ssh://a+b@h", "ssh://a,b@h", "ssh://a=b@h",
            "https://host_é", "https://" + "a".repeat(254),
        )) assertEquals(raw, LocatorProblem.Malformed, problem(raw))
        for (raw in listOf("https://host", "/tmp/a.sock", "unix:///tmp/a.sock", "host", "http://host:7890", "ssh://me@host", "ssh://first.last@host-1", "ssh://first.last_1-x@host-1")) {
            assertNull(raw, problem(raw))
        }
    }

    @Test
    fun checkLocatorKeepsTheSchemeOfAnUnsupportedLocator() {
        assertEquals("ftp", checkLocator("FTP://host").scheme)
        assertNull(checkLocator("FTP://host").url)
    }

    @Test
    fun theSchemeIsWhateverPrecedesTheFirstSeparator() {
        assertEquals(LocatorProblem.UnsupportedScheme, checkLocator("host/x://y").problem)
        // Whitespace decides nothing before the scheme does, and a socket path may contain it.
        assertEquals(LocatorProblem.UnsupportedScheme, checkLocator("ftp://a b").problem)
        assertEquals("/tmp/a b.sock", checkLocator("unix:///tmp/a b.sock").url)
    }

    @Test
    fun invalidAddressesExplainTheProblem() {
        fun sentence(raw: String) = locatorSentence(checkLocator(raw))
        assertNull(sentence(""))
        assertNull(sentence("https://host"))
        assertEquals("Use spaces only in Unix socket paths, not in server URLs.", sentence("https://a b"))
        assertEquals("Remove the password from the server address.", sentence("https://me:pw@h"))
        assertEquals("Enter the hostname or IP address of the server.", sentence("https://"))
        assertEquals("Use a full Unix socket path starting with /, for example /tmp/rex.sock.", sentence("unix://tmp/rex.sock"))
        assertEquals("Velo doesn't support ftp:// server addresses.", sentence("FTP://h"))
        assertEquals("Check the format of the server address.", sentence("https://h:0"))
        assertNull(sentence("ssh://me@h"))
        // `no_user` in native/src/rex/Core.zig: the locator as typed, and the host as written.
        assertEquals("Missing SSH username in ssh://h. For example: ssh://user@h.", sentence("ssh://h"))
        assertEquals("Missing SSH username in SSH://Studio.example:2222. For example: ssh://user@Studio.example.", sentence(" SSH://Studio.example:2222 "))
        assertEquals("Missing SSH username in ssh://@h. For example: ssh://user@h.", sentence("ssh://@h"))
        assertEquals("Missing SSH username in ssh://[2001:db8::1]:22. For example: ssh://user@[2001:db8::1].", sentence("ssh://[2001:db8::1]:22"))
        assertEquals("Enter the hostname or IP address of the server.", sentence("ssh://"))
    }

    @Test
    fun anSshLoginIsUsableAndNormalizedAsTheSshPoolNamesIt() {
        assertEquals("ssh://me@h:2222", checkLocator("ssh://me@h:2222").url)
        assertNull(checkLocator("ssh://me@h:2222").problem)
        // The port is left out when it is 22, the host is in lower case, the user is as typed.
        assertEquals("ssh://Me@studio.example", usableUrl(" SSH://Me@Studio.Example:22 "))
        assertEquals("ssh://alice@127.0.0.1:2222", usableUrl("ssh://alice@127.0.0.1:2222"))
        assertEquals("ssh://me@[2001:db8::1]:2222", usableUrl("ssh://me@[2001:DB8::1]:2222"))
        assertEquals("ssh://me@[2001:db8::1]", usableUrl("ssh://me@[2001:db8::1]:22"))
        assertEquals(hostKey("ssh://me@h"), hostKey("SSH://me@H:22"))
        // Users are case-sensitive: two logins, two remotes.
        assertNull(remoteWithUrl(listOf(Remote("remote:s", "s", "ssh://me@h")), "ssh://Me@h"))
    }

    @Test
    fun anSshRemoteIsNamedAfterItsLogin() {
        assertEquals("alice@studio", remoteLabel("ssh://alice@studio.example:2222"))
        assertEquals("alice@127.0.0.1", remoteLabel("ssh://alice@127.0.0.1:2222"))
        assertEquals("me@2001:db8::1", remoteLabel("ssh://me@[2001:db8::1]:2222"))
    }
}
