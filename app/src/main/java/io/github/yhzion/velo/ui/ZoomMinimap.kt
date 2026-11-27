package io.github.yhzion.velo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.TileDot
import io.github.yhzion.velo.ui.theme.VeloPopover
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.glass
import io.github.yhzion.velo.ui.theme.onScreen
import io.github.yhzion.velo.ui.theme.rememberFeel
import io.github.yhzion.velo.ui.theme.rounded
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/*
 * The minimap at the caption's end while the shown tab is zoomed (#141): a cell per pane, where the layout puts it
 * without the zoom, the floating ones over it as they float over the panes (#158), the zoomed one filled. A tap opens
 * a larger picker above the caption, a horizontal swipe zooms the next or previous pane. Only the composables at the
 * end touch Compose; the rules above them are tested.
 */

/**
 * A pane of a zoomed tab: its [rect] in a unit square, whether it is [floating] over the tiled ones, and the [alert]
 * of a report not yet seen. Its name is not here: the caption holds this all the time, and a hidden pane's title
 * can change many times a second, so only the open picker reads it ([zoomPaneName]).
 */
internal data class ZoomCell(val block: String, val rect: PaneRect, val alert: TabIndicator?, val floating: Boolean = false)

/** The zoomed [tab] as its minimap draws it: the [zoomed] pane among [cells], in layout order, the floating ones last. */
internal data class ZoomMap(val tab: String, val zoomed: String, val cells: List<ZoomCell>) {
    /** The zoomed pane's number, from 1, as the pane menu numbers it ([paneLabel]): the tiled panes come first. */
    val number: Int get() = cells.indexOfFirst { it.block == zoomed } + 1
    val count: Int get() = cells.size

    /** The pane [by] steps from the zoomed one in layout order, around the end: 1 is the next, -1 the previous. */
    fun step(by: Int): String = cells[(number - 1 + by).mod(count)].block

    /**
     * The pane a horizontal swipe of [dx] brings, as a page turns: toward the start of the line the next, toward its
     * end the previous. The start is on the left, on the right in [rtl], where the layout is drawn mirrored.
     */
    fun swiped(dx: Float, rtl: Boolean): String = step(if ((dx < 0f) != rtl) 1 else -1)
}

/**
 * The rectangles of [window]'s panes in a unit square: the tiled ones as [paneRects] lays them out without the zoom
 * and without gaps, then the floating ones in their own boxes, kept inside the square. [rtl] mirrors them, as the
 * panes are drawn.
 */
internal fun zoomCells(window: WindowInfo, rtl: Boolean): List<PaneRect> {
    val tiled = paneRects(window.copy(zoomed = null), 1f, 1f, 0f, rtl)
    val placed = tiled.mapTo(mutableSetOf()) { it.block }
    val floating = window.floating.filter { it.block !in placed }.distinctBy { it.block }.map { f ->
        val left = f.x.coerceIn(0f, 1f)
        val right = (f.x + f.w).coerceIn(left, 1f)
        val top = f.y.coerceIn(0f, 1f)
        val bottom = (f.y + f.h).coerceIn(top, 1f)
        if (rtl) PaneRect(f.block, 1f - right, top, 1f - left, bottom) else PaneRect(f.block, left, top, right, bottom)
    }
    return tiled + floating
}

/**
 * The minimap of [window], or null while it is not zoomed on one of its panes, tiled or floating. [alert] gives the
 * report of a pane not yet seen ([TabActivity.badge] of its [paneRow]). The zoomed pane is on screen, so its cell
 * shows none.
 */
internal fun zoomMap(window: WindowInfo, rtl: Boolean, alert: (String) -> TabIndicator?): ZoomMap? {
    val zoomed = window.zoomed ?: return null
    val rects = zoomCells(window, rtl)
    if (rects.none { it.block == zoomed }) return null
    val floating = window.floating.mapTo(mutableSetOf()) { it.block } - window.layout?.blocks().orEmpty().toSet()
    return ZoomMap(window.id, zoomed, rects.map { r ->
        ZoomCell(r.block, r, if (r.block == zoomed) null else alert(r.block), floating = r.block in floating)
    })
}

/** The name of [block] of [window] in the picker: its pane's alone ([paneRow]), without the tab's label. */
internal fun zoomPaneName(state: UiState, window: WindowInfo, block: String): String =
    window.paneRow(state, state.session.orEmpty(), block).name

