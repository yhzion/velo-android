package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo

/*
 * Pure rules for moving a pane by dragging it across the tab overview: where it would land,
 * what the capsule says about it, and how to reverse it. Nothing here touches Compose or
 * Android, so every rule has a unit test; the drag UI only feeds in points and draws results.
 */

/** A side of a pane as the user sees it on screen. In RTL the server's `left` and `right` are the opposite ones, see [wireDir]. */
enum class PaneEdge { Left, Right, Above, Below }

/** A pane's rectangle in the coordinates of its card. */
data class PaneRect(val block: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Where a released pane lands. */
sealed interface MovePlace {
    /** Next to [anchor] in [tab], on the half the finger was in; [highlight] is that half. */
    data class Beside(val tab: String, val anchor: String, val edge: PaneEdge, val highlight: PaneRect) : MovePlace

    /** In the place of [other] in [tab], which takes the dragged pane's place; [highlight] is the whole of [other]. */
    data class Swap(val tab: String, val other: String, val highlight: PaneRect) : MovePlace

    /** Alone in a tab of its own at the end of the row. */
    data object NewTab : MovePlace
}

/** [node] without the leaf [block]. A split that loses a side becomes the other side, as the server's layout does. */
fun removePane(node: LayoutNode?, block: String): LayoutNode? {
    if (node == null) return null
    if (node.block != null) return node.takeIf { it.block != block }
    val first = removePane(node.first, block)
    val second = removePane(node.second, block)
    return when {
        first == null -> second
        second == null -> first
        first === node.first && second === node.second -> node
        else -> node.copy(first = first, second = second)
    }
}

/**
 * The tab as it is after [block] leaves it, which is what its card shows during a drag. The zoom is cleared
 * only when it was on [block], because the zoom of another pane survives the move.
 */
fun liftedWindow(window: WindowInfo, block: String): WindowInfo = window.copy(
    layout = removePane(window.layout, block),
    floating = window.floating.filter { it.block != block },
    zoomed = window.zoomed.takeIf { it != block },
)

/**
 * The rectangles of a tab's tiled panes in a card of [width] by [height], by the rule `LayoutTree` draws with:
 * the first side gets `(size - gap) * ratio` (the ratio clamped to 0.05..0.95), then the gap, then the rest.
 * A zoomed tab draws only the zoomed pane, so it gives one full rectangle. `LayoutTree` uses a Compose `Row`,
 * which places the first child on the right in RTL, so [rtl] mirrors every x. Floating panes have no rectangle:
 * they are no place to drop on.
 */
fun paneRects(window: WindowInfo, width: Float, height: Float, gap: Float, rtl: Boolean): List<PaneRect> {
    val layout = window.layout ?: return emptyList()
    val zoomed = window.zoomed
    val out = mutableListOf<PaneRect>()
    if (zoomed != null) {
        if (zoomed in layout.blocks()) out += PaneRect(zoomed, 0f, 0f, width, height)
    } else {
        fun walk(node: LayoutNode, left: Float, top: Float, right: Float, bottom: Float) {
            val block = node.block
            if (block != null) {
                out += PaneRect(block, left, top, right, bottom)
                return
            }
            val a = node.first
            val b = node.second
            if (a == null || b == null) {
                (a ?: b)?.let { walk(it, left, top, right, bottom) }
                return
            }
            val ratio = node.ratio.coerceIn(0.05f, 0.95f)
            if (node.dir == "column") {
                val firstSize = ((bottom - top - gap).coerceAtLeast(0f)) * ratio
                walk(a, left, top, right, top + firstSize)
                walk(b, left, top + firstSize + gap, right, bottom)
            } else {
                val firstSize = ((right - left - gap).coerceAtLeast(0f)) * ratio
                walk(a, left, top, left + firstSize, bottom)
                walk(b, left + firstSize + gap, top, right, bottom)
            }
        }
        walk(layout, 0f, 0f, width, height)
    }
    return if (rtl) out.map { PaneRect(it.block, width - it.right, it.top, width - it.left, it.bottom) } else out
}

/** The central box, as a share of a pane's width and height, where a drop swaps instead of splitting. */
private const val SwapFrom = 0.3f
private const val SwapTo = 0.7f

/**
 * What a release at ([x], [y]) in [tab] would do. A point in the central box of a pane is a swap; elsewhere
 * the edge with the smallest distance relative to the pane's size wins, so a narrow pane is not always
 * "left". Ties go to left, right, above, below in that order. The [dragged] pane and the gaps are no place.
 * The rectangles are the visual ones, so in RTL the left edge is the screen's left: [wireDir] maps it.
 */
fun placeAt(rects: List<PaneRect>, x: Float, y: Float, tab: String, dragged: String): MovePlace? {
    val r = rects.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom } ?: return null
    if (r.block == dragged || r.width <= 0f || r.height <= 0f) return null
    val fx = (x - r.left) / r.width
    val fy = (y - r.top) / r.height
    if (fx in SwapFrom..SwapTo && fy in SwapFrom..SwapTo) return MovePlace.Swap(tab, r.block, r)
    val left = fx
    val right = 1f - fx
    val above = fy
    val below = 1f - fy
    val nearest = minOf(left, right, above, below)
    val midX = (r.left + r.right) / 2
    val midY = (r.top + r.bottom) / 2
    return when (nearest) {
        left -> MovePlace.Beside(tab, r.block, PaneEdge.Left, r.copy(right = midX))
        right -> MovePlace.Beside(tab, r.block, PaneEdge.Right, r.copy(left = midX))
        above -> MovePlace.Beside(tab, r.block, PaneEdge.Above, r.copy(bottom = midY))
        else -> MovePlace.Beside(tab, r.block, PaneEdge.Below, r.copy(top = midY))
    }
}

