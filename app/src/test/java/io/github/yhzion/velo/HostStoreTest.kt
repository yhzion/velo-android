package io.github.yhzion.velo

import io.github.yhzion.velo.core.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val workstation = "https://workstation.tailnet.example"
private const val mac = "https://lab-laptop.tailnet.example"

class HostStoreTest {

    /** Legacy preferences: a home, its host list and two per-host tokens. */
    private fun homePrefs() = FakePrefs().apply {
        edit().putString("home", workstation)
            .putString("token:$workstation", "example-workstation-token")
            .putString("session:$workstation", "session:00000000-0000-7000-8000-000000000001")
            .putString("token:$mac", "example-laptop-token")
            .putString("hosts", "[]")
            .putString("hostsHash", "4f53cda1")
            .putString("active", workstation)
            .apply()
    }

    @Test
    fun theHomeBecomesTheFirstRemoteWithItsToken() {
        val prefs = homePrefs()
        val store = HostStore(prefs)

        store.migrate()

        val remotes = store.remotes()
        assertEquals(1, remotes.size)
        assertEquals(workstation, remotes[0].url)
        assertEquals("workstation", remotes[0].label)
        assertEquals("example-workstation-token", store.token(workstation))
        assertEquals("session:00000000-0000-7000-8000-000000000001", store.lastSession(workstation))
        assertEquals(workstation, store.activeUrl)
        for (k in listOf("home", "hosts", "hostsHash")) assertFalse(k, prefs.contains(k))
        val after = prefs.all

        store.migrate()

        assertEquals(after, prefs.all)
    }

    @Test
    fun theFirstPairingOfOlderVersionsBecomesTheFirstRemote() {
        val prefs = FakePrefs()
        prefs.edit().putString("url", "https://WORKSTATION.tailnet.example:443/").putString("token", "T").apply()
        val store = HostStore(prefs)

        store.migrate()

        assertEquals(listOf(workstation), store.remotes().map { it.url })
        assertEquals("T", store.token(workstation))
        assertFalse(prefs.contains("url"))
        assertFalse(prefs.contains("token"))
        val after = prefs.all

        store.migrate()

        assertEquals(after, prefs.all)
    }

    @Test
    fun aNewInstallMigratesToAnEmptyList() {
        val prefs = FakePrefs()
        val store = HostStore(prefs)

        store.migrate()
        store.migrate()

        assertEquals(emptyList<Remote>(), store.remotes())
    }

    @Test
    fun migrationDoesNotComeBackAfterTheRemoteIsRemoved() {
        val store = HostStore(homePrefs())
        store.migrate()

        store.removeRemote(store.remotes().single().id)
        store.migrate()

        assertEquals(emptyList<Remote>(), store.remotes())
    }

    @Test
    fun saveRefusesAUrlAlreadySavedHoweverItIsSpelled() {
        val store = HostStore(FakePrefs())
        val first = store.saveRemote("workstation", workstation)

        assertNotNull(first)
        assertNull(store.saveRemote("again", "WORKSTATION.tailnet.example:443/"))
        assertEquals(listOf(first), store.remotes())
    }

    @Test
    fun saveKeepsTheOrderAndTheUsableSpelling() {
        val store = HostStore(FakePrefs())
        store.saveRemote("workstation", workstation)
        val m = store.saveRemote("  ", "Lab-Laptop.tailnet.example")!!

        assertEquals(listOf(workstation, mac), store.remotes().map { it.url })
        assertEquals("lab-laptop", m.label)
    }

    @Test
    fun removeDeletesOnlyThatRemote() {
        val store = HostStore(FakePrefs())
        val w = store.saveRemote("workstation", workstation)!!
        val m = store.saveRemote("mac", mac)!!
        store.saveToken(workstation, "W")
        store.saveToken(mac, "M")
        store.saveLastSession(mac, "session:m")

        store.removeRemote(m.id)

        assertEquals(listOf(w), store.remotes())
        assertEquals("W", store.token(workstation))
        assertNull(store.token(mac))
        assertNull(store.lastSession(mac))
        assertTrue(store.remotes().none { it.id == m.id })
    }

