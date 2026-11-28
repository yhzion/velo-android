package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.Feel

/**
 * What the sidebar lists under a remote, as pure functions of the core's state: one group per
 * session, one row per tab, what is asked before something is closed, and what a rename starts with. No
 * Android and no Compose, so the JVM tests run it directly.
 */

/** How a tab's row offers to close it. */
enum class CloseControl { X, Swipe }

/**
 * One tab of a session. [name] is the manual label or the lead pane's display title; [folder]
 * is that pane's directory and [program] what it runs ("" when that is not known). [panes]
 * counts the panes the tab draws; [minimized] says it has more that are not drawn and still
 * run, how many is not known. [ownLabel] is the label the user gave ("" for the server's automatic
 * one). [shown] marks the one tab the panes show: of the active remote, its selected session.
 * [others] names the programs of the other panes it draws, as [icon] names the lead pane's, in the
 * tab's layout order (null for one not known); it is empty when they are not known, as for a tab
 * of a session other than the selected one, whose lead pane alone the core follows.
 */
data class TabRow(
    val session: String,
    val tab: String,
    val name: String,
    val folder: String,
    val icon: String?,
    val program: String,
    val panes: Int,
    val minimized: Boolean,
    val activity: String,
    val ownLabel: String,
    val shown: Boolean,
    val since: Long? = null,
    val indicator: TabIndicator? = null,
    val block: String? = null,
    val terminalBlocks: List<String> = emptyList(),
    val kind: String? = null,
    val others: List<String?> = emptyList(),
    /** How far its program says it is, 0 to 100; null when it did not say (#250). */
    val progress: Int? = null,
) {
    /** The shown tab's row carries an X; any other row shows Close after a left swipe. */
    val closeControl: CloseControl get() = if (shown) CloseControl.X else CloseControl.Swipe
}

/**
 * One session of a remote: its header and the rows under it. [tabs] counts every tab of the
 * session, whatever [rows] shows (none while [collapsed], fewer while a filter is on); null
 * while its tabs are not known, 0 for a session that really has none.
 */
data class SessionGroup(val id: String, val label: String, val collapsed: Boolean, val tabs: Int?, val rows: List<TabRow>)

/**
 * Labels the server makes from an ID are not names: "Window 89abcdef" for a tab, "Block 89abcdef" for a block it
 * has no label for (#198).
 */
private val AutoTabLabel = Regex("^(Window|Block) [0-9a-f]{8}$")

/** The label a user gave a tab or a block; "" for none or the server's automatic one. */
fun ownTabLabel(label: String): String = if (AutoTabLabel.matches(label)) "" else label

/**
 * The agents the app knows by their program's names: how a tab's title, its report and its notifications are read.
 * [label] is what the app calls each one. Their tiles are [TileBrand][io.github.yhzion.velo.ui.theme.TileBrand]s.
 */
enum class TabAgent(val label: String) {
    Claude("Claude Code"), Codex("Codex"), Omp("omp"), Pi("pi"), Gemini("Gemini"), OpenCode("OpenCode");

    fun matchesProgramName(title: String): Boolean = title.equals(label, ignoreCase = true) || from(title) == this

    companion object {
        fun from(hint: String?): TabAgent? = when (hint?.trim()?.lowercase()) {
            "claude", "claude-code" -> Claude
            "codex", "codex-cli", "openai-codex" -> Codex
            "omp", "oh-my-pi" -> Omp
            "pi", "pi-coding-agent" -> Pi
            "gemini", "gemini-cli" -> Gemini
            "opencode" -> OpenCode
            else -> null
        }
    }
}

enum class TabIndicator(val description: String) {
    Working("Working"), CodexActive("Codex is active"), Waiting("Waiting for input"),
    Permission("Waiting for approval"), Question("Waiting for an answer"), Auth("Waiting for sign-in"),
    Done("Completed"), Error("Error"), Idle("Idle"), Offline("Disconnected");

    val animates: Boolean get() = this == Working || this == CodexActive
}

