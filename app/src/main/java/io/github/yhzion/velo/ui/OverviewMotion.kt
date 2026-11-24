package io.github.yhzion.velo.ui

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.Feel

/*
 * The tab overview's motion, as pure functions. One number, progress, runs from 0 (the
 * workspace: the selected tab fills the pane area) to 1 (the overview: the tabs stand in a
 * row of cards). Every tab is one container that is moved and scaled as a whole; what the
 * fingers, the rail button and the palette change is only the progress.
 */

/** Another session's row drops in from this share of the pane area's height above its place (#229). */
private const val DROP_SHARE = 0.25f

/** The cards keep at least this share of the pane area's height when the session strip takes its room. */
private const val MIN_SCALE_WITH_STRIP = 0.4f

/**
 * The room the session strip takes above the row: [room], where the cards and their names keep
 * enough of a pane area [height] px high; none in a low one (the keyboard up, a split screen).
 */
fun stripRoomFor(height: Float, labelRoom: Float, room: Float): Float =
    if (height > 0f && (height - labelRoom - room) / height >= MIN_SCALE_WITH_STRIP) room else 0f

/**
 * Whether the overview draws the session strip: where the cards keep [stripRoom] for it ([stripRoomFor]), and not in
 * a pane move's overview ([forMove], #217). A pane moves only within its session, so no chip may show another
 * session's tabs then, and the move's capsule with its Cancel button stands where the strip would.
 */
fun showsStrip(stripRoom: Float, forMove: Boolean): Boolean = stripRoom > 0f && !forMove

/**
 * Whether a point [y] px down the pane area lies in the strip's room over the grid ([Stage.stripRoom]). Grid cards scrolled up
 * pass under it, hidden by the strip or, in a pane move's overview, by the room's backdrop: no place of a pane move is there.
 * The row's cards stand below that room.
 */
fun inStripRoom(y: Float, stage: Stage): Boolean = stage.grid != null && y < stage.stripRoom

/**
 * Whether the overview is a pane move's after a step ([OverviewState.forMove]), when it [was] before. One that starts
 * [opening] is a move's only while a pane is [moving]: opened again while it closes after a move, it is the plain
 * overview at once. Else it stays what it was until it has closed ([progress] 0) with no move under way: the first
 * frame of a move's opening is at 0 too, and the closing after a move keeps it to the end.
 */
fun movesOverview(was: Boolean, moving: Boolean, progress: Float, opening: Boolean): Boolean = when {
    opening -> moving
    progress == 0f && !moving -> false
    else -> was
}

/**
 * Whether the row shows another session than before: the session it was [arranged] for last is not
 * [session] (chosen elsewhere), or the strip asked for a switch from a session [askedFrom] that is
 * not [session] (back to the first one through a session without tabs, which the row never saw).
 */
fun switchedSession(arranged: String?, askedFrom: String?, session: String?): Boolean =
    (arranged != null && session != arranged) || (askedFrom != null && session != askedFrom)

/**
 * Whether the overview closes once a card has flown away. Only after the last tab ([allAway]), as a
 * phone's recents do after the last app (#216), and only without the session strip: with the [strip]
 * there are other sessions, New Session and the empty session's New Tab to go on to (#247).
 */
fun closesAfterFlight(allAway: Boolean, strip: Boolean): Boolean = allAway && !strip

/**
 * Whether the row the open overview waits for has come, so that it drops in: it has tabs, and it
 * is the [awaited] session's. That is any session after a switch on one remote (null), and only
 * the chosen one after a switch of remote, whose old session may show first (#236).
 */
fun rowArrived(awaited: String?, session: String?, row: List<String>): Boolean =
    row.isNotEmpty() && (awaited == null || session == awaited)

/** How far up a row is drawn that has [drop] of its way in still to go (1 just shown, 0 in place), in px. */
fun dropOffset(drop: Float, stage: Stage): Float = -drop * stage.height * DROP_SHARE

/** The pane area and the measures of the row of cards, in px. */
data class Stage(
    val width: Float,
    val height: Float,
    /** Between two cards in the overview. */
    val cardGap: Float,
    /** Between two tabs in the workspace: it keeps a neighbour off the pane area. */
    val pageGap: Float,
    /** Under a card, for the tab's name. */
    val labelRoom: Float,
    /** Above the row in the overview, for the session strip (#229); the workspace has none. */
    val stripRoom: Float = 0f,
    /** From the pane area's bottom up to the top of the Undo note's touch area: the row's cards end above it. */
    val noteRoom: Float = 0f,
    /** How much of a neighbouring card shows at each side of the selected one in the overview. */
    val peek: Float = 0f,
    /** Between the session strip and the top of a card that fills the height. */
    val rowPad: Float = 0f,
    /** The grid layout's measures; null is the row of cards. */
    val grid: GridSpec? = null,
    /** The drum of the rail's window wheel (#304); null is the row or the grid. It wins over [grid]. */
    val drum: DrumSpec? = null,
)

/**
 * The drum the rail's window wheel turns (#304): the tabs' cards stand up and down on it, the one in front largest, the
 * others turned away, pressed and dimmed, each with its name to its left. [scale] of the pane area for a card, [tilt]
 * degrees between two cards; in px [radius] of the drum and [eye] the distance from the front card to the eye (a card
 * farther back is drawn smaller), [shift] of the cards to the right so that they and their names stand centred, and the
 * names' [labelWidth] and [labelGap] from their card.
 */
data class DrumSpec(
    val scale: Float, val tilt: Float, val radius: Float, val eye: Float, val shift: Float, val labelWidth: Float, val labelGap: Float,
)

/** A drum card's share of the pane area's height. derived: the mockup the owner chose (v2), a third of the height. */
private const val DRUM_SCALE = 1f / 3.15f

/** Degrees between two cards on the drum. derived: the mockup (v2). */
private const val DRUM_TILT = 22f

/** The drum's radius, as a share of a card's height over the sine of [DRUM_TILT]. derived: the mockup (v2). */
private const val DRUM_SPACING = 0.8f

/**
 * Between two cards on the drum, as a share of a card's height. Cards never overlap: a pane's surface stacks by its own
 * order, not by the card's, so an overlapped card would show through the one in front.
 */
private const val DRUM_GAP = 0.06f

/** Steps per card of the sum that stands the cards back to back ([drumMiddle]). */
private const val DRUM_STEPS = 12

/** How much larger the card in front is drawn. derived: the mockup (v2). */
private const val DRUM_FRONT = 0.2f

/** A card turned this far from the front is out of sight: farther, its lean would draw it larger than the front card. */
private const val DRUM_HIDDEN = 58f

/** Over this many degrees before [DRUM_HIDDEN] a card fades out. */
private const val DRUM_FADE = 12f

/**
 * The drum for a pane area of [width] by [height] px. Its names are [labelMax] wide at most and [labelMin] at least,
 * [labelGap] from their cards, and leave [margin] at the pane area's sides; [eye] is the eye's distance from the front.
 */
