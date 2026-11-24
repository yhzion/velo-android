package io.github.yhzion.velo.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.github.yhzion.velo.ui.KeyLegend
import kotlin.math.max
import kotlin.math.roundToInt

// The controls of a settings sheet: a group card and its rows, a switch, a slider, a segmented
// capsule and a key that is chosen or not. No image shows the Duo's settings; these are derived
// from the sheets that are shown (AK, MB1), the tab capsule (D12) and the iOS system controls
// (docs/design-tokens.md). Each reads the colours of the surface it is on.

/** The knob of a switch and of a slider. derived: white in both appearances, as the iOS system controls'. */
private val Knob = Color.White

/** The shade under a knob, so that it stands off a light track. derived. */
private val KnobShade = Color(0x40000000)

/** The heading above a [GroupCard]: secondary text, inset to the card's own padding. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    val s = VeloTheme.sizes
    Text(
        text, modifier.padding(start = s.overlayPadding, end = s.overlayPadding, top = s.menuPadding, bottom = s.menuPadding).semantics { heading() },
        style = VeloTheme.type.header, color = VeloTheme.surface.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** A group of rows on glass with round corners (AK grouped card). Put a [GroupDivider] between two rows. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = VeloTheme.sizes
    val c = VeloTheme.surface
    val panel = LocalSettingsPanel.current
    // Groups describe related rows; unlike their buttons and fields they aren't glass controls.
    val frame = if (panel) {
        val shape = rounded(VeloTheme.radii.paneCompact)
        Modifier.clip(shape).border(s.edge, c.divider, shape).padding(vertical = s.cellInset)
    } else Modifier.clip(rounded(VeloTheme.radii.card)).background(c.glass)
    Column(
        modifier.fillMaxWidth().then(frame).padding(horizontal = s.overlayPadding),
        content = content,
    )
}

/** The hairline between two rows of a [GroupCard] (AK). */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(VeloTheme.sizes.edge).background(VeloTheme.surface.divider))
}

/**
 * A row of a [GroupCard]: [label], and its control at the end. A control that does not fit
 * beside the label (a long segmented capsule on the folded screen, or at large text) goes
 * under it, at the start.
 */
@Composable
fun SettingRow(label: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    val s = VeloTheme.sizes
    Layout(
        content = {
            Text(label, style = VeloTheme.type.body, color = VeloTheme.surface.text)
            Box { control() }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = s.row).padding(vertical = s.cellInset),
    ) { measurables, constraints ->
        val gap = s.rowGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = measurables[1].measure(loose)
        val wanted = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val beside = wanted + gap + end.width <= constraints.maxWidth
        val text = measurables[0].measure(loose.copy(maxWidth = if (beside) max(0, constraints.maxWidth - gap - end.width) else constraints.maxWidth))
        val content = if (beside) max(text.height, end.height) else text.height + gap / 2 + end.height
        val height = max(content, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            if (beside) {
                text.placeRelative(0, (height - text.height) / 2)
                end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
            } else {
                val top = (height - content) / 2
                text.placeRelative(0, top)
                end.placeRelative(0, top + text.height + gap / 2)
            }
        }
    }
}

/**
 * A switch, drawn only: the row it is in takes the tap and says the state (`toggleable`), so
 * the switch is found by what the row says. On, its track is the accent.
 */
@Composable
fun VeloSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(SwitchMillis), label = "Switch")
    Box(
        modifier.size(s.switchWidth, s.switchHeight).drawBehind {
            val round = CornerRadius(size.height / 2)
            drawRoundRect(lerp(c.glassSelected, c.accent, on), cornerRadius = round)
            val radius = size.height / 2 - s.switchInset.toPx()
            val centre = Offset(size.height / 2 + on * (size.width - size.height), size.height / 2)
            drawCircle(KnobShade, radius, centre.copy(y = centre.y + density))
            drawCircle(Knob, radius, centre)
        },
    )
}

private const val SwitchMillis = 120

/** The share of a slider's travel at [x] of a track [width] wide, whose knob stays [inset] inside each end. */
fun sliderFraction(x: Float, width: Float, inset: Float): Float {
    val travel = width - 2 * inset
    return if (travel <= 0f) 0f else ((x - inset) / travel).coerceIn(0f, 1f)
}

