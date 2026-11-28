package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.UiState

/**
 * What Activity (the sidebar's eye) lists, as pure functions of the remotes' states: every reporting tab of every
 * connected remote in one list, no remote headers, in Rex for Mac's groups (#247, research of build 2185 on
 * 2026-11-28: described, not copied). No Android and no Compose, so the JVM tests run it directly.
 */

/**
 * The groups Activity shows, in Rex for Mac's order: programs waiting for the user, then failed, working and finished
 * ones. An empty group is not shown, its title neither.
 */
enum class ActivityPriority(val title: String) { Blocked("Blocked"), Failed("Failed"), Working("Working"), Done("Done") }

/**
 * The group a tab joins while Activity is on, from what its row draws ([activity]); null leaves it out. A tab that
 * works, waits or failed is in while it does so. A finished tab and one whose program reported that it is idle are
 * in Done, seen or not, the shown tab too (#247: Rex for Mac 2185, unlike 2077, keeps them). A tab with no report,
 * a shell's, is left out. Without a mark (the remote is offline) the lead pane's report decides.
 */
internal fun activityPriority(row: TabRow, activity: TabActivity): ActivityPriority? = when (activity.indicator) {
    TabIndicator.Working, TabIndicator.CodexActive -> ActivityPriority.Working
    TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth -> ActivityPriority.Blocked
    TabIndicator.Error -> ActivityPriority.Failed
    TabIndicator.Done, TabIndicator.Idle -> ActivityPriority.Done
    else -> when (row.activity) {
        "working" -> ActivityPriority.Working
        "blocked" -> ActivityPriority.Blocked
        "error" -> ActivityPriority.Failed
        "done" -> ActivityPriority.Done
        else -> ActivityPriority.Done.takeIf { row.indicator == TabIndicator.Idle }
    }
}

/**
 * One connected remote as Activity reads it: its id ([host]), its [state], whether the workspace draws it ([active]:
 * its shown tab is the one row with the pill) and what each of its rows draws ([activity]).
 */
internal class ActivitySource(val host: String, val state: UiState, val active: Boolean, val activity: (TabRow) -> TabActivity)

/**
 * One tab in Activity's list. [host] is its remote's id; [drawn] is what its row draws ([ActivityReadState.activity]).
 * [enteredAt] is when it came into its [priority] ([ActivityEntered]), null when that is not known. [sessionLabel]
 * names its session for the second line. [message] is the third line (#248, #249): for a working tab its program's
 * words, else its title ([workingMessage]); for a waiting or failed one the words it waits or failed with
 * ([tabWords]); null for a finished one, and whenever there are none.
 */
internal data class ActivityEntry(
    val host: String, val row: TabRow, val drawn: TabActivity, val priority: ActivityPriority, val enteredAt: Long?,
    val sessionLabel: String, val message: String?,
) {
    /** Its key in the list: the same tab keeps it from one group to another, so its row moves rather than going. */
    val key: String get() = activityKey(host, row.session, row.tab)
}

internal fun activityKey(host: String, session: String, tab: String) = "$host/$session/$tab"

/** One of Activity's groups: its [priority] and its tabs, of every remote and session. */
internal data class ActivityGroup(val priority: ActivityPriority, val entries: List<ActivityEntry>)

/**
 * When each tab came into its group, as Activity orders a group by it: newest first (#247). The core's `since` is when
 * a report arrived, and a program reports again with new words or progress in the same state; that moves no row. So
 * a tab keeps the time it had while it stays in its group. A tab seen for the first time takes its report's time; one
 * that moves to another group takes the new report's time, else (an idle report has none) the clock's [now]. Kept for
 * the sidebar's life ([SidebarState]), so a closed and opened sidebar keeps the order.
 */
internal class ActivityEntered {
    private val at = HashMap<String, Pair<ActivityPriority, Long?>>()

    fun enteredAt(key: String, priority: ActivityPriority, since: Long?, now: Long): Long? {
        val before = at[key]
        val value = when {
            before == null -> since
            before.first == priority -> before.second ?: since
            else -> since?.takeIf { s -> before.second.let { it == null || s > it } } ?: now
        }
        at[key] = priority to value
        return value
    }