fun drumSpec(width: Float, height: Float, labelMin: Float, labelMax: Float, labelGap: Float, margin: Float, eye: Float): DrumSpec {
    val cardHeight = height * DRUM_SCALE
    val radius = DRUM_SPACING * cardHeight / kotlin.math.sin(Math.toRadians(DRUM_TILT.toDouble())).toFloat()
    val label = (width - width * DRUM_SCALE * (1f + DRUM_FRONT) - labelGap - margin).coerceIn(labelMin, maxOf(labelMin, labelMax))
    return DrumSpec(DRUM_SCALE, DRUM_TILT, radius, eye, (label + labelGap) / 2, label, labelGap)
}

/** How far the tab at [index] stands from the drum's front, in cards (later tabs below): [scroll] turns the drum. */
fun drumOffset(index: Int, selected: Int, scroll: Float): Float = index - selected - scroll

/**
 * A card [u] cards from the drum's front: its scale of the pane area (larger in front, smaller farther back on the
 * drum, as the eye sees it) and the share of its height it keeps, turned away ([TabTransform.squash]).
 */
private fun drumCard(u: Float, drum: DrumSpec): Pair<Float, Float> {
    val c = kotlin.math.cos(Math.toRadians((u * drum.tilt).toDouble())).toFloat()
    val depth = drum.eye / (drum.eye - drum.radius * (c - 1f))
    val near = (1f - kotlin.math.abs(u)).coerceAtLeast(0f)
    return drum.scale * (1f + DRUM_FRONT * near) * depth to c
}

/**
 * How far below the pane area's middle the middle of a card [d] cards from the drum's front stands, in pane-area
 * heights: the cards between stand back to back, each as tall as it is drawn there, with [DRUM_GAP] between two.
 * A drum seen from the side would bring its far cards closer than they are tall; this never does.
 */
internal fun drumMiddle(d: Float, drum: DrumSpec): Float {
    val far = kotlin.math.abs(d)
    if (far == 0f) return 0f
    val steps = kotlin.math.ceil(far * DRUM_STEPS).toInt()
    var sum = 0f
    for (k in 0 until steps) {
        val (scale, squash) = drumCard(far * (k + 0.5f) / steps, drum)
        sum += scale * squash
    }
    return kotlin.math.sign(d) * (sum * far / steps + DRUM_GAP * drum.scale * far)
}

/**
 * The tab at [index] on the drum. At progress 0 it is a full-size page of a column centred on [selected], as the row's
 * pages stand side by side; at 1 a card on the drum, [scroll] cards turned; between them the linear blend of the two.
 * Neither end overlaps two cards, and the blend of their edges is linear, so no two cards overlap on the way either.
 */
private fun drumTransform(index: Int, selected: Int, progress: Float, scroll: Float, stage: Stage, drum: DrumSpec): TabTransform {
    val d = drumOffset(index, selected, scroll)
    val angle = d * drum.tilt
    val (scale, c) = drumCard(d, drum)
    val middleX = stage.width / 2 + drum.shift
    val middleY = stage.height / 2 + stage.height * drumMiddle(d, drum)
    // A card turned away out of sight stands at 0 exactly: a negative base would make Math.pow NaN, and NaN times
    // the zero fade is NaN still — Compose would draw the card, and its name with it.
    val shown = if (kotlin.math.abs(angle) >= DRUM_HIDDEN || c <= 0f) 0f
    else Math.pow(c.toDouble(), 2.2).toFloat() * ((DRUM_HIDDEN - kotlin.math.abs(angle)) / DRUM_FADE).coerceIn(0f, 1f)
    val squash = blend(1f, c, progress)
    val cardScale = blend(1f, scale, progress)
    return TabTransform(
        scale = cardScale,
        dx = blend(0f, middleX - stage.width * scale / 2, progress),
        // The page's top at 0, the pressed card's at 1: the middle moves on a straight line.
        dy = blend((index - selected) * (stage.height + stage.pageGap) + stage.height / 2, middleY, progress) -
            stage.height * cardScale * squash / 2,
        squash = squash,
        alpha = blend(1f, shown, progress),
    )
}

/**
 * Where a drum card's name stands, beside the card's [box]: its right edge [DrumSpec.labelGap] left of the card, its
 * middle level with the card's. The left edge and the middle, in px.
 */
fun drumLabelAt(box: CardBox, drum: DrumSpec): Offset =
    Offset(box.left - drum.labelGap - drum.labelWidth, (box.top + box.bottom) / 2)

/** How much of a drum card's name shows, [d] cards from the front ([drumOffset]): the front's in full, fading back. */
fun drumLabelAlpha(d: Float): Float = (1.25f - kotlin.math.abs(d) * 0.32f).coerceIn(0f, 1f)

/**
 * The measures of the overview's grid, in px: [columns] of cards, [pad] at each side, [gap] between two columns and between
 * two rows (a row's name included in the row), [top] between the session strip and the first row, and [places] cards in all
 * (the tabs and the new-tab card).
 */
data class GridSpec(val columns: Int, val pad: Float, val gap: Float, val top: Float, val places: Int) {
    /** A grid has a column at least, and no negative number of places. */
    init {
        require(columns >= 1) { "columns must be at least 1: $columns" }
        require(places >= 0) { "places must not be negative: $places" }
    }
}

/** The smallest size, in dp, the grid lets the terminal's text have on a card. */
const val MIN_CARD_TEXT = 5.5f

/**
 * How many columns of cards the grid has in a pane area [width] px wide, for a terminal [fontSize] (sp): as many as keep the
 * text on a card at [MIN_CARD_TEXT] or more, from one to four. A card is the pane area scaled, so its text is the font
 * scaled by the same share.
 */
fun gridColumns(width: Float, fontSize: Float, pad: Float, gap: Float): Int {
    val smallest = width * MIN_CARD_TEXT / fontSize
    return ((width - 2 * pad + gap) / (smallest + gap)).toInt().coerceIn(1, 4)
}

/**
 * A tab container's scale and the place of its top left corner in the pane area. On the drum (#304) a card is also
 * pressed to [squash] of its height, as a card turned away on a drum looks, and shows [alpha] of its light: the rest is
 * covered in black. A pane's surface turns with no layer and fades with none, so the drum leans and dims no other way.
 */
data class TabTransform(val scale: Float, val dx: Float, val dy: Float, val squash: Float = 1f, val alpha: Float = 1f)

/** Where a tab is drawn in the pane area. */
data class CardBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A card's share of the pane area's size, when the name under it leaves that much room. */
private const val CARD_SCALE = 0.55f

/** Three fingers open the overview over this share of the pane area's height. */
private const val SWIPE_TRAVEL = 0.45f

/** A pinch opens the overview over this share of the finger span the smallest text has. */
private const val PINCH_TRAVEL = 0.35f

/** Two fingers on the overview close it when they end this much further apart than they began. */
private const val SPREAD_TRAVEL = 0.6f

/** Progress per second from which a release settles by its direction. */
const val FLING_PER_SECOND = 1.2f

/** Tabs on each side of the one in view that keep their surfaces. */
private const val NEIGHBOURS = 2

