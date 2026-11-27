package io.github.yhzion.velo.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.feel
import io.github.yhzion.velo.ui.theme.VeloSizes
import io.github.yhzion.velo.ui.theme.MenuItem as VeloMenuItem
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.tileBox
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.glass
import io.github.yhzion.velo.ui.theme.rounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign

private val CardGap = 24.dp

// The drum of the rail's window wheel (#304). derived: the mockup the owner chose (v2).
/** The least and the most width of a drum card's name. */
private val DrumLabelMin = 120.dp
private val DrumLabelMax = 250.dp
/** From a drum card's name to the card. */
private val DrumLabelGap = 22.dp
/** What the drum's cards and names leave at the pane area's sides, together. */
private val DrumMargin = 70.dp
/** The eye's distance from the drum's front card: about the camera's of a turned layer, 8 inches. */
private val DrumEye = 1300.dp
/** The size of a drum card's name. */
internal val DrumLabelText = 17.sp
private val LabelRoom = 44.dp

/** The grid's measures (#281): at each side, between two columns and two rows, above its first row, and under a card for its name. */
private val GridPad = 16.dp
private val GridGap = 12.dp
private val GridTop = 8.dp
internal val GridLabelRoom = 36.dp

/** How long a tapped card waits for the server to select its tab before the row goes back. */
private const val SELECT_WAIT_MS = 2000L

/** How long a card that flew away waits for the server to close its tab before it comes back. */
private const val CLOSE_WAIT_MS = 2000L

/** How long the overview says which tab it closed. */
private const val NOTE_MS = 4000L

/** How long a shell tab's close waits for Undo before it is sent (#228). */
private const val UNDO_MS = 4000L

/** How long New Tab waits for the server's new tab before it stops looking for it (#230). */
private const val NEW_TAB_WAIT_MS = 2000L

/** How long the overview keeps the order of a tab move it sent, when the server's view has not come to match it. */
private const val ORDER_HOLD_MS = 2_000L

/** How long New Tab waits before its card says it is waiting. */
private const val NEW_TAB_BUSY_MS = 300L

/** How long a fast pinch's preview waits for the server to take the step it sent before it goes (#140). */
private const val PREVIEW_WAIT_MS = 2000L

/** How quickly a fast pinch's preview comes and goes. derived: the mockup's ease of about a quarter of the way each frame. */
private const val PREVIEW_STIFFNESS = 1500f

/** The new-tab card among the cards' drawn places and shifts: no tab has this ID. */
private const val NEW_TAB_KEY = "\u0000new-tab"

/** The new-tab card's mark, drawn at the card's full size: it is scaled with the card. */
private val NewTabMark = 120.dp

/** How faint the new-tab card is while no tab can be made (offline). */
private const val OFFLINE_ALPHA = .4f

/** Above the row in the overview, for the session strip (#229). */
private val StripRoom = 96.dp
private val ChipWidth = 136.dp
private val ChipHeight = 64.dp
private val ChipGap = 12.dp
private val StripPadding = 16.dp
private val CellWidth = 14.dp
private val CellHeight = 10.dp
/** A remote's name before its chips, where the strip shows several remotes (#236). */
private val RemoteNameWidth = 72.dp

/** What TalkBack says a long press on a session's chip does (#236). */
private const val SessionMenuLabel = "Show session menu"

/** Around the Undo note, a margin that takes touches meant for it. */
private val UndoMargin = 16.dp

/** Under the Undo note, between it and the pane area's bottom edge. */
private val NoteBottom = 12.dp

/** How much of a neighbouring card shows at each side of the selected one in the row. */
private val RowPeek = 20.dp

/** Between the session strip and the top of a card that fills the row's height. */
private val RowPad = 16.dp

/**
 * From the pane area's bottom up to the top of the Undo note's touch area, as [RowNote] draws it: its bottom padding,
 * the margin and the glass padding on both sides of the button, and the button's least height.
 */
internal fun noteRoom(sizes: VeloSizes): Dp = NoteBottom + UndoMargin * 2 + sizes.gapCompact * 2 + sizes.control

/** How far a card picked up to move its tab (#218) rises above the row, so the hand sees that it holds the card. */
private val PickupRaise = 16.dp

/** The pane a pinch began on and its text scale then. */
private class PinchFrom(val block: String, val scale: Float)

/** The pane [block] of tab [tab] that a fast pinch would take a step of the ladder with ([toward]), while its preview is drawn (#140). */
internal class LayoutPreview(val tab: String, val block: String, val toward: LayoutPinch)

/**
 * A card picked up to move its tab (#218): the tab [id], the place the row was built around when the card was picked up
 * ([frameAnchor]), and where on the card the finger held it, from its top left corner before its lift ([grab],
 * [OverviewState.pickUpTab]). The row keeps [frameAnchor] until the drop, so the places stay where they are on screen
 * while the cards move between them. [finger] is where the finger is now, in the pane area's px; each move of it is
 * [moved] (#373), which works out what composition decides from it ([OverviewState.shownDragPlace]).
 */
internal class TabDrag(val id: String, val frameAnchor: Int, val grab: Offset, private val moved: () -> Unit) {
    private val fingerState = mutableStateOf(Offset.Zero)
    var finger: Offset
        get() = fingerState.value
        set(value) {
            fingerState.value = value
            moved()
        }
}

/**
 * How far the tab overview of [conn]'s session is open, and where its row of cards stands.
 * [progress] runs from 0 (the workspace) to 1 (the overview); the fingers, the rail button
 * and the palette all move this one number, and [TabStage] draws what it says.
 */