internal fun TabRow.indicatorWhile(connected: Boolean): TabIndicator? =
    if (connected || indicator == null) indicator else TabIndicator.Offline

// Codex separates its spinner item from either neighbor with a space.
private val CodexSpinner = Regex("""(?<!\S)[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏](?:\s+|$)""")
private val CodexActionPrefix = Regex("""^(● )?\[ [.!] ] Action Required(?=\s|$)""")
// omp's title starts with its π, its state (a braille spinner frame, ⁝ or >) and a Nerd Font
// icon of the task, as in "π ⠼ <icon> ISSUE: ...". The sidebar draws omp's mark and the activity
// in their own places, and the app's text font has no Nerd Font icons.
private val OmpTitlePrefix = Regex("""^π\s+(?:[\u2800-\u28ff⁝⋮:>]\s+)?[\x{E000}-\x{F8FF}\x{F0000}-\x{10FFFD}]?\s*""")
// OpenCode titles a session "OC | <session title>" and a plugin's screen "OC | <id>"; its home
// screen is "OpenCode", which the program-name check already skips.
private val OpenCodeTitlePrefix = Regex("""^OC \| """)
private val VersionName = Regex("""v?\d+(?:\.\d+)+(?:[-+][\w.-]+)?""")
private val TitleBreak = Regex("""[\p{Cc}\p{Zl}\p{Zp}]+""")
private val BidiControl = Regex("""[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]""")

/** [this] on one line: line breaks and controls as spaces, without the controls that turn text around. */
internal fun String.singleLine() = replace(TitleBreak, " ").replace(BidiControl, "").trim()

internal fun blockedIndicator(kind: String?) = when (kind) {
    "permission" -> TabIndicator.Permission
    "question" -> TabIndicator.Question
    "auth" -> TabIndicator.Auth
    else -> TabIndicator.Waiting
}

/**
 * [this] tab as the sidebar's row of [session]. A row that is one [pane] of a tab ([paneRow]) is a terminal before
 * the server reports its program: only a tab is named "Tab" then (#181).
 */
private fun TabInfo.row(session: String, shown: Boolean, pane: Boolean = false, others: List<String?> = emptyList()): TabRow {
    // A blank program says no more than none does (a capsule named a pane so before #208).
    val executable = program.substringAfterLast('/').trim()
    val hint = programHint(icon, program)
    val agent = TabAgent.from(hint)
    val osc = if (titleIsLabel) "" else title.singleLine()
    val spinner = if (agent == TabAgent.Codex) CodexSpinner.find(osc) else null
    val subject = when (agent) {
        TabAgent.Codex -> {
            val stable = if (spinner == null) osc else osc.removeRange(spinner.range).trimEnd()
            stable.replaceFirst(CodexActionPrefix, "$1[ ! ] Action Required")
        }
        TabAgent.Omp -> osc.replaceFirst(OmpTitlePrefix, "")
        TabAgent.OpenCode -> osc.replaceFirst(OpenCodeTitlePrefix, "")
        else -> osc
    }
    val own = ownTabLabel(label)
    // Codex's OSC title includes the thread and any action-required prefix. The
    // structured title can instead name a task, even when that thread is available.
    val reported = statusTitle.singleLine()
    val titles = if (agent == TabAgent.Codex) listOf(subject, reported) else listOf(reported, subject)
    val topic = titles.firstOrNull {
        it.isNotEmpty() && agent?.matchesProgramName(it) != true &&
            (agent != TabAgent.Codex || !CodexActionPrefix.matches(it))
    }
    val name = own.ifEmpty { topic.orEmpty() }.ifEmpty { statusSummary.singleLine() }
        .ifEmpty { titles.firstOrNull { it.isNotEmpty() }.orEmpty() }
        .ifEmpty { agent?.label.orEmpty() }.ifEmpty { executable.takeUnless(VersionName::matches).orEmpty() }
        .ifEmpty { title.takeIf { titleIsLabel }?.singleLine()?.let(::ownTabLabel).orEmpty() }.ifEmpty { if (program.isEmpty() && !pane) "Tab" else "Terminal" }
    val mark = when (activity) {
        "working" -> TabIndicator.Working
        "blocked" -> blockedIndicator(kind)
        "done" -> TabIndicator.Done
        "error" -> TabIndicator.Error
        else -> when {
            reportedIdle -> TabIndicator.Idle
            spinner != null -> TabIndicator.CodexActive
            else -> null
        }
    }
    return TabRow(session, id, name, cwd, hint, program, panes, minimized, activity, own, shown,
        since, mark, block, terminalBlocks, kind, others, progress)
}

