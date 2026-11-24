package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val workstation = "https://workstation.tailnet.example"
private const val mac = "https://lab-laptop.tailnet.example"
private const val pc = "https://pc.tailnet.example"

private class FakeLink(
    val url: String,
    initiallyParked: Boolean,
    private val onConnectivityChanged: () -> Unit,
    initiallyConnected: Boolean = true,
    private val stream: (Boolean) -> Unit = {},
) : RemoteLink {
    override var connected = initiallyConnected
        private set
    var parked = initiallyParked
    var destroyed = false
    var composerInvalidations = 0
    init { stream(connected && !parked) }

    fun reportConnected(value: Boolean) {
        if (value == connected) return
        connected = value
        // Bootstrap can open the stream before main sees the connected snapshot.
        stream(connected && !parked)
        onConnectivityChanged()
    }
    override fun invalidateComposer() { composerInvalidations++ }
    override fun park() { parked = true; stream(false) }
    override fun unpark() { check(connected); parked = false; stream(true) }
    override fun destroy() { destroyed = true; stream(false) }
}

private class Fixture(prefs: FakePrefs = FakePrefs(), initiallyOffline: Set<String> = emptySet()) {
    val store = HostStore(prefs).also { it.migrate() }
    val opened = mutableListOf<FakeLink>()
    private val streams = mutableSetOf<String>()
    var peakStreams = 0
        private set
    val remotes = RemoteConnections(store) { url, parked, changed ->
        FakeLink(url, parked, changed, initiallyConnected = url !in initiallyOffline) { streaming ->
            if (streaming) streams.add(url) else streams.remove(url)
            peakStreams = maxOf(peakStreams, streams.size)
        }.also { opened += it }
    }
    fun link(url: String) = opened.last { it.url == url }
    fun streaming() = streams.toSet()
}

class RemoteConnectionsTest {