@Stable
internal class OverviewState(
    private val conn: HostConnection, private val scope: CoroutineScope, arrival: Arrival? = null, private val onPick: (String) -> Unit,
) {
    /** [progress] and [scroll], and what composition reads of them instead ([MotionValues], #347). */
    private val values = MotionValues()
    var progress: Float
        get() = values.progress
        private set(value) {
            values.progress = value
        }
    /** How far the row of cards is moved to the left, in px at progress 1. */
    var scroll: Float
        get() = values.scroll
        private set(value) {
            values.scroll = value
        }
    /**
     * The overview shows: [progress] is above 0. Composition reads this, which changes when the overview comes or goes, not
     * [progress], which changes on every frame of its motion (#347).
     */
    val showing: Boolean get() = values.showing

    /** [keptPlaces] for the stage, read in composition: they change when the places do, not with each frame ([MotionValues.keptAt]). */
    fun keptAt(count: Int, anchor: Int, stage: Stage): Set<Int> = values.keptAt(count, anchor, stage)

    /**
     * What [decide] answers, read in composition ([MotionValues.decided], #373): a recomposition when the answer changes, not
     * each time the motion, a finger ([TabDrag.finger], [paneFinger]) or the drop moves. One composable reads each [key],
     * and forgets it when it goes ([forgetDecided]).
     */
    fun <T> decided(key: Any, decide: () -> T): T = values.decided(key, decide)
    fun forgetDecided(key: Any) = values.forget(key)

    /** [dragPlace] as TabStage reads it: it changes as the dragged card comes over another place, not at each move (#373). */
    fun shownDragPlace(): Int? = values.decided(DragPlace) { dragPlace() }

    private object DragPlace

    /** The tab the row is built around: the selected one, or a tapped card until the server has selected it. */
    var anchor by mutableStateOf<String?>(null)
        private set
    /** Last server selection applied to the motion state; a pick or swipe changes only [anchor]. */
    var lastConfirmed by mutableStateOf<String?>(null)
        private set
    /** A pinch that opened the overview still has its fingers down. */
    var pinching by mutableStateOf(false)
        private set

    /** The step a fast pinch would take, drawn over its tab while its intent is on and until the server has taken it (#140). */
    var preview by mutableStateOf<LayoutPreview?>(null)
        private set
    /**
     * How far the [preview] is in, 0 to 1, read while drawing: the pane's outline and the dimming show by as much, and the
     * pinch's text goes back to its size from before the pinch by as much ([previewTextScale]).
     */
    var previewShare by mutableFloatStateOf(0f)
        private set
    /** How far toward its other place the [preview] takes the pane ([previewReach]); 1 once its step is sent. Read while drawing. */
    var previewReach by mutableFloatStateOf(0f)
        private set

    /** Where it is, or is on its way to. */
    val isOpen: Boolean get() = goal > 0f

    /**
     * What [TabStage] drew last: the pane area, the tabs of the row in their order (without the
     * tabs being closed, [rowSlots]), every tab of the session, and the place of the card the row
     * is built around ([rowAnchor]).
     */
    var stage: Stage? = null
    var tabs: List<String> = emptyList()
    var allTabs: List<String> = emptyList()
    var drawnAnchor = 0

    /**
     * The order of the tabs after a move this overview sent, kept until the server's view matches it ([serverOrder]) or
     * [ORDER_HOLD_MS] passes: the cards stay where they were put instead of jumping back before the server answers.
     */
    var orderHold by mutableStateOf<List<String>?>(null)
        private set
    private var holdJob: Job? = null

    /** Sends [move] and keeps the order it makes; false, with nothing sent, when there is no connection or session to send it in. */
    fun moveTab(move: TabMove): Boolean {
        val now = conn.state
        val session = now.session
        if (!conn.connected || session == null) return false
        // In a session switch (#229) the session can change before the tabs drawn do: a tab never goes to another session.
        if (move.window !in allTabs || now.windows.none { it.id == move.window }) return false
        conn.moveTab(session, move)
        orderHold = movedTabs(allTabs, move)
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(ORDER_HOLD_MS)
            orderHold = null
        }
        return true
    }

    /**
     * A screen reader's move of the tab [name] ([tabCardMoves]): sent as [moveTab] sends it, and the note under the row
     * says where the tab went, read aloud as `Closed` is. A drag says nothing, since the hand sees where the card went.
     */
    fun moveTabAloud(move: TabMove, name: String): Boolean {
        if (!moveTab(move)) return false
        val row = movedTabs(allTabs, move).filter { it !in closing }
        note(movedTabNote(name, row.indexOf(move.window) + 1, row.size))
        return true
    }

    /** The server's view of the tabs: once it no longer differs from the held order only by its arrangement, the hold has done its work. */
    fun serverOrder(tabs: List<String>) {
        if (heldOrder(tabs, orderHold) == null) {
            orderHold = null
            holdJob?.cancel()
            holdJob = null
        }
    }

    /** A card picked up to move its tab (#218), from the long press until the drop; null while none is. */
    var tabDrag by mutableStateOf<TabDrag?>(null)
        private set
    /** How far a picked-up card rises, in px: [PickupRaise] at the density TabStage draws with. */
    var pickupRaise = 0f

    /**
     * A long press on the card of [id], the finger at [at]: the card rises and follows the finger, and the row stays built
     * around the place it is built around now. False, with nothing picked up, when the card cannot move: the overview is
     * not open, the card flew away or is not in the row, the server cannot hear the move, or a pane is being moved.
     */
    fun pickUpTab(id: String, at: Offset): Boolean {
        val now = stage
        val index = tabs.indexOf(id)
        if (now == null || index < 0 || tabDrag != null || paneMove != null || !conn.connected || !liftable(id)) return false
        // A row still going after a throw stops: the places stay where the card was picked up from.
        holdRow()
        val frame = rowAnchor()
        val box = placed(id, cardBox(index, frame, progress, scroll, now))
        // From now on the card is drawn at the finger: a slide it was in has nothing more to do.
        shiftMotion.remove(id)?.cancel()
        shifts.remove(id)
        // It rises from where it is ([raise]); one still going back from a pull aside loses that lift, whose way is in the grab.
        raise(id, pickupRaise)
        // Measured from where the card is shown now, less the lift it rises on from there and the drop ([heldAt]): neither
        // moves it twice, and the card stays under the finger as it was held, with no jump.
        tabDrag = TabDrag(id, frame, at - Offset(box.left, box.top) + liftOffset(id), values::moved).also { it.finger = at }
        return true
    }

    /** The finger that holds a picked-up card is at [at]. */
    fun dragTab(at: Offset) {
        tabDrag?.finger = at
    }

    /**
     * Where the top left corner of the card [drag] holds is drawn, before its lift and the drop: under the finger, as it was
     * held. The finger holds it over another session's row that drops in ([dropBy]), so the drop that [shownAt] adds to
     * every card is taken out here.
     */
    private fun heldAt(drag: TabDrag): Offset = drag.finger - drag.grab - Offset(0f, dropBy())

    /** Where the card of [id] is drawn while it is dragged: under the finger, as it was held. Null for every other card. */
    fun dragLeft(id: String): Float? = tabDrag?.takeIf { it.id == id }?.let { heldAt(it).x }

    /**
     * Where the top of the card of [id] is drawn while it is dragged in the grid: under the finger, as it was held. Null
     * for every other card, and in the row, where a dragged card keeps its height and only rises ([pickupRaise]).
     */
    fun dragTop(id: String): Float? =
        tabDrag?.takeIf { it.id == id && stage?.grid != null }?.let { heldAt(it).y }

    /**
     * The place of the row the dragged card would drop on now, by its middle ([cardCentre], [dropPlace]); null while no card
     * is dragged.
     */
    fun dragPlace(): Int? {
        val drag = tabDrag ?: return null
        val now = stage ?: return null
        // Where the card is drawn ([drawnAt]): under the finger, and in the row at its place's height.
        val place = cardBox(tabs.indexOf(drag.id), drag.frameAnchor, progress, scroll, now)
        val middle = cardCentre(drawnAt(drag.id, Offset(place.left, place.top)), progress, now)
        return dropPlace(middle.x, middle.y, tabs.size, drag.frameAnchor, progress, scroll, now)
    }

    /**
     * The finger let the dragged card go: its tab moves to the place the card is over ([tabMoveTo]), in the order shown
     * before the drag, and nothing is sent when that is its own place. The card then slides into its place and comes down.
     * No tab is selected and the overview stays open. True when the move was sent: the finger feels that (#309).
     */
    fun dropTab(): Boolean {
        val drag = tabDrag ?: return false
        val move = dragPlace()?.let { tabMoveTo(tabs, drag.id, it) }
        tabDrag = null
        val sent = move?.takeIf { moveTab(it) }
        // The order shown next: the moved one once the move is sent ([orderHold]), else the one shown before.
        putDown(drag, if (sent != null) movedTabs(allTabs, sent) else allTabs)
        return sent != null
    }

    /** The dragged card goes back to its place, and nothing is sent: a second finger came, the tab or the connection went, the overview closed. */
    fun cancelTabDrag() {
        val drag = tabDrag ?: return
        tabDrag = null
        putDown(drag, allTabs)
    }

    /**
     * The drag is over and the row shows [order] next. The row is built around its anchor's place in that order again,
     * with every place kept where it stands on screen ([rebasedScroll]), and the card slides from where the finger left it
     * into its place there ([slide], the spring of [arranged]) and comes down.
     */
    private fun putDown(drag: TabDrag, order: List<String>) {
        val now = stage
        // The anchor as TabStage finds it: the tapped card's tab, else the tab the server selected.
        val id = anchor?.takeIf { it in order } ?: lastConfirmed?.takeIf { it in order }
        val frame = id?.let { rowAnchor(order, closing.keys, it) } ?: drag.frameAnchor
        if (now != null) {
            val row = order.filter { it !in closing }
            // Counted for the row as it is after the drop: `tabs` and rowAnchor() still describe the one before it.
            val rest = restAfterDrop(scroll, drag.frameAnchor, frame, rowPlaces(row.size), progress, now)
            scroll = rebasedScroll(scroll, drag.frameAnchor, frame, progress, now)
            val place = row.indexOf(drag.id)
            if (place >= 0) {
                val box = cardBox(place, frame, progress, scroll, now)
                val held = heldAt(drag)
                // In the row the card kept its height while it was dragged ([dragTop]): it slides across alone.
                val down = if (now.grid != null) held.y - box.top else 0f
                slide(drag.id, Offset(held.x - box.left, down))
            }
            // An edge scroll can have left the row between two cards: it rests on one, as after a lift. Not when the row's
            // anchor is gone from the order (another session came under the drag): that row's place is not the drag's to set.
            if (id != null) throwRow(0f, to = rest)
        }
        putBack(drag.id)
    }

    /** The card of [id] rises to [to] px, with the spring a card that is put back falls with ([putBack]). */
    private fun raise(id: String, to: Float) {
        liftMotion.remove(id)?.cancel()
        // A card still going back from a pull aside rises up from where it is: the way aside is in its grab ([pickUpTab]).
        if (liftSides.remove(id) != null) lifts[id] = 0f
        val from = liftOf(id)
        liftMotion[id] = scope.launch {
            animate(from, to, 0f, spring(dampingRatio = 0.75f, stiffness = 380f)) { value, _ -> lifts[id] = value }
        }
    }

    /**
     * What the overview says once a tab it closed is gone ([closedTabNote]), or where a screen reader moved one
     * ([movedTabNote]); null when there is nothing to say.
     */
    var closedNote by mutableStateOf<String?>(null)
        private set
    private var noteMotion: Job? = null

    /** Which panes come back to the tab the overview was opened from; this connection's alone. */
    val returns = ReturnMark()

    /**
     * A pane being moved across the overview (#217, [PaneMoveSession]), and the finger moving it in the pane area's
     * px. [stageBox] is where TabStage was placed last, for a point a pane's handle gives in root coordinates. What
     * composition decides from the finger is worked out as it moves ([MotionValues.moved], #373).
     */
    var paneMove by mutableStateOf<PaneMoveSession?>(null)
    private val paneFingerState = mutableStateOf<Offset?>(null)
    var paneFinger: Offset?
        get() = paneFingerState.value
        set(value) {
            paneFingerState.value = value
            values.moved()
        }
    var stageBox: LayoutCoordinates? = null

    /** The pane being moved, if any, stays where it is: nothing is sent. */
    fun endPaneMove() {
        paneMove = null
        paneFinger = null
    }

    /**
     * The overview is a pane move's: from the move's start until the overview has closed again, the closing after
     * the move too ([movesOverview]). It offers the move's places alone, in the move's own session, so the session
     * strip (#229), which shows another session's tabs, and the row's new-tab card (#230), whose place is the move's
     * New Tab card, stay away ([showsStrip]).
     */
    var forMove by mutableStateOf(false)
        private set

    /** [move] begins, and the overview opens around it: [settle] makes it a pane move's ([forMove]). */
    fun beginPaneMove(move: PaneMoveSession) {
        // One move at a time: a card being dragged goes back with nothing sent.
        cancelTabDrag()
        paneMove = move
        open()
    }

    private var goal = 0f
    private var motion: Job? = null
    var pageOffset by mutableFloatStateOf(0f)
        private set
    var pageGeneration = 0L
        private set
    private var pageMotion: Job? = null
    private var rowMotion: Job? = null
    private var pinch: PinchFrom? = null
    /** Where the [preview]'s share is going, its motion, and the text size of the pinch's fingers as it moves. */
    private var previewGoal = 0f
    private var previewMotion: Job? = null
    private var previewFinger = 1f

    /** How far each lifted card is from its place, in px ([cardLift]): up, or to its side ([liftSides]); a card that is not here is in its place. */
    private val lifts = mutableStateMapOf<String, Float>()
    /** The side a card is lifted to, -1 left or 1 right, while it is lifted sideways ([lift]); a card that is not here is lifted up. */
    private val liftSides = mutableStateMapOf<String, Float>()
    /** How far each card is still drawn off its place since the tabs changed ([rowShifts]), across and down, in px. */
    private val shifts = mutableStateMapOf<String, Offset>()
    /** Tabs whose cards flew away, true once out of sight: until the tab is closed or its card comes back. */
    private val away = mutableStateMapOf<String, Boolean>()
    /** Tabs whose close is sent: their cards have left the row ([rowSlots]) until the server answers. */
    private val closing = mutableStateMapOf<String, Unit>()
    /** The names of the tabs whose close was sent, until they are gone: a late answer still closes one. */
    private val sentNames = HashMap<String, String>()

    /** A shell tab's close that waits for Undo: its tab, its name and how it is sent (false when it cannot be). */
    private class Waiting(val id: String, val name: String, val send: () -> Boolean)
    private val undo = UndoSlot<Waiting>()
    /** The tab whose close waits for Undo, by name, shown under the row with the Undo button; null when none waits. */
    var undoName by mutableStateOf<String?>(null)
        private set
    /** How long a close waits for Undo: [UNDO_MS], or longer when the accessibility setting asks for more time. */
    var undoMillis = UNDO_MS
    private val liftMotion = HashMap<String, Job>()
    private val shiftMotion = HashMap<String, Job>()
    /** The top left corner each card was drawn at last, before its lift and the drop, in px. */
    private val drawn = HashMap<String, Offset>()
    /** The session's tabs and the row's that [arranged] saw last, and the session. */
    private var arrangedTabs: List<String> = emptyList()
    private var arrangedRow: List<String> = emptyList()
    private var arrangedSession: String? = null
    /**
     * Another session is shown in the open overview (#229): how much of its row's way in is still to go, 1 to 0. It moves the
     * cards under a held finger, so what composition decides from that is worked out as it moves ([MotionValues.moved]).
     */
    private val dropState = mutableFloatStateOf(0f)
    var drop: Float
        get() = dropState.floatValue
        private set(value) {
            dropState.floatValue = value
            values.moved()
        }
    private var dropMotion: Job? = null
    /** Another session is shown and its tabs have not come yet: its row drops in when they do. */
    private var awaitingRow = false
    /** The session the strip asked to leave, until the row shows another one. */
    private var askedFrom: String? = null
    /** The session whose row the open overview waits for after a switch of remote (#236); null for any session's. */
    private var awaited: String? = null
    private var arrivalWait: Job? = null
    /** New Session was tapped and its session has not come yet: a second tap makes no second one. */
    private var newSessionWait: Job? = null
    /**
     * A session tapped on the strip that the server has not shown yet: its chip is the current one
     * already (#246, [stripRemotes]). The server's selection takes over once it shows that session,
     * or after a while when it does not.
     */
    var chosen by mutableStateOf<String?>(null)
        private set
    private var chosenWait: Job? = null
    /**
     * The strip's place: kept while the strip is drawn anew (the stage goes for a frame in a switch),
     * and handed on with a switch of remote ([Arrival]).
     */
    val stripScroll = ScrollState(arrival?.stripScroll ?: 0)
    /** What each flying card's landing does ([fly]): a switch of session lands them at once. */
    private val flights = HashMap<String, (String) -> Unit>()

    fun open() = settle(1f)
    fun close() = settle(0f)
    fun toggle() = settle(if (isOpen) 0f else 1f)

    /**
     * The overview is the rail's window wheel (#304): its tabs stand on a drum ([DrumSpec]) the rail turns, and it takes
     * no touch of its own. It stays the wheel's until it has closed.
     */
    var wheel by mutableStateOf(false)
        private set

    /** The place in [tabs] of the tab in front of the wheel's drum, as the rail's wheel counts it; -1 without one. */
    var wheelFront by mutableIntStateOf(-1)
        private set

    /**
     * The rail's wheel opened: the overview opens as its drum, on the tab shown now. Not over an overview already open.
     * The row's tabs, in its order, for the rail to count by; null when it did not open.
     */
    fun openWheel(): List<String>? {
        if (isOpen || progress > 0f || paneMove != null || pinching) return null
        scroll = 0f
        wheelFront = rowAnchor()
        settle(1f, asWheel = true)
        return tabs.toList()
    }

    /** The rail turned its wheel to [position], a place in [tabs], with the tab at [front] in front of it. */
    fun turnWheel(position: Float, front: Int) {
        if (!wheel) return
        val from = rowAnchor()
        if (from >= 0) scroll = position - from
        wheelFront = front
    }

    /**
     * The rail's wheel was let go on [id]: that tab is picked ([pick]). When it cannot be (the connection went, the row
     * is on its way after another session's), the drum closes on the tab it was opened on all the same.
     */
    fun pickWheel(id: String, select: Boolean) {
        if (!wheel) return
        pick(id, select)
        if (isOpen) close()
    }

    /** Fingers took over: nothing moves by itself any more. */
    fun hold() {
        motion?.cancel()
        rowMotion?.cancel()
    }

    /** A finger took the row: a row still going after a throw stops where it is, and the overview opens or closes on. */
    fun holdRow() {
        rowMotion?.cancel()
    }

    /** The row, or the grid, is still on its way after a throw or a step ([throwRow]). */
    val rowMoving: Boolean get() = rowMotion?.isActive == true

    fun resetPage() {
        pageGeneration++
        pageMotion?.cancel()
        pageOffset = 0f
    }

    fun dragPage(offset: Float, stage: Stage) {
        pageMotion?.cancel()
        val from = index(anchor)
        val width = stage.width + stage.pageGap
        pageOffset = if (from < 0) 0f else offset.coerceIn(
            if (from < tabs.lastIndex) -width else 0f,
            if (from > 0) width else 0f,
        )
    }

    fun releasePage(velocity: Float, stage: Stage) {
        val from = index(anchor)
        val width = stage.width + stage.pageGap
        val offset = pageOffset
        val delta = pageStep(offset / width, velocity / width)
        // Without the connection no tab can be selected: the row goes back to where it was.
        val target = tabs.getOrNull(from + delta)?.takeIf { conn.connected }
        val rest = if (target != null) -delta * width else 0f
        if (rest == 0f && offset == 0f) return
        val session = conn.state.session
        val original = anchor
        pageMotion?.cancel()
        pageMotion = scope.launch {
            animate(offset, rest, velocity, spring(dampingRatio = 1f, stiffness = 320f)) { value, _ ->
                pageOffset = value.coerceIn(minOf(offset, rest), maxOf(offset, rest))
            }
            pageOffset = 0f
            // The connection may have gone down while the page moved: then the row stays where it was.
            if (rest != 0f && target != null && conn.connected && conn.state.session == session &&
                anchor == original && this@OverviewState.stage == stage &&
                tabs.getOrNull(index(original) + delta) == target) {
                anchor = target
                onPick(target)
                conn.op("selectWindow", "session" to session, "window" to target)
            }
        }
    }

    /** The fingers are at [p]. */
    fun follow(p: Float) {
        goal = if (p >= 0.5f) 1f else 0f
        moveTo(p)
    }

    /** The fingers lifted at [velocity] (progress per second). */
    fun release(velocity: Float) = settle(settleTarget(progress, velocity), velocity)

    /** Between its ends with nothing moving it: fingers that lift now must [release] it. */
    val adrift: Boolean get() = progress > 0f && progress < 1f && motion?.isActive != true

    private fun settle(target: Float, velocity: Float = 0f, asWheel: Boolean = false) {
        // Opened by anything but the rail's wheel (the button, a pinch, the palette), even while the wheel's drum still
        // closes, the overview is the row's or the grid's: the drum takes no touch, and the wheel is let go.
        if (target == 1f) wheel = asWheel
        // Leaving the overview sends the close that waits for Undo: Undo is offered only here.
        if (target == 0f) sendWaiting()
        // And stops looking for a new tab: the server shows it in the workspace when it comes.
        if (target == 0f) endNewTab()
        resetPage()
        // A pane is moved only across the open overview: however the overview closes, the move ends with nothing sent.
        if (target == 0f) endPaneMove()
        // A card is dragged only across the open overview too: however it closes, the card goes back with nothing sent.
        if (target == 0f) cancelTabDrag()
        // Opening, it is a pane move's only with a move under way: opened again while it closes after one, it is not.
        if (target == 1f) forMove = movesOverview(forMove, paneMove != null, progress, opening = true)
        goal = target
        motion?.cancel()
        motion = scope.launch {
            animate(progress, target, velocity, spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = 0.0005f)) { value, _ ->
                moveTo(value.coerceIn(0f, 1f))
            }
            moveTo(target)
            endPinch()
            // A row left beside its cards (a card picked while the overview was still opening) rests on one.
            val now = stage
            if (target == 1f && now != null && !wheel && scroll !in scrollBounds(rowPlaces(tabs.size), rowAnchor(), now)) throwRow(0f)
        }
    }

    private fun moveTo(p: Float) {
        // The text size first: a pane that is shown again is fitted with the size it has then.
        val from = pinch
        if (from != null && !pinching) conn.scale(from.block, pinchTextScale(from.scale, p), live = true)
        progress = p
        // The workspace shows no row: the next opening starts with the selected tab centred.
        if (p == 0f) {
            // Not on the first frame of a move's opening, which is at 0 too: only once it has closed with no move.
            forMove = movesOverview(forMove, paneMove != null, p, opening = false)
            rowMotion?.cancel()
            dropMotion?.cancel()
            drop = 0f
            awaitingRow = false
            awaited = null
            arrivalWait?.cancel()
            choose(null)
            scroll = 0f
            // Closed: the next opening is the row's or the grid's again, unless the rail opens it as its wheel.
            if (goal == 0f) {
                wheel = false
                wheelFront = -1
            }
            shiftMotion.values.forEach(Job::cancel)
            shiftMotion.clear()
            shifts.clear()
        }
    }

    fun liftOf(id: String): Float = lifts[id] ?: 0f

    /** Where the lift of the card of [id] moves it, in px: up, or to its side while it is lifted sideways ([liftOffset]). */
    fun liftOffset(id: String): Offset = liftOffset(liftOf(id), liftSides[id] ?: 0f)

    /** Whether the card of [id] is aside ([liftSides]): a grid card a finger takes aside, that goes back, or that flies out. */
    fun aside(id: String): Boolean = id in liftSides

    /** How far up the whole row is drawn while another session's row drops in, in px. */
    fun dropBy(): Float = stage?.let { dropOffset(drop, it) } ?: 0f
    fun shiftOf(id: String): Offset = shifts[id] ?: Offset.Zero

    /** The card of [id] flew out of sight: its tab is being closed, or asked about. */
    fun hidden(id: String): Boolean = away[id] == true

    /**
     * Where the top left corner of the card of [id] is drawn before its lift and the drop, its place's corner being at
     * [place]: under the finger while it is dragged ([dragLeft], and in the grid [dragTop]), else at its place or sliding
     * to it ([shiftOf]). [drew] records this.
     */
    fun drawnAt(id: String, place: Offset): Offset {
        val shift = shiftOf(id)
        return Offset(dragLeft(id) ?: (place.x + shift.x), dragTop(id) ?: (place.y + shift.y))
    }

    /** Where the top left corner of the card of [id] is shown, drawn at [at] ([drawnAt]): moved by its lift and the drop. */
    fun shownAt(id: String, at: Offset): Offset {
        val lift = liftOffset(id)
        return Offset(at.x + lift.x, at.y + lift.y + dropBy())
    }

    /** Where the card of [id] is drawn, [box] being its place in the row: moved as a whole to where it is shown ([shownAt]). */
    fun placed(id: String, box: CardBox): CardBox {
        val shown = shownAt(id, drawnAt(id, Offset(box.left, box.top)))
        val dx = shown.x - box.left
        val dy = shown.y - box.top
        return CardBox(shown.x, shown.y, box.right + dx, box.bottom + dy)
    }

    /** The card of [id] was drawn with its top left corner at [at] px, before its lift and the drop. */
    fun drew(id: String, at: Offset) {
        drawn[id] = at
    }

    /**
     * Where every card was drawn last. Read while composing: a card's layer may take its new place
     * while the change is applied, before [arranged] hears of it.
     */
    fun drawnPlaces(): Map<String, Offset> = HashMap(drawn)

    /**
     * A finger may lift the card of [id]: the overview is open, the card has not flown away, and its
     * row is in sight. A row that waits for another session's (#236) is drawn but not seen.
     */
    fun liftable(id: String): Boolean = isOpen && progress >= 0.5f && id !in away && !awaitingRow

    /** A finger holds the card of [id] [lift] px up, or [lift] px to a [side] (-1 left, 1 right) for a sideways lift. */
    fun lift(id: String, lift: Float, side: Float = 0f) {
        liftMotion.remove(id)?.cancel()
        lifts[id] = lift
        if (side == 0f || lift == 0f) liftSides.remove(id) else liftSides[id] = side
    }

    /**
     * The finger let the card of [id] go, moving at [velocity] (its [dismissWay] per second, up or aside). Far or fast
     * enough ([dismisses]) it flies away and [onGone] hears of it when it is out of sight; else it
     * falls back to its place. Without the connection no tab closes. True when it flies.
     */
    fun letGo(id: String, velocity: Float, onGone: (String) -> Unit): Boolean {
        val now = stage
        // The lift and its speed are measured on the dismiss way; the card flies until it is out of sight. In the row both are one.
        val way = now?.let { dismissWay(progress, it) }
        if (now == null || way == null || !conn.connected || !dismisses(liftOf(id) / way, velocity)) {
            putBack(id, velocity * (way ?: 0f))
            return false
        }
        fly(id, velocity * way, liftDistance(progress, now), onGone)
        return true
    }

    /** A screen reader's "Close tab": the card of [id] flies away as if a finger had let it go. */
    fun dismiss(id: String, onGone: (String) -> Unit): Boolean {
        val now = stage
        if (now == null || !liftable(id) || !conn.connected) return false
        // A grid card goes out on the side of the pane area nearer to it ([nearerSide]); one a finger holds aside, to that side.
        if (now.grid != null && id !in liftSides) drawn[id]?.let { liftSides[id] = nearerSide(it, progress, now) }
        fly(id, 0f, liftDistance(progress, now), onGone)
        return true
    }

    /** The card of [id] goes back to its place from wherever it is, at [speed] px per second (away from its place positive). */
    fun putBack(id: String, speed: Float = 0f) {
        liftMotion.remove(id)?.cancel()
        val from = liftOf(id)
        if (from == 0f) {
            lifts.remove(id)
            liftSides.remove(id)
            return
        }
        // A card lifted sideways goes back across, to its place.
        liftMotion[id] = scope.launch {
            animate(from, 0f, speed, spring(dampingRatio = 0.75f, stiffness = 380f)) { value, _ -> lifts[id] = value }
            lifts.remove(id)
            liftSides.remove(id)
        }
    }

    /** Nothing closes before the card is out of sight: as on a phone, the end of the flight is the close. */
    private fun fly(id: String, speed: Float, way: Float, onGone: (String) -> Unit) {
        // Another card closes: the one that waited for Undo goes now.
        sendWaiting()
        liftMotion.remove(id)?.cancel()
        // A grid card goes out aside ([liftDistance]): to the side a finger took it to or [dismiss] chose, else to the right.
        if (stage?.grid != null && id !in liftSides) liftSides[id] = 1f
        away[id] = false
        flights[id] = onGone
        liftMotion[id] = scope.launch {
            var flight = Flight(liftOf(id), speed)
            var last = withFrameNanos { it }
            while (flight.lift < way) {
                val now = withFrameNanos { it }
                flight = flightStep(flight, (now - last) / 1e9f, way)
                last = now
                lifts[id] = minOf(flight.lift, way)
            }
            away[id] = true
            flights.remove(id)
            onGone(id)
        }
    }

    /**
     * What became of the card of [id] that flew away: its tab was [closed] (the server is asked to),
     * or nothing was closed (the question turned down, the connection lost) and the card comes back.
     * A tab the server has not taken away in time comes back too.
     */
    fun flownAway(id: String, closed: Boolean, name: String = "") {
        if (id !in away) return
        if (!closed) {
            // The gap opens again before the card comes back into it.
            closing.remove(id)
            away.remove(id)
            putBack(id)
            return
        }
        // No tab is left to go back to: the overview closes, as a phone's recents do after the last app. Not
        // with the strip, which leads on to the other sessions, New Session and the empty session's New Tab (#247).
        if (closesAfterFlight(allTabs.all { it in away }, strip = stripShown())) close()
        // The cards after it close the gap now; the server's answer then moves nothing.
        closing[id] = Unit
        sentNames[id] = name
        awaitClose(id)
    }

    /** The session strip is drawn above the row ([showsStrip]): the overview has somewhere to go after its last tab (#247). */
    private fun stripShown(): Boolean = stage?.let { showsStrip(it.stripRoom, forMove) } == true

    /**
     * The session shown has no tab left while the overview is open (#247). [TabStage] has gone, so
     * this does what its [arranged] would do for an empty row: the tabs are forgotten now, and one
     * this overview closed is said to be closed here, not later under another session's row.
     */
    fun tabsGone() {
        for (id in arrangedTabs) forget(id)
        arrangedTabs = emptyList()
        arrangedRow = emptyList()
        tabs = emptyList()
        allTabs = emptyList()
    }

    /** The close of [id] is sent: a tab the server has not taken away in time comes back. */
    private fun awaitClose(id: String) {
        liftMotion.remove(id)?.cancel()
        liftMotion[id] = scope.launch {
            delay(CLOSE_WAIT_MS)
            if (id in allTabs) flownAway(id, closed = false)
        }
    }

    /**
     * The card of [id] flew away and nobody was asked, its tab holding only a shell: it leaves the
     * row now, and its close waits [UNDO_MS] for Undo before [send] sends it (false when it cannot,
     * the connection being down; the card then comes back). The last tab closes at once ([undoable]).
     */
    fun closeLater(id: String, name: String, send: () -> Boolean) {
        if (id !in away) return
        if (!undoable(asked = false, lastTab = allTabs.all { it in away }, inSight = isOpen, stays = stripShown())) {
            if (send()) flownAway(id, closed = true, name = name) else flownAway(id, closed = false)
            return
        }
        // One close waits at a time: the one before goes now.
        undo.hold(Waiting(id, name, send))?.let(::sendNow)
        closing[id] = Unit
        undoName = name
        liftMotion.remove(id)?.cancel()
        liftMotion[id] = scope.launch {
            delay(undoMillis)
            undo.takeIf { it.id == id }?.let(::sendNow)
        }
    }

    /** The close that waits for Undo goes now: another card closes, or the overview is left. */
    fun sendWaiting() {
        undo.take()?.let(::sendNow)
    }

    /** New Tab was tapped (#230): the session's tabs then and the session, until the new one is selected or the wait ends. */
    private var newTabFrom: Set<String>? = null
    private var newTabSession: String? = null
    private var newTabMotion: Job? = null
    /** New Tab waits for the server's new tab, and has waited long enough to show it. */
    var addingTab by mutableStateOf(false)
        private set

    /**
     * The new-tab card was tapped: the session gets a new tab, and once it is in the row the overview
     * opens on it, the card growing from where the new-tab card was ([arranged]).
     */
    fun newTab() {
        // Only while the overview is open or opening: a hold now would stop it closing halfway. Not on a row out of sight.
        if (!isOpen || awaitingRow || !conn.connected || newTabFrom != null) return
        val session = conn.state.session ?: return
        stayOpen()
        newTabFrom = allTabs.toSet()
        newTabSession = session
        conn.op("newWindow", "session" to session)
        newTabMotion?.cancel()
        newTabMotion = scope.launch {
            delay(NEW_TAB_BUSY_MS)
            addingTab = true
            delay(NEW_TAB_WAIT_MS - NEW_TAB_BUSY_MS)
            // No new tab came: the row stays as it is and nothing is picked.
            endNewTab()
        }
    }

    private fun endNewTab() {
        newTabFrom = null
        newTabSession = null
        addingTab = false
        newTabMotion?.cancel()
    }

    /**
     * A session's chip was tapped (#229): that session is shown and the overview stays open, its row
     * dropping in when the server has switched ([arranged]). A close waiting for Undo is sent first:
     * Undo is offered only for the row in sight.
     */
    fun showSession(id: String) {
        if (!isOpen || !conn.connected) return
        // The session shown, while the row of the one chosen on another remote's strip is awaited (#236): its row is wanted now.
        if (id == conn.state.session) return stopAwaiting()
        leaveRow()
        conn.selectSession(id)
        choose(id)
    }

    /** The strip's New Session: the server makes a session and shows it, and its row drops in. */
    fun newSession() {
        if (!isOpen || !conn.connected || newSessionWait?.isActive == true) return
        leaveRow()
        conn.op("newSession")
        newSessionWait = scope.launch { delay(NEW_TAB_WAIT_MS) }
    }

    /**
     * The row is left for another session. A card still flying lands now, in this session: its close
     * is the user's, and the tab is found only here. Then the close that waits for Undo is sent.
     */
    private fun leaveRow() {
        landFlights()
        sendWaiting()
        endNewTab()
        // The session chosen on another remote's strip is not awaited any more: the row of the one asked for now drops in.
        awaited = null
        arrivalWait?.cancel()
        choose(null)
        askedFrom = conn.state.session
        stayOpen()
    }

    /** No session's row is awaited any more (#236): the row the remote shows drops in, if it has one. */
    private fun stopAwaiting() {
        awaited = null
        arrivalWait?.cancel()
        choose(null)
        if (awaitingRow && arrangedRow.isNotEmpty()) {
            awaitingRow = false
            dropIn()
        }
    }

    /**
     * Another remote's chip was tapped (#236): this overview goes with its remote. As when another
     * session is shown ([leaveRow]), a card still flying lands and the close that waits for Undo is
     * sent. Returns what the overview of the next remote starts from.
     */
    fun leaveForRemote(session: String): Arrival {
        landFlights()
        sendWaiting()
        endNewTab()
        return Arrival(session, stripScroll.value)
    }

    private fun landFlights() {
        for ((id, onGone) in flights.toList()) {
            flights.remove(id)
            liftMotion.remove(id)?.cancel()
            away[id] = true
            onGone(id)
        }
    }

    /** [id]'s chip is the current one until the server shows that session, at most [NEW_TAB_WAIT_MS] ([chosen]). */
    private fun choose(id: String?) {
        chosen = id
        chosenWait?.cancel()
        if (id != null) chosenWait = scope.launch {
            delay(NEW_TAB_WAIT_MS)
            chosen = null
        }
    }

    /** The row stops; the overview goes on opening if it was: a tap that is no gesture has no release to settle it. */
    private fun stayOpen() {
        holdRow()
        open()
    }

    private fun dropIn() {
        dropMotion?.cancel()
        drop = 1f
        dropMotion = scope.launch {
            animate(1f, 0f, 0f, spring(dampingRatio = 1f, stiffness = 320f)) { value, _ -> drop = value.coerceIn(0f, 1f) }
            drop = 0f
        }
    }

    /** Undo: the waiting close is not sent, its gap opens again and its card comes back. */
    fun undoClose() {
        val waiting = undo.take() ?: return
        undoName = null
        flownAway(waiting.id, closed = false)
    }

    private fun sendNow(waiting: Waiting) {
        if (undo.waiting == null) undoName = null
        // Undo was offered under its name: once it is gone nothing more is said about it.
        if (waiting.send()) awaitClose(waiting.id) else flownAway(waiting.id, closed = false)
    }

    /** The tabs whose close is sent and not yet answered. */
    fun closingTabs(): Set<String> = closing.keys.toSet()

    /**
     * The place of the card the row is built around: the anchor's, or where TabStage drew it while its tab is closing.
     * A drag keeps the place it began with ([TabDrag.frameAnchor]) while the cards move between the places.
     */
    fun rowAnchor(): Int = tabDrag?.frameAnchor ?: index(anchor).takeIf { it >= 0 } ?: drawnAnchor

    /**
     * TabStage draws [row] in a row around the card at [anchor]; [tabs] are all the session's tabs.
     * When the row changed, the cards that stay slide from where they were drawn before ([before],
     * [drawnPlaces]) to their new places ([rowShifts]), and a tab that is gone leaves nothing behind.
     */
    fun arranged(tabs: List<String>, row: List<String>, anchor: Int, stage: Stage, before: Map<String, Offset>, session: String?) {
        for (id in arrangedTabs) if (id !in tabs) forget(id)
        arrangedTabs = tabs
        if (switchedSession(arrangedSession, askedFrom, session)) {
            awaitingRow = progress > 0f
            askedFrom = null
            newSessionWait?.cancel()
        }
        arrangedSession = session
        if (session != null && session == chosen) choose(null)
        if (awaitingRow) {
            // Another session's row (#229): no card slides from the last row's places. The server names
            // the session before its tabs come; once they have, the row drops in from above with its
            // selected tab in the middle.
            arrangedRow = row
            rowMotion?.cancel()
            scroll = 0f
            shiftMotion.values.forEach(Job::cancel)
            shiftMotion.clear()
            shifts.clear()
            if (rowArrived(awaited, session, row)) {
                awaitingRow = false
                awaited = null
                arrivalWait?.cancel()
                dropIn()
            }
            return
        }
        val last = arrangedRow
        if (row == last) return
        arrangedRow = row
        if (progress == 0f || last.isEmpty()) return
        // A grid card can go to another of its rows. The row's cards all stand at one height, which moves only as the
        // overview opens or closes: they slide across alone.
        val grid = stage.grid != null
        fun at(box: CardBox) = Offset(box.left, if (grid) box.top else 0f)
        val was = if (grid) before else before.mapValues { Offset(it.value.x, 0f) }
        val now = row.withIndex().associate { (index, id) -> id to at(cardBox(index, anchor, progress, scroll, stage)) } +
            (NEW_TAB_KEY to at(cardBox(row.size, anchor, progress, scroll, stage)))
        // A dragged card is where the finger holds it ([dragLeft]): only the others slide to open its place.
        for ((id, gap) in rowShifts(was, now)) if (id != tabDrag?.id) slide(id, gap)
        // The card at an end of the row went: the row rests on the card that is the end now, the new-tab card too.
        // Not the wheel's drum: the rail turns it, and a throw would fight the finger.
        if (!wheel && scroll !in scrollBounds(rowPlaces(row.size), anchor, stage)) throwRow(0f)
    }

    private fun slide(id: String, gap: Offset) {
        shiftMotion.remove(id)?.cancel()
        shifts[id] = gap
        shiftMotion[id] = scope.launch {
            animate(Offset.VectorConverter, gap, Offset.Zero, Offset.Zero, spring(dampingRatio = 1f, stiffness = 320f)) { value, _ -> shifts[id] = value }
            shifts.remove(id)
        }
    }

    private fun forget(id: String) {
        // Its tab left the session's list (another session shown, or closed elsewhere) while its close waited: send it.
        undo.takeIf { it.id == id }?.let { waiting ->
            undoName = null
            waiting.send()
        }
        liftMotion.remove(id)?.cancel()
        flights.remove(id)
        shiftMotion.remove(id)?.cancel()
        lifts.remove(id)
        liftSides.remove(id)
        shifts.remove(id)
        away.remove(id)
        drawn.remove(id)
        // A tab this overview closed is gone: say so, to the eye and to a screen reader.
        closing.remove(id)
        sentNames.remove(id)?.let { note(closedTabNote(it)) }
    }

    private fun note(text: String) {
        closedNote = text
        noteMotion?.cancel()
        noteMotion = scope.launch {
            delay(NOTE_MS)
            closedNote = null
        }
    }

    /** Two fingers on the pane [block], whose text scale was [startScale] when they began. */
    fun pinchBy(block: String, startScale: Float, step: PinchStep) {
        conn.scale(block, step.scale, live = true)
        if (pinch == null) {
            if (step.progress == 0f) return
            pinch = PinchFrom(block, startScale)
            pinching = true
            hold()
        }
        follow(step.progress)
    }

    /**
     * One step of a pinch on [block] of [tab] that can take [toward] (#140): the fingers' [step] from text scale [startScale].
     * While the intent is [on] the [preview] comes in and the text goes back to [startScale]; off, both go back and the
     * text follows the fingers again. [reach]: how far the preview takes the pane ([previewReach]).
     */
    fun pinchToward(tab: String, block: String, toward: LayoutPinch, startScale: Float, step: PinchStep, on: Boolean, reach: Float) {
        previewFinger = step.scale
        if (on) {
            previewReach = reach
            if (preview == null) preview = LayoutPreview(tab, block, toward)
        }
        val goal = if (on) 1f else 0f
        if (preview != null && previewGoal != goal) {
            previewGoal = goal
            previewMotion?.cancel()
            previewMotion = scope.launch {
                animate(previewShare, goal, 0f, spring(dampingRatio = 1f, stiffness = PREVIEW_STIFFNESS)) { value, _ ->
                    previewShare = value
                    // The fingers may rest while it moves: the text follows the share, at the fingers' last size.
                    conn.scale(block, previewTextScale(previewFinger, startScale, value), live = true)
                }
                previewShare = goal
                if (goal == 0f) preview = null
            }
        }
        pinchBy(block, startScale, step.copy(scale = previewTextScale(step.scale, startScale, previewShare)))
    }

    /**
     * The fingers of a pinch that could take a step of the ladder lifted, or became another gesture: [op] is the step it
     * sends, or none ([liftOp]). Sent, the text has its size from before the pinch ([startScale]) and the [preview] goes
     * on to the pane's other place, where it stays until the server has taken the step. Not sent, the preview goes at
     * once and the text keeps the size it has, as after any pinch.
     */
    fun endPreview(startScale: Float, op: String?) {
        previewMotion?.cancel()
        previewGoal = 0f
        val look = preview
        if (op == null || look == null) {
            preview = null
            previewShare = 0f
            return
        }
        conn.scale(look.block, startScale, live = true)
        conn.op(op, "block" to look.block)
        previewShare = 1f
        previewMotion = scope.launch {
            animate(previewReach, 1f, 0f, spring(dampingRatio = 1f, stiffness = PREVIEW_STIFFNESS)) { value, _ -> previewReach = value }
            // A step the server does not take (the pane went, the tab changed) leaves the tab as it was.
            withTimeoutOrNull(PREVIEW_WAIT_MS) {
                snapshotFlow { conn.state.windows.firstOrNull { it.id == look.tab }?.zoomed }
                    .first { if (look.toward == LayoutPinch.Zoom) it == look.block else it == null }
            }
            preview = null
            previewShare = 0f
        }
    }

    /**
     * A fast pinch's [preview] goes at once, with nothing sent: the pinch was cut off, or a new pinch begins while the last
     * one's still waits for the server ([endPreview]), whose share would hold the new one's text at its size from before.
     */
    fun dropPreview() {
        previewMotion?.cancel()
        previewGoal = 0f
        preview = null
        previewShare = 0f
    }

    /**
     * The pinch's fingers lifted at [velocity], or became another gesture. A pinch that only
     * changed the text size is a use of the pane: its terminal follows this device and is
     * fitted again. One that opened the overview settles, and its text size with it.
     */
    fun pinchEnd(block: String, startScale: Float, velocity: Float) {
        // The overview's own pinch settles once the overview comes to rest ([endPinch]); any other is over now.
        if (pinch?.block != block) conn.settleScale(block)
        if (pinch == null) {
            if (conn.scaleOf(block) != startScale) used(block)
            return
        }
        pinching = false
        // Back in the workspace the text keeps the size the fingers left it at.
        if (progress == 0f) endPinch() else release(velocity)
    }

    /**
     * The overview a pinch opened has come to rest. At 1 the text has its size from before the
     * pinch; back at 0 it keeps the smallest size, and the pane, shown again, is fitted to it by
     * the core (a pane that becomes shown is taken and fitted as a first surface is).
     */
    private fun endPinch() {
        val from = pinch ?: return
        if (pinching) return
        pinch = null
        conn.settleScale(from.block)
        // A text size the fingers changed is a use of that pane, as after every pinch.
        if (conn.scaleOf(from.block) != from.scale) returns.used(from.block)
    }

    private fun used(block: String) {
        conn.touched(block)
        conn.fit(block)
    }

    private fun index(id: String?) = tabs.indexOf(id)

    private fun rebase(to: String) {
        val now = stage
        // From where the row was drawn around: a closing anchor has handed its place on ([rowAnchor]).
        val from = rowAnchor()
        // A drag keeps the row on the place it began with; its end builds the row around the anchor again ([putDown]).
        if (now != null && from >= 0 && index(to) >= 0 && tabDrag == null) scroll = rebasedScroll(scroll, from, index(to), progress, now)
        anchor = to
    }

    /**
     * A card was tapped: its tab is selected and grows into the workspace from where it stands.
     * Without the connection only the tab shown now can be gone back to; another card stays in
     * the row ([selectsTab]). [select]: false when the server selects the tab by itself, as a pane
     * move's focus does (#217); the overview then only closes onto it.
     */
    fun pick(id: String, select: Boolean = true) {
        // A card that flew away is closing: it is no tab to go to. Nor is a card of a row out of sight (#236).
        if (awaitingRow || id in away || !selectsTab(id, anchor, conn.connected)) return
        hold()
        rebase(id)
        onPick(id)
        // Without the connection the shown tab's own card closes the overview and asks for nothing.
        if (conn.connected && select) conn.op("selectWindow", "window" to id)
        close()
    }

    /** The session's selected tab is [id] (the server's word, or a tapped card that was not selected in time). */
    fun selected(id: String) {
        lastConfirmed = id
        // The server selected the tab New Tab asked for, now in the new-tab card's place: the overview
        // closes into it from there. The server selected it already, so nothing is sent.
        if (opensNewTab(newTabFrom, newTabSession, conn.state.session, id) && id in tabs) {
            endNewTab()
            hold()
            rebase(id)
            onPick(id)
            close()
            return
        }
        if (id == anchor) return
        resetPage()
        val first = anchor == null
        rebase(id)
        // Another device's selection does not move the row from under a dragged card.
        if (!first && progress > 0f && tabDrag == null && !wheel) throwRow(0f, to = 0f)
    }

    /** A finger moved the row by [dx] px. */
    fun scrollBy(dx: Float) {
        val now = stage ?: return
        moveRow((scroll + dx).coerceIn(scrollBounds(rowPlaces(tabs.size), rowAnchor(), now)))
    }

    /**
     * The previews are stills ([PaneStill]): a finger or a throw changed the row's scroll within the last [ROW_QUIET_MS].
     * Read in composition, it changes as a motion starts and as it ends. Unlike [rowMoving] it counts a finger's moves too.
     */
    var rowStill by mutableStateOf(false)
        private set
    private val rowClock = RowMotionClock()
    private var rowQuiet: Job? = null

    /** A finger or a throw moves the row to [to]; a scroll that does not change is no motion. */
    private fun moveRow(to: Float) {
        if (to == scroll) return
        scroll = to
        if (!rowClock.changed(android.os.SystemClock.uptimeMillis())) return
        rowStill = true
        rowQuiet?.cancel()
        rowQuiet = scope.launch {
            while (true) {
                val left = rowClock.quietIn(android.os.SystemClock.uptimeMillis())
                if (left <= 0L) break
                delay(left)
            }
            rowStill = false
        }
    }

    fun rowBounds(): ClosedFloatingPointRange<Float> = stage?.let {
        scrollBounds(rowPlaces(tabs.size), rowAnchor(), it)
    } ?: (0f..0f)

    /**
     * A screen reader scrolls by one card so every tab can be reached in order, the grid by one of its rows ([readerStep]).
     * [delta]: along the way the cards scroll.
     */
    fun scrollCards(delta: Float): Boolean {
        val now = stage ?: return false
        if (!isOpen || delta == 0f) return false
        val from = rowAnchor()
        val places = rowPlaces(tabs.size)
        // The new-tab card is a place too: the last step reaches it.
        val next = readerStep(scroll, forward = delta > 0f, places, from, progress, now) ?: return false
        throwRow(0f, to = cardScroll(next, from, now))
        return true
    }

    /** The finger let the row go at [velocity] px per second: it rests on a card. */
    fun throwRow(velocity: Float, to: Float? = null) {
        val now = stage ?: return
        val rest = to ?: restingScroll(scroll, velocity, rowPlaces(tabs.size), rowAnchor(), now)
        rowMotion?.cancel()
        rowMotion = scope.launch {
            // The grid is at rest within half a px: a finger that lands after a fling is a tap again at once ([throwSpring]).
            animate(scroll, rest, velocity, throwSpring(now)) { value, _ -> moveRow(value) }
        }
    }

    /**
     * The panes are not drawn any more (behind the folded sidebar, no tab left), so a gesture on
     * them has no end: a pinch gives its pane the text size back, and the overview is where it
     * was going. [covered]: the app covers the panes and they are not gone; [ReturnMark.interrupted]
     * then keeps the way back to the tab the overview was opened from.
     */
    fun interrupted(covered: Boolean) {
        resetPage()
        hold()
        pinch?.let { conn.scale(it.block, it.scale) }
        pinch = null
        pinching = false
        // A fast pinch's preview sends nothing: its text keeps the size it has, as any pinch's that was cut off.
        dropPreview()
        sendWaiting()
        // A dragged card has no row to drop on: it goes back with nothing sent.
        cancelTabDrag()
        // A card that a finger held is put down; one that flew away waits for its tab to close.
        for (id in lifts.keys.toList()) if (id !in away) {
            liftMotion.remove(id)?.cancel()
            lifts.remove(id)
            liftSides.remove(id)
        }
        returns.interrupted(covered)
        // A pane being moved has no place to go without the panes: the move ends, and the overview it opened closes.
        if (paneMove != null) {
            endPaneMove()
            goal = 0f
        }
        moveTo(goal)
    }

    // Last, once every property has its first value.
    init {
        // Opened on another remote's strip (#236): open already, and the chosen session's row drops in when it comes.
        if (arrival != null) {
            goal = 1f
            progress = 1f
            drop = 1f
            awaitingRow = true
            awaited = arrival.session
            chosen = arrival.session
            // A session that does not come (closed meanwhile) holds no row back: the row shown drops in.
            arrivalWait = scope.launch {
                delay(NEW_TAB_WAIT_MS)
                arrivalWait = null
                stopAwaiting()
            }
        }
    }
}

