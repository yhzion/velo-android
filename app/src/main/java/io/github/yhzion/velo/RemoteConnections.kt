package io.github.yhzion.velo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.hostKey
import io.github.yhzion.velo.core.remoteRows
import io.github.yhzion.velo.core.remoteWithUrl

/** What the remote list needs of a connection: a [HostConnection] in the app, a fake in the JVM tests. */
interface RemoteLink {
    /** True once the control connection has completed bootstrap, even when its panes are parked. */
    val connected: Boolean
    /** Detach the session so its panes stop streaming. */
    fun park()
    fun unpark()
    fun invalidateComposer() {}
    fun destroy()
}

/**
 * The remotes saved on the phone, one connection each ([open] makes it), and the row the
 * workspace draws. The list lives in [store] only: no server's host list is read or written,
 * and nothing a server does adds or removes a row. Main thread only.
 */
class RemoteConnections<C : RemoteLink>(
    private val store: HostStore,
    private val open: (url: String, initiallyParked: Boolean, onConnectivityChanged: () -> Unit) -> C,
) {
    /** The saved remotes, in the order saved. */
    var saved by mutableStateOf(store.remotes())
        private set
    /** The active remote the user removed: its row stays, marked "Removed", until they leave it. */
    var removed by mutableStateOf<Remote?>(null)
        private set
    /** The ids of the remotes that stood before [removed] in the list, so its row does not move. */
    private var removedBefore by mutableStateOf(emptySet<String>())
    /** The destroyed connection of [removed]: what the workspace draws until the user leaves it. */
    private var removedLink by mutableStateOf<C?>(null)
    private var activeKey by mutableStateOf<String?>(null)
    /** Connections by [hostKey] of their URL: one per saved remote. */
    private val links = mutableStateMapOf<String, C>()
    /** Keys, most recently active first. Disconnecting does not change a row's priority. */
    private val recency = mutableListOf<String>()

    /** The sidebar's remote rows: the saved remotes in order, with the removed active one in its place. */
    val rows: List<Remote> get() = remoteRows(saved, removed, removedBefore)
    val active: C? get() = removedLink ?: activeKey?.let { links[it] }
    val activeRemote: Remote? get() = removed ?: activeKey?.let { key -> saved.firstOrNull { hostKey(it.url) == key } }
    /** The connections of the saved remotes. */
    val all: Collection<C> get() = links.values

    init {
        for (remote in saved) openRemote(remote)
        val last = store.activeUrl?.let(::hostKey)
        (saved.firstOrNull { hostKey(it.url) == last } ?: saved.firstOrNull())?.let { activate(it.url) }
    }

    private fun openRemote(remote: Remote) {
        // A core can finish bootstrap before its first connectivity callback reaches main.
        links[hostKey(remote.url)] = open(remote.url, links.size >= STREAMING_REMOTE_LIMIT, ::applyParking)
    }

    /** The connection of the row [remote]: a saved remote's, or the removed active one's (destroyed). */
    fun connectionOf(remote: Remote): C? = if (remote == removed) removedLink else links[hostKey(remote.url)]

    /** Make the saved remote at [url] the one the workspace draws. This leaves a removed row. */
    fun activate(url: String) {
        val key = hostKey(url)
        if (key !in links) return
        if (activeKey != key) activeKey?.let { links[it]?.invalidateComposer() }
        activeKey = key
        store.saveActive(url)
        recency.remove(key)
        recency.add(0, key)
        removed = null
        removedLink = null
        applyParking()
    }

    /**
     * Save a remote on the phone and connect to it. Returns the label of the saved remote that
     * already has [url] (nothing changes), else null. It becomes the active row only when the
     * active row has no live connection: there is none, or it is a removed one.
     */
    fun add(label: String, url: String): String? {
        remoteWithUrl(saved, url)?.let { return it.label }
        val remote = store.saveRemote(label, url) ?: return null
        saved = store.remotes()
        openRemote(remote)
        if (activeKey == null || removed != null) activate(remote.url) else applyParking()
        return null
    }

    /**
     * Rename the saved remote [id] on the phone ([HostStore.renameRemote]). Its connection and
     * its place stay; the removed row is not a saved remote and keeps its name.
     */
    fun rename(id: String, label: String) {
        store.renameRemote(id, label)
        saved = store.remotes()
    }

    /**
     * Forget the remote [id]: it leaves the phone with its reconnect token and last session,
     * and its connection is destroyed. The active one keeps its row ([removed]) until the user
     * leaves it. Once no remote is saved there is nothing to leave to: no row is kept and none
     * is active, whichever remote went last. No server is told.
     */
    fun remove(id: String) {
        val at = saved.indexOfFirst { it.id == id }
        if (at < 0) return
        val remote = saved[at]
        val key = hostKey(remote.url)
        val before = saved.take(at).map { it.id }.toSet()
        // Destroyed first: a token or session it still reports is not saved again.
        val link = links.remove(key)
        link?.destroy()
        store.removeRemote(id)
        saved = store.remotes()
        recency.remove(key)
        if (key == activeKey) store.saveActive(null)
        if (saved.isEmpty()) {
            activeKey = null
            removed = null
            removedLink = null
        } else if (key == activeKey) {
            removed = remote
            removedBefore = before
            removedLink = link
        }
        applyParking()
    }

    fun destroy() {
        links.values.forEach { it.destroy() }
        links.clear()
    }

    /** Park displaced cores before a promoted core can attach, including cores still dialing. */
    private fun applyParking() {
        val order = recency + saved.map { hostKey(it.url) }.filter { it !in recency }
        val (connected, offline) = order.partition { links[it]?.connected == true }
        val parked = rowsToPark(connected + offline)
        for (key in parked) links.getValue(key).park()
        for (key in connected) if (key !in parked) links.getValue(key).unpark()
    }
}
