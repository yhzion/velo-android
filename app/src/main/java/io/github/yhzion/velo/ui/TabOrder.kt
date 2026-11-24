package io.github.yhzion.velo.ui

import io.github.yhzion.velo.HostConnection

/**
 * The order of a session's tabs as pure functions. The server's order is the only order: a move
 * names the tab and the tab it goes before, as the `moveWindow` op does, and never an index.
 */

/** One tab to move: [window] goes before [before], or to the end when [before] is null. */
data class TabMove(val window: String, val before: String?)

/**
 * The move that puts [window] at index [place] of [row], the tabs a person sees: without tabs
 * that are closing, which no one can drop before. [place] is clamped to the row. Null when that
 * is the tab's own index, or when the tab is not in [row]. `before` is the tab that follows it there.
 */
fun tabMoveTo(row: List<String>, window: String, place: Int): TabMove? {
    val from = row.indexOf(window)
    if (from < 0) return null
    val to = place.coerceIn(0, row.size - 1)
    if (to == from) return null
    // The row without the tab is what is left to its neighbours once it is lifted out.
    val rest = row - window
    return TabMove(window, rest.getOrNull(to))
}

/** One place on for [window] in [order], toward the end when [forward]. Null past the end it faces, or for an unknown tab. */
fun tabStep(order: List<String>, window: String, forward: Boolean): TabMove? {
    val from = order.indexOf(window)
    if (from < 0) return null
    return tabMoveTo(order, window, if (forward) from + 1 else from - 1)
}

/** [tabs] after [move], by the server's rule: the tab leaves its place, then goes before `before`, or last when that is null or not in [tabs]. */
fun movedTabs(tabs: List<String>, move: TabMove): List<String> {
    val rest = tabs - move.window
    val at = move.before?.let(rest::indexOf) ?: -1
    return if (at < 0) rest + move.window else rest.take(at) + move.window + rest.drop(at)
}

/**
 * [held] without the tabs [server] no longer has, while that puts the server's tabs in another order; else null, as the
 * server's order is then the one to show. A tab that goes (a close under way when the order was held, or one closed
 * elsewhere) does not give the order up: the server may take it away before it applies the move, and the moved card
 * would jump back and then forward again. A tab the held order never had does give it up.
 */
fun heldOrder(server: List<String>, held: List<String>?): List<String>? {
    if (held == null || server.any { it !in held }) return null
    return held.filter { it in server }.takeIf { it != server }
}

/**
 * The moves a tab's card offers to accessibility services, by their screen text: one place toward the end, then one toward
 * the start. A move at an end it faces is left out, and a card offers none while the host is offline, since the server
 * would not hear it.
 */
fun tabCardMoves(row: List<String>, id: String, connected: Boolean): List<Pair<String, TabMove>> {
    if (!connected) return emptyList()
    return listOfNotNull(
        tabStep(row, id, forward = true)?.let { MoveTabForwardCommand to it },
        tabStep(row, id, forward = false)?.let { MoveTabBackwardCommand to it },
    )
}

/** Sends [move] in [session]; the server keeps the active tab and the focus, and the core refreshes the view after its answer. */
internal fun HostConnection.moveTab(session: String, move: TabMove) {
    op("moveWindow", "session" to session, "window" to move.window, "before" to move.before)
}