    /** Forget the tabs no longer listed: one that comes back is seen anew. */
    fun retain(keys: Set<String>) {
        at.keys.retainAll(keys)
    }
}

/**
 * What Activity lists: the tabs of every remote in [sources] that have an [activityPriority] and whose name, folder or
 * program holds [query], whatever session is folded, in one group per [ActivityPriority] that has any. A group lists
 * the tab that came into it last first ([ActivityEntered]); tabs of no known time follow, in the remotes' and the
 * servers' order. [entered] remembers the times; [now] is the clock's.
 */
internal fun activityGroups(sources: List<ActivitySource>, query: String, entered: ActivityEntered, now: Long): List<ActivityGroup> {
    val entries = sources.flatMap { source ->
        val state = source.state
        val drawn = HashMap<TabRow, Pair<TabActivity, ActivityPriority>>()
        sessionGroups(state, source.active, emptySet(), true, query) { row ->
            val activity = source.activity(row)
            activityPriority(row, activity)?.also { drawn[row] = activity to it } != null
        }.flatMap { group ->
            group.rows.map { row ->
                val (activity, priority) = drawn.getValue(row)
                val key = activityKey(source.host, row.session, row.tab)
                val message = when (priority) {
                    ActivityPriority.Working -> workingMessage(state, row)
                    ActivityPriority.Blocked, ActivityPriority.Failed -> tabWords(state, row)
                    ActivityPriority.Done -> null
                }
                ActivityEntry(source.host, row, activity, priority, entered.enteredAt(key, priority, activity.since, now),
                    group.label, message)
            }
        }
    }
    entered.retain(entries.mapTo(HashSet()) { it.key })
    val byPriority = entries.groupBy { it.priority }
    return ActivityPriority.entries.mapNotNull { priority ->
        byPriority[priority]?.let { group -> ActivityGroup(priority, group.sortedByDescending { it.enteredAt ?: Long.MIN_VALUE }) }
    }
}

/** The report of [row]'s lead pane: from its session's list, else, for the selected session, from its block. */
private fun leadReport(state: UiState, row: TabRow): Pair<String, String>? {
    val tab = state.sessions.firstOrNull { it.id == row.session }?.tabs?.firstOrNull { it.id == row.tab }
    if (tab != null) return tab.statusSummary to tab.statusTitle
    val block = row.block?.takeIf { row.session == state.session }?.let { state.blocks[it] } ?: return null
    return block.statusSummary to block.statusTitle
}

private fun String.oneLine() = lines().joinToString(" ") { it.trim() }.trim()

/**
 * The words the program of [row] reports with its state (OSC 7501 `msg`) on one line; null without any. A waiting or
 * failed tab's well says them even when they also name the row (a tab with no title is named by them): the well has
 * room for two lines, the name one.
 */
internal fun tabWords(state: UiState, row: TabRow): String? = leadReport(state, row)?.first?.oneLine()?.takeIf { it.isNotEmpty() }

/**
 * What a working row's capsule says (#249): its program's words ([tabWords]), else the title it reports (OSC 7501
 * `title`); null when it reports neither, and when that is the name the row already shows: a capsule would only repeat it.
 */
internal fun workingMessage(state: UiState, row: TabRow): String? =
    (tabWords(state, row) ?: leadReport(state, row)?.second?.oneLine())?.takeIf { it.isNotEmpty() && it != row.name }

/**
 * How long ago a tab came into its group, as Activity's second line says it (#248): "now" within the minute, then
 * whole minutes, hours and days ("7m", "1h", "19h", "1d"). Null when the time is not known, or is ahead of the clock
 * by more than a minute (the server's clock and the phone's differ a little).
 */
internal fun shortAge(atMs: Long, nowMs: Long): String? {
    val elapsed = nowMs - atMs
    if (elapsed < -60_000) return null
    val minutes = elapsed.coerceAtLeast(0) / 60_000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "now"
        hours < 1 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        else -> "${hours / 24}d"
    }
}

/**
 * The reason a waiting tab's well gives above its words (#248): what its mark says it waits for ("Waiting for an
 * answer", "Waiting for approval", …); null for any other mark. A failed tab's well has its words only.
 */
internal fun wellReason(indicator: TabIndicator?): String? = when (indicator) {
    TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth -> indicator.description
    else -> null
}
