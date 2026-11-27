package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

// Where the notices of the pane column go (#165): the move's Undo, the upload toast and the error notice.
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

/**
 * The notices, [gap] apart, over the whole pane column: placed by [noticeTop] against the [pane] area's box in
 * the column (null until it is placed), [padding] from the sides and centred between them. They are measured at
 * their own height, which a short pane area does not cut, and kept inside the column. [pane] is read only to place them: the pane area's
 * moves with the keyboard place them again and compose nothing.
 */
@Composable
internal fun PaneNotices(pane: () -> Rect?, padding: Dp, gap: Dp, lift: Dp, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Layout({
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap), content = content)
    }, modifier) { measurables, constraints ->
        val side = padding.roundToPx()
        val notices = measurables.first().measure(Constraints(maxWidth = (constraints.maxWidth - side * 2).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val box = pane() ?: return@layout
            val x = side + Alignment.CenterHorizontally.align(notices.width, constraints.maxWidth - side * 2, layoutDirection)
            notices.place(IntOffset(x, noticeTop(box.top.roundToInt(), box.bottom.roundToInt(), constraints.maxHeight, notices.height, side, lift.roundToPx())))
        }
    }
}