/** A released row goes on for this long at the speed it had, then rests on the nearest card. */
private const val ROW_THROW_SECONDS = 0.15f

/** A released grid goes on for this long at the speed it had, then stops: it scrolls freely, with no card to rest on. */
private const val GRID_THROW_SECONDS = 0.25f

/**
 * Two grid scrolls no more than this many px apart are the same; a grid card whose middle is no more than this many px from
 * the pane area's middle is in the middle ([nearerSide]).
 */
private const val GRID_TIE = 0.5f

/** A thrown grid this many px from where it stops is there ([throwSpring]). */
private const val GRID_REST = 0.5f

/** What counts as the speed at release, and how long fingers may rest before it is none. */
private const val SPEED_WINDOW_MS = 100L

private fun blend(from: Float, to: Float, progress: Float) = from + (to - from) * progress

/** The card's scale as it has been: [CARD_SCALE], less where the strip and the name leave less height. */
private fun todaysScale(stage: Stage) = minOf(CARD_SCALE, (stage.height - stage.labelRoom - stage.stripRoom) / stage.height)

/** The scale at which a card fills the height the strip and the Undo note leave. */
private fun heightFill(stage: Stage) = (stage.height - stage.stripRoom - stage.rowPad - stage.noteRoom) / stage.height

/** The scale at which both neighbours still show [Stage.peek] at the sides. */
private fun widthFill(stage: Stage) = (stage.width - 2 * (stage.cardGap + stage.peek)) / stage.width

/** Whether the row's own height is what limits the cards that fill it, not the width. */
private fun heightLimits(stage: Stage) = heightFill(stage) <= widthFill(stage)

/**
 * A card's scale in the overview: as large as the pane area allows (the height the strip and the note leave, the
 * neighbours' peek at the sides), never smaller than the card has been.
 */
private fun cardScale(stage: Stage) =
    maxOf(todaysScale(stage), minOf(heightFill(stage), widthFill(stage))).coerceAtLeast(0.1f)

/** The width of one grid column, in px: what the sides' padding and the gaps leave, shared by the columns. */
private fun gridCell(stage: Stage, grid: GridSpec) = (stage.width - 2 * grid.pad - grid.gap * (grid.columns - 1)) / grid.columns

/** Where the grid centres a card vertically: the middle of what the strip above and the note below leave. */
private fun gridMiddle(stage: Stage) = stage.stripRoom + (stage.height - stage.stripRoom - stage.noteRoom) / 2

/** A grid card's scale: it fills its column, and is never larger than the row's card ([cardScale]). */
private fun gridScale(stage: Stage, grid: GridSpec): Float =
    minOf(gridCell(stage, grid) / stage.width, cardScale(stage)).coerceAtLeast(0.1f)

/** From one grid row's top to the next one's: a card, its name and the gap. */
private fun gridRowStep(stage: Stage, grid: GridSpec) = stage.height * gridScale(stage, grid) + stage.labelRoom + grid.gap

/** Where a grid card's top is at scroll 0 with no home, from the pane area's top, for the place's row. */
private fun gridRowTop(row: Int, stage: Stage, grid: GridSpec) = stage.stripRoom + grid.top + row * gridRowStep(stage, grid)

/** How far the grid can scroll from its first rows: the content, to the Undo note's room under it, less the pane area. */
private fun gridMaxTop(stage: Stage, grid: GridSpec): Float {
    val rows = ((grid.places + grid.columns - 1) / grid.columns).coerceAtLeast(1)
    val contentBottom = gridRowTop(rows - 1, stage, grid) + stage.height * gridScale(stage, grid) + stage.labelRoom + stage.noteRoom
    return maxOf(0f, contentBottom - stage.height)
}

/**
 * The scroll that puts place [index]'s card in the middle of what the strip above and the note below leave, as far as the
 * content allows: from the first rows (0) to the last ([gridMaxTop]). A card taller than that room has its top where the
 * first row's is, under the strip, instead: centred, its top would be hidden by the strip (the Velvet in landscape).
 */
private fun gridHome(index: Int, stage: Stage, grid: GridSpec): Float {
    val cardH = stage.height * gridScale(stage, grid)
    val rowTop = gridRowTop(index.coerceAtLeast(0) / grid.columns, stage, grid)
    val home = if (cardH > stage.height - stage.stripRoom - stage.noteRoom) rowTop - stage.stripRoom - grid.top
    else rowTop + cardH / 2 - gridMiddle(stage)
    return home.coerceIn(0f, gridMaxTop(stage, grid))
}

/** From one card's left edge to the next one's, in the overview. */
private fun pitch(stage: Stage) = stage.width * cardScale(stage) + stage.cardGap

/** From one tab's left edge to the next one's, at [progress]. */
private fun step(progress: Float, stage: Stage) = blend(stage.width + stage.pageGap, pitch(stage), progress)

/**
 * The tab at [index] when the tab at [selected] is the one of the workspace. At progress 0
 * it is a full-size page of a row centred on [selected]; at 1 a card of a row that [scroll]
 * has moved to the left; between them the linear blend of the two.
 */
fun tabTransform(index: Int, selected: Int, progress: Float, scroll: Float, stage: Stage): TabTransform {
    stage.drum?.let { return drumTransform(index, selected, progress, scroll, stage, it) }
    val grid = stage.grid
    if (grid != null) return gridTransform(index, selected, progress, scroll, stage, grid)
    val scale = cardScale(stage)
    val offset = (index - selected).toFloat()
    val cardLeft = stage.width * (1 - scale) / 2 + offset * pitch(stage) - scroll
    val cardTop = when {
        // As it has been: in the middle of what the session strip above and the names below leave.
        todaysScale(stage) >= minOf(heightFill(stage), widthFill(stage)) ->
            stage.stripRoom + (stage.height * (1 - scale) - stage.labelRoom - stage.stripRoom) / 2
        // A card that fills the height starts just under the strip.
        heightLimits(stage) -> stage.stripRoom + stage.rowPad
        // A card the width limits is centred between the strip and the note.
        else -> stage.stripRoom + (stage.height - stage.noteRoom - stage.stripRoom - stage.height * scale) / 2
    }
    return TabTransform(
        scale = blend(1f, scale, progress),
        dx = blend(offset * (stage.width + stage.pageGap), cardLeft, progress),
        dy = blend(0f, cardTop, progress),
    )
}

/**
 * The tab at [index] in the grid: a full-size page at progress 0 as in the row, at 1 a card in its place's column and row.
 * [scroll] is the grid's position relative to the one that centres [selected] ([gridHome]); it moves the cards up.
 */
private fun gridTransform(index: Int, selected: Int, progress: Float, scroll: Float, stage: Stage, grid: GridSpec): TabTransform {
    val scale = gridScale(stage, grid)
    val cell = gridCell(stage, grid)
    val place = index.coerceAtLeast(0)
    // A column wider than its card centres the card.
    val cardLeft = grid.pad + place % grid.columns * (cell + grid.gap) + (cell - stage.width * scale) / 2
    val cardTop = gridRowTop(place / grid.columns, stage, grid) - gridHome(selected, stage, grid) - scroll
    return TabTransform(
        scale = blend(1f, scale, progress),
        dx = blend((index - selected) * (stage.width + stage.pageGap), cardLeft, progress),
        dy = blend(0f, cardTop, progress),
    )
}

