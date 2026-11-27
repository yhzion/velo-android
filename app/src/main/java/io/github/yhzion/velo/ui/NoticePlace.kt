package io.github.yhzion.velo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Where the notices of the pane column go (#165, #169): the move's Undo, the upload toast and the error notice.
// Where the sidebar covers the panes there is no pane column: the sidebar holds them under its Filter field.

/**
 * The top of the notices, in px of the pane column: [padding] above the pane area's lower edge and [lift] over the
 * capsules, as long as the pane area (from [paneTop] to [paneBottom]) holds them with [padding] above too. A pane
 * too short for them (landscape with the keyboard up leaves one line) has them from its top down, over the
 * caption under it, so each shows a whole line. The column ends at the keyboard: a stack that would run past its
 * bottom ([column] tall) moves up to end [padding] above it, and one taller than the whole column starts at its top.
 */
fun noticeTop(paneTop: Int, paneBottom: Int, column: Int, notices: Int, padding: Int, lift: Int): Int {
    val atBottom = paneBottom - padding - lift - notices
    return if (atBottom >= paneTop + padding) atBottom else minOf(paneTop + padding, column - padding - notices).coerceAtLeast(0)
}

/** How [PaneNotices] lays out the notices ([noticeArrangement]). */
internal enum class NoticeArrangement {
    /** Stacked, by [noticeTop] inside the column. */
    Stack,
    /** Stacked, by [noticeTop] with the key bar's top as the column's bottom. */
    AboveKeyBar,
    /** Side by side in one row, above the key bar ([noticeRowWidths]). */
    Row,
}

/**
 * How [count] notices go, stacked [stack] tall (#169). One notice, or a stack the pane area holds ([noticeTop]), stays
 * in the stack of #165. Two or more over a pane area too short for them stay stacked above the key bar ([keyBarTop],
 * or the column's bottom without one) when they fit there from the pane area's top, [padding] above it: so they
 * never go over the search bar above a short pane. Else they go side by side. Landscape with the keyboard up leaves
 * room for one notice over the one-line pane and its caption.
 */
internal fun noticeArrangement(paneTop: Int, paneBottom: Int, keyBarTop: Int, stack: Int, count: Int, padding: Int, lift: Int): NoticeArrangement = when {
    count < 2 || paneBottom - padding - lift - stack >= paneTop + padding -> NoticeArrangement.Stack
    paneTop + stack <= keyBarTop - padding -> NoticeArrangement.AboveKeyBar
    else -> NoticeArrangement.Row
}

/**
 * The widths of notices side by side in [space], [gap] apart: each its [own] width while they all fit, else the
 * widest are cut to an equal share of what the narrower ones leave, so each keeps as much of its line as it can.
 * Null when [space] has no room for [least] each: the notices stay stacked then.
 */
internal fun noticeRowWidths(own: List<Int>, space: Int, gap: Int, least: Int): List<Int>? {
    val room = space - gap * (own.size - 1)
    if (room < least * own.size) return null
    if (own.sum() <= room) return own
    var left = room
    var wider = own.size
    for (w in own.sorted()) {
        val share = left / wider
        if (w > share) return own.map { minOf(it, share) }
        left -= w
        wider--
    }
    return own
}

/** The least width each notice keeps in a row ([noticeRowWidths]): a few words of an error notice on one line. */
internal val NoticeRowLeast = 160.dp

/**
 * The notices, [gap] apart, over the whole pane column: placed by [noticeTop] against the [pane] area's box in
 * the column (null until it is placed), [padding] from the sides and centred between them. They are measured at
 * their own height, which a short pane area does not cut, and kept inside the column. Two or more that a short pane
 * area would stack into the [keyBar] (its box in the column, null without one) go side by side above it
 * ([noticeArrangement]), in their order from the start. [pane] and [keyBar] are read only to place them: the pane
 * area's moves with the keyboard place them again and compose nothing.
 */
@Composable
internal fun PaneNotices(pane: () -> Rect?, keyBar: () -> Rect?, padding: Dp, gap: Dp, lift: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {
            val box = pane() ?: return@layout
            val side = padding.roundToPx()
            val between = gap.roundToPx()
            val up = lift.roundToPx()
            val space = (constraints.maxWidth - side * 2).coerceAtLeast(0)
            val top = box.top.roundToInt()
            val bottom = box.bottom.roundToInt()
            val keyBarTop = keyBar()?.top?.roundToInt()?.coerceAtMost(constraints.maxHeight) ?: constraints.maxHeight
            val count = measurables.size
            // Measured here, once the pane area is placed: how they are measured depends on where it is.
            val stack = if (count < 2) 0 else measurables.sumOf { it.maxIntrinsicHeight(space) } + between * (count - 1)
            val arrangement = noticeArrangement(top, bottom, keyBarTop, stack, count, side, up)
            val widths = if (arrangement == NoticeArrangement.Row)
                noticeRowWidths(measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(space) }, space, between, NoticeRowLeast.roundToPx())
            else null
            if (widths != null) {
                val row = measurables.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) }
                val height = row.maxOf { it.height }
                var x = side + Alignment.CenterHorizontally.align(row.sumOf { it.width } + between * (count - 1), space, LayoutDirection.Ltr)
                val y = noticeTop(top, bottom, keyBarTop, height, side, up)
                row.forEach {
                    it.placeRelative(x, y + (height - it.height) / 2)
                    x += it.width + between
                }
                return@layout
            }
            val notices = measurables.map { it.measure(Constraints(maxWidth = space)) }
            if (notices.isEmpty()) return@layout
            val width = notices.maxOf { it.width }
            val height = notices.sumOf { it.height } + between * (count - 1)
            val floor = if (arrangement == NoticeArrangement.AboveKeyBar) keyBarTop else constraints.maxHeight
            val x = side + Alignment.CenterHorizontally.align(width, space, layoutDirection)
            var y = noticeTop(top, bottom, floor, height, side, up)
            notices.forEach {
                it.place(IntOffset(x + Alignment.CenterHorizontally.align(it.width, width, layoutDirection), y))
                y += it.height + between
            }
        }
    }
}
