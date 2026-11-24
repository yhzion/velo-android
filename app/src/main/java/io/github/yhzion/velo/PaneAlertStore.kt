package io.github.yhzion.velo

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.core.UiState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

@Serializable
internal data class PaneAddress(val session: String, val window: String, val block: String) {
    fun isPresent(state: UiState): Boolean {
        if (state.status != "connected" || state.session != session) return false
        val tab = state.windows.firstOrNull { it.id == window } ?: return false
        return block in tab.layout?.blocks().orEmpty() || tab.floating.any { it.block == block }
    }

    fun isOpen(state: UiState): Boolean = isPresent(state) && state.window == window &&
        state.focused == block && state.windows.first { it.id == window }.zoomed.let { it == null || it == block }
}

internal enum class ActivitySound(val resource: Int) {
    Attention(R.raw.activity_blocked), Warning(R.raw.activity_warning), Error(R.raw.activity_error),
    Success(R.raw.activity_done), Ready(R.raw.activity_ready), Loading(R.raw.activity_loading),
}

internal enum class PaneAlertKind(val channel: String, val label: String, val defaultSound: ActivitySound, val priority: Int) {
    Blocked("pane_blocked", "Needs your response", ActivitySound.Attention, 0),
    Done("pane_done", "Work completed", ActivitySound.Success, 2),
    Error("pane_error", "Work failed", ActivitySound.Error, 1),
    Working("pane_working", "Work started", ActivitySound.Loading, 3),
}

@Serializable
internal data class PaneReport(
    val target: PaneAddress,
    val id: String = "",
    val state: String? = null,
    val kind: String? = null,
    val updatedAt: String? = null,
    val removed: List<String> = emptyList(),
    val baseline: List<PaneStatusRecord>? = null,
    val pending: List<PaneReport> = emptyList(),
    val agent: String? = null,
    /** What the program says the work is ([title]) and how it goes ([msg]), as it reported them. */
    val title: String? = null,
    val msg: String? = null,
)

@Serializable
internal data class PaneStatusRecord(val id: String, val state: String, val updatedAt: String? = null, val kind: String? = null, val agent: String? = null)

@Serializable
internal data class SeenReport(val state: String, val updatedAt: String? = null, val kind: String? = null, val agent: String? = null)

@Serializable
internal data class PaneSubscription(
    val token: String,
    val remote: String,
    val target: PaneAddress,
    val label: String,
    val blocked: Boolean = false,
    val done: Boolean = false,
    val seen: Map<String, SeenReport> = emptyMap(),
    val error: Boolean = false,
    val working: Boolean = false,
) {
    fun enabled(kind: PaneAlertKind) = when (kind) {
        PaneAlertKind.Blocked -> blocked
        PaneAlertKind.Done -> done
        PaneAlertKind.Error -> error
        PaneAlertKind.Working -> working
    }
}

/**
 * [title] and [message] are the program's own words for the work and its state; [agent] is the
 * program that reported. [location] names where it runs (remote · session · tab) as the screen does.
 * [record] and [updatedAt] are the server's id and report time of the status record, as it sent
 * them: an answer from the notification is written only while the record is still this one.
 */
internal data class PaneAlert(
    val subscription: PaneSubscription,
    val kind: PaneAlertKind,
    val reason: String?,
    val title: String? = null,
    val message: String? = null,
    val agent: String? = null,
    val location: String = subscription.label,
    val record: String = "",
    val updatedAt: String? = null,
)

