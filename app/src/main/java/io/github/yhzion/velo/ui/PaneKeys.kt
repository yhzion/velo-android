package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.WindowInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Moving a pane with the keyboard or the palette: the pane swaps places with its neighbour on one side,
 * the swap #217 sends for a drop on the middle of a pane. This file holds the rule that finds the neighbour.
 */

/** A side of the focused pane as the user sees it on screen, with the palette command that moves the pane there. */
enum class PaneDirection(val label: String) {
    Left("Move Pane Left"),
    Right("Move Pane Right"),
    Up("Move Pane Up"),
    Down("Move Pane Down"),
}

private const val Epsilon = 1e-4f

/**
 * The tiled pane that [block] swaps with when it moves [direction], as its rectangle in a 1x1 card with no gap,
 * or null when it has no such neighbour. The rectangles are the drawn ones ([paneRects]), so in [rtl] the
 * server's left is the screen's right. A neighbour touches [block]'s edge on that side and overlaps it on the
 * other axis; the largest overlap wins, and a tie goes to the smaller top (left or right) or left (up or down).
 * A zoomed tab draws one pane only, and a floating pane has no rectangle, so both give null.
 */
fun paneToward(window: WindowInfo, block: String, direction: PaneDirection, rtl: Boolean): PaneRect? {
    if (window.zoomed != null) return null
    val rects = paneRects(window, 1f, 1f, 0f, rtl)
    val me = rects.firstOrNull { it.block == block } ?: return null
    val horizontal = direction == PaneDirection.Left || direction == PaneDirection.Right
    fun overlap(other: PaneRect) =
        if (horizontal) min(me.bottom, other.bottom) - max(me.top, other.top)
        else min(me.right, other.right) - max(me.left, other.left)
    fun touches(other: PaneRect) = when (direction) {
        PaneDirection.Left -> abs(other.right - me.left) < Epsilon
        PaneDirection.Right -> abs(other.left - me.right) < Epsilon
        PaneDirection.Up -> abs(other.bottom - me.top) < Epsilon
        PaneDirection.Down -> abs(other.top - me.bottom) < Epsilon
    }
    return rects
        .filter { it.block != block && touches(it) && overlap(it) > Epsilon }
        .minWithOrNull(
            // A larger overlap is better, but overlaps closer than the epsilon are a tie.
            Comparator { x, y ->
                val d = overlap(x) - overlap(y)
                when {
                    d > Epsilon -> -1
                    d < -Epsilon -> 1
                    else -> (if (horizontal) x.top.compareTo(y.top) else x.left.compareTo(y.left))
                }
            },
        )
}

/** A move sent by the keyboard or the palette, [before] being the tabs when it went out at [sentAtMillis]. */
internal class PendingMove(val block: String, val place: MovePlace, val before: List<WindowInfo>, val sentAtMillis: Long)

/**
 * Whether the move [pending] still has to show in [current] before another one may go out. The app does not
 * change the layout ahead of the server, so until a move lands the layout on screen is the old one, and a second
 * move computed from it could swap the wrong panes. The wait ends when the move lands ([moveLanded]) or after
 * [LandWaitMillis] at [nowMillis], like the wait for the Undo notice.
 */
internal fun moveBusy(pending: PendingMove?, nowMillis: Long, current: List<WindowInfo>): Boolean =
    pending != null && nowMillis - pending.sentAtMillis < LandWaitMillis &&
        !moveLanded(pending.before, current, pending.block, pending.place)