/** What names a pane's program: its [icon] hint, else its [program]'s file name; null when neither says. */
private fun programHint(icon: String?, program: String): String? =
    (icon ?: program.substringAfterLast('/')).takeUnless { it.isBlank() || VersionName.matches(it) }

/**
 * The programs of the panes of [w] other than its [lead] (its first when [lead] is not one of them), in layout
 * order, as [TabRow.others] names them: null for a pane the state knows nothing of.
 */
internal fun otherPanePrograms(state: UiState, w: WindowInfo, lead: String?): List<String?> {
    val blocks = tabBlocks(w)
    val face = lead?.takeIf { it in blocks } ?: blocks.firstOrNull()
    return blocks.filter { it != face }.map { b -> state.blocks[b]?.let { programHint(it.icon, it.program) } }
}

/** [TabRow.others] of the tab [tab] of [session]: known only in the selected session, whose windows the state holds. */
private fun otherPanePrograms(state: UiState, session: String, tab: TabInfo): List<String?> =
    if (session != state.session) emptyList()
    else state.windows.firstOrNull { it.id == tab.id }?.let { otherPanePrograms(state, it, tab.block) }.orEmpty()

internal fun WindowInfo.row(state: UiState, session: String, shown: Boolean): TabRow {
    val lead = leadBlock(state, this)
    val info = lead?.let(state.blocks::get)
    return TabInfo(id, label, icon, panes = tabBlocks(this).size, minimized = minimized, block = lead,
        terminalBlocks = (tabBlocks(this) + minimizedBlocks).distinct(),
        program = info?.program.orEmpty(), title = info?.title.orEmpty(), cwd = info?.cwd.orEmpty(),
        activity = info?.activity ?: "idle", since = info?.since, statusTitle = info?.statusTitle.orEmpty(),
        kind = info?.kind, reportedIdle = info?.reportedIdle == true, titleIsLabel = info?.titleIsLabel == true,
        statusSummary = info?.statusSummary.orEmpty(), progress = info?.progress)
        .row(session, shown, others = otherPanePrograms(state, this, lead))
}

/**
 * [block] of [this] tab as a row of its own, as if it were the tab's only pane: named, marked and read by its own
 * program, title and report, with no label of the tab's. The caption's minimap names and marks a hidden pane so.
 */
internal fun WindowInfo.paneRow(state: UiState, session: String, block: String): TabRow =
    state.blocks[block].asPane(id, block).row(session, shown = false, pane = true)

/** [this] block as a tab [tab] of the one pane [block] ([paneRow]), with no label of the tab's. */
private fun BlockInfo?.asPane(tab: String, block: String?): TabInfo =
    TabInfo(tab, icon = this?.icon, block = block, terminalBlocks = listOfNotNull(block),
        program = this?.program.orEmpty(), title = this?.title.orEmpty(), cwd = this?.cwd.orEmpty(),
        activity = this?.activity ?: "idle", since = this?.since, statusTitle = this?.statusTitle.orEmpty(),
        kind = this?.kind, reportedIdle = this?.reportedIdle == true, titleIsLabel = this?.titleIsLabel == true,
        statusSummary = this?.statusSummary.orEmpty(), progress = this?.progress)

