package io.github.yhzion.velo.ui

import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.glass
import io.github.yhzion.velo.ui.theme.rounded
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Moving a pane across the tab overview (#217). A long press on a pane's handle, or Move Pane to Tab…, starts a
 * move ([PaneMoveSession]) and opens the overview; the overview's gesture code hands the finger to [PaneMoves],
 * which finds the place under it with PaneMove.kt's rules; [PaneMoveOverlay] draws the places, the ghost and the
 * capsule over the cards. Everything is in the pane area's px ([Stage]) unless a name says otherwise.
 */

/**
 * One pane being moved: [block], from the tab [sourceTab] that holds it. [pick]: started from Move Pane to Tab… or a
 * screen reader, so a press on the overview shows the place under it and its release takes it; else a long press
 * on the pane's handle started it, and that finger drags the pane until it lifts.
 */
@Stable
internal class PaneMoveSession(val block: String, val sourceTab: String, val pick: Boolean) {
    /**
     * The finger has gone somewhere since the move began, and only then is there a place under it. A handle let go
     * where it was pressed is a change of mind: it must not move the pane to whatever half the opening overview
     * brought under the still finger. In pick mode every press aims. What composition decides from the finger is worked
     * out as the finger is written ([PaneMoves.shownPlace], #373), not as this is: [PaneMoves.follow] writes this first.
     */
    var aimed by mutableStateOf(pick)
}

/**
 * Starts moving [block], one of [windows], when the [connected] server can be asked and no move is under way.
 * [root]: where a handle's long press holds the pane, in root coordinates, which become the finger in the pane
 * area's; null starts pick mode. The overview opens around the selected tab, which holds the pane.
 */
internal fun OverviewState.startPaneMove(windows: List<WindowInfo>, connected: Boolean, block: String, root: Offset?): Boolean {
    if (paneMove != null || !connected) return false
    val source = windows.firstOrNull { block in tabBlocks(it) } ?: return false
    val box = stageBox?.takeIf { it.isAttached }
    paneFinger = if (root != null && box != null) box.localPositionOf(box.findRootCoordinates(), root) else null
    beginPaneMove(PaneMoveSession(block, source.id, pick = root == null))
    return true
}

/** A place under the finger and where it is drawn: the half or the pane it takes, or the New Tab card. */
data class StagePlace(val place: MovePlace, val box: CardBox)

/** [rect], in the coordinates of an unscaled card ([paneRects]), on the card that stands at [card]. */
fun onStage(rect: PaneRect, card: CardBox, stage: Stage): CardBox {
    val scale = (card.right - card.left) / stage.width
    return CardBox(card.left + rect.left * scale, card.top + rect.top * scale, card.left + rect.right * scale, card.top + rect.bottom * scale)
}

/**
 * What a release at ([x], [y]) would do while [block] is moved. [cards] are the windows of the row's cards in their
 * order, as the cards draw them ([cardWindow]); the New Tab card follows them, laid out by the same transform, and
 * takes the pane when [newTab]. The row stands as [anchor], [progress] and [scroll] place it. The point is taken
 * into the unscaled card under it, whose tiled panes are laid out with [gap] and [rtl], and [placeAt] chooses there.
 */
fun stagePlaceAt(
    x: Float, y: Float, cards: List<WindowInfo>, block: String, newTab: Boolean,
    anchor: Int, progress: Float, scroll: Float, stage: Stage, gap: Float, rtl: Boolean,
): StagePlace? {
    val index = cardAt(x, y, cards.size + 1, anchor, progress, scroll, stage) ?: return null
    val box = cardBox(index, anchor, progress, scroll, stage)
    if (index == cards.size) return if (newTab) StagePlace(MovePlace.NewTab, box) else null
    val scale = (box.right - box.left) / stage.width
    if (scale <= 0f) return null
    val window = cards[index]
    val place = placeAt(paneRects(window, stage.width, stage.height, gap, rtl), (x - box.left) / scale, (y - box.top) / scale, window.id, block)
    val rect = when (place) {
        is MovePlace.Beside -> place.highlight
        is MovePlace.Swap -> place.highlight
        MovePlace.NewTab, null -> return null
    }
    return StagePlace(place, onStage(rect, box, stage))
}

/** How far a finger at [x] scrolls the row in one frame: [step] towards the side of the pane area it is within [edge] of. */
fun edgeScroll(x: Float, width: Float, edge: Float, step: Float): Float = when {
    x < edge -> -step
    x > width - edge -> step
    else -> 0f
}

/** How long a finger stays in an edge band ([edgeScroll]) before the row scrolls there. */
private const val EdgeHoldMillis = 300L

/**
 * Whether a finger that has been [heldMillis] in an edge band scrolls the row now. Only one that stays there does: a tap
 * near an edge, or a finger passing through the band, chooses what is under it, so the row must not move from under it.
 */
fun edgeScrolls(heldMillis: Long): Boolean = heldMillis >= EdgeHoldMillis

/**
 * Scrolls the row by [scrollBy] a step each frame while the [finger] (null when there is none) stays in an edge band of
 * [stage] ([edgeScrollAt]: at a side in the row, at the top under the strip's room or the bottom in the grid), once it has
 * stayed there ([edgeScrolls]): a tap there, or a finger passing through, chooses what is under it before the row moves.
 * A pane move (#217) and a tab drag (#218) both use it; the grid's strip's room is in the top band only with [roomScrolls].
 */
internal suspend fun followEdges(finger: () -> Offset?, stage: Stage, edge: Float, step: Float, roomScrolls: Boolean, scrollBy: (Float) -> Unit) {
    snapshotFlow { finger()?.let { edgeScrollAt(it, stage, edge, step, roomScrolls) } ?: 0f }.collectLatest { delta ->
        if (delta == 0f) return@collectLatest
        // Counted from the frame after the finger came into the band; leaving it starts the count again.
        val entered = withFrameNanos { it }
        while (true) withFrameNanos { now -> if (edgeScrolls((now - entered) / 1_000_000)) scrollBy(delta) }
    }
}

/**
 * Where a pane move's finger [at] is for the edge scroll ([followEdges]): nowhere before the move has [aimed], nor over the
 * grid while it is on the move's [capsule] ([PaneMoves.capsule]), which covers the cards under it and holds pick mode's
 * Cancel. In a low pane area the grid has no strip's room, and the capsule stands in its top band. The row's bands are at
 * its sides, as they were.
 */
fun moveEdgeFinger(at: Offset?, aimed: Boolean, capsule: Rect?, stage: Stage): Offset? =
    at?.takeIf { aimed && (stage.grid == null || capsule?.contains(it) != true) }

/** What lifting the finger does to a pane move ([PaneMoves.release]). */
enum class MoveLift {
    /** The pane goes to the place under the finger. */
    Move,
    /** The move ends with nothing sent. */
    Cancel,
    /** Pick mode goes on, for the next press to choose a place. */
    Stay,
}

/**
 * What a lift does. Over a place ([overPlace]) the pane goes there. Over none a drag ends, but pick mode ([pick]) goes
 * on: it is left only with Back or its Cancel button ([overCancel]), whose touches the gesture code takes. A press that
 * [scrolled] the row chose nothing.
 */
fun moveLift(pick: Boolean, overPlace: Boolean, overCancel: Boolean, scrolled: Boolean): MoveLift = when {
    scrolled -> MoveLift.Stay
    overPlace -> MoveLift.Move
    pick && !overCancel -> MoveLift.Stay
    else -> MoveLift.Cancel
}

/**
 * What the capsule says while [block] is moved: what lifting the finger now does ([moveLift]), so the words never
 * promise what the lift does not. Over a [place] it names the place ([placeCaption], with [closingTab], the tab that
 * move empties). Over none a drag ends, and so does pick mode ([pick]) on its Cancel button ([overCancel]): both say
 * "Release here to cancel". Pick mode anywhere else goes on, so it keeps asking where the pane goes.
 */
fun moveCaption(
    pick: Boolean, place: MovePlace?, overCancel: Boolean, block: String,
    name: (String) -> String, tabName: (String) -> String, closingTab: String?,
): String = when (moveLift(pick, place != null, overCancel, scrolled = false)) {
    MoveLift.Move, MoveLift.Cancel -> placeCaption(place, name, tabName, closingTab)
    MoveLift.Stay -> "Choose where “${name(block)}” goes"
}

/**
 * Pick mode's Cancel button, for one press at a time. The capsule is centred and its words change under the finger
 * ("Release here to cancel" is shorter than "Choose where … goes", and a place's line is as long as it is), so the
 * button moves while the finger is down. The bounds a press found when it began decide for the whole press: what the
 * capsule says and what the lift does stay the same while the finger stays put, and do not flicker with it.
 */
class CancelBounds {
    /** Where the button was drawn last; null while it is not drawn. */
    var drawn: Rect? = null

    private var pressed: Rect? = null

    /** A press begins: only pick mode ([pick]) has the button. */
    fun press(pick: Boolean) {
        pressed = drawn.takeIf { pick }
    }

    /** Whether a finger at [at] is on the button as the press under way found it. */
    fun contains(at: Offset?): Boolean = at != null && pressed?.contains(at) == true

    /** The press ended: the next one finds the button where it is drawn then. */
    fun release() {
        pressed = null
    }
}

/**
 * Whether a press in pick mode scrolls the row instead of aiming: it began off every place ([onPlace] false) and went the
 * way the row scrolls further than [slop] and than across it, as a swipe moves the row on the overview. [travelX] is its
 * travel since it began along that way ([along]: sideways in the row, up or down in the grid), [travelY] across it.
 */
fun pickScrolls(onPlace: Boolean, travelX: Float, travelY: Float, slop: Float): Boolean =
    !onPlace && abs(travelX) > slop && abs(travelX) > abs(travelY)

/**
 * What the card of [window] draws while [block] of [sourceTab] is moved: its own tab without it ([liftedWindow]), and
 * no floating pane in any tab. A pane's surface takes no alpha, so the overlay draws floating panes faint in their
 * place ([PaneMoveOverlay]), and the tiled places under them stay in sight.
 */
fun cardWindow(window: WindowInfo, block: String, sourceTab: String): WindowInfo =
    (if (window.id == sourceTab) liftedWindow(window, block) else window).copy(floating = emptyList())

/** Nothing is left in [source] once [block] leaves it: no tiled, floating or minimized pane. */
private fun emptiesTab(source: WindowInfo, block: String): Boolean =
    tabBlocks(liftedWindow(source, block)).isEmpty() && source.minimizedBlocks.isEmpty()

/** The tab that closes when [block] leaves [source] for [place]: the source, when nothing else is left in it. A swap leaves the other pane there. */
fun closingTab(source: WindowInfo, block: String, place: MovePlace?): String? {
    if (place == null || place is MovePlace.Swap) return null
    if (place is MovePlace.Beside && place.tab == source.id) return null
    return source.id.takeIf { emptiesTab(source, block) }
}

/**
 * What the card of [window] says while [block] of [sourceTab] is moved: the source tab's card, which the lifted pane
 * leaves empty, says that its tab closes, so that it is no blank card. For the whole move, as a place is not yet chosen.
 */
fun sourceCardNote(window: WindowInfo, block: String, sourceTab: String): String? =
    "Tab closes".takeIf { window.id == sourceTab && emptiesTab(window, block) }

/** Why the New Tab card takes no [block] of [source] ([canMoveToNewTab]); null when it takes it. */
fun newTabNote(source: WindowInfo, block: String): String? =
    "Already alone in its tab".takeUnless { canMoveToNewTab(source, block) }

/** Every place the pane [rect] of [tab] offers, as pick mode lists them: beside it on each side, then the swap. */
fun placesOf(rect: PaneRect, tab: String): List<MovePlace> {
    val midX = (rect.left + rect.right) / 2
    val midY = (rect.top + rect.bottom) / 2
    return listOf(
        MovePlace.Beside(tab, rect.block, PaneEdge.Left, rect.copy(right = midX)),
        MovePlace.Beside(tab, rect.block, PaneEdge.Right, rect.copy(left = midX)),
        MovePlace.Beside(tab, rect.block, PaneEdge.Above, rect.copy(bottom = midY)),
        MovePlace.Beside(tab, rect.block, PaneEdge.Below, rect.copy(top = midY)),
        MovePlace.Swap(tab, rect.block, rect),
    )
}

/** The tab the overview closes onto after a move to [place]; null for a new tab, which the core's focus selects. */
fun destinationTab(place: MovePlace): String? = when (place) {
    is MovePlace.Beside -> place.tab
    is MovePlace.Swap -> place.tab
    MovePlace.NewTab -> null
}

/** The header of a moved pane's ghost: its [place] ("Pane 2"), then its name by the one rule ([paneName], #208). */
internal fun ghostHeader(place: String, info: BlockInfo?): String = "$place · ${paneName(info)}"

/** A pane's place in text: the pane menu's ("Pane 2"), from the panes of its tab. */
private fun panePlace(windows: List<WindowInfo>, block: String): String =
    windows.firstOrNull { block in tabBlocks(it) }?.let { paneLabel(tabBlocks(it), block) } ?: "Pane"

/**
 * What the fingers and a screen reader do with the pane being moved ([OverviewState.paneMove]): where it would land,
 * landing it, or leaving it where it is. [gap] and [rtl] are read when asked, as the cards are drawn then. [exploring]:
 * a screen reader explores the screen by touch now. [onMoved] hears of every move sent, with the session's tabs as
 * they were before it.
 */
@Stable
internal class PaneMoves(
    private val conn: HostConnection,
    private val overview: OverviewState,
    val gap: () -> Float,
    val rtl: () -> Boolean,
    private val exploring: () -> Boolean,
    private val onMoved: (block: String, place: MovePlace, before: List<WindowInfo>) -> Unit,
) {
    /**
     * A screen reader that explores by touch hands its two-finger scroll to the app as a one-finger drag, and may
     * turn a double tap into a tap. In pick mode neither is a choice of a place: there the screen reader chooses
     * with the places' own actions ([PickNodes]), so the fingers choose nothing.
     */
    private fun readerPicks(move: PaneMoveSession): Boolean = move.pick && exploring()

    /** The capsule at the top of the pane area while it is drawn. It covers what is under it, so no place is there. */
    var capsule: Rect? = null

    /**
     * Pick mode's Cancel button. The gesture code takes every touch on the overview, the button's too, so a lift on it
     * is what ends the move ([moveLift]); a screen reader still reaches its own action.
     */
    val cancelButton = CancelBounds()

    /** Whether a finger at [at] is on pick mode's Cancel button, as the press under way found it, where a lift ends the move. */
    fun onCancel(at: Offset?): Boolean = cancelButton.contains(at)

    /** Pick mode, for the press under way ([press]): it began on a place, and it scrolls the row ([pickScrolls]). */
    private var pressOnPlace = false
    private var scrolling = false

    /** The windows of the row's cards in their order, as they are drawn while [move] is under way ([cardWindow]). */
    fun cards(move: PaneMoveSession): List<WindowInfo> {
        val windows = conn.state.windows
        return overview.tabs.map { id -> cardWindow(windows.firstOrNull { it.id == id } ?: WindowInfo(id), move.block, move.sourceTab) }
    }

    /** The place under the finger now, read from the cards as they are drawn; null where a release moves nothing. */
    fun target(): StagePlace? = placeUnder(overview.paneFinger)

    /**
     * The place of [target] as [PaneMoveOverlay] reads it: it changes as the finger comes over another place, not at each
     * move of the finger or the row (#373, [OverviewState.decided]).
     */
    fun shownPlace(): MovePlace? = overview.decided(placeKey) { target()?.place }

    /** Whether the finger is on the Cancel button ([onCancel]) as the overlay reads it: it changes as it goes onto or off it. */
    fun shownOnCancel(): Boolean = overview.decided(cancelKey) { onCancel(overview.paneFinger) }

    /** The overlay that read [shownPlace] and [shownOnCancel] has gone. */
    fun forgetShown() {
        overview.forgetDecided(placeKey)
        overview.forgetDecided(cancelKey)
    }

    /** This object's own keys ([OverviewState.decided]): another stage's moves are not forgotten with these. */
    private val placeKey = Any()
    private val cancelKey = Any()

    /** The place a release at [at] takes, with the row as it stands now. */
    private fun placeUnder(at: Offset?): StagePlace? {
        val move = overview.paneMove ?: return null
        at ?: return null
        val stage = overview.stage ?: return null
        // Nor under the strip's room over the grid, which hides the cards scrolled under it ([inStripRoom]).
        if (!move.aimed || capsule?.contains(at) == true || inStripRoom(at.y, stage)) return null
        val source = conn.state.windows.firstOrNull { it.id == move.sourceTab } ?: return null
        return stagePlaceAt(
            at.x, at.y, cards(move), move.block, canMoveToNewTab(source, move.block),
            overview.drawnAnchor, overview.progress, overview.scroll, stage, gap(), rtl(),
        )
    }

    /** The gesture code hands the move a press that began at [at], or the finger of a handle's long press. */
    fun press(at: Offset) {
        scrolling = false
        val pick = overview.paneMove?.pick == true
        // A row still going after a throw stops for the press, as on the overview: the place the finger comes down on
        // is the place its lift takes. Only the row stops; an overview still opening goes on.
        if (pick) overview.holdRow()
        cancelButton.press(pick)
        // Read once, from the row as it stands when the press begins: the row may move under the finger later.
        pressOnPlace = pick && placeUnder(at) != null
    }

    /**
     * The move's finger is at [at], [fingers] are down, and it has gone [travelX] and [travelY] since the press began;
     * [slop] is the touch slop. True while the finger scrolls the row instead of aiming ([pickScrolls]): the caller
     * then moves the row, as it does on the overview.
     */
    fun follow(at: Offset, fingers: Int, travelX: Float, travelY: Float, slop: Float): Boolean {
        val move = overview.paneMove ?: return false
        if (readerPicks(move)) return false
        // A second finger is no way to choose a place: the move ends, and nothing is sent.
        if (fingers > 1) {
            cancel()
            return false
        }
        // How far the press went the way the row scrolls, and across it: sideways in the row, up and down in the grid.
        val stage = overview.stage
        val scrolled = stage?.let { along(travelX, travelY, it) } ?: travelX
        val crossed = stage?.let { across(travelX, travelY, it) } ?: travelY
        if (move.pick && (scrolling || pickScrolls(pressOnPlace, scrolled, crossed, slop))) {
            // From here the finger is the row's, as on the overview: a row still going after a throw stops for it.
            if (!scrolling) overview.hold()
            scrolling = true
            // No place is under a finger that scrolls: nothing is highlighted, and no edge scrolls the row as well.
            overview.paneFinger = null
            return true
        }
        // Aimed first: the finger's move works out the place under it ([shownPlace]), which only an aimed move has.
        if (maxOf(abs(travelX), abs(travelY)) > slop) move.aimed = true
        overview.paneFinger = at
        return false
    }

    /** The finger lifted, at [velocity] px per second of the row when it scrolled the row: what [moveLift] says. */
    fun release(velocity: Float) {
        val scrolled = scrolling
        scrolling = false
        val overCancel = onCancel(overview.paneFinger)
        cancelButton.release()
        val move = overview.paneMove ?: return
        if (readerPicks(move)) return
        if (scrolled) overview.throwRow(velocity)
        val place = target()?.place
        when (moveLift(move.pick, place != null, overCancel, scrolled)) {
            MoveLift.Move -> commit(place ?: return)
            MoveLift.Cancel -> cancel()
            MoveLift.Stay -> {
                // The next press shows its own place; none stays highlighted under a finger that has gone.
                overview.paneFinger = null
                // A row this press stopped in its throw ([press]) rests on a card; one it scrolled is thrown above.
                if (!scrolled) overview.throwRow(0f)
            }
        }
    }

    /**
     * Sends the move to [place] when the pane is still where the move began and the server can be asked; else the
     * move ends with nothing sent. The overview then closes onto the tab that will hold the pane.
     */
    fun commit(place: MovePlace) {
        val move = overview.paneMove ?: return
        val before = conn.state.windows
        val source = before.firstOrNull { it.id == move.sourceTab }
        if (!conn.connected || source == null || move.block !in tabBlocks(source) ||
            place == MovePlace.NewTab && !canMoveToNewTab(source, move.block)) return cancel()
        conn.sendMove(move.block, place, rtl())
        onMoved(move.block, place, before)
        overview.endPaneMove()
        // The core focuses the moved pane once the move lands, which makes its tab the active one, a new tab too.
        // So the overview only closes onto that tab here and asks the server for nothing: a request of its own would
        // be outside the core's Undo bookkeeping, and would switch the tab even when the core refuses the move.
        destinationTab(place)?.let { overview.pick(it, select = false) }
        if (overview.isOpen) overview.close()
    }

    /** The move ends with nothing sent, and the overview closes back onto the tab it was opened from. */
    fun cancel() {
        overview.endPaneMove()
        overview.close()
    }
}

/** The [PaneMoves] of [TabStage]: the gesture code keeps one object, which reads the current [gap], direction and [onMoved]. */
@Composable
internal fun rememberPaneMoves(conn: HostConnection, overview: OverviewState, gap: Dp, onMoved: (String, MovePlace, List<WindowInfo>) -> Unit): PaneMoves {
    val gapPx by rememberUpdatedState(with(LocalDensity.current) { gap.toPx() })
    val rtl by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Rtl)
    val moved by rememberUpdatedState(onMoved)
    val context = LocalContext.current
    val access = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    return remember(conn, overview) {
        PaneMoves(conn, overview, { gapPx }, { rtl }, { access?.isTouchExplorationEnabled == true }) { block, place, before -> moved(block, place, before) }
    }
}