fun cardBox(index: Int, selected: Int, progress: Float, scroll: Float, stage: Stage): CardBox {
    val t = tabTransform(index, selected, progress, scroll, stage)
    return CardBox(t.dx, t.dy, t.dx + stage.width * t.scale, t.dy + stage.height * t.scale * t.squash)
}

/**
 * Where a card's name stands: under the part of the card that is in the pane area, so a
 * neighbour that is half in view is named too. Null for a card that is out of view with its name ([inView]), sideways or,
 * as a grid scrolls them, up and down.
 */
fun labelSpan(box: CardBox, stage: Stage): ClosedFloatingPointRange<Float>? {
    if (!inView(box.copy(bottom = box.bottom + stage.labelRoom), stage)) return null
    val left = maxOf(box.left, 0f)
    val right = minOf(box.right, stage.width)
    return if (right > left) left..right else null
}

/**
 * The row's positions: from its first card centred to its last. The grid's: from its first rows to its last, held within
 * the content, relative to the position that centres [selected].
 */
fun scrollBounds(count: Int, selected: Int, stage: Stage): ClosedFloatingPointRange<Float> {
    // The drum turns by whole cards: from its first to its last in front.
    if (stage.drum != null) return -selected.toFloat()..(count - 1 - selected).coerceAtLeast(-selected).toFloat()
    val grid = stage.grid
    if (grid != null) return -gridHome(selected, stage, grid)..(gridMaxTop(stage, grid) - gridHome(selected, stage, grid))
    return cardScroll(0, selected, stage)..cardScroll((count - 1).coerceAtLeast(0), selected, stage)
}

/** The row offset that centres [index] in the fully open overview; the grid's, as far as the content allows. */
fun cardScroll(index: Int, selected: Int, stage: Stage): Float {
    if (stage.drum != null) return (index - selected).toFloat()
    val grid = stage.grid
    if (grid != null) return gridHome(index, stage, grid) - gridHome(selected, stage, grid)
    return (index - selected) * pitch(stage)
}

/**
 * Where a released row comes to rest: with the card its speed carries it to in the centre. A released grid goes on for a
 * moment at its speed and stops where it is, within the content.
 */
fun restingScroll(scroll: Float, velocity: Float, count: Int, selected: Int, stage: Stage): Float {
    // The rail turns the drum and lets it rest; nothing throws it.
    if (stage.drum != null) return Math.round(scroll).toFloat().coerceIn(scrollBounds(count, selected, stage))
    if (stage.grid != null) return (scroll + velocity * GRID_THROW_SECONDS).coerceIn(scrollBounds(count, selected, stage))
    val cards = Math.round((scroll + velocity * ROW_THROW_SECONDS) / pitch(stage))
    return (cards * pitch(stage)).coerceIn(scrollBounds(count, selected, stage))
}

/**
 * Whether a finger that lands on the open overview stops its cards where they are, and is no tap: in the grid while its
 * scroll is still [moving] (after a fling, a screen reader's step), as an Android list stops under a finger. The row goes
 * on to rest on its card, and a tap there picks the card under the finger as it did.
 */
fun catchesScroll(stage: Stage, moving: Boolean): Boolean = stage.grid != null && moving

/**
 * The spring a released row or grid goes to its rest with ([restingScroll]). The grid's is at rest within [GRID_REST] px:
 * the default's sub-pixel tail ran on for about 0.3 s after a fling, while a finger that landed only caught the scroll
 * ([catchesScroll]) and a tap was lost. The row's keeps the default.
 */
fun throwSpring(stage: Stage): SpringSpec<Float> =
    spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = if (stage.grid != null) GRID_REST else null)

/** Whether a card overlaps the pane area, on both axes. */
fun inView(box: CardBox, stage: Stage): Boolean =
    box.right > 0f && box.left < stage.width && box.bottom > 0f && box.top < stage.height

/** How far a finger moved along the way the overview scrolls: sideways in the row, up or down in the grid. */
fun along(dx: Float, dy: Float, stage: Stage): Float = if (stage.grid != null) dy else dx

/** How far a finger moved across the way the overview scrolls: the way a card is lifted. */
fun across(dx: Float, dy: Float, stage: Stage): Float = if (stage.grid != null) dx else dy

/** The pane area's size along the way the overview scrolls. */
fun scrollExtent(stage: Stage): Float = if (stage.grid != null) stage.height else stage.width

/**
 * How far a finger at [at] held in an edge band scrolls the overview in one frame ([edgeScroll]): at a side in the row, at the
 * top or bottom in the grid ([along], [scrollExtent]). The grid's top band starts under the strip's room, at the top of the
 * cards in sight, since the room hides the cards scrolled under it. With [roomScrolls] (a tab drag) a finger in the room
 * scrolls up too; without it (a pane move, whose capsule and Cancel stand there) it scrolls nothing. The bottom band and the
 * row's bands are the pane area's ends.
 */
fun edgeScrollAt(at: Offset, stage: Stage, edge: Float, step: Float, roomScrolls: Boolean = true): Float {
    if (!roomScrolls && inStripRoom(at.y, stage)) return 0f
    val start = if (stage.grid != null) stage.stripRoom else 0f
    return edgeScroll(along(at.x, at.y, stage) - start, scrollExtent(stage) - start, edge, step)
}

/**
 * The tab nearest to the middle of the pane area, in the row. The grid has no such tab: a screen reader steps it by rows
 * ([readerStep]), and the cards in view keep their surfaces ([keptPlaces]).
 */
fun centredTab(count: Int, selected: Int, progress: Float, scroll: Float, stage: Stage): Int {
    if (count <= 0) return 0
    if (stage.drum != null) return Math.round(selected + scroll).coerceIn(0, count - 1)
    val offset = progress * scroll / step(progress, stage)
    return Math.round(selected + offset).coerceIn(0, count - 1)
}

/**
 * The row's position that leaves every tab where it stands when the tab of the workspace
 * changes from index [from] to [to]: a tapped card then grows from its own place. In the grid, between progress 0 and 1 the
 * page term of dx still moves when the anchor changes (a vertical scroll cannot cancel it); at progress 1 every card stays put.
 */
fun rebasedScroll(scroll: Float, from: Int, to: Int, progress: Float, stage: Stage): Float {
    if (progress <= 0f) return scroll
    // The drum's turn counts cards: built around another card, it is turned that many cards less.
    if (stage.drum != null) return scroll - (to - from)
    val grid = stage.grid
    if (grid != null) return scroll + gridHome(from, stage, grid) - gridHome(to, stage, grid)
    return scroll - (to - from) * step(progress, stage) / progress
}