/**
 * What a pane whose block is [info] is called on its own, by one rule wherever one pane is named (#208): its row's
 * name ([paneRow]), with no label of its tab's. That is what its program reports (its title or its status), else the
 * agent's or the program's name, else its block's label, but not one the server makes from an ID ("Block 9ac42573",
 * [ownTabLabel]), else "Terminal" (#181, #198). It needs the live block, with its title: not the steady state's. The minimap's picker, its covered panes' row, the
 * palette, the hardware keys' pane picker, the close question, a move's ghost and a minimized pane's capsule say it.
 */
internal fun paneName(info: BlockInfo?): String = info.asPane("", null).row("", shown = false, pane = true).name

/** The name the sidebar shows for [this] tab of [session]: never the server's ID-made label. */
internal fun TabInfo.displayName(session: String): String = row(session, shown = false).name

/** Also used by the command palette, which lists every window in the selected session. */
fun tabTitle(state: UiState, w: WindowInfo): String = w.row(state, state.session.orEmpty(), w.id == state.window).name

/** The illuminated dot advances at a fixed interval while the mark is animated. */
internal const val DotIntervalMs = 220

/** Crossfade neighbouring dots, including the transition from the last dot to the first. */
internal fun workingDotAlpha(phase: Float, index: Int): Float {
    val distance = kotlin.math.abs(phase - index)
    val weight = (1f - minOf(distance, 3f - distance)).coerceIn(0f, 1f)
    val eased = weight * weight * (3f - 2f * weight)
    return .45f + .55f * eased
}

internal fun TabRow.reportAge(nowMs: Long): String? = since?.takeUnless { activity == "idle" }?.let { reportAge(it, nowMs) }

internal fun reportAge(reportMs: Long, nowMs: Long): String? {
    if (reportMs > nowMs) return null
    val elapsedMs = nowMs - reportMs
    if (elapsedMs < 0) return null
    val seconds = elapsedMs / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    val duration = when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
    return "$duration since report"
}

/**
 * The second line of a tab's row: what its status [mark] says ([TabIndicator.description]), the
 * time since its report ([age], [TabRow.reportAge]) and its [folder], those of them that are
 * there, joined by " · ". Null when none is. The words are the mark's own, of the mark the row
 * draws ([TabRow.indicatorWhile]), so the line and the mark beside it cannot say two things: the
 * mark's shape and colour alone do not say which state it is.
 */
internal fun tabRowSubtitle(mark: TabIndicator?, age: String?, folder: String): String? =
    listOfNotNull(mark?.description, age, folder.ifEmpty { null }).joinToString(" · ").ifEmpty { null }

/**
 * The tab the panes show: the one the state's top level names, else (before it names one) the
 * tab its session marks active, else the first. Null while no session is shown.
 */
fun shownTabId(state: UiState): String? {
    val session = state.session ?: return null
    return state.window
        ?: state.sessions.firstOrNull { it.id == session }?.tabs?.firstOrNull { it.active }?.id
        ?: state.windows.firstOrNull()?.id
}

/**
 * The groups under a remote's row, in the server's order: every session with every one of its
 * tabs, a tab with several panes as one row. A session whose tabs are not known, or that has
 * none, is its header alone. A row is the shown one only on the [active] remote, in the session
 * and tab its panes show ([shownTabId]). A session in [collapsed] keeps its header and shows no
 * rows. While [activityOnly] or [query] is on, a row shows only when its pane works or waits
 * and when its name or folder holds the query, and a session without such a row hides.
 */
fun sessionGroups(state: UiState, active: Boolean, collapsed: Set<String>, activityOnly: Boolean, query: String,
    activeReport: (TabRow) -> Boolean = { it.activity != "idle" || it.indicator == TabIndicator.CodexActive },
): List<SessionGroup> {
    val text = query.trim()
    val filtering = activityOnly || text.isNotEmpty()
    val shownTab = if (active) shownTabId(state) else null
    return state.sessions.mapNotNull { s ->
        val rows = s.tabs.orEmpty()
            .map { it.row(s.id, shown = s.id == state.session && it.id == shownTab, others = otherPanePrograms(state, s.id, it)) }
            .filter { row ->
                (!activityOnly || activeReport(row)) &&
                    (text.isEmpty() || row.name.contains(text, true) || row.folder.contains(text, true) ||
                        row.program.contains(text, true) || TabAgent.from(row.icon)?.label?.contains(text, true) == true)
            }
        if (filtering && rows.isEmpty()) return@mapNotNull null
        val folded = s.id in collapsed
        SessionGroup(s.id, s.label.ifEmpty { "Session" }, folded, s.tabs?.size, if (folded) emptyList() else rows)
    }
}

