package io.github.yhzion.velo

import android.content.SharedPreferences
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.hostKey
import io.github.yhzion.velo.core.remoteLabel
import io.github.yhzion.velo.core.remoteWithUrl
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * What the app remembers about the computers it reaches: the remotes saved on the phone, and
 * each one's reconnect token and last session (stored by URL), and the active row. Only
 * [SharedPreferences], so the JVM tests run it on a fake.
 */
class HostStore(private val prefs: SharedPreferences) {
    /** The saved remotes, in the order saved. */
    fun remotes(): List<Remote> {
        val text = prefs.getString(REMOTES, null) ?: return emptyList()
        return runCatching { json.decodeFromString(remoteList, text) }.getOrNull() ?: emptyList()
    }

    /**
     * Save a remote at [url] (kept in its usable spelling, [hostKey]) under [label], or under the
     * machine name when [label] is blank. Null when a saved remote already has the URL.
     */
    fun saveRemote(label: String, url: String): Remote? {
        val saved = remotes()
        if (remoteWithUrl(saved, url) != null) return null
        val remote = Remote(id = "remote:${UUID.randomUUID()}", label = label.trim().ifEmpty { remoteLabel(url) }, url = hostKey(url))
        writeRemotes(saved + remote)
        return remote
    }

    /**
     * Give the remote [id] the name [label] on this phone. Its place, its URL and what is stored
     * by the URL stay. A blank [label] or an unknown [id] changes nothing; no server is told.
     */
    fun renameRemote(id: String, label: String) {
        val name = label.trim()
        val saved = remotes()
        if (name.isEmpty() || saved.none { it.id == id }) return
        writeRemotes(saved.map { if (it.id == id) it.copy(label = name) else it })
    }

    /** Forget the remote [id]: it, its reconnect token and its last session. Other remotes stay. */
    fun removeRemote(id: String) {
        val saved = remotes()
        val gone = saved.firstOrNull { it.id == id } ?: return
        prefs.edit()
            .putString(REMOTES, json.encodeToString(remoteList, saved - gone))
            .remove(TOKEN + key(gone.url))
            .remove(SESSION + key(gone.url))
            .remove(ACTIVITY_READ + key(gone.url))
            .apply()
    }

    fun token(url: String): String? = prefs.getString(TOKEN + key(url), null)
    fun saveToken(url: String, token: String?) = prefs.edit().putString(TOKEN + key(url), token).apply()

    internal fun activityReadReceipts(url: String): String? = prefs.getString(ACTIVITY_READ + key(url), null)
    internal fun saveActivityReadReceipts(url: String, value: String) =
        prefs.edit().putString(ACTIVITY_READ + key(url), value).apply()

    fun lastSession(url: String): String? = prefs.getString(SESSION + key(url), null)
    fun saveLastSession(url: String, id: String?) = prefs.edit().putString(SESSION + key(url), id).apply()

    /** The URL of the row the user last worked in. */
    val activeUrl: String? get() = prefs.getString(ACTIVE, null)
    fun saveActive(url: String?) = prefs.edit().putString(ACTIVE, url).apply()

    /**
     * Once: the paired server of earlier versions becomes the first saved remote, with its
     * reconnect token. That is the home (`home`, its token already stored by URL) or, older,
     * `url` with `token`. The home's host list (`hosts`, `hostsHash`) is no longer kept.
     */
    fun migrate() {
        val old = OLD_KEYS.filter(prefs::contains)
        if (prefs.contains(REMOTES) && old.isEmpty()) return
        val edit = prefs.edit()
        if (!prefs.contains(REMOTES)) {
            val url = prefs.getString(HOME, null) ?: prefs.getString(OLD_URL, null)
            val remotes = url?.let { listOf(Remote(id = "remote:${UUID.randomUUID()}", label = remoteLabel(it), url = hostKey(it))) } ?: emptyList()
            edit.putString(REMOTES, json.encodeToString(remoteList, remotes))
            val token = prefs.getString(OLD_TOKEN, null)
            if (url != null && token != null && token(url) == null) edit.putString(TOKEN + key(url), token)
        }
        for (k in old) edit.remove(k)
        edit.apply()
    }

    private fun writeRemotes(remotes: List<Remote>) = prefs.edit().putString(REMOTES, json.encodeToString(remoteList, remotes)).apply()

    private companion object {
        const val REMOTES = "remotes"
        const val TOKEN = "token:"
        const val ACTIVITY_READ = "activityRead:"
        const val SESSION = "session:"
        const val ACTIVE = "active"
        const val HOME = "home"
        const val OLD_URL = "url"
        const val OLD_TOKEN = "token"
        /** Keys of earlier versions: the home, its debug override and host list, and the first pairing. */
        val OLD_KEYS = listOf(HOME, "homeUrlOverride", "hosts", "hostsHash", OLD_URL, OLD_TOKEN)

        val json = Json { ignoreUnknownKeys = true }
        val remoteList = ListSerializer(Remote.serializer())

        /** One machine, one key, however the URL is spelled. */
        fun key(url: String) = hostKey(url)
    }
}

/**
 * The session to select once a host is connected: the one saved for it, when the host still
 * has it and the core is not already on it. Null leaves the core's choice alone.
 */
fun sessionToRestore(saved: String?, sessions: List<String>, current: String?): String? =
    saved?.takeIf { it != current && it in sessions }

internal const val STREAMING_REMOTE_LIMIT = 4

/** Rows beyond the stream limit, with connected rows ranked before those still dialing. */
fun rowsToPark(order: List<String>, limit: Int = STREAMING_REMOTE_LIMIT): Set<String> = order.drop(limit).toSet()
