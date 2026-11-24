package io.github.yhzion.velo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.remoteWithUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the app's Tailscale node is doing. */
sealed interface TailnetState {
    data object Off : TailnetState
    data object Starting : TailnetState
    /** The node needs its owner to log in at [url] (in a browser). */
    data class NeedsLogin(val url: String) : TailnetState
    /** The node was logged in since it started and needs a login again (its key expired or was removed); it has no login page yet. */
    data object LoggedOut : TailnetState
    /** On the tailnet as [name] (MagicDNS name) with [ips]. */
    data class Running(val name: String, val ips: List<String>) : TailnetState
    data class Error(val message: String) : TailnetState
}

/** A Rex server found on the tailnet: its machine [name] and the [url] to reach it. */
data class DiscoveredServer(val name: String, val url: String)

/** An online tailnet peer: its machine [name] and MagicDNS [dns] name (no trailing dot). */
data class TailnetPeer(val name: String, val dns: String)

/** JNI bridge to the process-wide node (native/src/sys/tailnet.zig). Calls block. */
object TailnetNative {
    init {
        System.loadLibrary("velocore")
    }

    /** Null when the node started, else why not ("AlreadyStarted" when one runs). */
    @JvmStatic external fun start(dir: String, hostname: String): String?
    /** Returns once the node is closed. */
    @JvmStatic external fun stop()
    /** Null when logged out, else why not. */
    @JvmStatic external fun logout(): String?
    /** `{}` when stopped, else `{"statusError"?, "status": <tailscale status>}`. */
    @JvmStatic external fun statusJson(): String
    /** The body of a 200 answer to GET [url] through the node, else null. */
    @JvmStatic external fun get200(url: String, timeoutMs: Int): String?
}

/**
 * The app's own tailnet node (libtailscale): one per process, shared by every core. While it is
 * Running, every core connects through it; otherwise through the system network. Its state (and
 * login) persists under the app's files directory. While Running it sweeps online peers for Rex
 * servers ([discovered]). [state] and [discovered] are main-thread Compose state.
 */