/**
 * The value at [fraction] of [range], moved to the nearest stop: the two ends and [steps]
 * values evenly between them. Without steps every value of the range is one.
 */
fun sliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    val f = fraction.coerceIn(0f, 1f)
    val stops = steps + 1
    val at = if (steps > 0) (f * stops).roundToInt() / stops.toFloat() else f
    return range.start + at * (range.endInclusive - range.start)
}

/** A slider with [steps] ticks when a finger moves it [from] one stop [to] another; without steps there is no stop to feel. */
fun sliderTicks(steps: Int, from: Float, to: Float): Boolean = steps > 0 && from != to

/**
 * A slider: a glass track, filled with the accent up to a white knob. A tap or a drag along
 * it sets the value; the left and right keys and a screen reader's own actions move it one
 * stop. [onValueChangeFinished] is called when a tap, a drag or such a step is over.
 * With [steps], a finger feels each stop it moves the knob to ([sliderTicks]).
 */
@Composable
fun VeloSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
    /** A track that shows the range itself (the custom colour's hue): these colours from the knob's first stop to its last, thicker, with no filled part. */
    trackColors: List<Color>? = null,
    /** A colour in the knob, inside its white rim: what the value stands for. */
    knobColor: Color? = null,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val current by rememberUpdatedState(value)
    val feel = rememberFeel()
    // One stop, or a twentieth of the range where there are none: what a key or a screen reader moves by.
    val step = if (steps > 0) span / (steps + 1) else span / 20
    fun moveTo(target: Float): Boolean {
        val next = sliderValue(if (span > 0f) (target - valueRange.start) / span else 0f, valueRange, steps)
        if (next == current) return false
        change(next)
        finished?.invoke()
        return true
    }
    Box(
        modifier.height(s.control)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
                setProgress { moveTo(it) }
            }
            // The keys are read around the focus target: placed before it, this node is its parent.
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) false
                else when (it.key) {
                    Key.DirectionLeft -> moveTo(current - step)
                    Key.DirectionRight -> moveTo(current + step)
                    else -> false
                }
            }
            .focusable()
            .pointerInput(valueRange, steps, s.sliderKnob) {
                val inset = s.sliderKnob.toPx() / 2
                detectTapGestures { at ->
                    val next = sliderValue(sliderFraction(at.x, size.width.toFloat(), inset), valueRange, steps)
                    if (sliderTicks(steps, current, next)) feel(Feel.Step)
                    change(next)
                    finished?.invoke()
                }
            }
            .pointerInput(valueRange, steps, s.sliderKnob) {
                val inset = s.sliderKnob.toPx() / 2
                // The stop last felt: a drag's moves come faster than the value comes back as [current].
                var felt = current
                fun dragTo(x: Float) {
                    val next = sliderValue(sliderFraction(x, size.width.toFloat(), inset), valueRange, steps)
                    if (sliderTicks(steps, felt, next)) feel(Feel.Step)
                    felt = next
                    change(next)
                }
                // A drag along the track only: one across it stays the list's own scroll.
                detectHorizontalDragGestures(
                    onDragStart = { at -> felt = current; dragTo(at.x) },
                    onDragEnd = { finished?.invoke() },
                    onDragCancel = { finished?.invoke() },
                ) { pointer, _ ->
                    pointer.consume()
                    dragTo(pointer.position.x)
                }
            }
            .drawBehind {
                val knob = s.sliderKnob.toPx()
                val track = s.sliderTrack.toPx() * (if (trackColors != null) 3 else 1)
                val y = size.height / 2
                val start = knob / 2
                val travel = max(0f, size.width - knob)
                val round = CornerRadius(track / 2)
                // The colours span the knob's travel, so the colour under the knob is the one it stands for.
                if (trackColors != null) drawRoundRect(Brush.horizontalGradient(trackColors, start, start + travel), Offset(start, y - track / 2), Size(travel, track), round)
                else {
                    drawRoundRect(c.glassSelected, Offset(start, y - track / 2), Size(travel, track), round)
                    drawRoundRect(c.accent, Offset(start, y - track / 2), Size(travel * fraction, track), round)
                }
                val centre = Offset(start + travel * fraction, y)
                drawCircle(KnobShade, knob / 2, centre.copy(y = y + density))
                drawCircle(Knob, knob / 2, centre)
                if (knobColor != null) drawCircle(knobColor, knob / 2 - s.switchInset.toPx() * 2, centre)
            },
    )
}