/** What an overview open on the strip hands to the next remote's (#236): the session chosen there, and where the strip was scrolled. */
internal data class Arrival(val session: String, val stripScroll: Int)

/** Holds the [Arrival] of a tap on another remote's chip until that remote's overview is made. */
internal class OverviewCarry {
    var next: Arrival? = null
    fun take(): Arrival? = next.also { next = null }
}

@Composable
internal fun rememberOverviewState(conn: HostConnection, carry: OverviewCarry, onPick: (String) -> Unit): OverviewState {
    val currentOnPick by rememberUpdatedState(onPick)
    val scope = rememberCoroutineScope()
    return remember(conn) { OverviewState(conn, scope, carry.take()) { currentOnPick(it) } }
}

/**
 * The pane area: every tab of the session, each one a container that [overview] moves and
 * scales as a whole. The panes are the same views in the workspace and in the overview; only
 * the tab that fills the pane area has shown panes ([tabRoles]). Without [controls] no split draws
 * its drag handle ([WindowPanes]). [handles]: the panes' handles, drawn only on the shown tab and not while the overview shows. [haptics]: the Haptic feedback setting, which a pane's long press follows.
 * [strip]: what the session strip above the row shows and does ([SessionStrip]).
 * [grid]: the overview is a grid of cards scrolled up and down (the Overview setting, #281), not the row. [fontSize]: the
 * terminal's font size, by which the grid has as many columns as keep the text on a card readable ([gridColumns]).
 * [onDismiss]: a card was lifted away and is out of sight, so its tab is to close (#216).
 * [onMoved]: a pane was moved across the overview ([PaneMoves.commit]), with the tabs as they were before.
 */