/**
 * Where the row rests after a card's drag ends (#218): built around [to] instead of [from] with every place kept
 * ([rebasedScroll]), it rests on the place nearest the middle among its [places], as a released row does
 * ([restingScroll]). An edge scroll during the drag can have left it between two cards.
 */
fun restAfterDrop(scroll: Float, from: Int, to: Int, places: Int, progress: Float, stage: Stage): Float =
    restingScroll(rebasedScroll(scroll, from, to, progress, stage), 0f, places, to, stage)

/** The card under a finger, its name included; null between cards. */
fun cardAt(x: Float, y: Float, count: Int, selected: Int, progress: Float, scroll: Float, stage: Stage): Int? =
    (0 until count).firstOrNull { index ->
        val box = cardBox(index, selected, progress, scroll, stage)
        x in box.left..box.right && y in box.top..(box.bottom + stage.labelRoom * progress)
    }

/*
 * Closing a tab by lifting its card (#216), as the recent apps of Android and the app switcher of
 * iOS close an app. One number per card, its lift: how far up it is, in px. Nothing is closed while
 * a finger holds the card; a card let go far enough or fast enough flies away, and only when it is
 * out of sight is its tab closed. The cards after it then slide over its place ([rowShifts]).
 */

/** A card let go this share of its way ([dismissWay]) or more closes its tab. */
private const val DISMISS_SHARE = 0.5f

/** How hard a card resists a pull it does not follow: the share of the pull it moves at first. */
private const val RESISTANCE = 0.55f

/** A flying card goes at least this share of its way per second, and at most this share. */
private const val FLIGHT_MIN_PER_SECOND = 3f
private const val FLIGHT_MAX_PER_SECOND = 12f

/** And speeds up by this share of its way per second, each second. */
private const val FLIGHT_SPEEDUP = 10f

/**
 * How far a card goes at [progress] until it and its name have left the pane area: up in the row, the pane area's width
 * sideways in the grid.
 */
fun liftDistance(progress: Float, stage: Stage): Float {
    // A grid card goes sideways, past the pane area's side.
    if (stage.grid != null) return stage.width
    val t = tabTransform(0, 0, progress, 0f, stage)
    return t.dy + stage.height * t.scale + stage.labelRoom * progress
}

/**
 * The way a card's lift at [progress] is measured on, for its close ([dismisses]), the feel of half of it ([liftCrossing])
 * and its speed: in the row its way up out of the pane area ([liftDistance]); in the grid the card's own width, so a pull of
 * half a card aside closes it in whichever column it stands. A closing card still flies the whole [liftDistance].
 */
fun dismissWay(progress: Float, stage: Stage): Float {
    if (stage.grid == null) return liftDistance(progress, stage)
    return stage.width * tabTransform(0, 0, progress, 0f, stage).scale
}

/** A pull of [pull] px that moves a card less and less: never [limit] px or more. */
private fun resisted(pull: Float, limit: Float): Float = limit * (1f - 1f / (pull * RESISTANCE / limit + 1f))

/**
 * How far up a card is when the finger on it moved [dy] px (down is positive). Up it follows the
 * finger, when its tab can be [closable]; any other way it is held back within [limit] px.
 */
fun cardLift(dy: Float, limit: Float, closable: Boolean): Float = when {
    dy < 0f && closable -> -dy
    dy < 0f -> resisted(-dy, limit)
    else -> -resisted(dy, limit)
}

/**
 * How far aside a grid card is when the finger on it moved [dx] px across the grid (right is positive): either way it
 * follows the finger, when its tab can be [closable]; else it is held back within [limit] px ([cardLift]). It goes to the
 * side the finger is on, the sign of [dx].
 */
fun cardAside(dx: Float, limit: Float, closable: Boolean): Float = cardLift(-kotlin.math.abs(dx), limit, closable)

/** Where a lift of [lift] px moves a card from its place: up with no [side] (0), else to that side (-1 left, 1 right). */
fun liftOffset(lift: Float, side: Float): Offset = if (side == 0f) Offset(0f, -lift) else Offset(side * lift, 0f)

/**
 * The side a grid card flies out to when no finger took it aside (a screen reader's Close tab): the nearer side of the pane
 * area for the card drawn with its top left corner at [corner] at [progress] ([cardCentre]). -1, left, for a card whose
 * middle is left of the pane area's by more than half a px; else 1, right, where every such card went before.
 */
fun nearerSide(corner: Offset, progress: Float, stage: Stage): Float =
    if (cardCentre(corner, progress, stage).x < stage.width / 2 - GRID_TIE) -1f else 1f

/**
 * Whether a card let go at [progress] of its way ([dismissWay]: up in the row, aside in the grid), moving at [velocity] (its
 * way per second, away from its place positive), flies away: by a fast movement's direction, else from half its way.
 */
fun dismisses(progress: Float, velocity: Float): Boolean = when {
    velocity >= FLING_PER_SECOND -> true
    velocity <= -FLING_PER_SECOND -> false
    else -> progress >= DISMISS_SHARE
}

/**
 * A lift that went from [before] to [after] of its way: true when it passed the share from which a
 * card let go closes its tab ([dismisses] at rest), false when it fell back under it, else null.
 * The finger feels each crossing, so it knows what letting go does before it lets go.
 */
fun liftCrossing(before: Float, after: Float): Boolean? = when {
    before < DISMISS_SHARE && after >= DISMISS_SHARE -> true
    before >= DISMISS_SHARE && after < DISMISS_SHARE -> false
    else -> null
}

/**
 * Each tab's place in the row when the tabs [closing] (their close is sent) have left it already:
 * the cards after one close its gap at once, before the server takes the tab away, which over a
 * remote connection takes a while. A closing tab has the place of the card after it, where its
 * own card is out of sight.
 */
fun rowSlots(tabs: List<String>, closing: Set<String>): Map<String, Int> {
    var next = 0
    return tabs.associateWith { id -> if (id in closing) next else next++ }
}

/**
 * The place of the card the row is built around ([anchor]), in the row without the tabs [closing]:
 * a closing tab hands it to the card after it, or to the one before when it was the last.
 */
fun rowAnchor(tabs: List<String>, closing: Set<String>, anchor: String): Int {
    val count = tabs.count { it !in closing }
    return (rowSlots(tabs, closing)[anchor] ?: 0).coerceAtMost(count - 1).coerceAtLeast(0)
}

/**
 * Whether a tab's close waits a while for Undo before it is sent (#228): only a close nobody was
 * [asked] about, since a question was an answer already, and only while the overview is [inSight]
 * to show the Undo note. Not the [lastTab]'s, whose close takes the overview away with it, unless
 * the overview [stays] after it (with the session strip, #247).
 */
fun undoable(asked: Boolean, lastTab: Boolean, inSight: Boolean, stays: Boolean = false): Boolean =
    !asked && (!lastTab || stays) && inSight

/**
 * The close that waits for Undo, one at a time: another close, leaving the overview or the end of
 * its time sends it, and Undo takes it back. [T] is what would be sent.
 */
class UndoSlot<T : Any> {
    var waiting: T? = null
        private set