    @Test
    fun changingActiveRemoteInvalidatesTheComposerEvenWhenNeitherRemoteIsParked() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.activate(workstation)
        assertEquals(0, f.link(workstation).composerInvalidations)
        f.remotes.activate(mac)
        f.remotes.activate(workstation)
        assertEquals(1, f.link(workstation).composerInvalidations)
        assertEquals(1, f.link(mac).composerInvalidations)
        assertFalse(f.link(workstation).parked)
    }

    @Test
    fun anUpgradedInstallConnectsToItsPairedServerAsTheFirstRemote() {
        // Legacy preferences: a home with its token, no remote list.
        val prefs = FakePrefs().apply {
            edit().putString("home", workstation).putString("token:$workstation", "example-workstation-token")
                .putString("hosts", "[]").putString("hostsHash", "4f53cda1").putString("active", workstation).apply()
        }
        val f = Fixture(prefs)

        assertEquals(listOf(workstation), f.remotes.rows.map { it.url })
        assertEquals(listOf(workstation), f.opened.map { it.url })
        assertSame(f.link(workstation), f.remotes.active)
        // The connection is opened with the URL its token is stored by: no new pairing.
        assertEquals("example-workstation-token", f.store.token(f.link(workstation).url))
    }

    @Test
    fun aNewInstallHasNoRowAndNothingActive() {
        val f = Fixture()

        assertTrue(f.remotes.rows.isEmpty())
        assertNull(f.remotes.active)
        assertNull(f.remotes.activeRemote)
    }

    @Test
    fun rowsKeepTheSavedOrderWhicheverIsActive() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.add("pc", pc)
        f.remotes.activate(pc)
        f.remotes.activate(mac)

        assertEquals(listOf("workstation", "mac", "pc"), f.remotes.rows.map { it.label })
        // And after a restart: the order saved, the row last worked in active.
        val again = RemoteConnections(f.store) { url, parked, changed -> FakeLink(url, parked, changed) }
        assertEquals(listOf(workstation, mac, pc), again.rows.map { it.url })
        assertEquals("mac", again.activeRemote?.label)
    }

    @Test
    fun addingSavesConnectsAndLeavesTheActiveRowAlone() {
        val f = Fixture()

        assertNull(f.remotes.add("workstation", workstation))
        assertSame("the first remote becomes active", f.link(workstation), f.remotes.active)
        assertNull(f.remotes.add("mac", mac))

        assertEquals(listOf(workstation, mac), f.opened.map { it.url })
        assertSame(f.link(workstation), f.remotes.active)
        assertEquals(listOf(workstation, mac), f.store.remotes().map { it.url })
    }

    @Test
    fun addingAUrlAlreadySavedReturnsItsLabelAndChangesNothing() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)

        assertEquals("workstation", f.remotes.add("again", "WORKSTATION.tailnet.example:443/"))
        assertEquals("workstation", f.remotes.add("again", "https://Workstation.tailnet.example/llms.txt"))

        assertEquals(1, f.opened.size)
        assertEquals(listOf("workstation"), f.store.remotes().map { it.label })
    }

    @Test
    fun removingForgetsTheRemoteItsTokenAndItsSessionAndDestroysItsConnection() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.store.saveToken(workstation, "W")
        f.store.saveToken(mac, "M")
        f.store.saveLastSession(workstation, "session:w")
        f.store.saveLastSession(mac, "session:m")
        val macLink = f.link(mac)

        f.remotes.remove(f.remotes.saved.single { it.url == mac }.id)

        assertTrue(macLink.destroyed)
        assertFalse(f.link(workstation).destroyed)
        assertEquals(listOf(workstation), f.remotes.rows.map { it.url })
        assertEquals(listOf(workstation), f.store.remotes().map { it.url })
        assertNull(f.store.token(mac))
        assertNull(f.store.lastSession(mac))
        assertEquals("W", f.store.token(workstation))
        assertEquals("session:w", f.store.lastSession(workstation))
        assertNull("a remote that was not active leaves no row", f.remotes.removed)
        assertEquals(listOf(f.link(workstation)), f.remotes.all.toList())
    }

    @Test
    fun theRemovedActiveRemoteKeepsItsRowInPlaceUntilTheUserLeavesIt() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.add("pc", pc)
        f.remotes.activate(mac)
        val gone = f.remotes.activeRemote!!
        val macLink = f.link(mac)

        f.remotes.remove(gone.id)

        // Gone from the phone and disconnected, but still the row the workspace draws.
        assertEquals(listOf(workstation, pc), f.store.remotes().map { it.url })
        assertTrue(macLink.destroyed)
        assertEquals(gone, f.remotes.removed)
        assertEquals(listOf("workstation", "mac", "pc"), f.remotes.rows.map { it.label })
        assertEquals(gone, f.remotes.activeRemote)
        assertSame(macLink, f.remotes.active)
        assertSame(macLink, f.remotes.connectionOf(gone))
        assertNull("a restart does not come back to it", f.store.activeUrl)
        // It cannot be selected again, and is not one of the live connections.
        f.remotes.activate(mac)
        assertEquals(gone, f.remotes.removed)
        assertFalse(macLink in f.remotes.all)

        f.remotes.activate(pc)

        assertNull(f.remotes.removed)
        assertEquals(listOf("workstation", "pc"), f.remotes.rows.map { it.label })
        assertSame(f.link(pc), f.remotes.active)
    }

    @Test
    fun removingTheOnlyRemoteLeavesNothingActive() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)

        f.remotes.remove(f.remotes.saved.single().id)

        // Nothing to leave to: no row is kept, and the start screen shows.
        assertNull(f.remotes.active)
        assertNull(f.remotes.removed)
        assertTrue(f.remotes.rows.isEmpty())
        assertTrue(f.opened.single().destroyed)
    }

    @Test
    fun removingTheRestAfterTheActiveOneLeavesNoRowAndNothingActive() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.add("pc", pc)
        f.remotes.activate(mac)
        f.remotes.remove(f.remotes.activeRemote!!.id)
        assertEquals("the removed active row is kept while others are saved", "mac", f.remotes.removed?.label)

        f.remotes.remove(f.remotes.saved.single { it.url == workstation }.id)
        assertEquals(listOf("mac", "pc"), f.remotes.rows.map { it.label })
        f.remotes.remove(f.remotes.saved.single().id)

        // No remote is saved: the kept row goes too, so the start screen shows (active == null).
        assertTrue(f.remotes.saved.isEmpty())
        assertTrue(f.remotes.rows.isEmpty())
        assertNull(f.remotes.removed)
        assertNull(f.remotes.active)
        assertNull(f.remotes.activeRemote)
        assertTrue(f.opened.all { it.destroyed })
        // And a remote saved now is a normal first remote.
        assertNull(f.remotes.add("workstation", workstation))
        assertEquals(listOf("workstation"), f.remotes.rows.map { it.label })
        assertSame(f.link(workstation), f.remotes.active)
    }

    @Test
    fun theKeptRowStaysBetweenItsNeighboursWhenOneAboveItIsRemoved() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.add("pc", pc)
        f.remotes.activate(mac)
        f.remotes.remove(f.remotes.activeRemote!!.id)
        assertEquals(listOf("workstation", "mac", "pc"), f.remotes.rows.map { it.label })

        f.remotes.remove(f.remotes.saved.single { it.url == workstation }.id)

        // Above the pc as before: a stored position would have put it below.
        assertEquals(listOf("mac", "pc"), f.remotes.rows.map { it.label })
        assertEquals("mac", f.remotes.removed?.label)
    }

    @Test
    fun savingTheRemovedActiveRemoteAgainConnectsAndShowsIt() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.activate(mac)
        val dead = f.link(mac)
        f.remotes.remove(f.remotes.activeRemote!!.id)

        assertNull("the removed row does not make its URL taken", f.remotes.add("mac again", mac))

        assertNull(f.remotes.removed)
        assertEquals(listOf("workstation", "mac again"), f.remotes.rows.map { it.label })
        val live = f.remotes.active
        assertNotNull(live)
        assertTrue(live !== dead && !live!!.destroyed)
        assertEquals(1, f.remotes.rows.count { it.url == mac })
    }

    @Test
    fun aRemovedRowNeverSharesAKeyWithAnother() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        f.remotes.remove(f.remotes.activeRemote!!.id)
        f.remotes.add("pc", pc)

        val ids = f.remotes.rows.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotEmpty() })
    }

    @Test
    fun renamingARemoteKeepsItsConnectionAndItsPlace() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        val id = f.remotes.rows[0].id

        f.remotes.rename(id, "desk")

        assertEquals(listOf("desk", "mac"), f.remotes.rows.map { it.label })
        // The row the workspace draws is the renamed one, on the connection it had.
        assertEquals("desk", f.remotes.activeRemote?.label)
        assertSame(f.link(workstation), f.remotes.active)
        assertSame(f.link(workstation), f.remotes.connectionOf(f.remotes.rows[0]))
        assertEquals(2, f.opened.size)
        assertFalse(f.link(workstation).destroyed)
        // A URL already saved is reported under the new name.
        assertEquals("desk", f.remotes.add("again", workstation))
    }

    @Test
    fun theRemovedRemoteIsNotRenamed() {
        val f = Fixture()
        f.remotes.add("workstation", workstation)
        f.remotes.add("mac", mac)
        val gone = f.remotes.activeRemote!!
        f.remotes.remove(gone.id)

        f.remotes.rename(gone.id, "desk")

        assertEquals("workstation", f.remotes.removed?.label)
        assertEquals(listOf("mac"), f.store.remotes().map { it.label })
    }

    @Test
    fun pastFourRemotesTheLeastRecentArePaused() {
        val f = Fixture()
        val urls = (1..6).map { "https://m$it.tailnet.example" }
        for (u in urls) f.remotes.add("", u)

        // m1 is active; m2..m4 follow in saved order; m5 and m6 are parked.
        assertEquals(setOf(urls[4], urls[5]), f.opened.filter { it.parked }.map { it.url }.toSet())
        assertEquals("adding a remote must not open even a transient fifth stream", 4, f.peakStreams)

        f.remotes.activate(urls[5])

        assertFalse(f.link(urls[5]).parked)
        assertEquals(setOf(urls[3], urls[4]), f.opened.filter { it.parked }.map { it.url }.toSet())
    }

    @Test
    fun disconnectAndReconnectReassignTheFourthSessionWithoutChangingTheActiveRow() {
        val f = Fixture()
        val (a, b, c, d, e) = (1..5).map { "https://m$it.tailnet.example" }
        for (url in listOf(a, b, c, d, e)) f.remotes.add("", url)
        f.remotes.activate(e)
        assertEquals(setOf(e, a, b, c), f.streaming())

        f.link(e).reportConnected(false)
        assertEquals(setOf(a, b, c, d), f.streaming())
        assertSame(f.link(e), f.remotes.active)

        f.link(e).reportConnected(true)
        assertEquals(setOf(e, a, b, c), f.streaming())
        assertTrue(f.link(d).parked)
        assertSame(f.link(e), f.remotes.active)
        assertEquals("the displaced row must detach before E resumes", 4, f.peakStreams)

        f.link(a).reportConnected(false)
        f.remotes.activate(d)
        f.link(a).reportConnected(true)
        assertEquals(setOf(d, e, a, b), f.streaming())
        assertTrue(f.link(c).parked)
        assertSame(f.link(d), f.remotes.active)
    }

    @Test
    fun restoredOfflineActiveRemoteLeavesFourSlotsForOtherConnectionsAsTheyArrive() {
        val urls = listOf(mac, workstation, pc, "https://four.example.net", "https://five.example.net")
        val prefs = FakePrefs()
        val original = Fixture(prefs)
        for (url in urls) original.remotes.add("", url)

        val f = Fixture(prefs, initiallyOffline = urls.toSet())
        for (url in urls.drop(1).reversed()) f.link(url).reportConnected(true)
        assertEquals(urls.drop(1).toSet(), f.streaming())
        assertSame(f.link(mac), f.remotes.active)

        f.remotes.activate(workstation)
        f.link(mac).reportConnected(true)
        assertEquals(urls.take(4).toSet(), f.streaming())
        assertSame(f.link(workstation), f.remotes.active)
        assertEquals("simultaneous dials share the same four slots", 4, f.peakStreams)
    }

    @Test
    fun selectingAParkedRemoteWhileItIsOfflineTakesEffectWhenItReconnects() {
        val f = Fixture()
        val urls = (1..5).map { "https://m$it.tailnet.example" }
        for (url in urls) f.remotes.add("", url)
        val fifth = f.link(urls.last())
        assertTrue(fifth.parked)

        fifth.reportConnected(false)
        f.remotes.activate(fifth.url)
        assertSame(fifth, f.remotes.active)
        assertEquals(urls.take(4).toSet(), f.streaming())

        fifth.reportConnected(true)
        assertEquals((urls.take(3) + fifth.url).toSet(), f.streaming())
        assertEquals(fifth.url, f.store.activeUrl)
        assertTrue(f.link(urls[3]).parked)
    }
}