object Tailnet {
    var state by mutableStateOf<TailnetState>(TailnetState.Off)
        private set
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList())
        private set
    /**
     * How many sweeps have ended since the node last started or stopped, whatever they found (a
     * sweep that failed counts). 0 means nothing is known yet about who is on the tailnet.
     */
    var sweepsFinished by mutableStateOf(0)
        private set
    /** A sweep is probing now. */
    var searching by mutableStateOf(false)
        private set
    /** Android withholds the network from the app (Doze, background): the node cannot reach the tailnet. */
    var paused by mutableStateOf(false)
        private set
    private var watching = false

    /** Follow whether Android blocks the app's network ([paused]). Once per process. */
    fun watchNetwork(context: Context) {
        if (watching) return
        watching = true
        // Off the main thread: the app's controller asks for it before the first frame, and it is a call to
        // system_server ([SystemCalls], #336).
        SystemCalls.submit {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@submit
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    main.post { paused = blocked }
                }
                override fun onLost(network: Network) {
                    main.post { paused = false }
                }
            })
        }
    }

    private val main = Handler(Looper.getMainLooper())
    /** Every native call runs here, one at a time: start, stop and restart never overlap. */
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "tailnet") }
    /** Discovery GETs run here, so a slow peer does not hold status reads. */
    private val sweeper = Executors.newSingleThreadExecutor { Thread(it, "tailnet-sweep") }
    /** Bumped by start and stop: status reads of an older run stop and are not published. */
    private val generation = AtomicInteger()
    private val stateListeners = mutableListOf<(was: TailnetState, now: TailnetState) -> Unit>()
    @Volatile private var lastSweepMs = 0L
    /** A sweep is probing; set by the thread that starts one (compareAndSet), cleared when it ends. */
    private val sweeping = AtomicBoolean(false)

    /** [f] runs (main thread) each time the node's state changes. */
    fun addStateListener(f: (was: TailnetState, now: TailnetState) -> Unit) { stateListeners += f }
    fun removeStateListener(f: (was: TailnetState, now: TailnetState) -> Unit) { stateListeners -= f }

    /** Main thread: the node's state is [next] from now on. */
    private fun publish(next: TailnetState) {
        val was = state
        if (next == was) return
        state = next
        Log.i(TAG, "state: $next")
        stateListeners.toList().forEach { it(was, next) }
    }

    /** Main thread: no sweep of a node that started, stopped or left Running says anything about the tailnet now. */
    private fun forgetDiscovery() {
        discovered = emptyList()
        sweepsFinished = 0
        searching = false
    }

    /** Start the node as [hostname] with its state in [dir]; a running node is restarted. */
    fun start(dir: File, hostname: String) {
        val gen = generation.incrementAndGet()
        publish(TailnetState.Starting)
        forgetDiscovery()
        worker.execute {
            TailnetNative.stop()
            val failed = TailnetNative.start(dir.path, hostname)
            if (failed != null) {
                main.post { if (generation.get() == gen) publish(TailnetState.Error(failed)) }
                return@execute
            }
            readStatus(gen, loggedInBefore = false)
        }
    }

    /** Close the node; connections go back to the system network. The login stays. */
    fun stop() {
        generation.incrementAndGet()
        publish(TailnetState.Off)
        forgetDiscovery()
        worker.execute { TailnetNative.stop() }
    }

    /**
     * Log the node out, then start it again as [hostname] in [dir]: tsnet asks for an
     * interactive login only when a node starts, so only a new node shows a new login URL.
     */
    fun logout(dir: File, hostname: String) {
        val gen = generation.incrementAndGet()
        publish(TailnetState.Starting)
        forgetDiscovery()
        worker.execute {
            val failed = TailnetNative.logout()
            if (failed != null) {
                // The node keeps its state: show why, then let the status poll show the real
                // state again. No node at all (stopped meanwhile) is Off, not an error.
                val after = logoutFailedState(failed, TailnetNative.statusJson())
                Log.i(TAG, "logout failed ($failed): $after")
                main.post { if (generation.get() == gen) publish(after) }
                if (after != TailnetState.Off) worker.schedule({ readStatus(gen, loggedInBefore = false) }, STARTING_POLL_MS, TimeUnit.MILLISECONDS)
                return@execute
            }
            TailnetNative.stop()
            val startFailed = TailnetNative.start(dir.path, hostname)
            if (startFailed != null) {
                main.post { if (generation.get() == gen) publish(TailnetState.Error(startFailed)) }
                return@execute
            }
            readStatus(gen, loggedInBefore = false)
        }
    }

    /** Sweep the tailnet for Rex servers now (the sidebar opened), unless one ran in the last few seconds. */
    fun sweepSoon() {
        if (!sweepDue(state is TailnetState.Running, paused, lastSweepMs, System.currentTimeMillis(), MIN_SWEEP_GAP_MS)) return
        val gen = generation.get()
        worker.execute { sweep(gen, TailnetNative.statusJson()) }
    }

    /**
     * On the worker: read the status, publish it, read it again soon. [loggedInBefore] is what the
     * reads since this start have seen ([loggedInSince]); each start, stop and logout begins a new
     * chain of reads (a new [generation]) with it false.
     */
    private fun readStatus(gen: Int, loggedInBefore: Boolean) {
        if (generation.get() != gen) return
        val text = TailnetNative.statusJson()
        val next = parseTailnetStatus(text, loggedInBefore)
        val loggedIn = loggedInSince(loggedInBefore, next)
        main.post {
            if (generation.get() != gen) return@post
            publish(next)
            if (next !is TailnetState.Running) forgetDiscovery()
        }
        // `paused` is main-thread state; a stale read only delays or adds one sweep.
        if (sweepDue(next is TailnetState.Running, paused, lastSweepMs, System.currentTimeMillis(), SWEEP_MS)) sweep(gen, text)
        // While Running, often enough that the native dialer's Running evidence (5 s) stays fresh.
        val delay = if (next is TailnetState.Running) RUNNING_POLL_MS else STARTING_POLL_MS
        worker.schedule({ readStatus(gen, loggedIn) }, delay, TimeUnit.MILLISECONDS)
    }

    /** Probe the online peers of [statusText] for Rex servers, off the worker. */
    private fun sweep(gen: Int, statusText: String) {
        if (!sweeping.compareAndSet(false, true)) return
        lastSweepMs = System.currentTimeMillis()
        main.post { if (generation.get() == gen) searching = true }
        val peers = parsePeers(statusText)
        sweeper.execute {
            try {
                val found = peers.mapNotNull { peer ->
                    val url = "https://${peer.dns}"
                    val body = TailnetNative.get200("$url/llms.txt", PROBE_TIMEOUT_MS)
                    if (body != null && isRexLlmsTxt(body)) DiscoveredServer(peer.name, url) else null
                }
                Log.i(TAG, "sweep: ${peers.size} online peers, Rex on ${found.map { it.name }}")
                // One sweep at a time, and a sweep of an older node (generation) never publishes.
                main.post { if (generation.get() == gen) discovered = found }
            } finally {
                sweeping.set(false)
                // Also after a failed sweep: it ended, and "looking" must not last for ever. A sweep
                // that ends after the node left Running says nothing about the tailnet any more.
                main.post {
                    if (generation.get() == gen) {
                        searching = false
                        if (state is TailnetState.Running) sweepsFinished++
                    }
                }
            }
        }
    }

    private const val TAG = "velo.tailnet"
    private const val STARTING_POLL_MS = 2_000L
    private const val RUNNING_POLL_MS = 3_000L
    private const val SWEEP_MS = 60_000L
    private const val MIN_SWEEP_GAP_MS = 5_000L
    private const val PROBE_TIMEOUT_MS = 4_000
}