/** The band at each side of the pane area where a held finger scrolls the row, and how far the row goes in a frame. */
internal val EdgeBand = 48.dp
internal val EdgeStep = 12.dp

/** How faint the other floating panes are during a move, how strong the highlight's fill is, and a New Tab card that takes no pane. */
private const val FloatingAlpha = 0.3f
private const val HighlightAlpha = 0.3f
private const val DisabledAlpha = 0.4f
private val HighlightStroke = 2.dp
private val SwapDisc = 40.dp
private val NewTabDash = 6.dp

/** The ghost: how wide it is at most, how many of the pane's first lines it shows, and how far above the finger it floats. */
private val GhostWidth = 220.dp
private const val GhostLines = 3
private val GhostLift = 32.dp

private val CardBox.topLeft: Offset get() = Offset(left, top)
private val CardBox.size: Size get() = Size(right - left, bottom - top)

/**
 * Lays the node out on the box [box] gives, read while laying out, so it follows the row without a recomposition. The
 * cards stand at absolute px, so the offset is not mirrored in RTL.
 */
private fun Modifier.onBox(box: () -> CardBox): Modifier =
    absoluteOffset { box().let { IntOffset(it.left.roundToInt(), it.top.roundToInt()) } }
        .layout { measurable, _ ->
            val b = box()
            val width = (b.right - b.left).roundToInt().coerceAtLeast(0)
            val height = (b.bottom - b.top).roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(width, height) { placeable.place(0, 0) }
        }

