package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.WindowInfo

/** Stashing a pane: minimizing its floating layer, floating a tiled pane first. */
const val FloatAndMinimizeLabel = "Float and Minimize"
const val MinimizeFloatingLabel = "Minimize Floating Window"
const val MinimizeAllFloatingLabel = "Minimize Floating Windows"

/** Why a connected menu lists the stash item disabled: the server refuses to minimize a tab's last pane on screen. */
const val StashBlockedNote = "The last pane on screen"

/** True when [block] of [window] can be stashed: the tab keeps another pane on screen. */
fun canStash(window: WindowInfo, block: String): Boolean {
    val blocks = tabBlocks(window)
    return block in blocks && blocks.any { it != block }
}

/**
 * What the palette's `Minimize Floating Windows` does to [window]: true minimizes every floating
 * window (the tab keeps a tiled pane), false shows the minimized ones again (nothing floats on
 * screen), null when there is nothing to do.
 */
fun floatingToggle(window: WindowInfo): Boolean? = when {
    window.floating.isNotEmpty() && window.layout?.blocks().orEmpty().isNotEmpty() -> true
    window.floating.isEmpty() && window.minimizedLayers.isNotEmpty() -> false
    else -> null
}

/** The pane area's corner where the minimized-pane capsules sit; corners are visual, also in a right-to-left layout. */
enum class CapsuleCorner(val saved: String, val label: String) {
    TopLeft("top-left", "Top Left"),
    TopRight("top-right", "Top Right"),
    BottomLeft("bottom-left", "Bottom Left"),
    BottomRight("bottom-right", "Bottom Right");

    companion object {
        /** The corner saved as [saved], or null for no value or one this app does not know. */
        fun parse(saved: String?): CapsuleCorner? = entries.firstOrNull { it.saved == saved }
    }
}

/** The corner a user who never chose sees. It is written only here and never saved to the preferences. */
val DefaultCapsuleCorner = CapsuleCorner.BottomLeft

/** The corner to draw: the user's [chosen] one, else the [default]. */
fun shownCorner(chosen: CapsuleCorner?, default: CapsuleCorner = DefaultCapsuleCorner): CapsuleCorner = chosen ?: default

/** Where [this] corner is drawn in the pane area: absolute, so a left corner stays left in a right-to-left layout. */
fun CapsuleCorner.alignment(): Alignment = when (this) {
    CapsuleCorner.TopLeft -> AbsoluteAlignment.TopLeft
    CapsuleCorner.TopRight -> AbsoluteAlignment.TopRight
    CapsuleCorner.BottomLeft -> AbsoluteAlignment.BottomLeft
    CapsuleCorner.BottomRight -> AbsoluteAlignment.BottomRight
}

/** True for a corner on the pane area's lower edge, where the capsules stand over the notices' place. */
val CapsuleCorner.bottom: Boolean get() = this == CapsuleCorner.BottomLeft || this == CapsuleCorner.BottomRight

/** True for a corner on the pane area's left edge. */
val CapsuleCorner.left: Boolean get() = this == CapsuleCorner.TopLeft || this == CapsuleCorner.BottomLeft

/**
 * How far the notices (`Undo`, errors) rise above the pane area's lower edge: over the row of capsules
 * (its [rowHeight] and a [gap]) while it is [shown] at a bottom corner, else not at all.
 */
fun noticeLift(shown: Boolean, corner: CapsuleCorner, rowHeight: Dp, gap: Dp): Dp = if (shown && corner.bottom) rowHeight + gap else 0.dp

/**
 * How far the row of capsules stands from the edges of the box it is placed in: [side] from the left, right and
 * bottom edges, and at a top corner [top] from the top edge, which clears the panes' move handles and the
 * input-paused capsule. At a bottom corner its top padding is [side] too.
 */
data class CapsuleInsets(val side: Dp, val top: Dp)

/** The padding around the row at [corner]: absolute, as the corners are. */
fun CapsuleInsets.padding(corner: CapsuleCorner): PaddingValues =
    PaddingValues.Absolute(left = side, top = if (corner.bottom) side else top, right = side, bottom = side)

/** What the palette and a screen reader call the move of the capsules to [c]. */
fun moveCornerLabel(c: CapsuleCorner) = "Move Minimized Windows to ${c.label}"

/**
 * Where the top-left of a [row]-sized row stands, in px, at [corner] of a [box]-sized box, with [CapsuleInsets] of
 * [side] and [top] px: what the box's alignment and the row's padding ([padding]) make of it. The far place never
 * lies before the near one. A row too wide for two places across has one, x = [side]. A row too tall for two places
 * down has one, the top corners' y = [top], though at a bottom corner the row's own top padding is [side].
 */
fun capsulePlace(corner: CapsuleCorner, row: Size, box: Size, side: Float, top: Float): Offset {
    val right = maxOf(side, box.width - side - row.width)
    val bottom = maxOf(top, box.height - side - row.height)
    return Offset(if (corner.left) side else right, if (corner.bottom) bottom else top)
}