    /** [next] waits now: the close that waited before, to be sent at once. */
    fun hold(next: T): T? = waiting.also { waiting = next }

    /** The waiting close, to be sent or taken back; null when none waits. */
    fun take(): T? = waiting.also { waiting = null }

    /** The waiting close when it is the one [mine] picks out, else nothing. */
    fun takeIf(mine: (T) -> Boolean): T? = waiting?.takeIf(mine)?.also { waiting = null }
}

/**
 * The places of a row of [tabs] cards: one more at its end for the new-tab card (#230), which
 * scrolls, centres and is tapped as a card is. Its place is the one after the last tab's.
 */
fun rowPlaces(tabs: Int): Int = tabs + 1

/**
 * Whether the tab the server selected, [selected] in [session], is the one New Tab asked for: a tab
 * that was not among the session's tabs [before] New Tab was tapped in [askedIn], wherever the
 * server put it. Null [before]: nothing is awaited. Another session's tabs were never in [before].
 */
fun opensNewTab(before: Set<String>?, askedIn: String?, session: String?, selected: String): Boolean =
    before != null && askedIn == session && selected !in before

/** The place one step on from [current] among [places] (tabs and the new-tab card); null past an end. */
fun nextPlace(current: Int, forward: Boolean, places: Int): Int? =
    (current + if (forward) 1 else -1).takeIf { it in 0 until places }

/**
 * The place a screen reader's scroll [forward] or back takes the overview to, from [scroll], with the cards built around
 * [selected]; null past an end. The row steps one place on from its centred tab ([nextPlace]). The grid steps to the first
 * place of the nearest row whose scroll ([cardScroll]) lies past [scroll] that way: the places of one row share a scroll, and
 * so can rows at an end of the content, so a step by one place or one row could leave the grid where it is.
 */
fun readerStep(scroll: Float, forward: Boolean, places: Int, selected: Int, progress: Float, stage: Stage): Int? {
    val grid = stage.grid ?: return nextPlace(centredTab(places, selected, progress, scroll, stage), forward, places)
    val rows = 0 until places step grid.columns
    return if (forward) rows.firstOrNull { cardScroll(it, selected, stage) > scroll + GRID_TIE }
    else rows.lastOrNull { cardScroll(it, selected, stage) < scroll - GRID_TIE }
}

/** What the overview says, to the eye and to a screen reader, once the tab [name] is gone. */
fun closedTabNote(name: String): String = "Closed ${name.ifEmpty { "tab" }}"

/**
 * What the overview says when a screen reader moved the tab [name] to [place] of [count], counted from 1. The place
 * makes each move a new text, so the live region reads a second move of the same tab too.
 */
fun movedTabNote(name: String, place: Int, count: Int): String = "Moved ${name.ifEmpty { "tab" }} to $place of $count"

/** A flying card: how far up it is and how fast it goes up, in px and px per second. */
data class Flight(val lift: Float, val speed: Float)

/**
 * [flight] after [seconds] more on a way of [distance] px: it keeps the speed it was let go at,
 * goes no slower than a card that would leave soon, and speeds up as it goes.
 */
fun flightStep(flight: Flight, seconds: Float, distance: Float): Flight {
    val speed = (maxOf(flight.speed, distance * FLIGHT_MIN_PER_SECOND) + distance * FLIGHT_SPEEDUP * seconds)
        .coerceAtMost(maxOf(flight.speed, distance * FLIGHT_MAX_PER_SECOND))
    return Flight(flight.lift + speed * seconds, speed)
}

/**
 * The tabs changed under the row: how far each card that stays is from where it was drawn
 * ([drawn], its top left corner) to where it stands now ([now]), across and, in the grid, down. It is
 * drawn that far off at first, and the distance runs out; a card new to the row has none.
 */
fun rowShifts(drawn: Map<String, Offset>, now: Map<String, Offset>): Map<String, Offset> =
    now.mapNotNull { (id, at) ->
        val was = drawn[id] ?: return@mapNotNull null
        (was - at).takeIf { it.getDistance() > 0.5f }?.let { id to it }
    }.toMap()

/** Where a released overview goes: by a fast movement's direction, else to the nearer end. */
fun settleTarget(progress: Float, velocity: Float): Float = when {
    velocity >= FLING_PER_SECOND -> 1f
    velocity <= -FLING_PER_SECOND -> 0f
    progress >= 0.5f -> 1f
    else -> 0f
}

/**
 * Whether a tap on the card of tab [id] closes the overview on it. [shown]: the tab the row is
 * built around. Without the [connected] server no other tab can be selected, so only the shown
 * tab's own card is gone back to; the others stay in the row, as the swipe between pages stays.
 */
fun selectsTab(id: String, shown: String?, connected: Boolean): Boolean = connected || id == shown

/** Page displacement and velocity are in page widths and page widths per second. */
fun pageStep(displacement: Float, velocity: Float): Int {
    if (displacement == 0f) return 0
    val direction = if (displacement < 0f) 1 else -1
    val outwardSpeed = -direction * velocity
    if (outwardSpeed <= -FLING_PER_SECOND) return 0
    return if (outwardSpeed >= FLING_PER_SECOND || kotlin.math.abs(displacement) >= 0.35f) direction else 0
}

/** Three fingers moved by [dy] px since they began at progress [start]: up opens, down closes. */
fun swipeProgress(start: Float, dy: Float, stage: Stage): Float =
    (start - dy / (stage.height * SWIPE_TRAVEL)).coerceIn(0f, 1f)

/** A pane's text scale and the overview's progress during one pinch. */
data class PinchStep(val scale: Float, val progress: Float)

/**
 * A pinch whose fingers are [ratio] times as far apart as when it began on a pane with text
 * scale [startScale]. Down to the smallest text size it scales the text; further in it
 * opens the overview, and the text returns to [startScale] on the way.
 */
fun pinchStep(startScale: Float, ratio: Float): PinchStep {
    val handOver = HostConnection.MIN_SCALE / startScale
    if (ratio >= handOver) return PinchStep((startScale * ratio).coerceAtMost(HostConnection.MAX_SCALE), 0f)
    val progress = ((1 - ratio / handOver) / PINCH_TRAVEL).coerceIn(0f, 1f)
    return PinchStep(pinchTextScale(startScale, progress), progress)
}

/** The text scale of the pane a pinch opened the overview from, at [progress]. */
fun pinchTextScale(startScale: Float, progress: Float): Float =
    if (progress >= 1f) startScale else blend(HostConnection.MIN_SCALE, startScale, progress)

/**
 * What a finger feels between two steps of one pinch ([pinchStep]): the place where it stops scaling the text and
 * starts to open the overview, crossed either way, and the largest text size once the fingers scale it there (#309).
 * The overview, opening, brings the text back to where the pinch began: that is no end, even at the largest size.
 */
fun pinchFeel(before: PinchStep, after: PinchStep): Feel? = when {
    before.progress == 0f && after.progress > 0f -> Feel.ThresholdOn
    before.progress > 0f && after.progress == 0f -> Feel.ThresholdOff
    after.progress == 0f && before.scale < HostConnection.MAX_SCALE && after.scale >= HostConnection.MAX_SCALE -> Feel.End
    else -> null
}