    @Test
    fun renamingChangesOnlyThatRemotesLabel() {
        val prefs = FakePrefs()
        val store = HostStore(prefs)
        val w = store.saveRemote("workstation", workstation)!!
        val m = store.saveRemote("mac", mac)!!
        store.saveToken(mac, "M")
        store.saveLastSession(mac, "session:m")

        store.renameRemote(m.id, "  laptop ")

        // Its place, its id, its URL and what is stored by the URL all stay.
        assertEquals(listOf(w, m.copy(label = "laptop")), store.remotes())
        assertEquals("M", store.token(mac))
        assertEquals("session:m", store.lastSession(mac))
        // And it is what a restarted app reads.
        assertEquals("laptop", HostStore(prefs).remotes()[1].label)
    }

    @Test
    fun aBlankNameOrAnUnknownRemoteRenamesNothing() {
        val store = HostStore(FakePrefs())
        val w = store.saveRemote("workstation", workstation)!!

        store.renameRemote(w.id, "   ")
        store.renameRemote(w.id, "")
        store.renameRemote("remote:gone", "laptop")

        assertEquals(listOf(w), store.remotes())
    }

    @Test
    fun twoRemotesMayShareALabel() {
        // A label is the user's word for a machine; only the URL tells remotes apart.
        val store = HostStore(FakePrefs())
        val w = store.saveRemote("workstation", workstation)!!
        val m = store.saveRemote("mac", mac)!!

        store.renameRemote(m.id, "workstation")

        assertEquals(listOf("workstation", "workstation"), store.remotes().map { it.label })
        assertEquals(listOf(w.url, m.url), store.remotes().map { it.url })
    }

    @Test
    fun savedSessionThatNoLongerExistsIsNotSelected() {
        val a = "session:a"
        val b = "session:b"

        assertNull(sessionToRestore(saved = "session:gone", sessions = listOf(a, b), current = a))
        assertEquals(b, sessionToRestore(saved = b, sessions = listOf(a, b), current = a))
        assertNull(sessionToRestore(saved = a, sessions = listOf(a, b), current = a))
    }

    @Test
    fun aConnectionFailureIsKeptUntilTheRemoteIsConnectedAgain() {
        val failed = "connect_failed: could not reach the server (ConnectionRefused)"
        // The core reports a failed connect before the state that says so: the status is still the attempt's.
        assertEquals(failed, keptFailure(kept = null, status = "connecting", reported = failed))
        // The states of a remote that stays down keep it, the core's next attempts too.
        for (status in listOf("error", "connecting", "reconnecting", "idle")) assertEquals(status, failed, keptFailure(failed, status))
        // A later failure takes its place.
        val refused = "connect_failed: the server refused the connection (HTTP 403)"
        assertEquals(refused, keptFailure(failed, "error", refused))
        // Connected again: there is no failure to tell.
        assertNull(keptFailure(failed, "connected"))
        // An error while connected is a command's, not the connection's: the notice alone says it.
        assertNull(keptFailure(null, "connected", "conflict: no tiled pane to dock beside"))
    }

    @Test
    fun aCommandRefusedWhileDownKeepsTheFailureThatSaysWhy() {
        val failed = "connect_failed: could not reach the server (ConnectionRefused)"
        val failure = connectionFailure("connect_failed", "could not reach the server (ConnectionRefused)", "")
        assertEquals(failed, failure?.text)
        // `not_connected` says that a command was refused, not why the remote is down.
        assertNull(connectionFailure("not_connected", "not connected to the server", ""))
        for (status in listOf("error", "connecting", "reconnecting", "idle")) {
            assertEquals(status, failure, keptFailure(failure, status, connectionFailure("not_connected", "not connected to the server", "")))
        }
        // Nothing kept yet: a refused command gives no reason either.
        assertNull(keptFailure(null, "reconnecting", connectionFailure("not_connected", "not connected to the server", "")))
    }

    @Test
    fun parkKeepsTheFourMostRecent() {
        assertEquals(setOf("u5", "u6"), rowsToPark(order = listOf("u1", "u2", "u3", "u4", "u5", "u6")))
        assertTrue(rowsToPark(order = listOf("u1", "u2", "u3", "u4")).isEmpty())
        assertTrue(rowsToPark(order = listOf("u1")).isEmpty())
    }
}
