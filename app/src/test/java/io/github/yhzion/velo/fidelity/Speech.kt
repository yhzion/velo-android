package io.github.yhzion.velo.fidelity

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import io.github.yhzion.velo.ui.KEY_CODES
import io.github.yhzion.velo.ui.StickyKeys
import io.github.yhzion.velo.ui.spokenKeyName
import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.tileBrand

/**
 * What an accessibility service gets from a Compose view: the AccessibilityNodeInfo tree of its
 * node provider, as DeviceHeaderSpeechTest reads it. A screen reader stops on each focusable node
 * and says its own words, then those of its descendants that are not focusable themselves.
 */
internal object Speech {
    fun focusable(i: AccessibilityNodeInfo) =
        i.isImportantForAccessibility && (i.isScreenReaderFocusable || i.isFocusable || i.isClickable || i.isCheckable || i.isLongClickable)

    /**
     * What a service says for a node: its description, or its text, then its state description (#167);
     * nothing for a node it skips.
     */
    fun words(i: AccessibilityNodeInfo): List<String> =
        if (!i.isImportantForAccessibility) emptyList()
        else listOfNotNull(i.contentDescription ?: i.text, i.stateDescription).map { it.toString() }.filter { it.isNotBlank() }

    /** The node of the semantics node [id] (a virtual view id) in [view]. */
    fun node(view: View, id: Int): AccessibilityNodeInfo =
        checkNotNull(view.accessibilityNodeProvider?.createAccessibilityNodeInfo(id)) { "No node $id" }

    /** What the focusable [info] says: its own words, then its descendants' up to the next focusable node. */
    fun said(view: View, info: AccessibilityNodeInfo): List<String> {
        val provider = view.accessibilityNodeProvider
        val words = mutableListOf<String>()
        fun gather(parent: AccessibilityNodeInfo) {
            for (c in 0 until parent.childCount) {
                val id = childVirtualId(parent, c) ?: continue
                val child = provider.createAccessibilityNodeInfo(id) ?: continue
                if (focusable(child)) continue
                words += words(child)
                gather(child)
            }
        }
        words += words(info)
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
            for (c in 0 until info.childCount) childVirtualId(info, c)?.let(::walk)
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        return result
    }

    /** The virtual view id of [info]: the semantics node it is made from, which stays while its words change. */
    fun virtualId(info: AccessibilityNodeInfo): Int =
        (AccessibilityNodeInfo::class.java.getMethod("getSourceNodeId").invoke(info) as Long ushr 32).toInt()

    /**
     * The virtual view id of [info]'s child at [index]; null for a view of its own, such as a pane's surface in an
     * AndroidView (#174): its high half is the host's id, and read as a node of this provider it would be the root again.
     * So the scans skip a real View's subtree inside an AndroidView (a TextView of TerminalReader, a pane's surface),
     * which an accessibility service reads when its views matter for accessibility; only the Compose nodes around it are heard.
     */
    private fun childVirtualId(info: AccessibilityNodeInfo, index: Int): Int? {
        // AccessibilityNodeInfo keeps each child, and its own source, as a long whose high half is the
        // virtual view id; getChildId and getSourceNodeId are hidden, so they are read by reflection, as Robolectric allows.
        val id = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, index) as Long
        return (id ushr 32).toInt().takeIf { it != AccessibilityNodeProvider.HOST_VIEW_ID }
    }

    /**
     * Whether a focusable node that says [said] says a thing twice (#147, #167): a part of it (split at
     * ", ", " · " and ": ") comes again, ignoring case and a short or full name of one thing ([sameThing]),
     * or one of its words is said whole inside another: in the same case, or by another name of a part
     * of it ("Send Escape", "Esc"; "Open nvim", "Neovim, 2 panes"). One description that repeats a part of itself ("zsh, zsh") says it
     * twice too.
     */
    fun saysTwice(said: List<String>): Boolean {
        val parts = said.flatMap { it.split(", ", " · ", ": ") }.map { sameThing(it.trim()) }.filter { it.length >= 2 }
        if (parts.size != parts.toSet().size) return true
        for ((a, x) in said.withIndex()) for ((b, y) in said.withIndex()) {
            if (a == b || y.length < 2) continue
            if (Regex("(^|\\W)" + Regex.escape(y) + "(\\W|$)").containsMatchIn(x)) return true
            val names = namesIn(x)
            if (partsOf(y).any { part -> names.any { otherNameOf(it, part) } }) return true
        }
        return false
    }

    /** A folder, said as a path ("~/.config/nvim"): a fact of its own, not a name of the program in it. */
    private fun isPath(token: String) = '/' in token || token.startsWith("~")

    /** The parts of [said], as [saysTwice] splits it, but folders. */
    private fun partsOf(said: String) = said.split(", ", " · ", ": ").map { it.trim() }.filter { it.length >= 2 && !isPath(it) }

    /** The words of [said] but its folders, and each two side by side ("Page Up"): the names it may say a thing by. */
    private fun namesIn(said: String): List<String> {
        val prose = said.split(Regex("\\s+")).filterNot(::isPath).joinToString(" ")
        val words = Regex("[\\w-]+").findAll(prose).map { it.value }.toList()
        return words + words.zipWithNext { a, b -> "$a $b" }
    }

    /** Whether [name] is another name of the one thing [other] names ("Escape", "Esc"), not the same name in another case. */
    private fun otherNameOf(name: String, other: String) = !name.equals(other, ignoreCase = true) && sameThing(name) == sameThing(other)

    /**
     * One name for a short and a full name of one thing, in lower case: a key as the key bar draws it
     * and as a screen reader says it ([spokenKeyName]: Esc, ESC, Escape), in any case, and a program by
     * its command or its brand ([tileBrand], [TileBrand.label]: nvim, Neovim).
     */
    fun sameThing(part: String): String {
        val key = KeysByName[part.lowercase()]
        if (key != null && key != part.lowercase()) return key
        val brand = BrandsByLabel[part.lowercase()] ?: tileBrand(part).takeIf { it != TileBrand.Terminal }
        return brand?.let { "brand:${it.name}" } ?: part.lowercase()
    }

    /** Each key the key bar draws, by its name in lower case ("esc", as "ESC" is drawn), to what a screen reader says for it. */
    private val KeysByName = (KEY_CODES.keys + StickyKeys + listOf("-", "|", "/", "~")).associate { it.lowercase() to spokenKeyName(it).lowercase() }

    private val BrandsByLabel = TileBrand.entries.filter { it != TileBrand.Terminal }.associateBy { it.label.lowercase() }
}