/** Two fingers on the overview, [ratio] times as far apart as when they began at progress [start]. */
fun spreadProgress(start: Float, ratio: Float): Float = (start - (ratio - 1f) / SPREAD_TRAVEL).coerceIn(0f, 1f)

/** How fast a value (progress, the row) moves per second when the fingers lift, from its last samples. */
class ReleaseSpeed {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private var lifted: Long? = null

    fun add(timeMs: Long, value: Float) {
        lifted = null
        samples.addLast(timeMs to value)
        while (samples.first().first < timeMs - SPEED_WINDOW_MS) samples.removeFirst()
    }

    /**
     * A finger lifted at [timeMs]. Several fingers leave one after another: the release is
     * when the first of them does, unless the others move on.
     */
    fun lift(timeMs: Long) {
        if (lifted == null) lifted = timeMs
    }

    /** The speed when the last finger lifts at [timeMs]; none when the fingers rested first. */
    fun perSecond(timeMs: Long): Float {
        val (lastTime, last) = samples.lastOrNull() ?: return 0f
        val (firstTime, first) = samples.first()
        if ((lifted ?: timeMs) - lastTime > SPEED_WINDOW_MS || lastTime == firstTime) return 0f
        return (last - first) * 1000f / (lastTime - firstTime)
    }
}

/** Whose gesture the fingers on the pane area are. */
enum class Touch {
    /** One finger in the workspace: the pane under it (scroll, selection, tap). */
    Pane,
    /** Two fingers in the workspace: the text size, then the overview. */
    Pinch,
    /** Three fingers: the overview's progress. */
    Swipe,
    /** Fingers on the overview: the row of cards. */
    Row,
    /**
     * A pane being moved across the overview ([PaneMoveSession]): its handle's long press began the gesture, or,
     * in pick mode, a press on the overview did. The finger is the pane's place until every finger has lifted.
     */
    PaneDrag,
    /**
     * A card picked up to move its tab (#218): a long press on it began the gesture. The finger is the card's place until
     * every finger has lifted; a second finger puts the card back.
     */
    TabDrag,
}

/**
 * The gesture after the number of fingers down changed. [current] is null for the first
 * finger. Three fingers are the overview's until all have lifted; a pane keeps only a
 * gesture of one finger that began in the workspace. A pane drag keeps every finger: the
 * fingers that cancel it must not become a pinch, a swipe or a scroll of the row. A tab drag
 * keeps them for the same reason.
 */
fun touchMode(current: Touch?, fingers: Int, progress: Float): Touch = when {
    current == Touch.PaneDrag -> Touch.PaneDrag
    current == Touch.TabDrag -> Touch.TabDrag
    current == Touch.Swipe || fingers >= 3 -> Touch.Swipe
    current == Touch.Pinch || current == Touch.Row -> current
    progress > 0f -> Touch.Row
    fingers == 2 -> Touch.Pinch
    else -> Touch.Pane
}

/** What one finger on the row of cards turns out to be ([rowFinger]). */
enum class RowFinger {
    /** Not known yet: the finger is still within the touch slop, and no long press has passed. */
    Undecided,
    /** Up or down first, from a card: the finger lifts that card (#216). */
    Lift,
    /** Sideways first: the finger moves the row. */
    Scroll,
    /** It stayed still on a card for a long press: the card is picked up to move its tab (#218). */
    Pickup,
}

/**
 * What one finger on the row is after it moved [travelX] and [travelY] px. Up or down before sideways, past the [slop],
 * on a [liftable] card lifts that card; sideways past the slop moves the row. A finger still within the slop once the
 * long press has passed ([held]) picks up a [pickable] card. The two movements keep the order they had before #218, so
 * a swipe up still closes a tab and a sideways move still scrolls: only a finger that stays still is picked up.
 */
fun rowFinger(travelX: Float, travelY: Float, slop: Float, held: Boolean, liftable: Boolean, pickable: Boolean): RowFinger {
    val sideways = kotlin.math.abs(travelX)
    val upright = kotlin.math.abs(travelY)
    return when {
        liftable && upright > slop && upright > sideways -> RowFinger.Lift
        sideways > slop -> RowFinger.Scroll
        held && pickable && sideways <= slop && upright <= slop -> RowFinger.Pickup
        else -> RowFinger.Undecided
    }
}

/**
 * Whether a long press may pick up the card a finger is on: a card it can lift ([liftable]), under a finger that stayed
 * alone since it went down ([fingers], the most there were), while the server can hear the move ([connected]) and no
 * pane is being moved ([paneMoving]), since a pane move takes the gesture first.
 */
fun mayPickUp(liftable: Boolean, fingers: Int, connected: Boolean, paneMoving: Boolean): Boolean =
    liftable && fingers == 1 && connected && !paneMoving

/**
 * How long one finger on the row still waits for the long press that picks its card up, in ms, [since] ms after it went
 * down, with a long press of [timeout] ms: 0 or less once that has passed. Null when no long press is awaited: the card
 * is not [pickable], the long press was [held] already, more than one finger came ([fingers], the most there were), or
 * the finger has [decided] to lift, scroll or spread.
 */
fun longPressLeft(timeout: Long, since: Long, pickable: Boolean, held: Boolean, fingers: Int, decided: Boolean): Long? =
    if (pickable && !held && fingers == 1 && !decided) timeout - since else null

/**
 * The place among [count] tabs where a dragged card whose middle is at [x] and [y] px would drop: the place whose middle is
 * nearest, in the row or grid built around [anchor] at [progress] and [scroll]. The row compares [x] only. Past the last
 * card, over the new-tab card too, is the end; before the first card is the start.
 */
fun dropPlace(x: Float, y: Float, count: Int, anchor: Int, progress: Float, scroll: Float, stage: Stage): Int =
    (0 until count).minByOrNull { place ->
        val box = cardBox(place, anchor, progress, scroll, stage)
        val dx = (box.left + box.right) / 2 - x
        if (stage.grid == null) kotlin.math.abs(dx) else {
            val dy = (box.top + box.bottom) / 2 - y
            dx * dx + dy * dy
        }
    } ?: 0

/**
 * The middle of a card whose top left corner is at [corner], at [progress]: where a dragged card is, for [dropPlace]. A card
 * is the pane area scaled.
 */
fun cardCentre(corner: Offset, progress: Float, stage: Stage): Offset {
    val size = cardBox(0, 0, progress, 0f, stage)
    return Offset(corner.x + (size.right - size.left) / 2, corner.y + (size.bottom - size.top) / 2)
}

/**
 * What a pane holds back while the finger on it may still be the first of several: a finger
 * is the pane's once it has stayed alone for a moment or lifted alone. Fingers that were
 * joined by others leave nothing in the terminal.
 */
class FirstFinger {
    private enum class Is { Waiting, Alone, Joined }

    private var finger = Is.Alone
    private var dx = 0f
    private var dy = 0f