@Composable
internal fun TabStage(
    conn: HostConnection, state: UiState, selected: WindowInfo, overview: OverviewState, focused: String?, gap: Dp, radius: Dp,
    input: TerminalInputView, controls: Boolean, haptics: Boolean, handles: PaneHandles?, strip: StripSource, onFocus: (String) -> Unit,
    onDismiss: (String) -> Unit, onMoved: (block: String, place: MovePlace, before: List<WindowInfo>) -> Unit,
    grid: Boolean, fontSize: Float,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val dismiss by rememberUpdatedState(onDismiss)
    // Undo holds a button: a person who needs more time to reach it gets it from the accessibility setting.
    val accessibility = LocalAccessibilityManager.current
    overview.undoMillis = accessibility?.calculateRecommendedTimeoutMillis(UNDO_MS, containsText = true, containsControls = true) ?: UNDO_MS
    overview.pickupRaise = with(density) { PickupRaise.toPx() }
    val haptic by rememberUpdatedState(haptics)
    // The gesture outlives a composition: it reads what is current when a card is let go.
    val gestureEnds = remember {
        GestureEnds(
            dismiss = { dismiss(it) },
            flies = { if (haptic) view.feel(Feel.Confirm) },
            crossed = { closes -> if (haptic) view.feel(if (closes) Feel.ThresholdOn else Feel.ThresholdOff) },
            pickedUp = { if (haptic) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
            feel = { if (haptic) view.feel(it) },
        )
    }
    // A pane let go on a place that takes it is felt as done, as a dropped card that moved its tab is (#309).
    val paneMoves = rememberPaneMoves(conn, overview, gap) { block, place, before ->
        if (haptic) view.feel(Feel.Confirm)
        onMoved(block, place, before)
    }
    val backdrop = VeloTheme.colors.backdrop
    // The selected tab's edge takes the colour of the focused pane's edge, which it turns into.
    val selectedEdge = VeloTheme.selectedCardEdge
    // A fast pinch's preview: the pane's outline in the focused pane's colour, and the rest under the app's scrim.
    val previewEdge = VeloTheme.paneFocus
    val scrim = VeloTheme.colors.scrim
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sizes = VeloTheme.sizes
    val serverTabs = state.windows.map { it.id }
    // A tab this overview moved stays where it was put until the server's order agrees ([OverviewState.orderHold]).
    val shown = heldOrder(serverTabs, overview.orderHold) ?: serverTabs
    // A tab whose close is sent has left the row already ([rowSlots]): its gap closes before the server answers.
    val closing = overview.closingTabs()
    val shownRow = shown.filter { it !in closing }
    // A dragged card (#218) opens the place it would drop on: the row is drawn as if its tab stood there already. That is
    // only drawn; the overview acts on the order shown, in which the drop moves the tab ([OverviewState.dropTab]).
    val drag = overview.tabDrag
    // Read as the place, which changes as the card comes over another, not as the finger, which moves on each frame (#373).
    val dragPlace = overview.shownDragPlace()
    val dragMove = drag?.let { d -> dragPlace?.let { tabMoveTo(shownRow, d.id, it) } }
    val tabs = dragMove?.let { movedTabs(shown, it) } ?: shown
    val row = tabs.filter { it !in closing }
    val slots = rowSlots(tabs, closing)
    // The server would not hear the move, or the tab went: the card goes back with nothing sent.
    val dragLost = drag != null && (!conn.connected || drag.id !in serverTabs)
    LaunchedEffect(overview, dragLost) { if (dragLost) overview.cancelTabDrag() }
    // Where the cards were drawn before this row, taken before any card is laid out for it.
    val drawnBefore = remember(overview, row) { overview.drawnPlaces() }
    // The pane a finger went down on: a second finger makes it the pinch's pane.
    val finger = remember { arrayOfNulls<String>(1) }
    DisposableEffect(overview) { onDispose { overview.interrupted(covered = conn.cover.covering) } }
    LaunchedEffect(overview, serverTabs) { overview.serverOrder(serverTabs) }
    LaunchedEffect(overview, selected.id) { overview.selected(selected.id) }
    val pending by remember(overview) { derivedStateOf { overview.anchor.takeUnless { it == overview.lastConfirmed } } }
    LaunchedEffect(overview, pending, selected.id) {
        if (pending != null && pending != selected.id) {
            delay(SELECT_WAIT_MS)
            overview.selected(selected.id)
        }
    }
    // Read as whether the overview shows, not as its progress, which changes on each frame of its motion (#347).
    val showing = overview.showing
    // The cards' names are the tabs' titles. [state] is without them ([HostConnection.steady]): they are read with
    // the titles only while the cards are named, on the overview, the rail's wheel or a pane move.
    val named = if (showing || overview.wheel || overview.paneMove != null) conn.state else state
    val displayId by remember(overview, selected.id) {
        derivedStateOf { displayAnchor(selected.id, overview.lastConfirmed, overview.anchor, rowActive = overview.showing || overview.pinching) }
    }
    // The new-tab card is a place of the row too (#230): a single tab still has a row to scroll.
    val rowSemantics = if (showing && !overview.wheel) Modifier.semantics {
        val range = ScrollAxisRange(
            value = {
                val bounds = overview.rowBounds()
                overview.scroll.coerceIn(bounds) - bounds.start
            },
            maxValue = { overview.rowBounds().let { it.endInclusive - it.start } },
        )
        // The grid scrolls up and down, the row sideways.
        if (grid) verticalScrollAxisRange = range else horizontalScrollAxisRange = range
        scrollBy { x, y -> overview.scrollCards(if (grid) y else x) }
    } else Modifier
    // The note under the row lies over the stage, beside it: a tap on its Undo is not a tap beside the cards.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The strip's room, as the stage takes it: known here in the first frame, before the stage is laid out
    // (an overview that opens at once has no motion to draw the strip again, #236).
    val stripRoom = with(density) { stripRoomFor(constraints.maxHeight.toFloat(), LabelRoom.toPx(), StripRoom.toPx()) }
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().then(rowSemantics)
            // The rail's wheel holds the finger: its drum takes no touch of its own.
            .then(if (overview.wheel) Modifier else Modifier.pointerInput(overview, conn) {
                // The gestures end only when they are cut off (the wheel takes the stage, or it leaves the screen):
                // a pinch then has no end of its own, and its pane draws its text at the size it has (#334).
                // A fast pinch's preview goes with nothing sent ([OverviewState.dropPreview]).
                try { stageGestures(overview, conn, finger, gestureEnds, paneMoves) } finally {
                    overview.dropPreview()
                    conn.settleScales()
                }
            })
            .onGloballyPositioned { overview.stageBox = it },
    ) {
        val stage = with(density) {
            val width = constraints.maxWidth.toFloat()
            // The rail's wheel: a drum of cards with their names beside them, no strip, note or new-tab card.
            if (overview.wheel) return@with Stage(width, constraints.maxHeight.toFloat(), CardGap.toPx(), CardGap.toPx(), 0f,
                drum = drumSpec(width, constraints.maxHeight.toFloat(), DrumLabelMin.toPx(), DrumLabelMax.toPx(), DrumLabelGap.toPx(),
                    DrumMargin.toPx(), DrumEye.toPx()))
            // The grid's places are the row's: the tabs and the new-tab card. The strip's room is the row's too ([stripRoomFor]).
            val cells = if (grid) GridSpec(gridColumns(width, fontSize, GridPad.toPx(), GridGap.toPx()), GridPad.toPx(), GridGap.toPx(),
                GridTop.toPx(), rowPlaces(row.size)) else null
            Stage(width, constraints.maxHeight.toFloat(), CardGap.toPx(), CardGap.toPx(), (if (grid) GridLabelRoom else LabelRoom).toPx(),
                stripRoomFor(constraints.maxHeight.toFloat(), LabelRoom.toPx(), StripRoom.toPx()),
                noteRoom = noteRoom(sizes).toPx(), peek = RowPeek.toPx(), rowPad = RowPad.toPx(), grid = cells)
        }
        overview.stage = stage
        overview.tabs = shownRow
        overview.allTabs = shown
        // Another pane area (the keyboard, a fold): the row rests on a card of the new size. The grid, whose columns may have
        // changed, rests with the anchor's card centred. A tab more or fewer is no other pane area.
        LaunchedEffect(stage.copy(grid = stage.grid?.copy(places = 0))) {
            // The rail turns the wheel's drum: nothing else moves it.
            if (overview.progress > 0f && !overview.wheel) overview.throwRow(0f, to = if (stage.grid != null) 0f else null)
        }
        // A finger held at an end of the pane area scrolls the cards under a dragged card, as under a moved pane (#217): at a
        // side in the row, at the top under the strip's room or the bottom in the grid ([edgeScrollAt]); a card held up in
        // the strip's room scrolls up too. The place the card would drop on moves with them ([OverviewState.dragPlace]).
        val edgeBand = with(density) { EdgeBand.toPx() }
        val edgeStep = with(density) { EdgeStep.toPx() }
        LaunchedEffect(overview, stage, edgeBand, edgeStep) {
            followEdges({ overview.tabDrag?.finger }, stage, edgeBand, edgeStep, roomScrolls = true, overview::scrollBy)
        }
        val anchorId = if (displayId != selected.id && displayId in tabs) displayId else selected.id
        // A drag keeps the row built around the place it began with: the places stay put while the cards move between them.
        val anchor = drag?.frameAnchor ?: rowAnchor(tabs, closing, anchorId)
        overview.drawnAnchor = anchor
        // Before this frame is drawn: cards that stay slide from where they were to their new places.
        SideEffect { overview.arranged(tabs, row, anchor, stage, drawnBefore, state.session) }
        // The places that keep surfaces, read as such: a motion that keeps them runs no recomposition here (#347).
        val kept = overview.keptAt(row.size, anchor, stage)
        // While a pane is moved no tab is shown, from its first frame: nothing is sized for the lifted layout.
        val roles = tabRoles(row, selected.id, anchor, kept, closed = !showing, overview.pinching || overview.paneMove != null)
        // Read once for each change of the shown tab, before its panes hear of it.
        val session = state.session
        val shownWindow = row.getOrNull(roles.indexOf(PaneRole.Shown))?.let { id -> state.windows.firstOrNull { it.id == id } }
        val shown = shownWindow?.let { w -> session?.let { shownTab(it, w) } }
        val returning = remember(overview, shown, session) { overview.returns.step(shown, session) }
        DisposableEffect(overview, session, tabs, stage, selected.id) { onDispose { overview.resetPage() } }
        state.windows.forEach { w ->
            key(w.id) {
                // Its place in the row; a closing tab's card is out of sight in the place of the card after it.
                val slot = slots[w.id] ?: 0
                val cardSemantics = if (overview.hidden(w.id) || overview.wheel) {
                    // Out of sight, closing, or a card of the rail's wheel, which the rail's slider names: nothing to read or act on.
                    Modifier.clearAndSetSemantics {}
                } else if (overview.paneMove != null) {
                    // While a pane is moved the card only names its tab, and says when the move closes it ([sourceCardNote]):
                    // its panes are the places ([PaneMoveOverlay]).
                    val note = overview.paneMove?.let { sourceCardNote(w, it.block, it.sourceTab) }
                    Modifier.clearAndSetSemantics {
                        contentDescription = tabTitle(named, w)
                        if (note != null) stateDescription = note
                    }
                } else if (showing) Modifier.clearAndSetSemantics {
                    contentDescription = tabTitle(named, w)
                    // Compose suppresses Android ACTION_CLICK for a selected Role.Tab.
                    // This card can still be opened to close the overview on the same tab.
                    stateDescription = if (w.id == selected.id) "Selected" else "Not selected"
                    role = Role.Button
                    onClick {
                        if (!overview.isOpen) false else {
                            overview.pick(w.id)
                            true
                        }
                    }
                    // What lifting the card away does, for those who do not swipe.
                    customActions = listOf(CustomAccessibilityAction("Close tab") { overview.dismiss(w.id, gestureEnds.dismiss) }) +
                        tabCardMoves(shownRow, w.id, conn.connected).map { (text, move) -> CustomAccessibilityAction(text) { overview.moveTabAloud(move, tabTitle(named, w)) } }
                } else Modifier
                // A grid card aside ([OverviewState.aside]) rides over its neighbours too, so one swiped towards them passes
                // over them. Derived, so that only its start and end recompose, not each step of the finger.
                val aside by remember(overview) { derivedStateOf { overview.aside(w.id) } }
                val role = roles.getOrElse(row.indexOf(w.id)) { PaneRole.Detached }
                // While a pane is moved its own tab is drawn without it, and floating panes are the overlay's ([cardWindow]).
                val drawn = overview.paneMove?.let { cardWindow(w, it.block, it.sourceTab) } ?: w
                // A dragged card rides over the others; it stays under the finger, as it was held ([OverviewState.dragLeft],
                // and in the grid [OverviewState.dragTop]).
                Box(Modifier.fillMaxSize().then(if (w.id == drag?.id || aside) Modifier.zIndex(1f) else Modifier)
                    // On the drum the card in front is drawn over those behind it, read while laying out as the wheel turns.
                    .then(if (stage.drum != null) Modifier.layout { measurable, constraints ->
                        val card = measurable.measure(constraints)
                        layout(card.width, card.height) { card.place(0, 0, zIndex = -abs(drumOffset(slot, anchor, overview.scroll))) }
                    } else Modifier)
                    .graphicsLayer {
                    val t = tabTransform(slot, anchor, overview.progress, overview.scroll, stage)
                    // A swipe between the workspace's pages ([OverviewState.dragPage]) moves the tabs' places alone.
                    val at = overview.drawnAt(w.id, Offset(t.dx + overview.pageOffset, t.dy))
                    overview.drew(w.id, at)
                    val shown = overview.shownAt(w.id, at)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = t.scale
                    // A drum card is pressed in height as it turns away ([TabTransform.squash]); its dimming is drawn over it.
                    scaleY = t.scale * t.squash
                    translationX = shown.x
                    translationY = shown.y
                    alpha = if (overview.hidden(w.id)) 0f else 1f - overview.drop
                }.then(cardSemantics)
                    // Over a drum card's panes, in its own layer: black for the light it does not show ([TabTransform.alpha]),
                    // which dims a pane's surface where a layer's alpha would not, then its edge, the front one's in the
                    // selected tab's colour, which the focused pane's edge turns into.
                    .then(if (stage.drum != null) Modifier.drawWithContent {
                        val p = overview.progress
                        if (p <= 0f) {
                            drawContent()
                            return@drawWithContent
                        }
                        val t = tabTransform(slot, anchor, p, overview.scroll, stage)
                        // A card the drum has turned fully away puts no pixel on the glass: no content, no edge — the rims
                        // of a drawn card and its cover do not cancel, an undrawn card leaves none. Only a card that keeps
                        // a pane's surface is covered opaque instead, for the surface is drawn with no layer and a skipped
                        // card would leave it bare.
                        if (t.alpha <= 0f) {
                            if (role != PaneRole.Detached && tabBlocks(drawn).isNotEmpty())
                                drawRoundRect(backdrop.surface, cornerRadius = CornerRadius(radius.toPx()))
                            return@drawWithContent
                        }
                        drawContent()
                        val corner = CornerRadius(radius.toPx())
                        if (t.alpha < 1f) drawRoundRect(backdrop.surface.copy(alpha = 1f - t.alpha), cornerRadius = corner)
                        // The wheel's front, which a let-go wheel picks: past the halfway line by a little, the card behind.
                        val front = overview.wheelFront.let { if (it >= 0) slot == it else abs(drumOffset(slot, anchor, overview.scroll)) < 0.5f }
                        val width = (if (front) sizes.paneFocus else sizes.edge).toPx() / t.scale
                        drawRoundRect((if (front) selectedEdge else backdrop.glassEdge).copy(alpha = p * t.alpha),
                            Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
                            CornerRadius(radius.toPx() - width / 2), Stroke(width))
                    } else Modifier)) {
                    // A tab far from the one in view keeps no surface: its card is a plain one until it comes near.
                    if (role == PaneRole.Detached || tabBlocks(drawn).isEmpty()) {
                        Box(Modifier.fillMaxSize().clip(rounded(radius)).background(backdrop.glass))
                    } else {
                        // Its panes are stills while the row moves, as large as the card is drawn ([PaneStill]).
                        val still = remember(overview, w.id, slot, anchor, stage) {
                            PaneStill({ overview.rowStill }, { tabTransform(slot, anchor, overview.progress, overview.scroll, stage).scale },
                                { inView(overview.placed(w.id, cardBox(slot, anchor, overview.progress, overview.scroll, stage)), stage) })
                        }
                        CompositionLocalProvider(LocalPaneStill provides still) {
                            WindowPanes(conn, drawn, focused, gap, radius, input, preview = role == PaneRole.Preview, controls = controls, haptics = haptics, handles = handles.takeIf { controls && role == PaneRole.Shown && !showing }, returning = returning,
                                focusMark = { 1f - overview.progress }, onFinger = { finger[0] = it }, onFocus = onFocus)
                        }
                    }
                }
            }
        }
        // The new-tab card (#230): one more place at the end of the row, drawn and tapped as a card. A pane move's
        // overview draws the move's New Tab card there instead ([forMove], [PaneMoveOverlay]).
        val online = state.status == "connected"
        val newTabCard = showing && !overview.forMove && !overview.wheel
        if (newTabCard) {
            val place = row.size
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val t = tabTransform(place, anchor, overview.progress, overview.scroll, stage)
                    val at = overview.drawnAt(NEW_TAB_KEY, Offset(t.dx, t.dy))
                    overview.drew(NEW_TAB_KEY, at)
                    val shown = overview.shownAt(NEW_TAB_KEY, at)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = t.scale
                    scaleY = t.scale
                    translationX = shown.x
                    translationY = shown.y
                    alpha = overview.progress * (1f - overview.drop) * if (online) 1f else OFFLINE_ALPHA
                }.clearAndSetSemantics {
                    contentDescription = "New Tab"
                    role = Role.Button
                    if (!online) disabled()
                    onClick {
                        if (!overview.isOpen) false else {
                            overview.newTab()
                            true
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (overview.addingTab) CircularProgressIndicator(Modifier.size(NewTabMark), color = backdrop.secondary, strokeWidth = 6.dp)
                else Icon(Icons.Default.Add, null, Modifier.size(NewTabMark), tint = backdrop.secondary)
            }
        }
        // Every card has an edge, the selected tab's in the accent colour. Drawn over the tabs:
        // a pane's surface lies behind the window and shows through whatever is under it.
        fun DrawScope.cardEdge(index: Int, id: String) {
            val p = overview.progress
            val box = overview.placed(id, cardBox(index, anchor, p, overview.scroll, stage))
            if (!inView(box, stage) || overview.hidden(id)) return
            // On the tab's own edge, where the focused pane's mark was: one edge fades into the other.
            val mine = id == selected.id
            val width = (if (mine) sizes.paneFocus else sizes.edge).toPx()
            drawRoundRect(
                (if (mine) selectedEdge else backdrop.glassEdge).copy(alpha = p * (1f - overview.drop)), Offset(box.left + width / 2, box.top + width / 2),
                Size(box.right - box.left - width, box.bottom - box.top - width),
                CornerRadius(radius.toPx() * (box.right - box.left) / stage.width - width / 2), Stroke(width),
            )
        }
        // The cards that ride over the others ([zIndex]): the dragged one, and the grid's cards aside.
        fun rides(id: String) = id == drag?.id || overview.aside(id)
        // A fast pinch's preview (#140): the pane's outline on its way to its other place, and the rest of the pane area
        // dimmed. Drawn in the window over the panes' surfaces, which lie behind it, so no pane is laid out or scaled for it.
        fun DrawScope.layoutPreview(look: LayoutPreview) {
            val share = overview.previewShare
            if (share <= 0f) return
            val window = state.windows.firstOrNull { it.id == look.tab } ?: return
            val split = splitPlace(window, look.block, stage.width, stage.height, gap.toPx(), rtl) ?: return
            val reach = overview.previewReach * share
            val box = previewBox(look.toward, split, stage.width, stage.height, reach)
            val corner = CornerRadius(radius.toPx())
            val outline = Path().apply { addRoundRect(RoundRect(box.left, box.top, box.right, box.bottom, corner)) }
            clipPath(outline, ClipOp.Difference) { drawRect(scrim.copy(alpha = scrim.alpha * previewDim(reach))) }
            val width = sizes.paneFocus.toPx()
            drawRoundRect(previewEdge.copy(alpha = previewEdge.alpha * share), Offset(box.left + width / 2, box.top + width / 2),
                Size(box.right - box.left - width, box.bottom - box.top - width), CornerRadius(radius.toPx() - width / 2), Stroke(width))
        }
        Box(Modifier.fillMaxSize().drawBehind {
            val p = overview.progress
            // Only the tab in the workspace takes a step of the ladder.
            if (p == 0f) overview.preview?.takeIf { it.tab == selected.id }?.let { layoutPreview(it) }
            // The cards that ride over this layer have their edges drawn over them, below.
            // The drum's cards lean: a flat edge would not follow them.
            if (p > 0f && stage.drum == null) for ((index, id) in row.withIndex()) if (!rides(id)) cardEdge(index, id)
            // The new-tab card's edge is dashed: a place for a tab, not a tab.
            if (p > 0f && newTabCard) {
                val box = overview.placed(NEW_TAB_KEY, cardBox(row.size, anchor, p, overview.scroll, stage))
                if (inView(box, stage)) {
                    val width = sizes.edge.toPx() * 1.5f
                    val dash = 10.dp.toPx()
                    drawRoundRect(
                        backdrop.secondary.copy(alpha = p * (1f - overview.drop) * if (online) 1f else OFFLINE_ALPHA), Offset(box.left + width / 2, box.top + width / 2),
                        Size(box.right - box.left - width, box.bottom - box.top - width),
                        CornerRadius(radius.toPx() * (box.right - box.left) / stage.width - width / 2),
                        style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * .7f))),
                    )
                }
            }
        })
        // The edges of the cards that ride over the others, drawn after the cards themselves.
        if (drag != null || stage.grid != null) Box(Modifier.fillMaxSize().zIndex(1f).drawBehind {
            if (overview.progress > 0f) for ((index, id) in row.withIndex()) if (rides(id)) cardEdge(index, id)
        })
        if (showing) {
            val drum = stage.drum
            // The drum's front card's name is the bright one; the row's and the grid's, the selected tab's.
            val front by remember(overview, stage, anchor, row.size) {
                derivedStateOf { overview.wheelFront.takeIf { it >= 0 } ?: centredTab(row.size, anchor, overview.progress, overview.scroll, stage) }
            }
            state.windows.forEach { w ->
                key(w.id) {
                    val slot = slots[w.id] ?: 0
                    Box(
                        // Placed while laying out: the name follows its card without a recomposition.
                        Modifier.layout { measurable, constraints ->
                            val box = overview.placed(w.id, cardBox(slot, anchor, overview.progress, overview.scroll, stage))
                            // On the drum the name stands to the card's left, level with its middle.
                            if (drum != null) {
                                val at = drumLabelAt(box, drum)
                                val name = measurable.measure(Constraints.fixedWidth(drum.labelWidth.roundToInt()))
                                return@layout layout(constraints.maxWidth, constraints.maxHeight) {
                                    name.place(at.x.roundToInt(), (at.y - name.height / 2f).roundToInt())
                                }
                            }
                            val span = labelSpan(box, stage)
                            val width = span?.let { (it.endInclusive - it.start).roundToInt() } ?: 0
                            val name = measurable.measure(Constraints.fixed(width, stage.labelRoom.roundToInt()))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                if (span != null) name.place(span.start.roundToInt(), box.bottom.roundToInt())
                            }
                        }.graphicsLayer {
                            val drawn = if (overview.hidden(w.id)) 0f else overview.progress * (1f - overview.drop)
                            alpha = if (drum == null) drawn else drawn * drumLabelAlpha(drumOffset(slot, anchor, overview.scroll)) *
                                tabTransform(slot, anchor, 1f, overview.scroll, stage).alpha
                        }.padding(horizontal = 8.dp).clearAndSetSemantics {},
                        contentAlignment = if (drum != null) Alignment.CenterEnd else Alignment.Center,
                    ) {
                        val bright = if (drum != null) slot == front else w.id == selected.id
                        CardName(w.row(named, named.session.orEmpty(), w.id == named.window), named.status == "connected", conn.activityReadState,
                            bright = bright, room = if (drum != null) null else with(density) { stage.labelRoom.toDp() })
                    }
                }
            }
            // Under the new-tab card, where a tab's name is.
            if (newTabCard) Box(
                Modifier.layout { measurable, constraints ->
                    val box = overview.placed(NEW_TAB_KEY, cardBox(row.size, anchor, overview.progress, overview.scroll, stage))
                    val span = labelSpan(box, stage)
                    val width = span?.let { (it.endInclusive - it.start).roundToInt() } ?: 0
                    val name = measurable.measure(Constraints.fixed(width, stage.labelRoom.roundToInt()))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        if (span != null) name.place(span.start.roundToInt(), box.bottom.roundToInt())
                    }
                }.graphicsLayer { alpha = overview.progress * (1f - overview.drop) }.padding(horizontal = 8.dp).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(if (online) "New Tab" else "Offline", color = backdrop.secondary, style = VeloTheme.type.caption, maxLines = 1)
            }
        }
        // A pane being moved: its places, the New Tab card, its ghost and the capsule, over the cards. It hides the grid's
        // cards under the strip's room while the move goes on; the closing after the move keeps them hidden here.
        PaneMoveOverlay(conn, state, overview, paneMoves, stage, row, roles, radius, haptics)
        if (showing && stage.grid != null && stage.stripRoom > 0f && overview.forMove && overview.paneMove == null) {
            StripBackdrop(overview, Modifier.align(Alignment.TopCenter))
        }
    }
    if (showing && !overview.wheel) RowNote(overview, Modifier.align(Alignment.BottomCenter))
    // Over the stage, beside it like the note: a tap on a chip is not a tap beside the cards.
    // Only where the cards keep room enough under it, and not in a pane move's overview ([showsStrip]).
    // Grid cards scrolled up pass under the strip: its room hides them behind the chips.
    if (showing && !overview.wheel && showsStrip(stripRoom, overview.forMove)) SessionStrip(strip, overview, Modifier.align(Alignment.TopCenter), opaque = grid)
    }
}