/** The server's direction word for a screen [edge]. Left and right exchange in RTL, because the layout is drawn mirrored. */
fun wireDir(edge: PaneEdge, rtl: Boolean): String = when (edge) {
    PaneEdge.Left -> if (rtl) "right" else "left"
    PaneEdge.Right -> if (rtl) "left" else "right"
    PaneEdge.Above -> "above"
    PaneEdge.Below -> "below"
}

/** Every pane a tab holds, tiled, floating or minimized. */
private fun heldBlocks(window: WindowInfo): List<String> = (tabBlocks(window) + window.minimizedBlocks).distinct()

/**
 * Whether [block] can leave [window] for a tab of its own: the tab holds another pane (a minimized one counts,
 * it still runs), or the block floats in a tab without a layout, which restores it as `Restore Pane in New Tab`
 * does. The only tiled pane of a tab cannot, because the tab would only be renamed.
 */
fun canMoveToNewTab(window: WindowInfo, block: String): Boolean {
    if (block !in tabBlocks(window)) return false
    if (heldBlocks(window).any { it != block }) return true
    return window.layout == null && window.floating.any { it.block == block }
}

/** False only when there is nothing to move a pane to: one tab that holds one pane. */
fun showsMoveHandle(windows: List<WindowInfo>): Boolean =
    !(windows.size == 1 && heldBlocks(windows[0]).size == 1)

/** The exact inverse of a move, in terms the core can send. */
sealed interface UndoMove {
    /** Swap the same two panes again. */
    data class Swap(val block: String, val other: String) : UndoMove

    /** Move [block] beside [anchor] with the old structural [dir] (`left|right|above|below`) and split [ratio]. */
    data class Beside(val block: String, val anchor: String, val dir: String, val ratio: Float) : UndoMove

    /** Rebuild the tab [block] was alone in: name it [label] when the owner had, and put it before tab [before] (null for the end). */
    data class Retab(val block: String, val label: String?, val before: String?) : UndoMove
}

/** The split that holds the leaf [block] as a direct child, with the side [block] is on. */
private fun parentOf(node: LayoutNode?, block: String): Pair<LayoutNode, Boolean>? {
    if (node == null || node.block != null) return null
    val a = node.first
    val b = node.second
    if (a?.block == block && b != null) return node to true
    if (b?.block == block && a != null) return node to false
    return parentOf(a, block) ?: parentOf(b, block)
}

/**
 * How to put [block] back after it was moved to [place], computed from the layouts [before] the move.
 * The direction is structural, read from the old layout, so it does not depend on RTL. Null when no exact
 * inverse exists: the old sibling was a split (`move_block` anchors on a block, not a split), the pane floated,
 * or the pane's tab keeps other panes while the pane was its only tiled one.
 *
 * [ownLabel] gives the label the owner chose for a tab, or an empty string when the tab's name is automatic.
 */
fun undoFor(before: List<WindowInfo>, block: String, place: MovePlace, ownLabel: (WindowInfo) -> String): UndoMove? {
    if (place is MovePlace.Swap) return UndoMove.Swap(block, place.other)
    val index = before.indexOfFirst { block in tabBlocks(it) }
    if (index < 0) return null
    val window = before[index]
    val layout = window.layout ?: return null
    if (block !in layout.blocks()) return null
    if (layout.block == block) {
        // The tab closes when its only pane leaves, so Undo has to make a tab again.
        if (heldBlocks(window) != listOf(block)) return null
        return UndoMove.Retab(block, ownLabel(window).ifEmpty { null }, before.getOrNull(index + 1)?.id)
    }
    val (split, first) = parentOf(layout, block) ?: return null
    val sibling = (if (first) split.second else split.first)?.block ?: return null
    val column = split.dir == "column"
    val dir = when {
        column -> if (first) "above" else "below"
        else -> if (first) "left" else "right"
    }
    return UndoMove.Beside(block, sibling, dir, split.ratio)
}