/** Main thread only. A baseline updates deduplication state silently. */
internal class PaneAlertStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "paneSubscriptions"
    private val automatic = mutableMapOf<Pair<String, PaneAddress>, PaneSubscription>()
    private var routes = prefs.getString("activityRoutes", null)?.let {
        runCatching { json.decodeFromString<List<PaneSubscription>>(it) }.getOrNull()
    }.orEmpty()

    fun rememberRoute(sub: PaneSubscription, activeTokens: Set<String>) {
        val active = routes.filter { it.token in activeTokens && it.token != sub.token }
        val next = if (find(sub.remote, sub.target) == null) active + sub.copy(seen = emptyMap()) else active
        if (next == routes) return
        routes = next
        saveRoutes()
    }

    private fun saveRoutes() {
        prefs.edit().putString("activityRoutes", json.encodeToString(routes)).apply()
    }

    fun retainTargets(remote: String, targets: Set<PaneAddress>) {
        automatic.keys.removeAll { it.first == remote && it.second !in targets }
    }
    private var subscriptions by mutableStateOf(
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<List<PaneSubscription>>(it) }.getOrNull() }.orEmpty()
    )

    fun postedTokens(remote: String) = (routes + subscriptions).filter { it.remote == remote }.map { it.token }.toSet()
    fun forRemote(remote: String) = subscriptions.filter { it.remote == remote }
    fun find(remote: String, target: PaneAddress) = subscriptions.firstOrNull { it.remote == remote && it.target == target }
    fun byToken(token: String) = subscriptions.firstOrNull { it.token == token } ?: routes.firstOrNull { it.token == token }
    /** Every token a notification of the pane [target] of [remote] may carry: its subscription's, its automatic one's, a remembered route's. */
    fun tokensFor(remote: String, target: PaneAddress) = listOfNotNull(find(remote, target), automatic[remote to target])
        .plus(routes.filter { it.remote == remote && it.target == target }).map { it.token }.toSet()

    fun records(remote: String, target: PaneAddress) = (find(remote, target) ?: automatic[remote to target])?.seen.orEmpty()

    fun customize(remote: String, target: PaneAddress, label: String, enabled: (PaneAlertKind) -> Boolean) {
        if (find(remote, target) != null) return
        val current = automatic.remove(remote to target)
        val token = current?.token ?: UUID.randomUUID().toString()
        save(subscriptions + PaneSubscription(token, remote, target, label,
            blocked = enabled(PaneAlertKind.Blocked), done = enabled(PaneAlertKind.Done),
            error = enabled(PaneAlertKind.Error), working = enabled(PaneAlertKind.Working), seen = current?.seen.orEmpty()))
    }

    fun inherit(remote: String, target: PaneAddress) {
        val sub = find(remote, target) ?: return
        automatic[remote to target] = sub.copy(blocked = false, done = false, error = false, working = false)
        save(subscriptions.filterNot { it.token == sub.token })
    }

    fun set(remote: String, target: PaneAddress, label: String, kind: PaneAlertKind, enabled: Boolean) {
        val old = find(remote, target)
        if ((old?.enabled(kind) ?: false) == enabled) return
        val sub = old ?: automatic.remove(remote to target)?.copy(token = UUID.randomUUID().toString()) ?: PaneSubscription(UUID.randomUUID().toString(), remote, target, label)
        val next = when (kind) {
            PaneAlertKind.Blocked -> sub.copy(blocked = enabled, label = label)
            PaneAlertKind.Done -> sub.copy(done = enabled, label = label)
            PaneAlertKind.Error -> sub.copy(error = enabled, label = label)
            PaneAlertKind.Working -> sub.copy(working = enabled, label = label)
        }
        save(subscriptions.filter { it.token != sub.token } + next)
    }

    fun removeRemote(remote: String) {
        save(subscriptions.filter { it.remote != remote })
        retainTargets(remote, emptySet())
        routes = routes.filterNot { it.remote == remote }
        saveRoutes()
    }

    fun receive(remote: String, report: PaneReport, activityLabel: String? = null): List<PaneAlert> {
        val explicit = find(remote, report.target)
        val sub = explicit ?: activityLabel?.let { label ->
            val key = remote to report.target
            automatic.getOrPut(key) {
                val token = routes.firstOrNull { it.remote == remote && it.target == report.target }?.token
                PaneSubscription(token ?: UUID.randomUUID().toString(), remote, report.target, label)
            }.let { if (it.label == label) it else it.copy(label = label) }
        } ?: return emptyList()
        val seen = sub.seen.toMutableMap()
        val alerts = mutableListOf<PaneAlert>()
        val snapshot = report.baseline
        if (snapshot == null) {
            applyLive(sub, seen, report, activityLabel != null)?.let(alerts::add)
        } else {
            val pending = report.pending.filter { it.target == sub.target }
            val removedLive = pending.flatMap { it.removed }.toSet()
            clearRecords(seen, seen.keys - snapshot.map { it.id }.toSet() - removedLive)
            // A sample also seen on the wire belongs at its live position. Other samples
            // seed their record silently, before any newer live report for that record.
            val changes = pending.withIndex().filter { it.value.state != null }.groupBy { it.value.id }
            val samples = snapshot.mapNotNull { record ->
                val live = changes[record.id].orEmpty()
                val at = timestamp(record.updatedAt)
                if (live.any { it.value.state == record.state && timestamp(it.value.updatedAt) == at }) null
                else {
                    val index = if (live.isEmpty() || at == null) 0 else live.firstOrNull {
                        val t = timestamp(it.value.updatedAt)
                        t == null || t >= at
                    }?.index ?: pending.size
                    index to record
                }
            }.groupBy({ it.first }, { it.second })
            for (i in 0..pending.size) {
                for (record in samples[i].orEmpty()) {
                    val at = timestamp(record.updatedAt)?.toString() ?: seen[record.id]?.updatedAt
                    seen[record.id] = SeenReport(record.state, at, record.kind, record.agent)
                }
                if (i < pending.size) applyLive(sub, seen, pending[i], activityLabel != null)?.let(alerts::add)
            }
        }
        val updated = sub.copy(seen = seen)
        if (explicit == null) automatic[remote to report.target] = updated else replace(updated)
        return alerts
    }

    private fun applyLive(sub: PaneSubscription, seen: MutableMap<String, SeenReport>, report: PaneReport, global: Boolean): PaneAlert? {
        if (report.removed.isNotEmpty()) {
            clearRecords(seen, report.removed)
            return null
        }
        val state = report.state ?: return null
        val old = seen[report.id]
        val at = timestamp(report.updatedAt)
        val last = old?.updatedAt?.let { Instant.parse(it) }
        if (at != null && last != null && (at < last || at == last && old.state == "clear")) return null
        seen[report.id] = SeenReport(state, (at ?: last)?.toString(), report.kind, report.agent)
        if (old?.state == state) return null
        val kind = when (state) {
            "blocked" -> PaneAlertKind.Blocked
            "done" -> PaneAlertKind.Done
            "error" -> PaneAlertKind.Error
            "working" -> PaneAlertKind.Working
            else -> return null
        }
        return if (global || sub.enabled(kind)) PaneAlert(sub, kind, report.kind, report.title, report.msg, report.agent,
            record = report.id, updatedAt = report.updatedAt) else null
    }

    private fun timestamp(value: String?) = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun clearRecords(seen: MutableMap<String, SeenReport>, removed: Collection<String>) {
        // Keep recent removal timestamps so replaying an old record cannot revive it.
        for (id in removed) {
            val old = seen.remove(id) ?: continue
            if (old.updatedAt != null) seen[id] = old.copy(state = "clear")
        }
        val cleared = seen.filterValues { it.state == "clear" }.keys.toList()
        for (id in cleared.dropLast(64)) seen.remove(id)
    }

    private fun replace(next: PaneSubscription) = save(subscriptions.map { if (it.token == next.token) next else it })
    private fun save(next: List<PaneSubscription>) {
        if (next == subscriptions) return
        subscriptions = next
        prefs.edit().putString(key, json.encodeToString(next)).apply()
    }
}