    fun down() {
        finger = Is.Waiting
        dx = 0f
        dy = 0f
    }

    /** The finger moved: what to move the terminal by now, null while it is held back or not the pane's. */
    fun drag(dx: Float, dy: Float): Pair<Float, Float>? = when (finger) {
        Is.Alone -> dx to dy
        Is.Joined -> null
        Is.Waiting -> {
            this.dx += dx
            this.dy += dy
            null
        }
    }

    /** The finger stayed or lifted alone: its movement so far, null when that was said before. */
    fun alone(): Pair<Float, Float>? {
        if (finger != Is.Waiting) return null
        finger = Is.Alone
        return dx to dy
    }

    /** More fingers came down: the gesture is not the pane's, until the next finger goes down. */
    fun joined() {
        finger = Is.Joined
    }
}

/** Tabs that keep surfaces: the selected one and those around the one in view. */
fun keptTabs(count: Int, selected: Int, centred: Int): Set<Int> =
    ((centred - NEIGHBOURS)..(centred + NEIGHBOURS)).filter { it in 0 until count }.toSet() +
        setOfNotNull(selected.takeIf { it in 0 until count })

/**
 * The places that keep surfaces: the row's [keptTabs] around the centred tab. In the workspace (progress 0) the tab in view
 * is the anchor, in the grid too; once the grid opens, the places whose card at progress 1 overlaps the pane area grown by
 * one row pitch above and below, and [anchor]. Between the two the anchor's page neighbours keep theirs as well.
 */
fun keptPlaces(count: Int, anchor: Int, progress: Float, scroll: Float, stage: Stage): Set<Int> {
    // On the drum the cards around the front keep theirs, and the tab the wheel was opened on.
    if (stage.drum != null) return keptTabs(count, anchor, centredTab(count, anchor, progress, scroll, stage))
    val grid = stage.grid
    if (grid == null) return keptTabs(count, anchor, centredTab(count, anchor, progress, scroll, stage))
    // The workspace shows the anchor's page whatever the layout, and its neighbours are the pages next to it.
    if (progress <= 0f) return keptTabs(count, anchor, anchor)
    val room = gridRowStep(stage, grid)
    val cells = (0 until count).filter { place ->
        val box = cardBox(place, anchor, 1f, scroll, stage)
        box.right > 0f && box.left < stage.width && box.bottom > -room && box.top < stage.height + room
    }.toSet() + setOfNotNull(anchor.takeIf { it in 0 until count })
    // On the way between the workspace and the grid the pages next to the anchor's come in from the sides, from cells that
    // can be far out of view (a scrolled grid that closes with no pick): they keep their surfaces until it has opened.
    return if (progress < 1f) cells + keptTabs(count, anchor, anchor) else cells
}

/** What a tab's panes are to the core. */
enum class PaneRole {
    /** Shown: the core takes their terminals and fits them to the panes. */
    Shown,
    /** Drawn, but nothing is taken or sized. */
    Preview,
    /** No surface. */
    Detached,
}

/** Keep an active row on its scroll basis until it is rebased. In the workspace only a
 * local pick or swipe awaiting a change from [lastConfirmed] can override [selected]. */
internal fun displayAnchor(selected: String, lastConfirmed: String?, anchor: String?, rowActive: Boolean): String =
    if (rowActive || selected == lastConfirmed) anchor ?: selected else selected

/**
 * The role of every tab's panes. Only the tab that fills the pane area is shown, and only
 * when it is the selected one: [selected] is the server's tab, [anchor] the index of the
 * tab the row is built around (a tapped card, until the server has selected it). The tabs in [kept] ([keptPlaces]) and the
 * selected one keep their surfaces; the others have none.
 * [closed]: the overview is closed (its progress is 0): from its first frame open every tab is a preview.
 * [pinching]: a pinch that opened the overview still has its fingers down, so the text size
 * may change again.
 */
fun tabRoles(tabs: List<String>, selected: String?, anchor: Int, kept: Set<Int>, closed: Boolean, pinching: Boolean): List<PaneRole> {
    val surfaced = kept + tabs.indexOf(selected)
    return tabs.mapIndexed { index, id ->
        when {
            index !in surfaced -> PaneRole.Detached
            id == selected && index == anchor && closed && !pinching -> PaneRole.Shown
            else -> PaneRole.Preview
        }
    }
}

/** The tab whose panes are shown: its session, its ID and the blocks of the panes it draws (a zoom hides the rest). */
data class ShownTab(val session: String, val tab: String, val blocks: Set<String>)

/**
 * The tab [window] of [session] shown with its drawn panes ([keyboardPaneBlocks]): a pane a zoom
 * hides has no view, so it is no return when the zoom ends. Null for a tab without panes.
 */
internal fun shownTab(session: String, window: WindowInfo): ShownTab? =
    if (tabBlocks(window).isEmpty()) null else ShownTab(session, window.id, keyboardPaneBlocks(window).toSet())

/**
 * Which panes come back to where they were. Looking at the overview and returning to the tab
 * it was opened from (cancelled, reversed, or that tab's own card tapped) is no use of its
 * terminals: the panes that were shown there are shown as they were, and nothing is taken.
 * Another tab, a pane made meanwhile, a pane that was used on the way and another session are
 * shown by the usual rules. One mark belongs to one connection, and a return is used once.
 */
class ReturnMark {
    /** The tab shown at the last step. */
    private var last: ShownTab? = null
    /** The tab the overview was opened from, while no tab is shown. */
    private var from: ShownTab? = null
    private var back: Set<String> = emptySet()

    /**
     * The tab shown now ([shown], null while the overview is open at all) in the selected
     * [session]: the blocks of its panes that came back.
     */
    fun step(shown: ShownTab?, session: String?): Set<String> {
        val before = last
        last = shown
        if (shown == null) {
            if (before != null) from = before
            // Another session meanwhile: the tab it was opened from is not come back to.
            if (from?.session != session) from = null
            back = emptySet()
        } else if (before == null) {
            val opened = from
            from = null
            back = if (opened != null && opened.session == shown.session && opened.tab == shown.tab) opened.blocks intersect shown.blocks
            else emptySet()
        } else if (before.session != shown.session || before.tab != shown.tab) {
            // One tab straight after another: a tab switch.
            back = emptySet()
        } else {
            back = back intersect shown.blocks
        }
        return back
    }

    /** The pane of [block] was used while the overview was open (a pinch changed its text size). */
    fun used(block: String) {
        from = from?.let { it.copy(blocks = it.blocks - block) }
    }

    /**
     * The pane views went away. Behind a [covered] workspace (the sidebar in the panes' place,
     * Settings) the panes are not gone: the overview's way back to the tab it was opened from
     * stays, and drawn again the tab's panes come back as from the overview ([PaneCover] keeps the
     * workspace's own panes the same way). Without a cover (no tab left) nothing is come back to.
     */
    fun interrupted(covered: Boolean) {
        last = null
        back = emptySet()
        // While the overview is open no tab is shown and [from] holds the tab it was opened from.
        if (!covered) from = null
    }
}
