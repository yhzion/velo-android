package io.github.yhzion.velo.ui

import androidx.compose.runtime.mutableStateMapOf
import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.SeenReport
import io.github.yhzion.velo.core.BlockInfo
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class TabActivity(val icon: String?, val indicator: TabIndicator?, val badge: TabIndicator?, val since: Long?)

/** One host's latest records, with read receipts keyed by pane, record ID and the server's update time. */
internal class ActivityReadState(saved: String? = null, private val persist: (String) -> Unit = {}) {
    @Serializable
    private data class Receipt(val target: PaneAddress, val id: String?, val state: String, val updatedAt: String?)
    private data class Candidate(val receipt: Receipt, val mark: TabIndicator, val icon: String?) {
        val at = receipt.updatedAt?.let(Instant::parse)
        val rank get() = when (mark) {
            TabIndicator.Done -> 0
            TabIndicator.Working -> 1
            TabIndicator.Error -> 3
            else -> 4
        }
    }

    private val views = mutableMapOf<Any, Set<PaneAddress>>()
    private val records = mutableStateMapOf<PaneAddress, Map<String, SeenReport>>()
    private val seen = mutableStateMapOf<Pair<PaneAddress, String?>, Receipt>()
    private val order = LinkedHashSet<Pair<PaneAddress, String?>>()

    init {
        val receipts = saved?.let { runCatching { Json.decodeFromString<List<Receipt>>(it) }.getOrNull() }.orEmpty()
        for (receipt in receipts.takeLast(LIMIT)) remember(receipt)
    }

    fun retainTargets(targets: Set<PaneAddress>) {
        records.keys.removeAll { it !in targets }
    }

    /** Called after the alert store has reconciled snapshots, removals and out-of-order live events. */
    fun update(target: PaneAddress, latest: Map<String, SeenReport>) {
        val fallback = seen[target to null]
        val visible = views.values.any { target in it }
        var changed = false
        // Legacy reports can omit their time. An intervening state or removal ends the old receipt.
        for ((id, previous) in records[target].orEmpty()) {
            if (previous.state != latest[id]?.state) changed = forget(target, id) || changed
        }
        records[target] = latest
        for ((id, record) in latest) {
            if (record.state !in badgeStates) {
                changed = forget(target, id) || changed
                continue
            }
            val receipt = Receipt(target, id, record.state, record.updatedAt)
            // A pane can be drawn before its initial records arrive. Bridge its millisecond snapshot once.
            val readFromSnapshot = fallback?.state == record.state && fallback.updatedAt != null &&
                receipt.updatedAt?.let { Instant.parse(it).toEpochMilli() } == Instant.parse(fallback.updatedAt).toEpochMilli()
            if (visible || readFromSnapshot) changed = remember(receipt) || changed
        }
        if (fallback != null) {
            changed = forget(target, null) || changed
        }
        if (changed) save()
    }

    fun show(owner: Any, panes: Map<PaneAddress, BlockInfo?>): Set<PaneAddress> {
        views[owner] = panes.keys
        var changed = false
        for ((target, info) in panes) {
            val latest = records[target]
            if (latest != null) {
                for ((id, record) in latest) if (record.state in badgeStates) {
                    changed = remember(Receipt(target, id, record.state, record.updatedAt)) || changed
                }
            } else if (info != null) {
                if (info.activity in badgeStates) {
                    changed = remember(Receipt(target, null, info.activity, instant(info.since))) || changed
                } else changed = forget(target, null) || changed
            }
        }
        if (changed) save()
        return views.values.flatten().toSet()
    }

    fun hide(owner: Any): Set<PaneAddress> {
        views.remove(owner)
        return views.values.flatten().toSet()
    }

    /**
     * The tab's tile, mark and badge. The mark and the badge are its panes' unseen or animating report with the
     * highest rank, then the newest, from any pane. The tile names the program of the focused pane ([TabRow.block])
     * alone (#162): the agent of that pane's best unseen report, else of its best seen one, ranked the same way, and
     * else the program ([TabRow.icon]). A seen report loses its mark but keeps the agent it names. Both come from
     * [records] as they are now, so a report that has left the pane takes its agent with it.
     */
    fun activity(row: TabRow, connected: Boolean): TabActivity {
        if (!connected) return TabActivity(row.icon, row.indicatorWhile(false), null, null)
        var selected: Candidate? = null
        var leadShown: Candidate? = null
        var leadSeen: Candidate? = null
        for (block in row.terminalBlocks.ifEmpty { listOfNotNull(row.block) }) {
            val target = PaneAddress(row.session, row.tab, block)
            val lead = block == row.block
            val latest = records[target]
            if (latest == null && !lead) continue
            val candidates = if (latest == null) {
                listOfNotNull(row.indicator?.takeIf { row.activity in badgeStates || it == TabIndicator.Working }?.let {
                    Candidate(Receipt(target, null, row.activity, instant(row.since)), it, row.icon)
                })
            } else latest.mapNotNull { (id, record) ->
                val mark = when (record.state) {
                    "working" -> TabIndicator.Working
                    "done" -> TabIndicator.Done
                    "error" -> TabIndicator.Error
                    "blocked" -> blockedIndicator(record.kind ?: row.kind.takeIf { lead && row.activity == "blocked" })
                    else -> return@mapNotNull null
                }
                Candidate(Receipt(target, id, record.state, record.updatedAt), mark, record.agent)
            }
            for (candidate in candidates) {
                val receipt = candidate.receipt
                if (!candidate.mark.animates && seen[target to receipt.id] == receipt) {
                    // Seen: no mark, but the agent it names still runs in the pane.
                    if (lead && candidate.agent != null && outranks(candidate, leadSeen)) leadSeen = candidate
                    continue
                }
                if (outranks(candidate, selected)) selected = candidate
                if (lead && outranks(candidate, leadShown)) leadShown = candidate
            }
        }
        // Another pane's report marks the tile but never names it: the front face is the focused pane's program.
        val icon = leadShown?.agent ?: leadSeen?.agent ?: row.icon
        val result = selected ?: return TabActivity(icon, row.indicator, null, row.since)
        return TabActivity(icon, result.mark, result.mark.takeUnless { it.animates }, result.at?.toEpochMilli())
    }

    /** The agent this report names, or null when its icon is a program's (a snapshot's [TabRow.icon]). */
    private val Candidate.agent get() = icon?.takeIf { TabAgent.from(it) != null }

    /** Whether [candidate] beats [best] for the tile: the higher rank, then the newer report. */
    private fun outranks(candidate: Candidate, best: Candidate?) =
        best == null || compareValuesBy(candidate, best, { it.rank }, { it.at }) > 0

    private fun forget(target: PaneAddress, id: String?): Boolean {
        val key = target to id
        order.remove(key)
        return seen.remove(key) != null
    }

    private fun remember(receipt: Receipt): Boolean {
        val key = receipt.target to receipt.id
        if (seen[key] == receipt) return false
        order.remove(key)
        order.add(key)
        seen[key] = receipt
        if (order.size > LIMIT) {
            val oldest = order.first()
            order.remove(oldest)
            seen.remove(oldest)
        }
        return true
    }

    private fun save() = persist(Json.encodeToString(order.mapNotNull { seen[it] }))

    private companion object {
        const val LIMIT = 512
        val badgeStates = setOf("blocked", "error", "done")
        fun instant(at: Long?) = at?.let { Instant.ofEpochMilli(it).toString() }
    }
}
