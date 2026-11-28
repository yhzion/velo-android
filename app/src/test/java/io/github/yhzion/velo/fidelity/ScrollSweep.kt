package io.github.yhzion.velo.fidelity

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * Scans a scene in every place its lists scroll to (#276). Compose leaves a node wholly off the screen out of the
 * AccessibilityNodeInfo tree, so a scan of the window alone stops at the fold of a long settings page, or at the edge of
 * the overview's strip of cards. A screen reader reaches those nodes by scrolling, a page at a time
 * ([AccessibilityNodeInfo.ACTION_SCROLL_FORWARD]), and so does this: it calls [scan] as the scene is, then after each page
 * of each list, until none scrolls further.
 *
 * A list is a node with a scroll action ([SemanticsActions.ScrollBy]): a column or row that scrolls, a lazy list, a
 * pager. A slider moves by the same accessibility action, but has no ScrollBy, so its value stays. A list off the screen
 * cannot scroll; it is swept when another brings it into view, while it is on the screen. Each list is swept once, and at
 * most [MaxPages] pages.
 */
internal class ScrollSweep(private val compose: ComposeTestRule, private val view: View) {
    /** Calls [scan] as the scene is drawn, then after each page any of its lists scrolls. */
    fun sweep(scan: () -> Unit) {
        val swept = mutableSetOf<Int>()
        fun visit() {
            compose.waitForIdle()
            compose.runOnIdle(scan)
            for (list in lists()) {
                // A list off the screen cannot scroll yet: it is swept when another brings it into view.
                if (!onScreen(list) || !swept.add(list)) continue
                var pages = 0
                while (pages++ < MaxPages && forward(list)) visit()
            }
        }
        visit()
    }

    /** The lists drawn now, by their semantics node's id, which is their virtual view id. */
    private fun lists(): List<Int> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).fetchSemanticsNodes(atLeastOneRootRequired = false).map { it.id }

    /** Whether [list] is on the screen, wholly or in part, so a screen reader can scroll it. */
    private fun onScreen(list: Int): Boolean =
        compose.runOnIdle { view.accessibilityNodeProvider?.createAccessibilityNodeInfo(list)?.isVisibleToUser == true }

    /** Scrolls [list] a page forward, as a screen reader does; false when it is at its end or not on the screen. */
    private fun forward(list: Int): Boolean =
        compose.runOnIdle { view.accessibilityNodeProvider?.performAction(list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null) == true }

    private companion object {
        /** More pages than any scene's list has, so a list that never ends stops the sweep instead of hanging it. */
        const val MaxPages = 30
    }
}
