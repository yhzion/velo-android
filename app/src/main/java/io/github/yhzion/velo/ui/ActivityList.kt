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
 * its shown tab is the one row with the pill), what each of its rows draws ([activity]) and when each of its tabs came
 * into its group ([entered]).
 */
internal class ActivitySource(
    val host: String, val state: UiState, val active: Boolean, val entered: ActivityEntered, val activity: (TabRow) -> TabActivity,
)

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
    val key: String get() = "$host/${row.session}/${row.tab}"
}

/** One of Activity's groups: its [priority] and its tabs, of every remote and session. */
internal data class ActivityGroup(val priority: ActivityPriority, val entries: List<ActivityEntry>)

/**
 * When each tab of one remote came into its group, as Activity orders a group by it: newest first (#247). The core's
 * `since` is when a report arrived, and a program reports again with new words or progress in the same state; that
 * moves no row. So a tab keeps the time it had while it stays in its group. A tab seen for the first time takes its
 * report's time; one that moves to another group takes the new report's time, else (an idle report has none, or the
 * time is older than the one it had) the phone's clock: the times of a group then come from two clocks, a few seconds
 * apart at most where both keep time. The remote's connection keeps it ([ActivityReadState.entered]) and [observeActivity]
 * updates it with every state and report, whether the sidebar is open, Activity is on or a filter hides the tab.
 */
internal class ActivityEntered {
    private val at = HashMap<String, Pair<ActivityPriority, Long?>>()

    /** When the tab [tab] of [session] came into its group; null when it was not seen yet, or its time is not known. */
    fun at(session: String, tab: String): Long? = at[key(session, tab)]?.second

    /** Notes that the tab [tab] of [session] is in [priority] now, with a report of [since]; returns when it came into it. */
    fun note(session: String, tab: String, priority: ActivityPriority, since: Long?, now: Long): Long? {
        val key = key(session, tab)
        val before = at[key]
        val value = when {
            before == null -> since
            before.first == priority -> before.second ?: since
            else -> since?.takeIf { s -> before.second.let { it == null || s > it } } ?: now
        }
        at[key] = priority to value
        return value
    }

    /** Forgets a tab that is in no group now: when it comes back, it comes into its group then. */
    fun leave(session: String, tab: String) {
        at.remove(key(session, tab))
    }

    /** Forgets the tabs of [session] for which [keep] is false. */
    fun retain(keep: (session: String, tab: String) -> Boolean) {
        at.keys.removeAll { k -> k.split('/', limit = 2).let { !keep(it[0], it[1]) } }
    }

    private fun key(session: String, tab: String) = "$session/$tab"
}

/**
 * Notes in [read]'s [ActivityReadState.entered] the group every tab of [state] is in now ([activityPriority] of what
 * its row draws), at the clock's [now]: called with every state and every report of a connected remote, so the
 * times stay right while nothing shows them. A tab of a session whose tabs are not known keeps what it had; one that
 * has gone is forgotten.
 */
internal fun observeActivity(state: UiState, read: ActivityReadState, now: Long) {
    val entered = read.entered
    for (group in sessionGroups(state, false, emptySet(), false, "")) for (row in group.rows) {
        val drawn = read.activity(row, true)
        val priority = activityPriority(row, drawn)
        if (priority == null) entered.leave(row.session, row.tab) else entered.note(row.session, row.tab, priority, drawn.since, now)
    }
    val tabs = state.sessions.associate { s -> s.id to s.tabs?.mapTo(HashSet()) { it.id } }
    entered.retain { session, tab -> session in tabs && tabs[session].let { it == null || tab in it } }
}

/**
 * What Activity lists: the tabs of every remote in [sources] that have an [activityPriority] and whose name, folder or
 * program holds [query], whatever session is folded, in one group per [ActivityPriority] that has any. A group lists
 * the tab that came into it last first ([ActivitySource.entered], else its report's time); tabs of no known time
 * follow, in the remotes' and the servers' order. It only reads: [observeActivity] keeps the times.
 */
internal fun activityGroups(sources: List<ActivitySource>, query: String): List<ActivityGroup> {
    val entries = sources.flatMap { source ->
        val state = source.state
        val drawn = HashMap<TabRow, Pair<TabActivity, ActivityPriority>>()
        sessionGroups(state, source.active, emptySet(), true, query) { row ->
            val activity = source.activity(row)
            activityPriority(row, activity)?.also { drawn[row] = activity to it } != null
        }.flatMap { group ->
            group.rows.map { row ->
                val (activity, priority) = drawn.getValue(row)
                val message = when (priority) {
                    ActivityPriority.Working -> workingMessage(state, row)
                    ActivityPriority.Blocked, ActivityPriority.Failed -> tabWords(state, row, priority)
                    ActivityPriority.Done -> null
                }
                ActivityEntry(source.host, row, activity, priority, source.entered.at(row.session, row.tab) ?: activity.since,
                    group.label, message)
            }
        }
    }
    val byPriority = entries.groupBy { it.priority }
    return ActivityPriority.entries.mapNotNull { priority ->
        byPriority[priority]?.let { group -> ActivityGroup(priority, group.sortedByDescending { it.enteredAt ?: Long.MIN_VALUE }) }
    }
}

/**
 * The report of [row]'s lead pane, when that pane itself is in [priority]: a tab's group can come from another of its
 * panes ([ActivityReadState.activity]), whose words the state does not carry, and the lead pane's words would then say
 * something else. From the tab's session's list, else, for the selected session, from its block.
 */
private fun leadReport(state: UiState, row: TabRow, priority: ActivityPriority): Pair<String, String>? {
    val lead = when (priority) {
        ActivityPriority.Working -> row.activity == "working" || row.indicator == TabIndicator.CodexActive
        ActivityPriority.Blocked -> row.activity == "blocked"
        ActivityPriority.Failed -> row.activity == "error"
        ActivityPriority.Done -> false
    }
    if (!lead) return null
    val tab = state.sessions.firstOrNull { it.id == row.session }?.tabs?.firstOrNull { it.id == row.tab }
    if (tab != null) return tab.statusSummary to tab.statusTitle
    val block = row.block?.takeIf { row.session == state.session }?.let { state.blocks[it] } ?: return null
    return block.statusSummary to block.statusTitle
}

/**
 * The words the program of [row] reports with its state (OSC 7501 `msg`) on one line, while its lead pane is in
 * [priority]; null without any. A waiting or failed tab's well says them even when they also name the row (a tab with
 * no title is named by them): the well has room for two lines, the name one.
 */
internal fun tabWords(state: UiState, row: TabRow, priority: ActivityPriority): String? =
    leadReport(state, row, priority)?.first?.singleLine()?.takeIf { it.isNotEmpty() }

/**
 * What a working row's capsule says (#249): its program's words ([tabWords]), else the title it reports (OSC 7501
 * `title`); null when it reports neither. Words or a title that are the name the row already shows are passed over:
 * Velo names a tab by the title its program reports, and the capsule would only repeat it.
 */
internal fun workingMessage(state: UiState, row: TabRow): String? {
    val report = leadReport(state, row, ActivityPriority.Working) ?: return null
    return listOf(report.first, report.second).map { it.singleLine() }.firstOrNull { it.isNotEmpty() && it != row.name }
}

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

/**
 * What Activity says when it lists nothing: that the filter ([query]) matches no tab, that no remote is [connected],
 * or that nothing works, waits, failed or finished.
 */
internal fun activityEmptyNote(query: String, connected: Boolean): String = when {
    query.isNotBlank() -> "No matching tab"
    !connected -> "No remote is connected."
    else -> "Nothing is working or waiting."
}