private val statusJson = Json { ignoreUnknownKeys = true }

/**
 * [TailnetState] from [TailnetNative.statusJson]'s text. [loggedInBefore]: since the node started,
 * it has been Running or has needed a login at an address ([loggedInSince]). A node that needs a
 * login without an address is still starting at first, and logged out after that: its key expired
 * while the app ran, and a new login page comes only when the node starts again.
 */
fun parseTailnetStatus(text: String, loggedInBefore: Boolean = false): TailnetState {
    val root = runCatching { statusJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return TailnetState.Error("unreadable node status")
    if (root.isEmpty()) return TailnetState.Off
    val status = root["status"] as? JsonObject
        ?: return TailnetState.Error(root.string("statusError") ?: "no node status")
    val backend = status.string("BackendState") ?: ""
    val authUrl = status.string("AuthURL") ?: ""
    return when (backend) {
        "Running" -> {
            val self = status["Self"] as? JsonObject
            val name = self?.string("DNSName")?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: self?.string("HostName") ?: ""
            val ips = (status["TailscaleIPs"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            TailnetState.Running(name, ips)
        }
        "NeedsLogin" -> when {
            authUrl.isNotEmpty() -> TailnetState.NeedsLogin(authUrl)
            loggedInBefore -> TailnetState.LoggedOut
            else -> TailnetState.Starting
        }
        "NeedsMachineAuth" -> TailnetState.Error("waiting for the tailnet admin to approve this device")
        "Stopped" -> TailnetState.Error("the node is stopped")
        else -> TailnetState.Starting
    }
}

/** [loggedInBefore] after a read that found [now]: Running, or a login at an address, shows the node was logged in. */
fun loggedInSince(loggedInBefore: Boolean, now: TailnetState): Boolean =
    loggedInBefore || now is TailnetState.Running || now is TailnetState.NeedsLogin

/** The online peers with a MagicDNS name in a status ([TailnetNative.statusJson] or `tailscale status --json`). */
fun parsePeers(text: String): List<TailnetPeer> {
    val root = runCatching { statusJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
    val status = root["status"] as? JsonObject ?: root
    val peers = status["Peer"] as? JsonObject ?: return emptyList()
    return peers.values.mapNotNull { p ->
        val peer = p as? JsonObject ?: return@mapNotNull null
        if ((peer["Online"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
        val dns = peer.string("DNSName")?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        TailnetPeer(peer.string("HostName")?.takeIf { it.isNotEmpty() } ?: dns.substringBefore('.'), dns)
    }.sortedBy { it.name.lowercase() }
}

/** A Rex server answers `GET /llms.txt` with its protocol reference, which names Rex. */
fun isRexLlmsTxt(body: String): Boolean = body.contains("Rex")

/** The state after a failed logout: Off when there is no node any more ([statusText] `{}`), else the error. */
fun logoutFailedState(message: String, statusText: String): TailnetState =
    if (parseTailnetStatus(statusText) == TailnetState.Off) TailnetState.Off else TailnetState.Error("Log out failed: $message")

/**
 * Whether to sweep now: only on a Running node whose network Android does not withhold
 * ([paused]), and at least [gapMs] after the last sweep began.
 */
fun sweepDue(running: Boolean, paused: Boolean, lastSweepMs: Long, nowMs: Long, gapMs: Long): Boolean =
    running && !paused && nowMs - lastSweepMs >= gapMs

/** What the connections do when the node's state changes ([redialOn]). */
enum class Redial { None, Waiting, All }

/**
 * The node arrived at Running: every connection dials again, through it ([Redial.All]). It
 * stopped starting without coming to run (a login is needed, it failed, it was switched off):
 * the connections that waited for it dial on the system network ([Redial.Waiting]). Anything
 * else changes nothing: a core whose link ended retries by itself.
 */
fun redialOn(was: TailnetState, now: TailnetState): Redial = when {
    now is TailnetState.Running -> if (was is TailnetState.Running) Redial.None else Redial.All
    was == TailnetState.Starting && now != TailnetState.Starting -> Redial.Waiting
    else -> Redial.None
}

/** The servers that are not a [saved] remote yet, however a URL is spelled: "Found on tailnet". */
fun notYetRows(found: List<DiscoveredServer>, saved: List<Remote>): List<DiscoveredServer> =
    found.filter { remoteWithUrl(saved, it.url) == null }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
