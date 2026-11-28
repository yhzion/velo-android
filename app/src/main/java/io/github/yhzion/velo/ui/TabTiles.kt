package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.TileDot
import io.github.yhzion.velo.ui.theme.tileBehind
import io.github.yhzion.velo.ui.theme.tileBrand
import io.github.yhzion.velo.ui.theme.tileLabel

// What a tab's tile and its row's end draw, as pure functions of what the tab reports (TabTiles and
// Sidebar boards). Rex for Mac's sidebar: the program's brand tile, one layer per pane, and the state
// of a report not yet seen in a dot on the tile; what the program does now at the row's end.

/**
 * The tile of a tab: its program's [brand], its [panes] (one layer each up to four, the count from five) and its state
 * [dot]. [others] names the programs of its other panes in layout order ([TabRow.others]), and [behind] is the brand of
 * each layer behind the face, the nearest first ([tileBehind]). [progress] puts a pie on the face ([tileProgress]).
 */
data class TabTileLook(
    val brand: TileBrand, val panes: Int, val dot: TileDot?,
    val others: List<String?> = emptyList(), val behind: List<TileBrand> = emptyList(),
    val progress: Int? = null,
)

/**
 * The tile of [row] as it draws [activity] ([ActivityReadState.activity]): the brand of the agent its
 * report names, else of the program it runs; a layer per pane it draws, at least one, each in the brand
 * of another pane's program ([tileBehind]); and the dot of the report not yet seen ([tileDot]).
 */
internal fun tabTile(row: TabRow, activity: TabActivity): TabTileLook {
    val brand = tileBrand(activity.icon ?: row.icon)
    val panes = row.panes.coerceAtLeast(1)
    return TabTileLook(brand, panes, tileDot(activity.badge), row.others, tileBehind(brand, row.others.map(::tileBrand), panes),
        tileProgress(row, activity))
}

/**
 * The progress the tile of [row] shows as a pie in place of its logo (#250): the percent its focused pane's program
 * reports while that program works and the tab shows it working with nothing unseen. A program that waits, fails or
 * finishes goes back to its logo and dot, as Rex for Mac's does (report 242, section 6), even with a progress.
 */
internal fun tileProgress(row: TabRow, activity: TabActivity): Int? =
    row.progress?.takeIf { row.activity == "working" && activity.indicator == TabIndicator.Working && activity.badge == null }

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
 * What a screen reader hears for a row's line of [words] when its [tile] draws a progress pie (#251): the percent after
 * the line's first part, "Working · 30% · 2m since report · ~", or before [words] that have no state, as an Activity
 * row's session ("30% · T250"). Null without a pie, when the line says only what it draws. The screen shows no percent,
 * as Rex for Mac's does not, and the words are no state, so a new percent is heard when the row is reached, not as it comes.
 */
internal fun progressSaid(tile: TabTileLook, words: String?, afterFirst: Boolean): String? {
    val percent = tile.progress?.let { "$it%" } ?: return null
    val parts = words?.split(" · ").orEmpty().filter(String::isNotEmpty)
    return (if (afterFirst && parts.isNotEmpty()) listOf(parts.first(), percent) + parts.drop(1) else listOf(percent) + parts).joinToString(" · ")
}

/** The mark at the end of a row whose [tile] is drawn: a tile with a progress pie already shows the work, so no dots (#250). */
internal fun rowMark(indicator: TabIndicator?, tile: TabTileLook): RowMark? =
    rowMark(indicator).takeUnless { it == RowMark.Working && tile.progress != null }

/**
 * What a screen reader says for a tab's tile: its brand's name and its panes ([tileLabel]). A program
 * without a brand of its own is called by its name ([hint]), as its mark was before the tiles. When its
 * panes run more than one program, the make-up follows (#217): "bash, 8 panes: 4 Claude Code, 2 OpenCode,
 * 1 Codex, 1 bash", the most panes first, a tie in layout order, and the panes whose program is not known last.
 */
internal fun tabTileDescription(tile: TabTileLook, hint: String?): String =
    listOfNotNull(tileProgram(tile, hint), tilePanes(tile)).joinToString(", ") + tileMakeUp(tile, hint).orEmpty()

/** The program a tile names: its brand's name, or the program's own ([hint]) for one without a brand. */
private fun tileProgram(tile: TabTileLook, hint: String?): String =
    if (tile.brand != TileBrand.Terminal) tile.brand.label else programName(hint) ?: tile.brand.label

/** What a pane running [hint] is called: its brand's name, or the program's own; null when neither is known. */
private fun programName(hint: String?): String? =
    tileBrand(hint).takeIf { it != TileBrand.Terminal }?.label ?: hint?.trim()?.takeIf { it.isNotEmpty() }

private fun tilePanes(tile: TabTileLook): String? = if (tile.panes > 1) "${tile.panes} panes" else null

/** ": 4 Claude Code, 2 OpenCode, 1 bash" for a tile whose panes run more than one program, else null. */
private fun tileMakeUp(tile: TabTileLook, hint: String?): String? {
    if (tile.panes < 2) return null
    val others = (tile.others + List((tile.panes - 1 - tile.others.size).coerceAtLeast(0)) { null }).take(tile.panes - 1)
    val names = others.map(::programName)
    // The face's pane after the others, so a tie lists the other panes in layout order first.
    val counts = (names.filterNotNull() + tileProgram(tile, hint)).groupingBy { it }.eachCount()
    if (counts.size < 2) return null
    val unknown = names.count { it == null }
    return ": " + (counts.entries.sortedByDescending { it.value }.map { "${it.value} ${it.key}" } +
        listOfNotNull(unknown.takeIf { it > 0 }?.let { "$it unknown" })).joinToString(", ")
}

/**
 * What a screen reader hears for a tab's row besides its second line: the tile's [description] with
 * its [state], and the row's name unless it only says the tile's program again ([nameSaid]).
 */
internal data class RowTileSpeech(val description: String, val state: String?, val nameSaid: Boolean)

/**
 * What a tab's row says, so it says each part once (#150). The tile says the program and the panes
 * ([tabTileDescription]); the row's [name] is said unless it is that program. The second line says
 * what the program does now ([mark]) and the folder ([tabRowSubtitle]), so the tile's state is only
 * a report not yet seen ([badge]): "Unread", with the report's words when the second line does not
 * say them. A progress pie's percent is not the tile's state, which a screen reader would say again at each new
 * percent: the row's words say it ([progressSaid], #251).
 */
internal fun tabRowTileSpeech(tile: TabTileLook, hint: String?, name: String, badge: TabIndicator?, mark: TabIndicator?): RowTileSpeech =
    RowTileSpeech(
        tabTileDescription(tile, hint),
        badge?.let { if (it == mark) "Unread" else "Unread, ${it.description}" },
        nameSaid = name.trim().let { n ->
            // A tab without a name of its own is named by its command, such as nvim for Neovim. Every
            // unknown name maps to Terminal, so a Terminal tile's name is compared as it is.
            !(tileProgram(tile, hint).equals(n, ignoreCase = true) || (tile.brand != TileBrand.Terminal && tileBrand(n) == tile.brand))
        },
    )