/** The caption's short line beside the minimap. */
internal fun zoomLabel(map: ZoomMap): String = "Full Screen ${map.number}/${map.count}"

/** What a screen reader says for the minimap, a button. */
internal fun zoomMinimapDescription(map: ZoomMap): String = "Full screen, pane ${map.number} of ${map.count}. Show panes"

/** The panes the minimap marks, as a screen reader says them after the button; null when none is marked. */
internal fun zoomMinimapState(map: ZoomMap): String? = map.cells.withIndex().filter { it.value.alert != null }
    .takeIf { it.isNotEmpty() }?.joinToString("; ") { (i, cell) -> "Pane ${i + 1}, ${cell.alert!!.description}" }

/** What a screen reader says for [cell], the [index]th of the picker's cells, its pane [name]d so. */
internal fun zoomCellDescription(map: ZoomMap, index: Int, cell: ZoomCell, name: String): String =
    "Pane ${index + 1} of ${map.count}, $name" + cell.alert?.let { ", ${it.description}" }.orEmpty()

/**
 * The picker's map ([cells] scaled to it) in dp: at least [width] by [height], and grown so that the narrowest and the
 * shortest cell are [least] across, a finger's target. A map grown past the popover scrolls in it (#144).
 */
internal fun zoomPickerSize(cells: List<PaneRect>, width: Float, height: Float, least: Float): Pair<Float, Float> {
    val w = cells.filter { it.width > 0f }.maxOfOrNull { least / it.width } ?: 0f
    val h = cells.filter { it.height > 0f }.maxOfOrNull { least / it.height } ?: 0f
    return max(width, w) to max(height, h)
}