/** Whether a tab's row draws the dot of news the user has not seen: what lights the dot on the eye. */
internal fun wantsAttention(activity: TabActivity): Boolean = activity.badge != null

/**
 * The words the waiting program of [row] reports with its state (OSC 7501 `msg`), for its title
 * place to run ([showsTicker]): from the tab's lead pane, while that pane waits for the user.
 * Null without words, and when they are the name the row already shows.
 */
internal fun tabMessage(state: UiState, row: TabRow): String? {
    if (row.activity != "blocked") return null
    val tabs = state.sessions.firstOrNull { it.id == row.session }?.tabs
    val summary = tabs?.firstOrNull { it.id == row.tab }?.statusSummary
        ?: row.block?.takeIf { row.session == state.session }?.let { state.blocks[it]?.statusSummary }
    return summary?.singleLine()?.takeIf { it.isNotEmpty() && it != row.name }
}

/**
 * The tab [tab] of [session] as the state knows it now: from its session's list, else (while
 * that list is not known) from the state's top level, which describes the selected session and
 * arrives first. Null when the session or the tab is not there.
 */
fun tabOf(state: UiState, session: String, tab: String): TabRow? {
    val s = state.sessions.firstOrNull { it.id == session } ?: return null
    val shown = session == state.session && tab == shownTabId(state)
    s.tabs?.let { tabs -> return tabs.firstOrNull { it.id == tab }?.let { it.row(session, shown, others = otherPanePrograms(state, session, it)) } }
    if (session != state.session) return null
    return state.windows.firstOrNull { it.id == tab }?.row(state, session, shown)
}

/** The tab the panes show, as [tabOf] knows it: what the palette's tab commands act on. Null without one. */
fun shownTab(state: UiState): TabRow? {
    val session = state.session ?: return null
    val w = state.windows.firstOrNull { it.id == state.window } ?: state.windows.firstOrNull() ?: return null
    return w.row(state, session, shown = true)
}

/**
 * How many tabs [session] has: its own list when that is known, else, for the selected session,
 * the tabs of the state's top level. Null when neither says, or the session is not listed.
 */
fun sessionTabs(state: UiState, session: String): Int? {
    val s = state.sessions.firstOrNull { it.id == session } ?: return null
    return s.tabs?.size ?: if (session == state.session) state.windows.size.takeIf { it > 0 } else null
}

/** The ids of [session]'s tabs in the server's order, whatever a filter hides; empty when the session or its tabs are not known. */
fun sessionTabOrder(state: UiState, session: String): List<String> =
    state.sessions.firstOrNull { it.id == session }?.tabs?.map { it.id }.orEmpty()

/** What a tap on a session's header does. */
enum class HeaderTap { Collapse, Select }

/**
 * A header with tabs under it folds them away, and shows them again. One without (its tabs are
 * not known, or it has none) selects its session: no row of it is there to tap.
 */
fun headerTap(group: SessionGroup): HeaderTap = if ((group.tabs ?: 0) > 0) HeaderTap.Collapse else HeaderTap.Select

/** Shells: a tab that holds one of these and nothing else has only its history to lose. */
private val Shells = setOf("sh", "bash", "zsh", "fish", "dash", "ksh", "csh", "tcsh", "nu", "pwsh")

/** Whether [program] (a file name, a path or a login shell's `-zsh`) is a shell. "" is not: it means the program is not known. */
fun isShell(program: String): Boolean = program.substringAfterLast('/').removePrefix("-").lowercase() in Shells