/**
 * A tab's name under its card, centred in the [room] under it, or beside a drum card (a null [room]): the tab's caption
 * as under the panes ([TabCaption]), on the backdrop. The selected tab's name, or the drum's front card's, is
 * [bright]; the others' text is dimmer and their tiles keep their colour, as a sidebar row's do. Beside a drum card
 * the name is read at a glance, at a size near the body text's, and its tile and marks grow with it.
 */
@Composable
internal fun CardName(row: TabRow, connected: Boolean, readState: ActivityReadState, bright: Boolean, room: Dp?) =
    VeloSurface(VeloTheme.colors.backdrop) {
        val activity = readState.activity(row, connected)
        val colors = VeloTheme.surface
        val caption = VeloTheme.type.caption
        // The tile's layers step above its face, into the room its box has above it, and stay under the card. Where the
        // room under the card is lower than the face with that room above and below it (the grid's at the larger
        // sizes), the name drops so the face starts that far under the card.
        val drop = room?.let { (VeloTheme.medium((2 * tileBox(CaptionTile) - CaptionTile).dp) - it).coerceAtLeast(0.dp) }
        val tag = "card-name-${row.tab}"
        TabCaption(
            tabTile(row, activity), activity, row.name, if (bright) colors.text else colors.secondary,
            if (room == null) caption.copy(fontSize = DrumLabelText, lineHeight = 1.2.em) else caption, tag,
            Modifier.testTag(tag).padding(top = drop ?: 0.dp), fill = false,
        )
    }