/** A picker cell's place in a map [width] by [height] in dp: its left, top, width and height. */
internal data class PickerBox(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The map that [zoomPickerSize] gives [map]: grown for the tiled cells alone, so that a small floating box, or a sliver
 * of one past the pane area, does not grow the whole map.
 */
internal fun zoomPickerSize(map: ZoomMap, width: Float, height: Float, least: Float): Pair<Float, Float> =
    zoomPickerSize(map.cells.filter { !it.floating }.map { it.rect }, width, height, least)

/**
 * Where [cell] lies in the picker's map [width] by [height]: its rectangle scaled to it. A floating cell is at least
 * [least] across, as the tiled ones are made, grown about its middle so that RTL still mirrors it, and kept inside
 * the map: the map was not grown for it.
 */
internal fun zoomPickerBox(cell: ZoomCell, width: Float, height: Float, least: Float): PickerBox {
    val r = cell.rect
    if (!cell.floating) return PickerBox(width * r.left, height * r.top, width * r.width, height * r.height)
    val w = max(width * r.width, least).coerceAtMost(width)
    val h = max(height * r.height, least).coerceAtMost(height)
    val left = width * (r.left + r.width / 2) - w / 2
    val top = height * (r.top + r.height / 2) - h / 2
    return PickerBox(left.coerceIn(0f, width - w), top.coerceIn(0f, height - h), w, h)
}

/** The minimap's board size: 46 by 30 (`.mini`), its cells 4 inside its edge. */
private const val MiniWidth = 46f
private const val MiniHeight = 30f
private const val MiniInset = 4f

/** The picker's map at least (`.bigmap` in a pop 214 wide with 10 inside), and the room around each cell. */
private const val PickerWidth = 194f
private const val PickerHeight = 128f
private const val PickerCellInset = 2f

/** A finger's target, the least size of a picker cell. */
private const val TargetDp = 48f

/**
 * The minimap of a zoomed tab ([ZoomMap]) at the caption's end, [height] board units high in board [unit]s, and
 * its picker, which names the panes with [name]. A tap opens the picker above the caption, a horizontal swipe zooms
 * the next or previous pane; both call [onZoom] with the pane to zoom. While it is not [enabled] (no server to send
 * the zoom to) it only shows the panes: it takes no tap or swipe, and an open picker closes. Another tab closes it too.
 */
@Composable
internal fun ZoomMinimap(map: ZoomMap, unit: Dp, height: Float, enabled: Boolean, name: (String) -> String, onZoom: (String) -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val feel = rememberFeel()
    val current by rememberUpdatedState(map)
    val zoom by rememberUpdatedState(onZoom)
    val live by rememberUpdatedState(enabled)
    // The tab the picker was opened for: another tab, or the connection going, closes it.
    var openFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(enabled) { if (!enabled) openFor = null }
    var at by remember { mutableStateOf<IntRect?>(null) }
    val shape = rounded(unit * 6 * height / MiniHeight)
    val sheet = VeloTheme.colors.sheet
    val line = VeloTheme.surface.secondary
    val fill = VeloTheme.surface.accent
    val ring = VeloTheme.surface.glass
    Box(
        Modifier.testTag("caption-minimap").size(unit * MiniWidth * height / MiniHeight, unit * height)
            .onGloballyPositioned { at = it.onScreen() }
            .glass(shape)
            .semantics {
                contentDescription = zoomMinimapDescription(map)
                zoomMinimapState(map)?.let { stateDescription = it }
                role = Role.Button
                if (!enabled) disabled()
                else {
                    onClick { openFor = current.tab; true }
                    customActions = listOf(
                        CustomAccessibilityAction("Next pane") { zoom(current.step(1)); true },
                        CustomAccessibilityAction("Previous pane") { zoom(current.step(-1)); true },
                    )
                }
            }
            .pointerInput(rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!live) return@awaitEachGesture
                    var swiping = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val dx = change.position.x - down.position.x
                        if (!swiping && abs(dx) > viewConfiguration.touchSlop) swiping = true
                        if (swiping) change.consume()
                        if (!change.pressed) {
                            if (swiping) {
                                feel(Feel.Step)
                                zoom(current.swiped(dx, rtl))
                            } else if (!change.isConsumed) openFor = current.tab
                            break
                        }
                    }
                }
            }
            .padding(unit * MiniInset * height / MiniHeight)
            .drawBehind {
                val edge = 1.dp.toPx()
                val corner = CornerRadius(2.dp.toPx())
                val dot = unit.toPx() * 5f
                for (cell in map.cells) {
                    val r = cell.rect
                    val topLeft = Offset(r.left * size.width + edge, r.top * size.height + edge)
                    val box = Size(r.width * size.width - 2 * edge, r.height * size.height - 2 * edge)
                    if (box.width <= 0f || box.height <= 0f) continue
                    if (cell.block == map.zoomed) drawRoundRect(fill, topLeft, box, corner)
                    else {
                        // A floating cell hides the lines of the cells under it, as its pane hides theirs.
                        if (cell.floating) drawRoundRect(ring, topLeft, box, corner)
                        drawRoundRect(line, topLeft + Offset(edge / 2, edge / 2), Size(box.width - edge, box.height - edge), corner, Stroke(edge))
                    }
                }
                // The dots over every cell: a floating cell hides no report of a pane under it.
                for (cell in map.cells) {
                    val r = cell.rect
                    if (r.width * size.width <= 2 * edge || r.height * size.height <= 2 * edge) continue
                    val tint = cell.alert?.let(::tileDot)?.let { dotTint(it, sheet.accent, sheet.warning, sheet.danger) } ?: continue
                    // At the cell's upper end corner, ringed in the minimap's glass.
                    val x = if (rtl) r.left * size.width + dot / 2 else r.right * size.width - dot / 2
                    val center = Offset(x, r.top * size.height + dot / 2)
                    drawCircle(ring, dot / 2 + 1.5f * unit.toPx(), center)
                    drawCircle(tint, dot / 2, center)
                }
            },
    )
    val density = LocalDensity.current
    // A cell's target is inside the room around it.
    val least = TargetDp + 2 * VeloTheme.medium(PickerCellInset.dp).value
    val (mapW, mapH) = zoomPickerSize(map, VeloTheme.medium(PickerWidth.dp).value, VeloTheme.medium(PickerHeight.dp).value, least)
    val padding = VeloTheme.sizes.overlayPadding
    // Above the caption, its end at the minimap's end: an anchor that reaches the screen's bottom leaves no room under it.
    val most = VeloTheme.sizes.dialogWidth
    val anchor = {
        at?.let { mini ->
            // No wider than the popover, which scrolls a wider map: its end stays at the minimap's.
            val width = with(density) { minOf(mapW.dp + padding * 2, most).roundToPx() }
            val left = if (rtl) mini.left else mini.right - width
            IntRect(left, mini.top, left + width, Int.MAX_VALUE / 2)
        }
    }
    // It edits no text: the keyboard stays up for the full screen pane, which keeps its size.
    VeloPopover(enabled && openFor == map.tab, onDismissRequest = { openFor = null }, modifier = Modifier.testTag("minimap-picker"),
        anchor = anchor, keepImeTarget = true) {
        ZoomPicker(map, mapW, mapH, least, name) { block ->
            openFor = null
            if (block != map.zoomed) zoom(block)
        }
    }
}