/**
 * What the overview draws over its cards while a pane is moved ([OverviewState.paneMove]): the panes of cards that
 * keep no surface as outlines with their names, the floating panes faint, the New Tab card at the end of the row, the
 * place under the finger, the ghost of the pane at the finger, and the capsule that names the place. In pick mode
 * every place is also a node a screen reader can act on. [row] and [roles] are the cards [TabStage] draws; [radius]
 * is a pane's corner radius. The move ends with nothing sent when the connection or the pane goes.
 */
@Composable
internal fun PaneMoveOverlay(
    conn: HostConnection, state: UiState, overview: OverviewState, moves: PaneMoves, stage: Stage,
    row: List<String>, roles: List<PaneRole>, radius: Dp, haptics: Boolean,
) {
    val move = overview.paneMove ?: return
    val source = state.windows.firstOrNull { it.id == move.sourceTab }
    if (source == null || move.block !in tabBlocks(source) || !conn.connected) {
        LaunchedEffect(move) { moves.cancel() }
        return
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val feel by rememberUpdatedState(haptics)
    // Once the overlay has gone, a finger or the row moving works out nothing it showed.
    DisposableEffect(moves) { onDispose { moves.forgetShown() } }
    // A tick for each change of the place under the finger, so the hand knows what letting go does. A move that has
    // ended has no place any more: that is no change to feel, and its tick would cut the drop's own short (#309).
    LaunchedEffect(move) {
        snapshotFlow { moves.target()?.place }.drop(1).collect {
            if (feel && overview.paneMove === move) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    // A finger held near an end of the pane area scrolls the row there, a step each frame, once it has stayed in that
    // band ([edgeScrolls]): at a side in the row, at the top under the strip's room or the bottom in the grid
    // ([edgeScrollAt]). A tap there chooses the place under it before the row moves. A drag that has not aimed yet rests
    // where the handle was, and scrolls nothing; nor does a finger in the strip's room or on the capsule ([moveEdgeFinger]),
    // where an upper pane's handle is pressed and Cancel stands.
    val edge = with(density) { EdgeBand.toPx() }
    val step = with(density) { EdgeStep.toPx() }
    LaunchedEffect(move, stage, edge, step) {
        followEdges({ moveEdgeFinger(overview.paneFinger, move.aimed, moves.capsule, stage) }, stage, edge, step, roomScrolls = false, overview::scrollBy)
    }
    val windows = state.windows
    val cards = row.map { id -> cardWindow(windows.firstOrNull { it.id == id } ?: WindowInfo(id), move.block, move.sourceTab) }
    val name = { block: String -> panePlace(windows, block) }
    val tabName = { tab: String -> windows.firstOrNull { it.id == tab }?.let { tabTitle(state, it) }.orEmpty() }
    // Everything here stands at absolute px of the pane area: no child starts on the right in RTL.
    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        PlaceOutlines(overview, moves, move, stage, cards, windows, roles, radius, name)
        NewTabCard(overview, moves, stage, cards.size, newTabNote(source, move.block), radius)
        Highlight(overview, moves, stage, radius)
        if (move.pick) PickNodes(overview, moves, move, source, stage, cards, name, tabName)
        // No strip stands over the grid in a move's overview: its room hides the cards scrolled under the capsule, and
        // their places, as the strip does outside a move.
        if (stage.grid != null && stage.stripRoom > 0f) StripBackdrop(overview, Modifier.align(Alignment.TopCenter))
        if (!move.pick) Ghost(conn, overview, move, stage, name)
        // Read as the place under the finger and whether it is on the Cancel button, which change as the finger comes over
        // another place or onto and off the button, not as the finger, which moves on each frame (#373).
        val place = moves.shownPlace()
        val caption = moveCaption(move.pick, place, moves.shownOnCancel(), move.block, name, tabName, closingTab(source, move.block, place))
        Capsule(overview, moves, caption, move.pick)
    }
}

/**
 * The panes of cards that keep no surface, as outlines with their names, and every floating pane but the moved one,
 * faint and in its place. Each is drawn on its card as the row stands at this frame. The source card that the move
 * empties says so in its middle ([sourceCardNote]).
 */
@Composable
private fun PlaceOutlines(
    overview: OverviewState, moves: PaneMoves, move: PaneMoveSession, stage: Stage, cards: List<WindowInfo>,
    windows: List<WindowInfo>, roles: List<PaneRole>, radius: Dp, name: (String) -> String,
) {
    val colors = VeloTheme.colors.backdrop
    val sizes = VeloTheme.sizes
    val measurer = rememberTextMeasurer()
    val style = VeloTheme.type.caption
    Spacer(Modifier.fillMaxSize().drawBehind {
        val p = overview.progress
        if (p <= 0f) return@drawBehind
        val edge = sizes.edge.toPx()
        val inset = sizes.gapCompact.toPx()
        fun DrawScope.label(text: String, box: CardBox, alpha: Float) {
            val width = (box.right - box.left - 2 * inset).toInt()
            if (width <= 0) return
            val laid = measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = width))
            if (laid.size.height > box.bottom - box.top) return
            drawText(laid, colors.text, Offset((box.left + box.right - laid.size.width) / 2, (box.top + box.bottom - laid.size.height) / 2), alpha)
        }
        for ((index, window) in cards.withIndex()) {
            val card = cardBox(index, overview.drawnAnchor, p, overview.scroll, stage)
            // Only the cards in the pane area, on both axes: a grid's cards scrolled out above and below draw nothing.
            if (!inView(card, stage)) continue
            val corner = CornerRadius(radius.toPx() * (card.right - card.left) / stage.width)
            if (roles.getOrElse(index) { PaneRole.Detached } == PaneRole.Detached) {
                for (rect in paneRects(window, stage.width, stage.height, moves.gap(), moves.rtl())) {
                    val box = onStage(rect, card, stage)
                    drawRoundRect(colors.glassEdge.copy(alpha = p), box.topLeft, box.size, corner, Stroke(edge))
                    label(name(rect.block), box, p)
                }
            }
            val original = windows.firstOrNull { it.id == window.id } ?: continue
            sourceCardNote(original, move.block, move.sourceTab)?.let { label(it, card, p) }
            for (f in original.floating) {
                if (f.block == move.block || f.block == original.zoomed) continue
                val rect = PaneRect(f.block, f.x * stage.width, f.y * stage.height, (f.x + f.w) * stage.width, (f.y + f.h) * stage.height)
                val box = onStage(rect, card, stage)
                drawRoundRect(colors.surface.copy(alpha = FloatingAlpha * p), box.topLeft, box.size, corner)
                drawRoundRect(colors.glassEdge.copy(alpha = FloatingAlpha * p), box.topLeft, box.size, corner, Stroke(edge))
                label(name(f.block), box, FloatingAlpha * p)
            }
        }
    })
}

