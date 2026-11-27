package io.github.yhzion.velo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Where the notices of the pane column go (#165, #169): the move's Undo, the upload toast and the error notice.
// Where the sidebar covers the panes there is no pane column: the sidebar holds them under its Filter field, with the
// keyboard up too ([sidebarBodyHeight], #195).

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

/**
 * The space the error notice's own width ([ownWidth]) keeps on each side of its text: the Material Snackbar keeps
 * 16 dp before its text and 8 after it, so the text fits whole with some room to spare.
 */
internal val SnackbarTextSide = 16.dp

/**
 * Answers [width] px when asked how wide the element is at most, and measures it as it is: a notice whose own width
 * is not what its layout answers (the error notice's Snackbar answers its widest) tells a row of notices its real
 * width ([noticeRowWidths]). Where it is measured, as in a stack, nothing changes.
 */
internal fun Modifier.ownWidth(width: Int): Modifier = this then OwnWidthElement(width)

private data class OwnWidthElement(val width: Int) : ModifierNodeElement<OwnWidthNode>() {
    override fun create() = OwnWidthNode(width)
    override fun update(node: OwnWidthNode) { node.width = width }
}

private class OwnWidthNode(var width: Int) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int = width
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

/**
 * The height of the sidebar's body (the list, with the Filter field at its foot) when the sidebar that covers the
 * panes holds notices [notices] px tall with their padding (#165, #195): its column is [height] px tall and its head
 * (the toolbar and This Device) [head] px. The notices go under the body, which grows shorter, while it keeps [least]
 * px beside them (the Filter's band, whole). Else the body keeps all the height under the head and the notices lie
 * over its foot: landscape with the keyboard up, where the Filter field itself has no room. Either way they end at
 * the column's foot, so the keyboard only places them again and never composes them elsewhere ([HeardNotices]).
 */
internal fun sidebarBodyHeight(height: Int, head: Int, notices: Int, least: Int): Int {
    val left = (height - head).coerceAtLeast(0)
    return if (left - notices >= least) left - notices else left
}

/**
 * The notices a screen reader has heard, kept per connection beside them as [ErrorNoticeTimer] keeps their time
 * (#195). A notice moves between the pane column and the sidebar that covers the panes, and each move composes it
 * anew: TalkBack reads out a live region whose node is new, so the same words would be read again. So a node made
 * for a notice that another node shows already is no live region ([liveNotice]). A notice is known by a key, such as
 * its words; once no node shows it, it is forgotten, and the same words shown later are read again.
 */
@Stable
internal class HeardNotices {
    // How many nodes show each notice now: a move makes the new node before the old one goes.
    private val showing = mutableMapOf<Any, Int>()

    /** Whether a node made now for the notice [key] reads it out: no node shows it yet. */
    fun isNew(key: Any): Boolean = key !in showing

    fun shown(key: Any) {
        showing[key] = (showing[key] ?: 0) + 1
    }

    fun gone(key: Any) {
        val left = (showing[key] ?: return) - 1
        if (left > 0) showing[key] = left else showing -= key
    }
}

/**
 * Whether this node of the notice [key] is a live region ([HeardNotices]). It is decided once, when the node is made
 * for the key, so a node keeps it while it shows the notice, and a node made later for the same notice has none.
 * New words are a new key: they are read. Without [heard] every node is one.
 */
@Composable
internal fun liveNotice(heard: HeardNotices?, key: Any): Boolean {
    if (heard == null) return true
    val live = remember(heard, key) { heard.isNew(key) }
    DisposableEffect(heard, key) {
        heard.shown(key)
        onDispose { heard.gone(key) }
    }
    return live
}