/** The colour of a cell's [dot], as the tab's tile colours its own. */
private fun dotTint(dot: TileDot, unread: Color, waiting: Color, failed: Color): Color = when (dot) {
    TileDot.Unread -> unread
    TileDot.Waiting -> waiting
    TileDot.Failed -> failed
}

/**
 * The picker (`.pop`): its title, then a button per pane where the layout puts it, [mapW] by [mapH] dp, named with
 * [name]. The cells are placed as [ZoomMap] has them, already mirrored in RTL, at least [least] across
 * ([zoomPickerBox]); the floating ones come last, so they are drawn and touched over the tiled ones. A map larger than
 * the popover scrolls, as the minimized panes' row does, and shows the zoomed cell, at once.
 */
@Composable
private fun ZoomPicker(map: ZoomMap, mapW: Float, mapH: Float, least: Float, name: (String) -> String, onPick: (String) -> Unit) {
    val c = VeloTheme.surface
    val sheet = VeloTheme.colors.sheet
    val unit = VeloTheme.medium(1.dp)
    Text("Panes in this tab", Modifier.padding(bottom = unit * 8), color = c.secondary, style = VeloTheme.type.caption)
    val width = mapW.dp
    val height = mapH.dp
    val across = rememberScrollState()
    val down = rememberScrollState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    // The zoomed cell in the middle of what shows, without a slide; again when another device zooms another pane.
    // The map can change while the effect waits for the viewports: it reads the one shown then.
    val shown by rememberUpdatedState(Triple(map, mapW, mapH))
    LaunchedEffect(map.zoomed) {
        snapshotFlow { across.viewportSize > 0 && down.viewportSize > 0 }.first { it }
        val (now, nowW, nowH) = shown
        val cell = now.cells.firstOrNull { it.block == now.zoomed } ?: return@LaunchedEffect
        val box = zoomPickerBox(cell, nowW, nowH, least)
        with(density) {
            val middle = (box.left + box.width / 2).dp.toPx()
            // In RTL the scroll starts at the map's right edge.
            val x = if (rtl) nowW.dp.toPx() - middle else middle
            across.scrollTo((x - across.viewportSize / 2f).roundToInt())
            down.scrollTo(((box.top + box.height / 2).dp.toPx() - down.viewportSize / 2f).roundToInt())
        }
    }
    // One scroll a node: a node keeps one scroll action, and a screen reader needs both.
    Box(Modifier.testTag("minimap-picker-map").horizontalScroll(across)) {
        Box(Modifier.testTag("minimap-picker-rows").verticalScroll(down)) {
            Box(Modifier.size(width, height)) {
                for ((index, cell) in map.cells.withIndex()) {
                    val box = zoomPickerBox(cell, mapW, mapH, least)
                    val on = cell.block == map.zoomed
                    val named = name(cell.block)
                    Box(
                        // From the left edge, not the line's start: the rectangles are mirrored already.
                        Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset(box.left.dp, box.top.dp).size(box.width.dp, box.height.dp)
                            .padding(unit * PickerCellInset).testTag("minimap-picker-cell-${cell.block}")
                            .background(if (on) c.accent else c.glass, rounded(unit * 7))
                            .then(if (on) Modifier else Modifier.border(VeloTheme.sizes.edge, c.glassEdge, rounded(unit * 7)))
                            .clickable(role = Role.Button) { onPick(cell.block) }
                            .semantics {
                                contentDescription = zoomCellDescription(map, index, cell, named)
                                selected = on
                                // In the panes' order, not where they lie: a floating cell lies among the tiled ones.
                                traversalIndex = index.toFloat()
                            }
                            .padding(unit * 7),
                    ) {
                        Column(Modifier.align(Alignment.BottomStart).clearAndSetSemantics { },
                            verticalArrangement = Arrangement.spacedBy(unit * 2)) {
                            Text(named, color = if (on) c.onAccent else c.text, style = VeloTheme.type.caption.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Pane ${index + 1}", color = if (on) c.onAccent.copy(alpha = 0.7f) else c.secondary, style = VeloTheme.type.caption,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        cell.alert?.let(::tileDot)?.let { dot ->
                            Box(Modifier.align(Alignment.TopEnd).size(unit * 6)
                                .background(dotTint(dot, sheet.accent, sheet.warning, sheet.danger), CircleShape))
                        }
                    }
                }
            }
        }
    }
}
