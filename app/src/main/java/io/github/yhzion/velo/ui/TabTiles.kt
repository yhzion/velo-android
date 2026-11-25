package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.TileDot
import io.github.yhzion.velo.ui.theme.tileBrand
import io.github.yhzion.velo.ui.theme.tileLabel

// What a tab's tile and its row's end draw, as pure functions of what the tab reports (TabTiles and
// Sidebar boards). Rex for Mac's sidebar: the program's brand tile, one layer per pane, and the state
// of a report not yet seen in a dot on the tile; what the program does now at the row's end.

/** The tile of a tab: its program's [brand], its [panes] (one layer each up to four, the count from five) and its state [dot]. */
data class TabTileLook(val brand: TileBrand, val panes: Int, val dot: TileDot?)

/**
 * The tile of [row] as it draws [activity] ([ActivityReadState.activity]): the brand of the agent its
 * report names, else of the program it runs; a layer per pane it draws, at least one; and the dot of
 * the report not yet seen ([tileDot]).
 */
internal fun tabTile(row: TabRow, activity: TabActivity): TabTileLook =
    TabTileLook(tileBrand(activity.icon ?: row.icon), row.panes.coerceAtLeast(1), tileDot(activity.badge))

/**
 * The dot a report not yet seen ([TabActivity.badge]) puts at the tile's upper right: blue for a
 * finish, red for a failure, orange for a program that waits for the user (owner decision 2).
 * A program at work is no report to see: it has no dot.
 */
internal fun tileDot(badge: TabIndicator?): TileDot? = when (badge) {
    TabIndicator.Done -> TileDot.Unread
    TabIndicator.Error -> TileDot.Failed
    TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth -> TileDot.Waiting
    else -> null
}

/** What a tab's row draws at its end: three dots while its program works, an orange dot while it waits, the offline mark. */
enum class RowMark { Working, Waiting, Offline }

/** The mark at the end of a row whose program shows [indicator] now; a finish or a failure is said by the tile's dot and the second line. */
internal fun rowMark(indicator: TabIndicator?): RowMark? = when (indicator) {
    TabIndicator.Working, TabIndicator.CodexActive -> RowMark.Working
    TabIndicator.Waiting, TabIndicator.Permission, TabIndicator.Question, TabIndicator.Auth -> RowMark.Waiting
    TabIndicator.Offline -> RowMark.Offline
    else -> null
}

/**
 * What a screen reader says for a tab's tile: its brand's name and its panes ([tileLabel]). A program
 * without a brand of its own is called by its name ([hint]), as its mark was before the tiles.
 */
internal fun tabTileDescription(tile: TabTileLook, hint: String?): String {
    val named = if (tile.brand != TileBrand.Terminal) tile.brand.label else hint?.takeIf { it.isNotBlank() } ?: tile.brand.label
    return named + if (tile.panes > 1) ", ${tile.panes} panes" else ""
}