/**
 * One choice of a few, as the Duo's tab capsule shows the current tab (D12): a capsule whose
 * selected cell is lighter. The cells are as wide as their words; where the capsule is given
 * more room they share it, and where it is given less they are cut short alike.
 */
@Composable
fun <T> VeloSegmented(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onPick: (T) -> Unit) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val type = VeloTheme.type
    if (LocalSettingsPanel.current) {
        SettingsChoices(options, selected, label, modifier, onPick)
        return
    }
    Layout(
        content = {
            for (option in options) {
                val current = option == selected
                // The whole height of the capsule takes the tap; the lighter cell is drawn inside it.
                Box(
                    Modifier.fillMaxHeight().selectable(current, role = Role.RadioButton, onClick = { onPick(option) }).padding(s.cellInset)
                        .clip(Capsule).background(if (current) c.glassSelected else Color.Transparent).padding(horizontal = s.rowGap),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option), style = type.control, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                        color = if (current) c.onGlass else c.secondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        // The track is the surface's own fill inside a rim: on a group card it reads as cut into the glass.
        modifier = modifier.height(s.capsule).clip(Capsule).background(c.surface).border(s.edge, c.divider, Capsule).selectableGroup(),
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val wanted = measurables.map { it.maxIntrinsicWidth(height) }
        val widths = segmentWidths(wanted, constraints.minWidth, constraints.maxWidth)
        val cells = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
        layout(widths.sum(), height) {
            var x = 0
            for (cell in cells) {
                cell.placeRelative(x, 0)
                x += cell.width
            }
        }
    }
}

/** Keep choices together on glass, but wrap whole choices instead of abbreviating their names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingsChoices(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier, onPick: (T) -> Unit) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    // The compact pane corner works for a multi-line track too; the selected cell remains a pill.
    FlowRow(modifier.glass(rounded(VeloTheme.radii.paneCompact)).selectableGroup()) {
        for (option in options) {
            val current = option == selected
            Box(
                Modifier.heightIn(min = s.control).selectable(current, role = Role.RadioButton, onClick = { onPick(option) })
                    .padding(s.cellInset).clip(Capsule).background(if (current) c.glassSelected else Color.Transparent)
                    .padding(horizontal = s.menuPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = if (current) c.onGlass else c.secondary, style = VeloTheme.type.control,
                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

/**
 * The widths of the cells of a segmented capsule that want [wanted], in a capsule at least
 * [min] and at most [max] wide: what they want when it fits, with the room left over shared
 * evenly, and otherwise each cut by the same share.
 */
fun segmentWidths(wanted: List<Int>, min: Int, max: Int): List<Int> {
    if (wanted.isEmpty()) return wanted
    val total = wanted.sum()
    if (total > max) return wanted.map { (it.toLong() * max / total).toInt() }
    val spare = (min - total).coerceAtLeast(0)
    return wanted.mapIndexed { i, w -> w + spare / wanted.size + if (i < spare % wanted.size) 1 else 0 }
}

/**
 * A key that is on the key bar or not, in the settings: the key's label in a capsule, lighter
 * glass when [selected], a rim alone when not.
 */
@Composable
fun KeyChoice(key: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Box(
        (if (selected) modifier.glass(Capsule, selected = true) else modifier.clip(Capsule).border(s.edge, c.divider, Capsule))
            .selectable(selected, role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = key }
            .widthIn(min = s.key).heightIn(min = s.control).padding(horizontal = s.rowGap),
        contentAlignment = Alignment.Center,
    ) {
        KeyLegend(key, if (selected) c.onGlass else c.secondary)
    }
}

/** The round button that closes a sheet: the lighter glass of the sheet's own controls (AK close disc). */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Close") {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Box(
        modifier.size(s.control).glass(c, Capsule, s.edge, selected = true).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Close, contentDescription, Modifier.size(s.icon), tint = c.onGlass) }
}
