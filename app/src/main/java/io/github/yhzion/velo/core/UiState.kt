package io.github.yhzion.velo.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json

/** UI model published by the core through [CoreListener.onState]. Decoding only; no logic. */
@Serializable
data class UiState(
    val status: String = "idle",
    val server: String = "",
    val url: String = "",
    /** Operating system account the server runs as; "" until the server says. */
    val account: String = "",
    val sessions: List<SessionInfo> = emptyList(),
    val session: String? = null,
    val windows: List<WindowInfo> = emptyList(),
    val window: String? = null,
    val focused: String? = null,
    val blocks: Map<String, BlockInfo> = emptyMap(),
    /** The remote's ports this phone forwards (#226), in the order they were made. */
    val forwards: List<ForwardInfo> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        fun decode(text: String): UiState = json.decodeFromString(serializer(), text)
    }
}

/**
 * This state without what programs change many times a second: the titles of tabs and panes, and
 * the titles and summaries of their reports. An agent sets its title several times a second while it
 * works. A screen that draws none of these reads this state ([io.github.yhzion.velo.HostConnection.steady])
 * and is not composed again for each new title.
 */
fun UiState.steady(): UiState = copy(
    sessions = sessions.map { s -> s.tabs?.let { tabs -> s.copy(tabs = tabs.map(TabInfo::untitled)) } ?: s },
    blocks = blocks.mapValues { (_, b) -> b.copy(title = "", statusTitle = "", statusSummary = "") },
)

private fun TabInfo.untitled() = copy(title = "", statusTitle = "", statusSummary = "")

/** Confined to the native serialized onState callback (not to the main thread). */
internal class StateAccumulator(private val postFullRequest: () -> Unit) {
    private var generation = 0L
    private var snapshot: UiState? = null
    private var awaitingFull = false

    fun accept(gen: Long, full: Boolean, text: String): UiState? {
        if (gen <= generation || (!full && (awaitingFull || snapshot == null || gen != generation + 1))) {
            return reject()
        }
        val next = try {
            wireJson.decodeFromString(StateDecoder(if (full) null else snapshot), text)
        } catch (_: IllegalArgumentException) { // Includes SerializationException and invalid group membership.
            return reject()
        }
        snapshot = next
        generation = gen
        awaitingFull = false
        return next
    }

    private fun reject(): UiState? {
        if (!awaitingFull) {
            awaitingFull = true
            postFullRequest()
        }
        return null
    }
}

private val wireJson = Json { ignoreUnknownKeys = true }

/** Decode the flat native groups directly into their final lists/maps. No JSON tree or
 * serialization of the previous state. A null base requires a complete replacement. */
private class StateDecoder(private val base: UiState?) : DeserializationStrategy<UiState> {
    override val descriptor get() = UiState.serializer().descriptor

    override fun deserialize(decoder: Decoder): UiState = decoder.decodeStructure(descriptor) {
        var status = base?.status
        var server = base?.server
        var url = base?.url
        var account = base?.account
        var session = base?.session
        var window = base?.window
        var focused = base?.focused
        var sessions = base?.sessions
        var windows = base?.windows
        var blocks = base?.blocks
        var forwards = base?.forwards
        var seen = 0
        while (true) {
            val i = decodeElementIndex(descriptor)
            if (i == CompositeDecoder.DECODE_DONE) break
            val bit = 1 shl i
            require(seen and bit == 0) { "Duplicate state field" }
            seen = seen or bit
            when (descriptor.getElementName(i)) {
                "status" -> status = decodeStringElement(descriptor, i)
                "server" -> server = decodeStringElement(descriptor, i)
                "url" -> url = decodeStringElement(descriptor, i)
                "account" -> account = decodeStringElement(descriptor, i)
                "session" -> session = decodeSerializableElement(descriptor, i, idSerializer)
                "window" -> window = decodeSerializableElement(descriptor, i, idSerializer)
                "focused" -> focused = decodeSerializableElement(descriptor, i, idSerializer)
                "sessions" -> sessions = decodeSerializableElement(descriptor, i, sessionsSerializer)
                "windows" -> windows = decodeSerializableElement(descriptor, i, windowsSerializer)
                "blocks" -> blocks = decodeSerializableElement(descriptor, i, blocksSerializer)
                "forwards" -> forwards = decodeSerializableElement(descriptor, i, forwardsSerializer)
                else -> throw SerializationException("Unmapped state field: " + descriptor.getElementName(i))
            }
        }
        // Members of the scalar group travel together. In particular, explicit null ids
        // replace old selections; if the group is absent the old ids survive unchanged.
        val scalars = seen and scalarMask
        require(seen != 0 && (scalars == 0 || scalars == scalarMask)) { "Incomplete state group" }
        if (base == null) require(seen == fullMask) { "Incomplete full state" }
        UiState(status = requireNotNull(status), server = requireNotNull(server), url = requireNotNull(url),
            account = requireNotNull(account), session = session, window = window, focused = focused,
            sessions = requireNotNull(sessions), windows = requireNotNull(windows), blocks = requireNotNull(blocks),
            forwards = requireNotNull(forwards))
    }

