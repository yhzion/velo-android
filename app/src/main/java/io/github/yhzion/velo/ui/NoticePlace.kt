package io.github.yhzion.velo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.Density
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
 * width ([noticeRowWidths]). Where it is measured, as in a stack, nothing changes. With [height], it answers that
 * for its height at a width too (the Snackbar answers one line's whatever its text, #192), so that [PaneNotices]
 * knows how tall it is before it is measured.
 */
internal fun Modifier.ownWidth(width: Int, height: ((Int) -> Int)? = null): Modifier = this then OwnWidthElement(width, height)

private data class OwnWidthElement(val width: Int, val height: ((Int) -> Int)?) : ModifierNodeElement<OwnWidthNode>() {
    override fun create() = OwnWidthNode(width, height)
    override fun update(node: OwnWidthNode) {
        node.width = width
        node.height = height
    }
}

private class OwnWidthNode(var width: Int, var height: ((Int) -> Int)?) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int = width

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        height?.invoke(width) ?: measurable.maxIntrinsicHeight(width)

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        height?.invoke(width) ?: measurable.minIntrinsicHeight(width)
}

/** The least width each notice keeps in a row ([noticeRowWidths]): a few words of an error notice on one line. */
internal val NoticeRowLeast = 160.dp

/**
 * The notices, [gap] apart, over the whole pane column: placed by [noticeTop] against the [pane] area's box in
 * the column (null until it is placed), [padding] from the sides and centred between them. They are measured at
 * their own height, which a short pane area does not cut, and kept inside the column. Two or more that a short pane
 * area would stack into the [keyBar] (its box in the column, null without one) go side by side above it
 * ([noticeArrangement]), in their order from the start. [pane] and [keyBar] are read only to place them: the pane
 * area's moves with the keyboard place them again, and compose nothing unless a notice starts or stops waiting.
 *
 * Where two or more do not end [padding] above the key bar so (a larger text size wraps them to more lines, #192),
 * the notices without a control ([waitsIn]) wait, the oldest first, until the rest fit or one is left: a waiting
 * notice is not placed, and its [NoticeWait] says so. The move's Undo has a control and never waits. The notices
 * left stay above the key bar, from the column's top if they must, as one or more stacked there do.
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
            val least = NoticeRowLeast.roundToPx()
            // Measured here, once the pane area is placed: how they are measured depends on where it is. Only by
            // their intrinsic sizes until the notices that wait are known, since each is measured once.
            fun arrangementOf(notices: List<Measurable>): NoticeArrangement {
                val count = notices.size
                val stack = if (count < 2) 0 else notices.sumOf { it.maxIntrinsicHeight(space) } + between * (count - 1)
                return noticeArrangement(top, bottom, keyBarTop, stack, count, side, up)
            }
            fun widthsOf(notices: List<Measurable>): List<Int>? =
                noticeRowWidths(notices.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(space) }, space, between, least)
            // Whether two or more notices end [padding] above the key bar, as they would be placed below.
            fun fits(notices: List<Measurable>): Boolean {
                val arrangement = arrangementOf(notices)
                if (arrangement != NoticeArrangement.Row) return true
                val widths = widthsOf(notices)
                val height = if (widths != null) notices.indices.maxOf { notices[it].maxIntrinsicHeight(widths[it]) }
                    else notices.sumOf { it.maxIntrinsicHeight(space) } + between * (notices.size - 1)
                val floor = if (widths != null) keyBarTop else constraints.maxHeight
                return noticeTop(top, bottom, floor, height, side, up) + height <= keyBarTop - side
            }
            // The notices without a control wait, the oldest first, while the rest do not fit (#192).
            var shown = measurables
            for (m in measurables.filter { it.parentData is NoticeWait }.sortedBy { (it.parentData as NoticeWait).since }) {
                if (shown.size < 2 || fits(shown)) break
                shown = shown.filter { it !== m }
            }
            measurables.forEach { m -> (m.parentData as? NoticeWait)?.onScreen(shown.any { it === m }) }
            val count = shown.size
            val arrangement = arrangementOf(shown)
            val widths = if (arrangement == NoticeArrangement.Row) widthsOf(shown) else null
            if (widths != null) {
                val row = shown.mapIndexed { i, m -> m.measure(Constraints(maxWidth = widths[i])) }
                val height = row.maxOf { it.height }
                var x = side + Alignment.CenterHorizontally.align(row.sumOf { it.width } + between * (count - 1), space, LayoutDirection.Ltr)
                val y = noticeTop(top, bottom, keyBarTop, height, side, up)
                row.forEach {
                    it.placeRelative(x, y + (height - it.height) / 2)
                    x += it.width + between
                }
                return@layout
            }
            val notices = shown.map { it.measure(Constraints(maxWidth = space)) }
            if (notices.isEmpty()) return@layout
            val width = notices.maxOf { it.width }
            val height = notices.sumOf { it.height } + between * (count - 1)
            // Where notices wait, those left keep off the key bar: alone, one goes there from the column's top.
            val floor = if (arrangement == NoticeArrangement.AboveKeyBar || shown.size < measurables.size) keyBarTop else constraints.maxHeight
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
 * A notice without a control in the pane column, which waits behind newer ones where the notices do not fit
 * ([PaneNotices], #192): the upload toast or the error notice, one showing of it ([key] in [heard]). Its age, by
 * which the oldest waits first, is when a node was first made for it ([HeardNotices.age]), so a move to the sidebar
 * and back keeps it. [PaneNotices] says as it places the notices whether this one is on screen.
 */