/**
 * What to ask before closing the tab [name], whose lead pane runs [program], which draws
 * [panes] panes and may have [minimized] ones besides; null when it closes at once. That is
 * only when it is known to hold nothing but a shell: one drawn pane, none minimized, a shell.
 * It asks when the lead pane runs anything else, when what it runs is not known ("": the core
 * has not learned it, or could not), when there are more panes (only the lead pane's program
 * is known) and when a pane is minimized (it runs unseen). With a minimized pane no number of
 * panes is named: how many are minimized is not known. A tab that draws nothing and has
 * nothing minimized is asked about too: what it holds is not known.
 */
fun closeTabQuestion(name: String, program: String, panes: Int, minimized: Boolean): String? {
    val running = when {
        program.isEmpty() || isShell(program) -> null
        name == program -> "It is still running"
        else -> "$program is still running"
    }
    return when {
        minimized -> if (running != null) "Close $name? $running, and so are its minimized panes." else "Close $name? Its minimized panes are still running."
        panes <= 0 -> "Close $name? It may be running a program."
        running != null -> if (panes > 1) "Close $name? $running, and its $panes panes will end." else "Close $name? $running."
        panes > 1 -> "Close $name? Its $panes panes will end."
        program.isEmpty() -> "Close $name? It may be running a program."
        else -> null
    }
}

/**
 * What to ask before closing a session, always: how many tabs end with it. [tabs] null means
 * they are not known, so no number is claimed; 0 is a session that really has none.
 */
fun closeSessionQuestion(label: String, tabs: Int?): String = when (tabs) {
    null -> "Close $label? Every tab in it will end."
    0 -> "Close $label? It has no tabs."
    1 -> "Close $label? 1 tab will end."
    else -> "Close $label? $tabs tabs will end."
}

/** What a close acts on, by its ids: a pane, a tab of a session, or a session. */
sealed interface CloseTarget {
    data class Pane(val block: String) : CloseTarget
    data class LastPane(val session: String, val tab: String, val block: String) : CloseTarget
    data class Tab(val session: String, val tab: String) : CloseTarget
    data class Session(val session: String) : CloseTarget
}

/**
 * What an open close dialog asks about [target], from the state as it is now: counts and
 * programs follow what other clients do while it is open. The dialog closes by itself when
 * the target is gone, becomes the last pane of its tab, or (a pane) its connection is lost:
 * nothing could be closed then. A tab that would now close unasked ([closeTabQuestion]) is
 * still asked about, since the dialog is open, with nothing claimed.
 */
fun closeQuestion(state: UiState, target: CloseTarget): String? = when (target) {
    is CloseTarget.Pane -> state.blocks[target.block]
        ?.takeIf {
            state.status == "connected" && state.windows.any { window ->
                val blocks = tabBlocks(window)
                target.block in blocks && blocks.size > 1
            }
        }
        ?.let { "Close ${paneName(it)}? Its process will end." }
    is CloseTarget.LastPane -> state.windows.firstOrNull { it.id == target.tab }
        ?.takeIf {
            state.status == "connected" && state.session == target.session &&
                !it.minimized && tabBlocks(it).singleOrNull() == target.block
        }
        ?.let { "Close this tab? Its pane and process will end." }
    is CloseTarget.Tab -> tabOf(state, target.session, target.tab)
        ?.let { closeTabQuestion(it.name, it.program, it.panes, it.minimized) ?: "Close ${it.name}?" }
    is CloseTarget.Session -> state.sessions.firstOrNull { it.id == target.session }
        ?.let { closeSessionQuestion(it.label.ifEmpty { "Session" }, sessionTabs(state, it.id)) }
}

/**
 * Whether closing [target] changes what the panes show, so that the folded sidebar closes to
 * show the result: the shown tab, the shown session, or a pane, on the remote the workspace
 * draws ([active]). Closing any other tab or session only edits the list, and the sidebar stays.
 */