/**
 * Under the row: the close that waits for Undo, with its button (#228), else which tab was closed
 * once it is gone. In sight and read by a screen reader as it changes. While Undo is offered the
 * note takes every touch on it, so none reaches the stage under it.
 */
@Composable
internal fun RowNote(overview: OverviewState, modifier: Modifier) {
    val backdrop = VeloTheme.colors.backdrop
    val sizes = VeloTheme.sizes
    val waiting = overview.undoName
    val text = waiting?.let(::closedTabNote) ?: overview.closedNote.orEmpty()
    // A touch that just misses Undo is taken too: beside the cards it would close the overview and send the close.
    val margin = if (waiting == null) Modifier else Modifier
        .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
        .padding(UndoMargin)
    val offer = if (waiting == null) Modifier else Modifier.glass()
        .padding(start = sizes.rowPadding, end = sizes.gapCompact, top = sizes.gapCompact, bottom = sizes.gapCompact)
    Row(
        modifier.padding(bottom = NoteBottom).graphicsLayer { alpha = overview.progress }.then(margin).then(offer),
        horizontalArrangement = Arrangement.spacedBy(sizes.gapCompact), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            // Read aloud, the note says Undo is there before the time runs out.
            if (waiting != null) contentDescription = "$text. Undo is available"
        }, color = if (waiting != null) backdrop.text else backdrop.secondary, style = VeloTheme.type.caption)
        if (waiting != null) VeloButton("Undo", overview::undoClose, kind = ButtonKind.Primary)
    }
}

