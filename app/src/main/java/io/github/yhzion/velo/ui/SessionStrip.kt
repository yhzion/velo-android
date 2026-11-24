package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.UiState

/*
 * The session strip at the top of the overview (#229), as macOS shows its desktops above the
 * windows of Mission Control: one chip per session of the server, and a tap shows that session's
 * tabs in the row below. A chip draws a sketch of the session's tabs, not their screens: a
 * terminal shown is taken and fitted to this device, so drawing another session's would resize it.
 */

/** A chip draws at most this many tab cells. */
private const val MAX_CELLS = 6

/**
 * One session's chip. [tabs] counts its tabs, null while they are not known. [shownTab] is the
 * place of the tab it shows (-1 when none is known); [current] marks the session the panes show;
 * [attention]: one of its tabs has news the user has not seen.
 */
data class SessionChip(
    val id: String,
    val label: String,
    val tabs: Int?,
    val shownTab: Int,
    val current: Boolean,
    val attention: Boolean,
)

/**
 * The chips of [state]'s sessions in the server's order. [attention] says whether a tab's row
 * wants the user, as the sidebar's mark does ([wantsAttention]).
 */
fun sessionChips(state: UiState, attention: (TabRow) -> Boolean): List<SessionChip> {
    val groups = sessionGroups(state, active = true, collapsed = emptySet(), activityOnly = false, query = "")
        .associateBy { it.id }
    return state.sessions.map { s ->
        val current = s.id == state.session
        val tabs = s.tabs.orEmpty()
        // The panes' own selection first: the session list may still mark the tab shown before.
        val shown = tabs.indexOfFirst { current && it.id == state.window }.takeIf { it >= 0 }
            ?: tabs.indexOfFirst { it.active }
        SessionChip(
            id = s.id,
            label = s.label.ifEmpty { "Session" },
            tabs = s.tabs?.size,
            shownTab = shown,
            current = current,
            attention = groups[s.id]?.rows.orEmpty().any(attention),
        )
    }
}

/** The cells a chip draws for [tabs] tabs, true for the one of the tab shown ([shown]; past the last cell, the last). */
fun chipCells(tabs: Int, shown: Int): List<Boolean> {
    val cells = tabs.coerceIn(0, MAX_CELLS)
    val lit = if (shown < 0) -1 else minOf(shown, cells - 1)
    return List(cells) { it == lit }
}

/** What a screen reader says for a session's chip; [remote] names the remote of a session the panes are not on (#236). */
fun sessionChipDescription(label: String, tabs: Int?, remote: String? = null): String {
    val name = if (remote == null) "Session $label" else "Session $label on $remote"
    return when (tabs) {
        null -> name
        0 -> "$name, no tabs"
        1 -> "$name, 1 tab"
        else -> "$name, $tabs tabs"
    }
}

/**
 * A saved remote as the strip sees it (#236). [chips] are its sessions' ([sessionChips]), [active]
 * marks the remote the panes show, and [connected] says whether its connection is up.
 */
data class StripRemote(val id: String, val name: String, val active: Boolean, val connected: Boolean, val chips: List<SessionChip>)

/**
 * The remotes whose sessions the strip shows, in the order saved. The active remote is always
 * shown: it holds New Session, and offline its chips are greyed out. Another remote is shown only
 * while it is connected and has a session to go to. A chip is current only on the active remote,
 * because another remote's shown session is not what the panes show. [chosen] is a session of the
 * active remote tapped on the strip that the server has not shown yet: it is the current chip at
 * once (#246). A [chosen] session the active remote does not have changes nothing.
 */
fun stripRemotes(remotes: List<StripRemote>, chosen: String? = null): List<StripRemote> =
    remotes.filter { it.active || (it.connected && it.chips.isNotEmpty()) }
        .map { r ->
            when {
                !r.active -> r.copy(chips = r.chips.map { it.copy(current = false) })
                chosen != null && r.chips.any { it.id == chosen } -> r.copy(chips = r.chips.map { it.copy(current = it.id == chosen) })
                else -> r
            }
        }

/** One thing the strip draws: a remote's name, a session's chip, or New Session. */
sealed interface StripItem {
    data class Name(val remote: StripRemote) : StripItem
    data class Chip(val remote: StripRemote, val chip: SessionChip) : StripItem
    data object NewSession : StripItem
}

/**
 * What the strip draws from left to right. With one remote it is its chips and New Session, as in
 * #229. With several, each remote's chips follow its name. New Session follows the active remote's
 * chips, since it makes the session on that remote.
 */
fun stripItems(remotes: List<StripRemote>): List<StripItem> {
    val named = remotes.size > 1
    return remotes.flatMap { r ->
        listOfNotNull<StripItem>(if (named) StripItem.Name(r) else null) +
            r.chips.map { StripItem.Chip(r, it) } +
            listOfNotNull<StripItem>(if (r.active) StripItem.NewSession else null)
    }
}

/** The middle of item [index] along the strip, from the [widths] of its items and the [gap] between two. */
fun stripMiddle(widths: List<Float>, gap: Float, index: Int): Float =
    widths.take(index).sum() + index * gap + widths[index] / 2