@Stable
internal class NoticeWait(private val heard: HeardNotices?, private val key: Any) {
    private val made = nextNoticeAge()
    // What [PaneNotices] last said, read there without observing the states below.
    private var onScreenNow = true
    private var wasOnScreen = false

    /** It has been on screen: only then has a screen reader had it to read ([liveNotice]). */
    var shownOnce by mutableStateOf(false)
        private set

    /** Not on screen: newer notices hold the row. A screen reader does not find it either. */
    var waiting by mutableStateOf(false)
        private set

    /**
     * It was on screen before it waited: a screen reader has read it there. Once it is back it is no live region,
     * so it is not read a second time.
     */
    var heardBefore by mutableStateOf(false)
        private set

    /** The order in which the notices came: the oldest has the least. */
    val since: Long get() = heard?.age(key) ?: made

    internal fun onScreen(on: Boolean) {
        if (on == onScreenNow && (!on || wasOnScreen)) return
        onScreenNow = on
        if (on && !wasOnScreen) {
            wasOnScreen = true
            shownOnce = true
        }
        waiting = !on
        if (!on && wasOnScreen) heardBefore = true
    }
}

/** The [NoticeWait] of the notice [key] that [heard] knows: a new one for new words, which are a new showing. */
@Composable
internal fun rememberNoticeWait(heard: HeardNotices?, key: Any): NoticeWait = remember(heard, key) { NoticeWait(heard, key) }

/**
 * Lets the notice wait in [PaneNotices] as [wait] says ([NoticeWait]), and leaves it out of what a screen reader finds
 * while it waits: Compose keeps a node it no longer places in that tree. Nothing without [wait] (the sidebar's notices).
 */
internal fun Modifier.waitsIn(wait: NoticeWait?): Modifier = when {
    wait == null -> this
    wait.waiting -> this then NoticeWaitElement(wait) then Modifier.clearAndSetSemantics { hideFromAccessibility() }
    else -> this then NoticeWaitElement(wait)
}

private data class NoticeWaitElement(val wait: NoticeWait) : ModifierNodeElement<NoticeWaitNode>() {
    override fun create() = NoticeWaitNode(wait)
    override fun update(node: NoticeWaitNode) { node.wait = wait }
}

private class NoticeWaitNode(var wait: NoticeWait) : Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = wait
}

/** The ages of the notices ([NoticeWait.since]): the main thread's, as composition is. */
private var noticeAge = 0L

private fun nextNoticeAge(): Long = ++noticeAge

/**
 * Whether the sidebar folds its head (the toolbar and This Device) into its list (#209): its column is [height] px
 * tall, the head [head] px, the notices it holds [notices] px with their padding, and the Filter's band [least] px.
 * It folds when the head and the notices leave the body less than the band: landscape with the keyboard up, where the
 * Filter field would get no room and leave what a screen reader sees, or a short sidebar whose notices would lie over
 * the toolbar's buttons. Folded, the head scrolls with the list, and the band and the notices share the whole height.
 */
internal fun sidebarFoldsHead(height: Int, head: Int, notices: Int, least: Int): Boolean = height - head - notices < least