/** How far apart the fingers are: their mean distance from their centre. */
private fun span(points: List<Offset>): Float {
    val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
    val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
    return points.sumOf { hypot(it.x - cx, it.y - cy).toDouble() }.toFloat() / points.size
}

/**
 * Where a card lifted away goes: its tab to [dismiss] once it is out of sight, the feel of it
 * starting to fly ([flies]), and of its lift passing half its way, up or back ([crossed], [liftCrossing]).
 * [pickedUp]: the feel of a long press that picked a card up to move its tab (#218).
 * [feel]: the pinch's own places ([pinchFeel]) and a dropped card that moved its tab (#309).
 */
private class GestureEnds(
    val dismiss: (String) -> Unit, val flies: () -> Unit, val crossed: (Boolean) -> Unit, val pickedUp: () -> Unit, val feel: (Feel) -> Unit,
)

/**
 * Every finger on the pane area passes here before a pane sees it ([touchMode]): one finger
 * in the workspace is left to the pane under it; two are the pinch; three move the overview;
 * and on the overview they all belong to the row of cards. One finger moving up or down from a
 * card lifts that card ([cardLift]), in the grid one moving sideways takes it aside ([cardAside]); let go far or fast
 * enough, it flies away and its tab closes.
 * One finger held still on a card for a long press picks the card up ([Touch.TabDrag]): it follows
 * the finger, and letting go moves its tab to the place it is over (#218).
 * While a pane is moved ([OverviewState.paneMove]) the gesture is the move's, and [moves] takes it.
 */
private suspend fun PointerInputScope.stageGestures(overview: OverviewState, conn: HostConnection, finger: Array<String?>, ends: GestureEnds, moves: PaneMoves) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        var mode: Touch? = null
        // Since the gesture became what it is: how far the fingers moved, and how much further apart they are.
        var travelX = 0f
        var travelY = 0f
        var spread = 0f
        var ratio = 1f
        var moving = false
        var horizontal = false
        var pageStart = 0f
        var pageGeneration = overview.pageGeneration
        var spreading = false
        var most = 0
        var start = 0f
        var block: String? = null
        var blockScale = 1f
        // The pinch's last step, to feel the places it passes ([pinchFeel]).
        var lastPinch = PinchStep(1f, 0f)
        // The step of the ladder the pinch can take, in which tab, its intent and speed, and whether a finger lifted,
        // which decides it (#140).
        var toward: LayoutPinch? = null
        var tab: String? = null
        var intent = LayoutIntent()
        var pinchSpeed = PinchSpeed()
        var decided = false
        var down = Offset.Zero
        var time = 0L
        var speed = ReleaseSpeed()
        // The card one finger lifts, the way its lift is measured on ([dismissWay]) and how far it may go the other way.
        var lifting: String? = null
        var aside = false
        var liftWay = 1f
        var liftLimit = 0f
        // The first finger landed on a grid still on its way, and stopped it ([catchesScroll]): it is no tap.
        var caught = false
        // One finger on the row: the card it went down on, whether that card can be lifted or picked up, when it went down,
        // where it is now, and whether it was held still for a long press ([rowFinger]).
        var onCard: String? = null
        var canLift = false
        var canPick = false
        var downTime = 0L
        var at = Offset.Zero
        var held = false
        // The touch was cancelled (the system took it): what the fingers did is not let go.
        var cancelled = false
        finger[0] = null

        // [cut]: the pinch became another gesture, or the touch was cancelled; it takes no step of the ladder.
        fun endPinch(cut: Boolean) {
            val pane = block ?: return
            if (!moving) return
            if (toward != null) overview.endPreview(blockScale, liftOp(toward, intent, conn.connected, cut))
            overview.pinchEnd(pane, blockScale, speed.perSecond(time))
        }

        // The ladder's intent at [time] ([layoutIntent]), felt as it turns on and off, and the pinch's step with it.
        fun intend(pane: String, step: PinchStep) {
            val way = toward ?: return
            val ln = ln(ratio)
            if (!decided) {
                val next = layoutIntent(intent, way, ln, pinchSpeed.perSecond(time), time)
                layoutIntentFeel(intent, next)?.let(ends.feel)
                intent = next
            }
            overview.pinchToward(tab ?: return, pane, way, blockScale, step, intent.on, previewReach(way, ln))
        }

        // The card under the first finger where the row stands now, and whether it can be lifted or picked up.
        fun lookAtCard() {
            val stage = overview.stage
            val tabs = overview.tabs
            val anchor = overview.rowAnchor()
            onCard = stage?.let { cardAt(down.x, down.y, tabs.size, anchor, overview.progress, overview.scroll, it) }?.let(tabs::get)
            canLift = onCard?.let(overview::liftable) == true
            canPick = mayPickUp(canLift, most, conn.connected, overview.paneMove != null)
        }

        // What the one finger on the cards is ([rowFinger]), by how far it moved along the way they scroll and across it.
        fun cardFinger(): RowFinger {
            val stage = overview.stage
            val scrolled = stage?.let { along(travelX, travelY, it) } ?: travelX
            val crossed = stage?.let { across(travelX, travelY, it) } ?: travelY
            return rowFinger(scrolled, crossed, slop, held, canLift, canPick)
        }

        while (true) {
            // A finger alone and still on a card that can move waits for the long press: when no event comes before it has
            // passed, the card is picked up. Any event in time is the gesture as before.
            val pressLeft = longPressLeft(longPress, time - downTime, mode == Touch.Row && canPick, held, most, decided = lifting != null || moving || spreading)
            // Fingers resting in a fast pinch send no event, yet they slow it: its intent is looked at again in time.
            val intentLeft = if (mode == Touch.Pinch && moving && toward != null && !decided) layoutIntentWait(intent, time) else null
            val wait = pressLeft ?: intentLeft
            val event = when {
                wait == null -> awaitPointerEvent(PointerEventPass.Initial)
                wait > 0 -> withTimeoutOrNull(wait) { awaitPointerEvent(PointerEventPass.Initial) }
                else -> null
            }
            if (event == null && pressLeft == null) {
                time = SystemClock.uptimeMillis()
                block?.let { intend(it, lastPinch) }
                continue
            }
            if (event == null) {
                held = true
                // A still finger does not stop a thrown row or the opening: only the card still under it is picked up.
                val card = onCard
                lookAtCard()
                if (card != null && onCard == card && cardFinger() == RowFinger.Pickup &&
                    overview.pickUpTab(card, at)) {
                    mode = Touch.TabDrag
                    ends.pickedUp()
                }
                continue
            }
            // A finger that lands or lifts is no movement: it must not count as a rest before the release.
            val moved = event.type == PointerEventType.Move
            val pressed = event.changes.filter { it.pressed }
            event.changes.firstOrNull()?.let { time = it.uptimeMillis }
            if (event.changes.any { it.previousPressed && !it.pressed }) speed.lift(time)
            if (pressed.isEmpty()) {
                // A cancelled touch lifts every finger with the lift already consumed: a pinch then takes no step.
                if (event.changes.any { it.previousPressed && !it.pressed && it.isConsumed }) cancelled = true
                // A handle's long press gave this gesture to the move, though its finger lifted before it moved.
                if (overview.paneMove != null && most > 0) mode = Touch.PaneDrag
                if (mode != null && mode != Touch.Pane) event.changes.forEach(PointerInputChange::consume)
                break
            }
            if (mode == null) {
                down = pressed.first().position
                downTime = time
            }
            at = pressed.first().position
            most = maxOf(most, pressed.size)
            // A pane being moved takes the gesture: the long press on its handle began it, or a press in pick mode.
            val next = if (overview.paneMove != null) Touch.PaneDrag else touchMode(mode, pressed.size, overview.progress)
            if (next != mode) {
                if (mode == null && next == Touch.Row) {
                    caught = overview.stage?.let { catchesScroll(it, overview.rowMoving) } == true
                    if (caught) overview.holdRow()
                }
                if (mode == Touch.Pinch) endPinch(cut = true)
                // A pane move takes the gesture from a dragged card: the card goes back with nothing sent.
                if (mode == Touch.TabDrag) overview.cancelTabDrag()
                // Three fingers take over: the lifted card goes back.
                lifting?.let(overview::putBack)
                lifting = null
                mode = next
                travelX = 0f; travelY = 0f; spread = 0f; ratio = 1f
                moving = false
                start = overview.progress
                speed = ReleaseSpeed()
                if (next == Touch.Swipe) pageGeneration = overview.pageGeneration
                if (next == Touch.Pinch) {
                    // Fingers that began between the panes pinch the focused one.
                    block = finger[0] ?: conn.state.focused
                    blockScale = block?.let(conn::scaleOf) ?: 1f
                    lastPinch = PinchStep(blockScale, 0f)
                    val window = block?.let { b -> conn.state.windows.firstOrNull { b in tabBlocks(it) } }
                    tab = window?.id
                    toward = block?.let { layoutPinch(window, it, conn.connected) }
                    overview.dropPreview()
                    intent = LayoutIntent()
                    pinchSpeed = PinchSpeed()
                    decided = false
                }
                // Where the move's press began decides what it does ([PaneMoves.press]).
                if (next == Touch.PaneDrag) moves.press(pressed.first().position)
            }
            // Only fingers that were down before move the gesture: one that lands or lifts changes no distance.
            val kept = pressed.filter { it.previousPressed }
            var dx = 0f
            var dy = 0f
            if (kept.isNotEmpty()) {
                dx = kept.sumOf { (it.position.x - it.previousPosition.x).toDouble() }.toFloat() / kept.size
                dy = kept.sumOf { (it.position.y - it.previousPosition.y).toDouble() }.toFloat() / kept.size
                travelX += dx
                travelY += dy
            }
            if (kept.size >= 2) {
                val now = span(kept.map { it.position })
                val before = span(kept.map { it.previousPosition })
                spread += now - before
                if (before > 0f) ratio *= now / before
            }
            val stage = overview.stage
            when (mode) {
                Touch.Swipe -> if (stage != null && pressed.size >= 3) {
                    if (!moving && maxOf(abs(travelX), abs(travelY)) > slop) {
                        horizontal = abs(travelX) > abs(travelY)
                        pageStart = overview.pageOffset
                        if (!horizontal) overview.resetPage()
                        travelX = 0f
                        moving = true
                        travelY = 0f
                        start = overview.progress
                        overview.hold()
                    }
                    if (moving && horizontal) {
                        if (start == 0f) {
                            if (overview.pageGeneration == pageGeneration) overview.dragPage(pageStart + travelX, stage)
                            if (moved) speed.add(time, travelX)
                        // An open grid scrolls up and down, which three fingers open and close: sideways they do nothing.
                        } else if (stage.grid == null) {
                            overview.scrollBy(-dx)
                            if (moved) speed.add(time, overview.scroll)
                        }
                    } else if (moving) {
                        val p = swipeProgress(start, travelY, stage)
                        overview.follow(p)
                        if (moved) speed.add(time, p)
                    }
                }
                Touch.Pinch -> block?.let { pane ->
                    if (!moving && abs(spread) > slop) {
                        moving = true
                        ratio = 1f
                    }
                    if (moving) {
                        val step = ladderStep(blockScale, ratio, toward)
                        // While the intent is on the text goes back to its size from before: the fingers reach no end of it.
                        if (!intent.on) pinchFeel(lastPinch, step)?.let(ends.feel)
                        lastPinch = step
                        // The first finger to lift decides the step: the one left moves no distance, nor any intent.
                        if (pressed.size < 2) decided = true
                        if (toward == null) overview.pinchBy(pane, blockScale, step)
                        else {
                            if (moved && !decided) pinchSpeed.add(time, ln(ratio))
                            intend(pane, step)
                        }
                        if (moved) speed.add(time, step.progress)
                    }
                }
                Touch.Row -> if (pressed.size >= 2 && lifting == null) {
                    if (!spreading && abs(spread) > slop) {
                        spreading = true
                        moving = false
                        ratio = 1f
                        start = overview.progress
                        speed = ReleaseSpeed()
                        overview.hold()
                    }
                    if (spreading) {
                        val p = spreadProgress(start, ratio)
                        overview.follow(p)
                        if (moved) speed.add(time, p)
                    }
                } else if (!spreading) {
                    // Until the finger is something, how it moved and the card it went down on decide what ([rowFinger]).
                    if (lifting == null && !moving) {
                        lookAtCard()
                        when (cardFinger()) {
                            // Up or down before sideways, from a card that can go: the finger lifts that card. In the grid,
                            // sideways before up or down, it takes the card aside, either way.
                            RowFinger.Lift -> if (stage != null) {
                                lifting = onCard
                                aside = stage.grid != null
                                liftWay = dismissWay(overview.progress, stage)
                                liftLimit = stage.labelRoom
                                // From here: the card does not jump by the distance the finger needed to be a lift.
                                if (aside) travelX = 0f else travelY = 0f
                                overview.hold()
                            }
                            RowFinger.Scroll -> {
                                moving = true
                                dx = 0f
                                dy = 0f
                                overview.hold()
                            }
                            // A card is picked up when the long press passes with no event (above), not by an event.
                            RowFinger.Pickup, RowFinger.Undecided -> {}
                        }
                    }
                    val card = lifting
                    if (card != null) {
                        // Up in the row; aside in the grid, to the side the finger is on.
                        val lift = if (aside) cardAside(travelX, liftLimit, conn.connected) else cardLift(travelY, liftLimit, conn.connected)
                        val before = overview.liftOf(card) / liftWay
                        overview.lift(card, lift, side = if (aside) sign(travelX) else 0f)
                        liftCrossing(before, lift / liftWay)?.let(ends.crossed)
                        if (moved) speed.add(time, lift / liftWay)
                    } else if (moving) {
                        // Sideways in the row, up and down in the grid.
                        overview.scrollBy(-(stage?.let { along(dx, dy, it) } ?: dx))
                        if (moved) speed.add(time, overview.scroll)
                    }
                }
                Touch.PaneDrag -> if (moves.follow(pressed.first().position, pressed.size, travelX, travelY, slop)) {
                    // Pick mode: a swipe begun beside every place moves the row, as on the overview: sideways in the row, up
                    // and down in the grid.
                    overview.scrollBy(-(stage?.let { along(dx, dy, it) } ?: dx))
                    if (moved) speed.add(time, overview.scroll)
                }
                // The card follows one finger; a second one puts it back, and the fingers stay the drag's until all lift.
                Touch.TabDrag -> if (pressed.size > 1) overview.cancelTabDrag() else overview.dragTab(pressed.first().position)
                Touch.Pane -> {}
            }
            if (mode != Touch.Pane) event.changes.forEach(PointerInputChange::consume)
        }

        when (mode) {
            Touch.Swipe -> if (moving) {
                val velocity = speed.perSecond(time)
                when {
                    !horizontal -> overview.release(velocity)
                    // Three fingers sideways moved no grid ([Touch.Swipe] above): there is nothing to throw.
                    start > 0f -> if (overview.stage?.grid == null) overview.throwRow(velocity)
                    overview.pageGeneration == pageGeneration -> overview.stage?.let { overview.releasePage(velocity, it) }
                }
            }
            Touch.Pinch -> endPinch(cut = cancelled)
            Touch.Row -> when {
                lifting != null -> {
                    if (overview.letGo(lifting, speed.perSecond(time), ends.dismiss)) ends.flies()
                    // The lift stopped a row on its way: it rests on a card.
                    overview.throwRow(0f)
                }
                spreading -> overview.release(speed.perSecond(time))
                moving -> overview.throwRow(speed.perSecond(time))
                // The finger only stopped the grid: no tab is picked, and a grid stopped past an end of its content goes back within it.
                caught -> overview.throwRow(0f)
                // A tap: on a card it picks that tab, beside the cards it goes back to the selected one.
                most == 1 && abs(travelX) <= slop && abs(travelY) <= slop -> {
                    val stage = overview.stage
                    val tabs = overview.tabs
                    val anchor = overview.rowAnchor()
                    val card = stage?.let { cardAt(down.x, down.y, rowPlaces(tabs.size), anchor, overview.progress, overview.scroll, it) }
                    when (card) {
                        null -> overview.close()
                        // The place after the last tab's is the new-tab card's (#230).
                        tabs.size -> overview.newTab()
                        else -> overview.pick(tabs[card])
                    }
                }
            }
            Touch.PaneDrag -> moves.release(speed.perSecond(time))
            // Its tab moves to the place the card is over; on its own place, or after a cancel, nothing is sent.
            Touch.TabDrag -> if (overview.dropTab()) ends.feel(Feel.Confirm)
            Touch.Pane, null -> {}
        }
        // However the fingers left it: the overview never stays between its ends.
        if (mode != Touch.Pane && overview.adrift) overview.release(0f)
    }
}

