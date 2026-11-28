package io.github.yhzion.velo.fidelity

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Scans a scene in every place its lists scroll to (#276). Compose leaves a node wholly off the screen out of the
 * AccessibilityNodeInfo tree, so a scan of the window alone stops at the fold of a long settings page, or at the edges of
 * the overview's strip of cards, which is drawn around the selected card. A screen reader reaches those nodes by
 * scrolling, a page at a time, and so does this: it calls [scan] as the scene is drawn, then takes each list in turn,
 * scrolls it back to its start ([AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD]) and forward to its end
 * ([AccessibilityNodeInfo.ACTION_SCROLL_FORWARD]) a page at a time, and calls [scan] at each page.
 *
 * A list is a node with a scroll action ([SemanticsActions.ScrollBy]) that [takes] accepts: a column or row that scrolls,
 * a lazy list. A slider moves by the same accessibility action, but has no ScrollBy, so its value stays. A list inside
 * another is taken first, before the outer one moves it off the screen, and the outer one waits until it is done. A list that is not wholly shown along its axis
 * waits for another to bring it into view, as each page of it would show only the part shown; it is taken as it is once
 * every other list has been scrolled. Each list is taken once. One that still scrolls after [MaxPages] pages fails the
 * scan, so no list is left half scanned.
 */
internal class ScrollSweep(
    private val compose: ComposeTestRule,
    private val view: View,
    private val takes: (Int) -> Boolean = { true },
) {
    /** Calls [scan] as the scene is drawn, then at each page of each of its lists. */
    fun sweep(scan: () -> Unit) {
        val taken = mutableSetOf<Int>()
        // The lists being scrolled now, outermost first: a list around one of them waits, or it would move that one away.
        val scrolling = ArrayDeque<Int>()
        fun visit(last: Boolean) {
            compose.waitForIdle()
            compose.runOnIdle(scan)
            // Inner lists first: Compose lists the nodes parents first, so the reverse puts each list before those around it.
            // A list only partly shown is taken on the [last] visit, when every other list has been scrolled.
            val drawn = lists()
            for (list in drawn.asReversed()) {
                if (list.id in taken || holdsScrolling(list, drawn, scrolling) || !onScreen(list.id) || !(last || wholly(list))) continue
                taken += list.id
                scrolling.addLast(list.id)
                pages(list.id, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {}
                visit(false)
                pages(list.id, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { visit(false) }
                scrolling.removeLast()
            }
        }
        visit(false)
        visit(true)
    }

    /** The lists drawn now, parents first, that [takes] accepts. */
    private fun lists(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .filter { takes(it.id) }

    /** Whether [list] holds one of the lists [scrolling] now, among those [drawn]. */
    private fun holdsScrolling(list: SemanticsNode, drawn: List<SemanticsNode>, scrolling: Collection<Int>): Boolean =
        drawn.filter { it.id in scrolling }.any { inner -> generateSequence(inner.parent) { it.parent }.any { it.id == list.id } }

    /** Scrolls [list] by [action] a page at a time until it is at that end, calling [each] after each page. */
    private fun pages(list: Int, action: Int, each: () -> Unit) {
        var pages = 0
        while (compose.runOnIdle { view.accessibilityNodeProvider?.performAction(list, action, null) == true }) {
            check(++pages <= MaxPages) { "List #$list still scrolls after $MaxPages pages: the sweep would leave it half scanned" }
            each()
        }
        compose.waitForIdle()
    }

    /** Whether [list] is on the screen, wholly or in part, so a screen reader can scroll it. */
    private fun onScreen(list: Int): Boolean =
        compose.runOnIdle { view.accessibilityNodeProvider?.createAccessibilityNodeInfo(list)?.isVisibleToUser == true }

    /**
     * Whether [list] is shown along the axis it scrolls on as far as it can be: all of it, or as much as the list around it
     * shows (the window, when no list is around it). A page of a list is its own size, so one cut short would never show
     * the rest.
     */
    private fun wholly(list: SemanticsNode): Boolean {
        val vertical = SemanticsProperties.VerticalScrollAxisRange in list.config
        fun shown(n: SemanticsNode) = if (vertical) n.boundsInRoot.height else n.boundsInRoot.width
        val own = (if (vertical) list.size.height else list.size.width).toFloat()
        val around = generateSequence(list.parent) { it.parent }.firstOrNull { SemanticsActions.ScrollBy in it.config }
        val room = around?.let(::shown) ?: (if (vertical) view.height else view.width).toFloat()
        return shown(list) >= minOf(own, room) - 1f
    }

    private companion object {
        /** More pages than any scene's list has, either way. */
        const val MaxPages = 30
    }
}