/**
 * Where a row at the [shown] corner goes when it is let go [delta] px from its place: the corner whose [place] (its
 * top-left, [capsulePlace]) is nearest to where the dragged row's top-left is. Null, so that nothing is saved, when
 * that is the shown corner: a row let go where it stood, or after a wiggle or a short drag, stays. Null too for a
 * drag no longer than the touch [slop], even where two places lie closer than twice the slop (a row nearly as wide
 * as the box, or a very short box). A tie keeps the shown corner, then the corner that keeps more of it (its side,
 * or its edge).
 */
fun dropCorner(shown: CapsuleCorner, delta: Offset, slop: Float, place: (CapsuleCorner) -> Offset): CapsuleCorner? {
    if (delta.getDistanceSquared() <= slop * slop) return null
    val at = place(shown) + delta
    fun changes(c: CapsuleCorner) = (if (c.left != shown.left) 1 else 0) + (if (c.bottom != shown.bottom) 1 else 0)
    val nearest = CapsuleCorner.entries.minWith(compareBy({ (place(it) - at).getDistanceSquared() }, { changes(it) }))
    return nearest.takeIf { it != shown }
}

/** What one finger on a capsule turns out to be before the long press that lifts the row has passed ([capsuleFinger]). */
enum class CapsuleFinger {
    /** Down and within the touch slop: the long press may still come. */
    Holding,
    /** Lifted first: a tap, which the capsule's click takes to show its pane. */
    Tap,
    /** Moved past the touch slop first: no long press; a sideways move is the row's scroll. */
    Moved,
    /** Another finger came, or something else took the change before it was looked at: no long press. */
    Taken,
}

/**
 * What one finger on a capsule is after it moved [travelX] and [travelY] px from where it went down, while the long
 * press has not passed: [pressed] or lifted, with [fingers] down on the capsule, and its change [consumed] by another
 * handler (a scroll that began) or by the system (a cancel comes as a lift already consumed). Only a finger that
 * stays alone, down and within the [slop] keeps [CapsuleFinger.Holding]; all else leaves the touch to the capsule's
 * click and the row's scroll.
 */
fun capsuleFinger(travelX: Float, travelY: Float, slop: Float, pressed: Boolean, fingers: Int, consumed: Boolean): CapsuleFinger = when {
    consumed -> CapsuleFinger.Taken
    !pressed -> CapsuleFinger.Tap
    fingers > 1 -> CapsuleFinger.Taken
    travelX * travelX + travelY * travelY > slop * slop -> CapsuleFinger.Moved
    else -> CapsuleFinger.Holding
}

/** What the finger that holds the lifted row does next ([liftedFinger]). */
enum class LiftedFinger {
    /** Still down: the row follows it. */
    Follow,
    /** Lifted: the row goes to the corner whose place is nearest where the finger took it ([dropCorner]). */
    Drop,
    /** The system cancelled the touch: the row goes back where it stood, and nothing is saved. */
    PutBack,
}

/**
 * What the finger holding the lifted row does: still [pressed], it leads the row; lifted, it drops it. A lift
 * that comes [consumed] before this handler looked is the system's cancel, which puts the row back.
 */
fun liftedFinger(pressed: Boolean, consumed: Boolean): LiftedFinger = when {
    pressed -> LiftedFinger.Follow
    consumed -> LiftedFinger.PutBack
    else -> LiftedFinger.Drop
}

/** One minimized floating window: [block] is its first pane, [others] the panes beside it that come back with it. */
data class Capsule(val block: String, val name: String, val others: Int) {
    val text: String get() = if (others > 0) "$name +$others" else name
}

/** One capsule per minimized floating window of [window], in the layer stack's order, named by its first pane ([paneName], #208). */
fun capsules(window: WindowInfo, blocks: Map<String, BlockInfo>): List<Capsule> =
    window.minimizedLayers.mapNotNull { layer ->
        val first = layer.firstOrNull() ?: return@mapNotNull null
        Capsule(first, paneName(blocks[first]), layer.size - 1)
    }

/** Shows [block]'s whole minimized floating window again, on top and focused. */
internal fun HostConnection.showMinimized(session: String, window: String, block: String) =
    op("restorePane", "session" to session, "window" to window, "block" to block, "focus" to true)

/**
 * The capsules the pane area draws over [window]: none without a window, none while the tab overview
 * is open or closing ([overviewShown]: its progress is above 0), and none from the first frame of a pane move
 * ([paneMoving], which is set before the overview starts to open). A tab whose panes are all
 * minimized has capsules too: they are the way back.
 */
fun shownCapsules(window: WindowInfo?, blocks: Map<String, BlockInfo>, overviewShown: Boolean, paneMoving: Boolean): List<Capsule> =
    if (window == null || overviewShown || paneMoving) emptyList() else capsules(window, blocks)

/** True when the stage of [window] stays blank, for the capsules to bring a pane back: no pane is drawn and some are minimized. */
fun blankStage(window: WindowInfo?): Boolean = window != null && !drawsPanes(window) && window.minimizedLayers.isNotEmpty()