/**
 * What the strip shows, and what its chips do beyond a switch of session on this remote (#236).
 * The workspace makes it, since it knows every remote. [remotes] is read while the strip is drawn,
 * so only the strip follows the other remotes' sessions. [showElsewhere] shows another remote's
 * session; [rename] and [close] act on a chip's session on its own remote, as the sidebar's menu does.
 */
internal class StripSource(
    val remotes: () -> List<StripRemote>,
    val showElsewhere: (StripRemote, SessionChip) -> Unit,
    val rename: (StripRemote, SessionChip) -> Unit,
    val close: (StripRemote, SessionChip) -> Unit,
)

/**
 * The session strip above the row (#229): a chip for every session and New Session. With other
 * remotes connected, their sessions follow under each remote's name (#236). It comes down with the
 * overview. A chip draws its session's tabs as cells, the one it shows lit, and a dot while one of
 * them has news; the session shown has the accent edge. A long press opens the session's menu.
 * [opaque]: the strip's room has the backdrop's colour behind the chips, for cards that pass under it (the grid's, #281).
 */
@Composable
internal fun SessionStrip(strip: StripSource, overview: OverviewState, modifier: Modifier, opaque: Boolean = false) {
    val remotes = strip.remotes()
    val items = stripItems(remotes)
    val online = remotes.firstOrNull { it.active }?.connected == true
    val scroll = overview.stripScroll
    val density = LocalDensity.current
    val current = items.indexOfFirst { it is StripItem.Chip && it.chip.current }
    val currentChip = (items.getOrNull(current) as? StripItem.Chip)?.let { it.remote.id to it.chip.id }
    // The chip of the session shown stays in sight: after a switch, a new session, a strip drawn anew. Not when only
    // its place changes (another remote connects): the strip stays where the user scrolled it.
    LaunchedEffect(currentChip) {
        if (current < 0) return@LaunchedEffect
        // Once the strip is laid out with that chip.
        withFrameNanos {}
        val widths = with(density) { items.map { stripItemWidth(it).toPx() } }
        val gap = with(density) { ChipGap.toPx() }
        val padding = with(density) { StripPadding.toPx() }
        val middle = padding + stripMiddle(widths, gap, current) - scroll.viewportSize / 2f
        scroll.animateScrollTo(middle.roundToInt().coerceIn(0, scroll.maxValue))
    }
    Box(
        modifier.stripPlace(overview)
            // Every touch on the strip is the strip's: beside the chips it would be a tap beside the cards,
            // which closes the overview and sends the close that waits for Undo.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            // Inside the layer: the colour comes down and fades in with the chips.
            .then(if (opaque) Modifier.background(VeloTheme.colors.backdrop.surface) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.horizontalScroll(scroll).padding(horizontal = StripPadding),
            horizontalArrangement = Arrangement.spacedBy(ChipGap), verticalAlignment = Alignment.CenterVertically,
        ) {
            for (item in items) when (item) {
                is StripItem.Name -> key("remote", item.remote.id) { StripRemoteName(item.remote) }
                is StripItem.Chip -> key(item.remote.id, item.chip.id) { SessionChipView(item.remote, item.chip, strip, overview) }
                StripItem.NewSession -> key("new") { NewSessionChip(online, overview) }
            }
        }
    }
}

/** Where the session strip stands: across the top of the pane area, its room high, coming down and fading in with the overview. */
private fun Modifier.stripPlace(overview: OverviewState): Modifier = fillMaxWidth().height(StripRoom).graphicsLayer {
    alpha = overview.progress
    translationY = -(1f - overview.progress) * size.height
}

/**
 * The strip's room with no strip in it, in the backdrop's colour the strip has over the grid ([SessionStrip]): a pane move's
 * overview draws no strip ([showsStrip]), and grid cards scrolled up pass under its capsule there (#281). Laid over the cards
 * and their places, under the capsule. It takes no touch, as the gesture under it is the move's.
 */
@Composable
internal fun StripBackdrop(overview: OverviewState, modifier: Modifier) {
    Box(modifier.stripPlace(overview).background(VeloTheme.colors.backdrop.surface))
}

/** How wide the strip draws [item]: the widths [stripMiddle] adds up. */
private fun stripItemWidth(item: StripItem): Dp = when (item) {
    is StripItem.Name -> RemoteNameWidth
    is StripItem.Chip -> ChipWidth
    StripItem.NewSession -> ChipHeight
}

/** A remote's name before its chips (#236); the active remote's is drawn as the sidebar marks it. */
@Composable
private fun StripRemoteName(remote: StripRemote) {
    val backdrop = VeloTheme.colors.backdrop
    val color = if (remote.active) backdrop.text else backdrop.secondary
    Column(
        Modifier.size(RemoteNameWidth, ChipHeight).clearAndSetSemantics {
            heading()
            contentDescription = "Remote ${remote.name}"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Computer, null, Modifier.size(18.dp), tint = if (remote.active) backdrop.accent else color)
        Text(remote.name, color = color, style = VeloTheme.type.caption, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun SessionChipView(remote: StripRemote, chip: SessionChip, strip: StripSource, overview: OverviewState) {
    val backdrop = VeloTheme.colors.backdrop
    val shape = RoundedCornerShape(12.dp)
    val online = remote.connected
    val description = sessionChipDescription(chip.label, chip.tabs, remote = if (remote.active) null else remote.name)
    var menu by remember { mutableStateOf(false) }
    val show = { if (remote.active) overview.showSession(chip.id) else strip.showElsewhere(remote, chip) }
    Box(
        Modifier.size(ChipWidth, ChipHeight).graphicsLayer { alpha = if (online) 1f else OFFLINE_ALPHA }
            .glass(shape, selected = chip.current)
            .then(if (chip.current) Modifier.border(2.dp, backdrop.accent, shape) else Modifier)
            .combinedClickable(enabled = online, onLongClickLabel = SessionMenuLabel, onLongClick = { menu = true }, onClick = show)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                selected = chip.current
                if (!online) disabled()
                onClick {
                    show()
                    true
                }
                if (online) onLongClick(SessionMenuLabel) {
                    menu = true
                    true
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chip.label, Modifier.weight(1f, fill = false), color = if (chip.current) backdrop.text else backdrop.secondary,
                    style = VeloTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                chip.tabs?.let { Text("$it", color = backdrop.secondary, style = VeloTheme.type.caption, maxLines = 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                chipCells(chip.tabs ?: 0, chip.shownTab).forEach { lit ->
                    Box(Modifier.size(CellWidth, CellHeight).clip(RoundedCornerShape(2.dp))
                        .background(if (lit) backdrop.accent else backdrop.glassEdge))
                }
            }
        }
        // News in one of its tabs, by the sidebar's measure.
        if (chip.attention) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(backdrop.accent))
        // The sidebar's session menu, with the same questions ([sessionMenu]).
        VeloMenu(menu, onDismissRequest = { menu = false }) {
            for (item in sessionMenu(online)) {
                val close = item.action == SessionAction.Close
                VeloMenuItem(item.label, {
                    menu = false
                    if (close) strip.close(remote, chip) else strip.rename(remote, chip)
                }, icon = if (close) Icons.Default.Close else Icons.Default.Edit, enabled = item.enabled, destructive = close)
            }
        }
    }
}

@Composable
private fun NewSessionChip(online: Boolean, overview: OverviewState) {
    val backdrop = VeloTheme.colors.backdrop
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(ChipHeight, ChipHeight).graphicsLayer { alpha = if (online) 1f else OFFLINE_ALPHA }
            .clip(shape).border(1.dp, backdrop.glassEdge, shape)
            .clickable(enabled = online) { overview.newSession() }
            .clearAndSetSemantics {
                contentDescription = "New Session"
                role = Role.Button
                if (!online) disabled()
                onClick {
                    overview.newSession()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Add, null, Modifier.size(24.dp), tint = backdrop.secondary)
    }
}