/**
 * The height of the sidebar's body (the list, with the Filter field at its foot) when the sidebar that covers the
 * panes holds notices [notices] px tall with their padding (#165, #195): its column is [height] px tall and its head
 * (the toolbar and This Device) [head] px. The notices go under the body, which grows shorter, while it keeps [least]
 * px beside them (the Filter's band, whole). Else the body keeps all the height under the head and the notices lie
 * over it, above its band where it has one ([sidebarNoticesTop]). Either way they stay the column's children, so the
 * keyboard only places them again and never composes them elsewhere ([HeardNotices]). A sidebar that folds its head
 * ([sidebarFoldsHead]) asks with a [head] of 0.
 */
internal fun sidebarBodyHeight(height: Int, head: Int, notices: Int, least: Int): Int {
    val left = (height - head).coerceAtLeast(0)
    return if (left - notices >= least) left - notices else left
}

/**
 * Where the sidebar's notices [notices] px tall start in its column, laid out as [sidebarBodyHeight] says (#195).
 * Under the body when it makes room for them. Else, while the body keeps the Filter's band ([least]), they end just
 * above the band, over the list, or over the head where the list above the band is shorter than them (even the
 * toolbar's buttons): over the field, Compose would leave it out of what a screen reader sees and take its focus away. They start at the column's top when they are taller than the room above the band. Only when the body
 * has no room for the band, where the field has none of its own, do they lie at the column's foot. The sidebar folds
 * its head before either can happen ([sidebarFoldsHead], #209), and asks with a [head] of 0: then only notices taller
 * than the room above the band lie over the list, and only a column shorter than the band has them at its foot.
 */
internal fun sidebarNoticesTop(height: Int, head: Int, notices: Int, least: Int): Int {
    val left = (height - head).coerceAtLeast(0)
    return if (left - notices < least && left >= least) (height - least - notices).coerceAtLeast(0)
    else (height - notices).coerceAtLeast(0)
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
    // How many nodes show each notice now: a move makes the new node before the old one goes. A node in the pane
    // column that waits ([NoticeWait]) counts only once it has been on screen.
    private val showing = mutableMapOf<Any, Int>()
    // How many nodes are made for each notice, from the composition that makes one to the one that drops it, and the
    // notice's age ([NoticeWait.since]). Counted as a node is made, so a move, whose old node is forgotten before the
    // new one is remembered, keeps the age.
    private val held = mutableMapOf<Any, Int>()
    private val ages = mutableMapOf<Any, Long>()

    /** Whether a node made now for the notice [key] reads it out: no node shows it yet. */
    fun isNew(key: Any): Boolean = key !in showing

    /** The age of the notice [key] ([NoticeWait.since]) while a node is made for it. */
    fun age(key: Any): Long? = ages[key]

    fun shown(key: Any) {
        showing[key] = (showing[key] ?: 0) + 1
    }

    fun gone(key: Any) {
        val left = (showing[key] ?: return) - 1
        if (left > 0) showing[key] = left else showing -= key
    }

    internal fun hold(key: Any) {
        if (key !in held) ages[key] = nextNoticeAge()
        held[key] = (held[key] ?: 0) + 1
    }

    internal fun release(key: Any) {
        val left = (held[key] ?: return) - 1
        if (left > 0) held[key] = left else {
            held -= key
            ages -= key
        }
    }
}

/**
 * Whether this node of the notice [key] is a live region ([HeardNotices]). It is decided once, when the node is made
 * for the key, so a node keeps it while it shows the notice, and a node made later for the same notice has none.
 * New words are a new key: they are read. Without [heard] every node is one. A node in the pane column that [wait]s
 * counts as showing the notice only once it has been on screen (#192): one that waited from the start leaves the
 * notice to be read where it first shows, there or in the sidebar. Once it comes back from waiting it is none.
 */
@Composable
internal fun liveNotice(heard: HeardNotices?, key: Any, wait: NoticeWait? = null): Boolean {
    if (heard == null) return wait?.heardBefore != true
    remember(heard, key) { NoticeHold(heard, key) }
    val live = remember(heard, key) { heard.isNew(key) }
    val onScreen = wait == null || wait.shownOnce
    DisposableEffect(heard, key, onScreen) {
        if (onScreen) heard.shown(key)
        onDispose { if (onScreen) heard.gone(key) }
    }
    return live && wait?.heardBefore != true
}

/** Holds the notice [key] in [heard] from the composition that makes its node until that node is dropped. */
private class NoticeHold(private val heard: HeardNotices, private val key: Any) : RememberObserver {
    init { heard.hold(key) }

    override fun onRemembered() {}

    override fun onForgotten() = heard.release(key)

    override fun onAbandoned() = heard.release(key)
}