/**
 * The New Tab card at the end of the row, laid out as the card after the last tab. It takes the pane unless [note]
 * says why not ([newTabNote]); then it is faint, says the reason under its name, and a screen reader hears it disabled
 * with that reason. Its corners are a tab's, [radius] scaled.
 */
@Composable
private fun NewTabCard(overview: OverviewState, moves: PaneMoves, stage: Stage, index: Int, note: String?, radius: Dp) {
    val colors = VeloTheme.colors.backdrop
    val sizes = VeloTheme.sizes
    val enabled = note == null
    // Only the card itself is faint: the reason under it stays as readable as any caption.
    val faint = if (enabled) 1f else DisabledAlpha
    Column(
        Modifier.onBox { cardBox(index, overview.drawnAnchor, overview.progress, overview.scroll, stage) }
            .graphicsLayer { alpha = overview.progress }
            .drawBehind {
                val width = sizes.edge.toPx().coerceAtLeast(1f)
                val dash = NewTabDash.toPx()
                drawRoundRect(
                    colors.glassEdge.copy(alpha = colors.glassEdge.alpha * faint), Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
                    CornerRadius(radius.toPx() * size.width / stage.width), Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
            .clearAndSetSemantics {
                contentDescription = "New Tab"
                role = Role.Button
                if (note != null) {
                    stateDescription = note
                    disabled()
                }
                onClick("Move to a new tab") {
                    if (enabled) moves.commit(MovePlace.NewTab)
                    enabled
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Add, null, Modifier.size(sizes.icon), tint = colors.text.copy(alpha = colors.text.alpha * faint))
        Text("New Tab", color = colors.text.copy(alpha = colors.text.alpha * faint), style = VeloTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) {
            Text(note, Modifier.padding(horizontal = sizes.gapCompact), color = colors.secondary, style = VeloTheme.type.caption,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The place under the finger: the half a move beside a pane takes, filled faintly and outlined in the accent; a swap
 * outlines the whole pane and shows the swap symbol in its middle; the New Tab card is filled as a half is.
 */
@Composable
private fun Highlight(overview: OverviewState, moves: PaneMoves, stage: Stage, radius: Dp) {
    val colors = VeloTheme.colors.backdrop
    val swap = rememberVectorPainter(Icons.Default.SwapHoriz)
    val glyph = VeloTheme.sizes.icon
    Spacer(Modifier.fillMaxSize().drawBehind {
        val target = moves.target() ?: return@drawBehind
        val box = target.box
        val stroke = HighlightStroke.toPx()
        val corner = CornerRadius(radius.toPx() * tabTransform(0, 0, overview.progress, 0f, stage).scale)
        if (target.place !is MovePlace.Swap) drawRoundRect(colors.accent.copy(alpha = HighlightAlpha), box.topLeft, box.size, corner)
        drawRoundRect(
            colors.accent, Offset(box.left + stroke / 2, box.top + stroke / 2),
            Size(box.right - box.left - stroke, box.bottom - box.top - stroke), corner, Stroke(stroke),
        )
        if (target.place is MovePlace.Swap) {
            val centre = Offset((box.left + box.right) / 2, (box.top + box.bottom) / 2)
            drawCircle(colors.accent, SwapDisc.toPx() / 2, centre)
            val icon = glyph.toPx()
            translate(centre.x - icon / 2, centre.y - icon / 2) {
                with(swap) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(colors.onAccent)) }
            }
        }
    })
}

/**
 * Pick mode for a screen reader: each tiled pane of the cards in sight is one node, named as the pane menu names it,
 * whose actions are the places it offers ([placesOf]), worded as the capsule words them.
 */
@Composable
private fun PickNodes(
    overview: OverviewState, moves: PaneMoves, move: PaneMoveSession, source: WindowInfo, stage: Stage,
    cards: List<WindowInfo>, name: (String) -> String, tabName: (String) -> String,
) {
    val count = cards.size
    // Only the cards in the pane area, on both axes ([inView]); the row scrolls (one card a step, the grid one row) to bring
    // the others in. Read as those cards, which change as one comes in or goes out, not as the row, which moves on each frame
    // of an opening or a scroll (#373).
    // Its own key ([OverviewState.decided]): a PickNodes that comes as this one goes is not forgotten with it.
    val sight = remember { Any() }
    val inSight = overview.decided(sight) {
        (0 until count).filter { index -> inView(cardBox(index, overview.drawnAnchor, overview.progress, overview.scroll, stage), stage) }
    }
    DisposableEffect(overview, sight) { onDispose { overview.forgetDecided(sight) } }
    for (index in inSight) {
        val window = cards.getOrNull(index) ?: continue
        key(window.id) {
            for (rect in paneRects(window, stage.width, stage.height, moves.gap(), moves.rtl())) key(rect.block) {
                val actions = placesOf(rect, window.id).map { place ->
                    CustomAccessibilityAction(placeCaption(place, name, tabName, closingTab(source, move.block, place))) {
                        moves.commit(place)
                        true
                    }
                }
                Box(
                    Modifier.onBox { onStage(rect, cardBox(index, overview.drawnAnchor, overview.progress, overview.scroll, stage), stage) }
                        .clearAndSetSemantics {
                            contentDescription = name(rect.block)
                            customActions = actions
                        },
                )
            }
        }
    }
}

/** The ghost of the pane being dragged, above the finger: its place and its name ([paneName]), then its first lines, on a raised surface. */
@Composable
private fun BoxScope.Ghost(conn: HostConnection, overview: OverviewState, move: PaneMoveSession, stage: Stage, name: (String) -> String) {
    val overlay = VeloTheme.colors.overlay
    val sizes = VeloTheme.sizes
    val shape = rounded(VeloTheme.radii.card)
    val lines = remember(move) {
        conn.viewportText(move.block).lines().map(String::trimEnd).filter(String::isNotEmpty).take(GhostLines)
    }
    // Named from the live state, as every other place names a pane (#208): the overlay's state is the steady one, without titles.
    val header = remember(move) { ghostHeader(name(move.block), conn.state.blocks[move.block]) }
    Column(
        Modifier.align(AbsoluteAlignment.TopLeft)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(maxWidth = minOf(GhostWidth.roundToPx(), constraints.maxWidth)))
                layout(placeable.width, placeable.height) {
                    // Read while placing: the ghost follows the finger without a recomposition.
                    val at = overview.paneFinger
                    if (at != null) placeable.place(
                        (at.x - placeable.width / 2f).roundToInt().coerceIn(0, (stage.width.roundToInt() - placeable.width).coerceAtLeast(0)),
                        (at.y - GhostLift.toPx() - placeable.height).roundToInt().coerceAtLeast(0),
                    )
                }
            }
            .clearAndSetSemantics {}
            .shadow(sizes.overlayShadow, shape, clip = false)
            .clip(shape)
            .background(overlay.surface)
            .border(sizes.edge, overlay.glassEdge, shape)
            .padding(horizontal = sizes.rowGap, vertical = sizes.menuPadding),
    ) {
        Text(header, color = overlay.text, style = VeloTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        for (line in lines) {
            Text(line, color = overlay.secondary, style = VeloTheme.type.caption.merge(TextStyle(fontFamily = FontFamily.Monospace)),
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

/**
 * The capsule at the top of the pane area: what a release does now ([moveCaption]); pick mode adds Cancel. A screen reader hears each change. No place lies under it ([PaneMoves.capsule]).
 */
@Composable
private fun BoxScope.Capsule(overview: OverviewState, moves: PaneMoves, caption: String, pick: Boolean) {
    val sizes = VeloTheme.sizes
    Row(
        Modifier.align(Alignment.TopCenter).padding(sizes.overlayMargin)
            .onGloballyPositioned { at -> moves.capsule = overview.stageBox?.takeIf { it.isAttached && at.isAttached }?.localBoundingBoxOf(at) }
            .graphicsLayer { alpha = overview.progress }
            .height(sizes.capsule).glass(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(caption, Modifier.weight(1f, fill = false).padding(horizontal = sizes.rowPadding).semantics { liveRegion = LiveRegionMode.Polite },
            color = VeloTheme.surface.onGlass, style = VeloTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (pick) VeloButton("Cancel", { moves.cancel() }, Modifier.padding(end = sizes.cellInset).onGloballyPositioned { at ->
            moves.cancelButton.drawn = overview.stageBox?.takeIf { it.isAttached && at.isAttached }?.localBoundingBoxOf(at)
        })
    }
}

/** Sends the move of [block] to [place] ([moveOp]); the overview's places and the pane menu move a pane alike. */
internal fun HostConnection.sendMove(block: String, place: MovePlace, rtl: Boolean) {
    val (name, args) = moveOp(place, block, rtl)
    op(name, *args.toTypedArray())
}

/** A pane move that was sent and waits to be confirmed on screen: [undo] is its exact inverse, null when none exists. */
internal class MoveNotice(val block: String, val place: MovePlace, val before: List<WindowInfo>, val undo: UndoMove?)

/**
 * The notice of the move of [block] to [place], sent while the session's tabs were [before], which replaces [last],
 * the notice of the move before it. Until [last]'s move shows in [before], [before] is not the layout this move
 * starts from on the server, so no inverse is known to be exact and the notice offers no Undo. [ownLabel] is
 * [undoFor]'s.
 */
internal fun moveNoticeAfter(last: MoveNotice?, block: String, place: MovePlace, before: List<WindowInfo>, ownLabel: (WindowInfo) -> String): MoveNotice {
    val waiting = last != null && !moveLanded(last.before, before, last.block, last.place)
    return MoveNotice(block, place, before, if (waiting) null else undoFor(before, block, place, ownLabel))
}

/** How long the app waits for a sent move to show in its state before it gives up on telling the user. */
internal const val LandWaitMillis = 3000L

/** How long the notice stays, which is long enough to reach Undo with a thumb. */
private const val UndoNoticeMillis = 6000L

/**
 * The notice after a move ("Moved vim", "Swapped vim and psql") with an Undo button when [MoveNotice.undo] exists.
 * It shows only once the move is visible in the state, because Undo is valid only against the layout the move
 * ended with; if that does not happen within [LandWaitMillis] the move failed and the error notice speaks instead.
 * It then stays [UndoNoticeMillis] (longer with the accessibility timeout setting) or until the next move, which
 * replaces [notice]. A tap sends the inverse once and hides it. The caller places it, above [ErrorNotice].
 */
@Composable
internal fun UndoNotice(conn: HostConnection, notice: MoveNotice?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    notice ?: return
    var landed by remember(notice) { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    // Keyed by the notice, so a new move starts both waits again.
    LaunchedEffect(notice) {
        val seen = withTimeoutOrNull(LandWaitMillis) {
            snapshotFlow { moveLanded(notice.before, conn.state.windows, notice.block, notice.place) }.first { it }
        }
        if (seen == null) return@LaunchedEffect onDone()
        landed = true
        delay(accessibility?.calculateRecommendedTimeoutMillis(UndoNoticeMillis, containsIcons = false, containsText = true, containsControls = notice.undo != null) ?: UndoNoticeMillis)
        onDone()
    }
    if (!landed) return
    val sizes = VeloTheme.sizes
    Row(modifier.height(sizes.capsule).glass(), verticalAlignment = Alignment.CenterVertically) {
        Text(undoCaption(notice.place, notice.block) { panePlace(conn.state.windows, it) },
            Modifier.weight(1f, fill = false).padding(horizontal = sizes.rowPadding).semantics { liveRegion = LiveRegionMode.Polite },
            color = VeloTheme.surface.onGlass, style = VeloTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        notice.undo?.let { undo ->
            VeloButton("Undo", {
                conn.op("undoMove", *undoOp(undo).toTypedArray())
                onDone()
            }, Modifier.padding(end = sizes.cellInset))
        }
    }
}
