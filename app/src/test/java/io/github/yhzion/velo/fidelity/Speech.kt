package io.github.yhzion.velo.fidelity

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * What an accessibility service gets from a Compose view: the AccessibilityNodeInfo tree of its
 * node provider, as DeviceHeaderSpeechTest reads it. A screen reader stops on each focusable node
 * and says its own words, then those of its descendants that are not focusable themselves.
 */
internal object Speech {
    fun focusable(i: AccessibilityNodeInfo) =
        i.isImportantForAccessibility && (i.isScreenReaderFocusable || i.isFocusable || i.isClickable || i.isCheckable || i.isLongClickable)

    /** The description of a node, or its text, as a service says it; null for a node it skips. */
    fun words(i: AccessibilityNodeInfo): String? =
        if (!i.isImportantForAccessibility) null else (i.contentDescription ?: i.text)?.toString()?.takeIf { it.isNotBlank() }

    /** The node of the semantics node [id] (a virtual view id) in [view]. */
    fun node(view: View, id: Int): AccessibilityNodeInfo =
        checkNotNull(view.accessibilityNodeProvider?.createAccessibilityNodeInfo(id)) { "No node $id" }

    /** What the focusable [info] says: its own words, then its descendants' up to the next focusable node. */
    fun said(view: View, info: AccessibilityNodeInfo): List<String> {
        val provider = view.accessibilityNodeProvider
        val words = mutableListOf<String>()
        fun gather(parent: AccessibilityNodeInfo) {
            for (c in 0 until parent.childCount) {
                val child = provider.createAccessibilityNodeInfo(childVirtualId(parent, c)) ?: continue
                if (focusable(child)) continue
                words(child)?.let(words::add)
                gather(child)
            }
        }
        words(info)?.let(words::add)
        gather(info)
        return words
    }

    /** What the semantics node [id] says, as [said]. */
    fun saidAt(view: View, id: Int): List<String> = said(view, node(view, id))

    /** Every focusable node in [view], in tree order, with what it says. */
    fun focusableNodes(view: View): List<Pair<AccessibilityNodeInfo, List<String>>> {
        val provider = view.accessibilityNodeProvider ?: return emptyList()
        val result = mutableListOf<Pair<AccessibilityNodeInfo, List<String>>>()
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            if (focusable(info)) result += info to said(view, info)
            for (c in 0 until info.childCount) walk(childVirtualId(info, c))
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        return result
    }

    // AccessibilityNodeInfo keeps each child as a long whose high half is the virtual view id;
    // getChildId is hidden, so it is read by reflection, as Robolectric allows.
    private fun childVirtualId(info: AccessibilityNodeInfo, index: Int): Int {
        val id = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, index) as Long
        return (id ushr 32).toInt()
    }
}