/**
 * Where [block] sits: the tab and its position among the tab's tiled panes, or [FloatingSlot] for a floating pane.
 * A swap exchanges these slots, so a floating pane that swaps with a tiled one is confirmed the same way.
 */
private fun slotOf(windows: List<WindowInfo>, block: String): Pair<String, Int>? {
    for (w in windows) {
        val i = w.layout?.blocks()?.indexOf(block) ?: -1
        if (i >= 0) return w.id to i
        if (w.floating.any { it.block == block }) return w.id to FloatingSlot
    }
    return null
}

/** The position of a floating pane: it has no leaf, and a place is always a tiled pane, so two floating panes never swap. */
private const val FloatingSlot = -1

/** Whether [a] and [b] are the two leaves of one split somewhere in [node]. */
private fun areSiblings(node: LayoutNode?, a: String, b: String): Boolean {
    if (node == null || node.block != null) return false
    val first = node.first
    val second = node.second
    if (first?.block != null && second?.block != null && setOf(first.block, second.block) == setOf(a, b)) return true
    return areSiblings(first, a, b) || areSiblings(second, a, b)
}

/**
 * Whether the move to [place] is visible in [after], which the app waits for before it offers Undo.
 * A state that is the same as [before] has not landed yet, so a move that changes nothing never reads as done.
 */
fun moveLanded(before: List<WindowInfo>, after: List<WindowInfo>, block: String, place: MovePlace): Boolean = when (place) {
    is MovePlace.Beside -> {
        // This confirms the destination, not the side: the rule has no RTL flag to map the edge with.
        val now = after.firstOrNull { it.id == place.tab }?.layout
        areSiblings(now, block, place.anchor) && now != before.firstOrNull { it.id == place.tab }?.layout
    }
    is MovePlace.Swap -> {
        val a = slotOf(before, block)
        val b = slotOf(before, place.other)
        a != null && b != null && slotOf(after, block) == b && slotOf(after, place.other) == a
    }
    MovePlace.NewTab -> {
        val known = before.map { it.id }.toSet()
        after.any { it.id !in known && tabBlocks(it) == listOf(block) }
    }
}

/**
 * The capsule's line for the place under the finger. [name] gives a pane's name and [tabName] a tab's, both of
 * which the caller builds from the same title-or-pane-label rule the pane menu uses. [closingTab] is the id of
 * the tab this move empties, if any.
 */
fun placeCaption(place: MovePlace?, name: (String) -> String, tabName: (String) -> String, closingTab: String?): String {
    val line = when (place) {
        null -> return "Release here to cancel"
        is MovePlace.Beside -> {
            val side = when (place.edge) {
                PaneEdge.Left -> "Left of"
                PaneEdge.Right -> "Right of"
                PaneEdge.Above -> "Above"
                PaneEdge.Below -> "Below"
            }
            "$side ${name(place.anchor)} · ${tabName(place.tab)}"
        }
        is MovePlace.Swap -> "Swap with ${name(place.other)}"
        MovePlace.NewTab -> "Move to a new tab"
    }
    return if (closingTab != null) "$line · tab “${tabName(closingTab)}” closes" else line
}

/** What the Undo capsule says about the move that [undo] (the place the pane went to) just did. */
fun undoCaption(undo: MovePlace, block: String, name: (String) -> String): String = when (undo) {
    is MovePlace.Swap -> "Swapped ${name(block)} and ${name(undo.other)}"
    is MovePlace.Beside, MovePlace.NewTab -> "Moved ${name(block)}"
}

/**
 * The core op that moves [block] to [place] and its arguments, as `HostConnection.op` takes them. The places are
 * drawn as the user sees them, so in [rtl] a side is the server's other side ([wireDir]).
 */
fun moveOp(place: MovePlace, block: String, rtl: Boolean): Pair<String, List<Pair<String, Any?>>> = when (place) {
    is MovePlace.Beside -> "movePane" to listOf("block" to block, "anchor" to place.anchor, "dir" to wireDir(place.edge, rtl))
    is MovePlace.Swap -> "swapPanes" to listOf("block" to block, "other" to place.other)
    MovePlace.NewTab -> "moveToNewTab" to listOf("block" to block)
}

/**
 * The arguments of the `undoMove` op for [undo], in the order the core documents them. A null stays in the list
 * (an unnamed tab has no label, the last tab has none after it) because `HostConnection.op` skips null values.
 */
fun undoOp(undo: UndoMove): List<Pair<String, Any?>> = when (undo) {
    is UndoMove.Swap -> listOf("kind" to "swap", "block" to undo.block, "other" to undo.other)
    is UndoMove.Beside -> listOf("kind" to "beside", "block" to undo.block, "anchor" to undo.anchor, "dir" to undo.dir, "ratio" to undo.ratio)
    is UndoMove.Retab -> listOf("kind" to "retab", "block" to undo.block, "label" to undo.label, "before" to undo.before)
}