    companion object {
        private val idSerializer = String.serializer().nullable
        private val sessionsSerializer = ListSerializer(SessionInfo.serializer())
        private val windowsSerializer = ListSerializer(WindowInfo.serializer())
        private val blocksSerializer = MapSerializer(String.serializer(), BlockInfo.serializer())
        private val forwardsSerializer = ListSerializer(ForwardInfo.serializer())
        private val fields = UiState.serializer().descriptor
        private val fullMask = (1 shl fields.elementsCount) - 1
        private val scalarMask = listOf("status", "server", "url", "account", "session", "window", "focused", "forwards")
            .fold(0) { mask, name -> mask or (1 shl fields.getElementIndex(name)) }
    }
}

/**
 * A port of the remote that the phone forwards (#226): [host]:[port] on the remote, reached on
 * the phone at `127.0.0.1:`[localPort] through the tunnel block [block]. [connections] counts the
 * connections it relays now.
 */
@Serializable
data class ForwardInfo(val block: String, val host: String, val port: Int, val localPort: Int, val connections: Int = 0) {
    /** The remote's address as a link writes it, with an IPv6 host in brackets. */
    val label: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/**
 * A session of the server. [tabs] are its tabs in order. Null means they are not known: that
 * session's view has not arrived, the request for it failed, or the connection is parked. An
 * empty list is a session that really has no tabs.
 */
@Serializable
data class SessionInfo(val id: String, val label: String = "", val tabs: List<TabInfo>? = null)

/**
 * A tab of any session, as the sidebar lists it. [panes] counts the panes it draws and [active]
 * marks the tab its session shows. [minimized] is true when it also has a pane in a minimized
 * layer: not drawn, not in [panes], never the lead block, and still running. [block] is its lead block (the focused pane, else the first; null
 * when unknown), and [title], [program], [cwd], [activity] and [since] describe that block as the same
 * fields of [BlockInfo] do ([program] is "" while the core has not learned it).
 */
@Serializable
data class TabInfo(
    val id: String,
    val label: String = "",
    val icon: String? = null,
    val panes: Int = 1,
    val terminalBlocks: List<String> = emptyList(),
    val active: Boolean = false,
    val minimized: Boolean = false,
    val block: String? = null,
    val title: String = "",
    val program: String = "",
    val cwd: String = "",
    val activity: String = "idle",
    val since: Long? = null,
    val statusTitle: String = "",
    val kind: String? = null,
    val reportedIdle: Boolean = false,
    val titleIsLabel: Boolean = false,
    val statusSummary: String = "",
    /** How far a working or blocked program says it is, 0 to 100 (OSC 7501); null when it did not say. */
    val progress: Int? = null,
)

@Serializable
data class WindowInfo(
    val id: String,
    val label: String = "",
    val icon: String? = null,
    val layout: LayoutNode? = null,
    val zoomed: String? = null,
    /** True when the tab has a pane in a minimized layer: it is in neither [layout] nor [floating], and still runs. */
    val minimized: Boolean = false,
    val floating: List<FloatingLayer> = emptyList(),
    val minimizedBlocks: List<String> = emptyList(),
    /** [minimizedBlocks] grouped by minimized layer, in layer stack order; one entry per stashed layer. */
    val minimizedLayers: List<List<String>> = emptyList(),
)

/** Either a leaf (`block`) or a binary split (`dir` row|column, `ratio` = share of `first`). */
@Serializable
data class LayoutNode(
    val block: String? = null,
    val dir: String? = null,
    val ratio: Float = 0.5f,
    val first: LayoutNode? = null,
    val second: LayoutNode? = null,
) {
    fun blocks(): List<String> =
        if (block != null) listOf(block) else (first?.blocks().orEmpty() + second?.blocks().orEmpty())
}

/** Fractions of the pane area. */
@Serializable
data class FloatingLayer(val block: String, val x: Float = 0.1f, val y: Float = 0.1f, val w: Float = 0.5f, val h: Float = 0.5f)

/**
 * `program` and `cwd` describe the terminal's foreground process ("" when unknown).
 * `activity` is what its program last reported: idle, done, working, error or blocked.
 * `since` is when that report arrived at the server, as Unix milliseconds.
 */
@Serializable
data class BlockInfo(
    val title: String = "",
    val icon: String? = null,
    val program: String = "",
    val cwd: String = "",
    val activity: String = "idle",
    val attention: Boolean = false,
    val since: Long? = null,
    val statusTitle: String = "",
    val kind: String? = null,
    val reportedIdle: Boolean = false,
    val titleIsLabel: Boolean = false,
    val statusSummary: String = "",
    /** How far a working or blocked program says it is, 0 to 100 (OSC 7501); null when it did not say. */
    val progress: Int? = null,
)