fun closeChangesThePanes(state: UiState, active: Boolean, target: CloseTarget): Boolean = active && when (target) {
    is CloseTarget.Pane -> true
    is CloseTarget.LastPane -> state.session == target.session && shownTabId(state) == target.tab
    is CloseTarget.Tab -> state.session == target.session && shownTabId(state) == target.tab
    is CloseTarget.Session -> state.session == target.session
}

/** What a rename acts on, by its ids. */
sealed interface RenameTarget {
    data class OfRemote(val id: String) : RenameTarget
    data class OfSession(val session: String) : RenameTarget
    data class OfTab(val session: String, val tab: String) : RenameTarget
}

/**
 * The text a rename dialog starts with, whichever control opened it: the name the unit really
 * has. That is the label [saved] holds for a remote, the server's label of a session, and the
 * label the user gave a tab ([ownTabLabel]); "" when it has none. Never a word the screen only
 * draws in a name's place ("Remote", "Session", the program a tab runs), which Rename would save.
 */
fun renameText(target: RenameTarget, saved: List<Remote>, state: UiState): String = when (target) {
    is RenameTarget.OfRemote -> saved.firstOrNull { it.id == target.id }?.label
    is RenameTarget.OfSession -> state.sessions.firstOrNull { it.id == target.session }?.label
    is RenameTarget.OfTab -> tabOf(state, target.session, target.tab)?.ownLabel
}.orEmpty()

/**
 * Whether a tab row shows Close after a horizontal drag of [dragPx] (left is negative): a
 * left swipe past [thresholdPx] shows it, a right swipe past it hides it, less changes nothing.
 */
fun closeRevealed(shown: Boolean, dragPx: Float, thresholdPx: Float): Boolean = when {
    dragPx <= -thresholdPx -> true
    dragPx >= thresholdPx -> false
    else -> shown
}

/**
 * What a finger feels as a tab row's swipe goes from [beforePx] to [afterPx]: the place where letting go would
 * change whether Close shows ([closeRevealed]), crossed one way or back (#309).
 */
fun revealFeel(shown: Boolean, beforePx: Float, afterPx: Float, thresholdPx: Float): Feel? {
    val was = closeRevealed(shown, beforePx, thresholdPx)
    val now = closeRevealed(shown, afterPx, thresholdPx)
    return when {
        !was && now -> Feel.ThresholdOn
        was && !now -> Feel.ThresholdOff
        else -> null
    }
}

// The buttons of a row repeat on every row, so a screen reader needs the row's name in each of
// them. The visible texts and the click labels stay as they are.

/** What `+` of a session's header says: it makes a tab in that session. An empty label reads as the header does. */
fun newTabDescription(session: String): String = "New Tab in ${session.ifEmpty { "Session" }}"

/** What `…` of a session's header says. */
fun sessionMenuDescription(session: String): String = "Menu of session ${session.ifEmpty { "Session" }}"

/** What the X of a tab's row says; [tab] is the name its row shows ([tabName]). */
fun closeTabDescription(tab: String): String = "Close tab ${tab.ifEmpty { "Tab" }}"

/** What the chevron of a remote's row says: where it goes from its [expanded] state, and for which remote. */
fun remoteFoldDescription(expanded: Boolean, remote: String): String =
    "${if (expanded) "Collapse" else "Expand"} ${remote.ifEmpty { "Remote" }}"

/** What `…` of a remote's row says. */
fun remoteMenuDescription(remote: String): String = "Menu of remote ${remote.ifEmpty { "Remote" }}"

/** A tab's panes: the tiled ones in layout order, then the floating ones. */
internal fun tabBlocks(w: WindowInfo): List<String> = (w.layout?.blocks().orEmpty() + w.floating.map { it.block }).distinct()

/** The pane a tab of the selected session is named after: the focused one when the tab is shown, else its first. */
internal fun leadBlock(state: UiState, w: WindowInfo): String? =
    (if (w.id == state.window) state.focused else null)
        ?: state.sessions.firstOrNull { it.id == state.session }?.tabs?.firstOrNull { it.id == w.id }?.block
            ?.takeIf { it in tabBlocks(w) }
        ?: tabBlocks(w).firstOrNull()
