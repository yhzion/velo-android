package io.github.yhzion.velo.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.KeyLegend
import kotlin.math.max
import kotlin.math.roundToInt

// The controls of a settings sheet, as the redesign's Controls board draws them: a group and its
// rows, a switch, a slider, a segmented control, a menu button and a key that is chosen or not.
// Each reads the colours of the surface it is on; a number in a comment is the board's, at Medium.

/** The knob of a switch that is on and of a slider: white (Controls board). */
private val Knob = Color.White

/** The shade under a knob, so that it stands off a light track (Controls board: 0 1px 2px black at 40 %). */
private val KnobShade = Color(0x40000000)

/** How far a group's rows keep from its end: 12, so a control's rim lines up with the chevrons (Controls board). */
private val GroupEnd = 12.dp

/** How far a group's rows keep from its start, and where its dividers start: 16 (Controls board). */
private val GroupStart = 16.dp

/** The end padding of the group a row is in, which a divider reaches past to the group's border. */
private val LocalGroupEnd = staticCompositionLocalOf { 0.dp }

/** The start padding of the group a row is in, which a row reaches past to the group's border. */
private val LocalGroupStart = staticCompositionLocalOf { 0.dp }

/**
 * A row of a [GroupCard] reaches the group's borders ([groupInset] puts its content back at the
 * group's inset): its tap, its pill and its test bounds span the group, as the boards' rows do.
 * Outside a group it changes nothing.
 */
@Composable
fun Modifier.groupBleed(): Modifier {
    val start = LocalGroupStart.current
    val end = LocalGroupEnd.current
    if (start == 0.dp && end == 0.dp) return this
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
        val s = start.roundToPx()
        val e = end.roundToPx()
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + s + e, maxWidth = constraints.maxWidth + s + e))
        layout(maxOf(constraints.minWidth, placeable.width - s - e), placeable.height) { placeable.placeRelative(-s, 0) }
    }
}

/** The content of a row that [groupBleed]s: back at the group's inset. */
@Composable
fun Modifier.groupInset(): Modifier {
    val start = LocalGroupStart.current
    val end = LocalGroupEnd.current
    return if (start == 0.dp && end == 0.dp) this else padding(start = start, end = end)
}

/**
 * The heading above a [GroupCard]: the caption's size at Medium weight, secondary (Controls
 * board, a settings group: 13, 500). [first] is the first heading of its page, which has less above it.
 */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = VeloTheme.medium(4.dp), end = VeloTheme.medium(4.dp), top = VeloTheme.medium(if (first) 12.dp else 20.dp), bottom = VeloTheme.space.s8)
            .semantics { heading() },
        style = VeloTheme.type.caption, fontWeight = FontWeight.Medium, color = VeloTheme.surface.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A group of rows. On the sheet it is drawn as the redesign's group: no fill, a border of the
 * divider, the group radius (Controls board). On an overlay it is a glass card (Overlays board,
 * the examples in Add Remote). Put a [GroupDivider] between two rows.
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = VeloTheme.sizes
    val c = VeloTheme.surface
    val onOverlay = c === VeloTheme.colors.overlay
    val frame = if (onOverlay) Modifier.clip(rounded(VeloTheme.radii.card)).background(c.glass)
    else rounded(VeloTheme.radii.group).let { shape -> Modifier.clip(shape).border(s.edge, c.divider, shape) }
    val start = VeloTheme.medium(if (onOverlay) 14.dp else GroupStart)
    val end = VeloTheme.medium(if (onOverlay) 14.dp else GroupEnd)
    // On the sheet the rows sit inside the border, as the boards' do (their border adds to the group).
    val rim = if (onOverlay) 0.dp else s.edge
    CompositionLocalProvider(LocalGroupEnd provides end, LocalGroupStart provides start) {
        Column(modifier.fillMaxWidth().then(frame).padding(rim).padding(start = start, end = end), content = content)
    }
}

/** The hairline between two rows of a [GroupCard]: from the rows' start to the group's border (Controls board). */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    val end = LocalGroupEnd.current
    Spacer(
        modifier.layout { measurable, constraints ->
            // Reach past the group's end padding to its border; without a width there is no border to reach.
            if (!constraints.hasBoundedWidth) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
            val wide = constraints.maxWidth + end.roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
            layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(0, 0) }
        }.height(VeloTheme.sizes.edge).background(VeloTheme.surface.divider),
    )
}

/** The test tag `<tag>-<part>` of a row's part, as the redesign's boards name them: `-row`, `-label`, `-switch`. */
internal fun tagged(tag: String?, part: String): Modifier = if (tag != null) Modifier.testTag("$tag-$part") else Modifier

/**
 * A row of a [GroupCard]: [label], and its control at the end. [tag] names its parts for tests
 * (`<tag>-row`, `<tag>-label`). A control that does not fit
 * beside the label (a long segmented control on the folded screen, or at large text) goes
 * under it, at the start.
 */
@Composable
fun SettingRow(label: String, modifier: Modifier = Modifier, tag: String? = null, control: @Composable () -> Unit) {
    val s = VeloTheme.sizes
    val gapDp = VeloTheme.medium(14.dp)
    Layout(
        content = {
            Text(label, tagged(tag, "label"), style = VeloTheme.type.body, color = VeloTheme.surface.text)
            Box { control() }
        },
        modifier = modifier.groupBleed().then(tagged(tag, "row")).fillMaxWidth().heightIn(min = s.row).groupInset().padding(vertical = VeloTheme.medium(6.dp)),
    ) { measurables, constraints ->
        val gap = gapDp.roundToPx()
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
 * A row that is a switch: [label], an optional [subtitle] under it, and the switch at the end.
 * The whole row takes the tap and says the state, so the switch is found by what it says
 * (Controls board: 60 high, 64 with a second line).
 */
@Composable
fun SwitchRow(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, enabled: Boolean = true,
    /** Names the parts for tests: `<tag>-row`, `-label`, `-caption` (the subtitle) and `-switch`. */
    tag: String? = null,
) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    Row(
        modifier.groupBleed().then(tagged(tag, "row")).fillMaxWidth().heightIn(min = if (subtitle != null) VeloTheme.medium(64.dp) else VeloTheme.sizes.row)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).groupInset()
            // A switch keeps 16 from the group's border, 4 more than the group's other rows.
            .padding(vertical = VeloTheme.medium(if (subtitle != null) 10.dp else 6.dp)).padding(end = VeloTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        // The label and the subtitle are as wide as the wider of them, as the boards set them.
        Box(Modifier.weight(1f)) {
            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(3.dp))) {
                Text(label, Modifier.fillMaxWidth().then(tagged(tag, "label")), style = type.body, color = if (enabled) c.text else c.disabled)
                if (subtitle != null) Text(subtitle, Modifier.fillMaxWidth().then(tagged(tag, "caption")), style = type.caption, color = if (enabled) c.secondary else c.disabled)
            }
        }
        VeloSwitch(checked, tagged(tag, "switch"), enabled = enabled)
    }
}

/**
 * A row that opens a page: [title], its current [value] in the secondary colour, anything in
 * [trailing] (a state, a dot), and a chevron (Controls board, sample-row-navigation).
 */
@Composable
fun NavigationRow(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier, value: String? = null, subtitle: String? = null,
    enabled: Boolean = true,
    /** Names the parts for tests: `<tag>-row`, `-label` and `-value`. */
    tag: String? = null,
    /** Between the parts: 12, or 10 where a row has more to show (About). */
    spacing: Dp = VeloTheme.space.s12,
    /** At the end: the chevron, or [Icons.AutoMirrored.Filled.OpenInNew] for a row that leaves the app. */
    icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    Row(
        modifier.groupBleed().then(tagged(tag, "row")).fillMaxWidth().heightIn(min = VeloTheme.sizes.row).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .groupInset().padding(vertical = VeloTheme.medium(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(3.dp))) {
            Text(title, Modifier.fillMaxWidth().then(tagged(tag, "label")), style = type.body, color = if (enabled) c.text else c.disabled, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = type.caption, color = c.secondary)
        }
        if (value != null) Text(value, tagged(tag, "value"), style = type.value, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
        Icon(icon, null, Modifier.size(VeloTheme.space.s16), tint = c.secondary)
    }
}

/** A row that does something rather than open a page: an icon and words in the accent (Controls board, sample-row-action: Add Remote…). */
@Composable
fun ActionRow(
    text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    /** The row's test tag; it spans the group, as the row does. */
    tag: String? = null,
) {
    val c = VeloTheme.surface
    val color = if (enabled) c.accent else c.disabled
    Row(
        modifier.groupBleed().then(if (tag != null) Modifier.testTag(tag) else Modifier).fillMaxWidth()
            .heightIn(min = VeloTheme.medium(56.dp).coerceAtLeast(MinTouch)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).groupInset(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        Icon(icon, null, Modifier.size(VeloTheme.medium(22.dp)), tint = color)
        Text(text, style = VeloTheme.type.bodyStrong, color = color)
    }
}

/**
 * A switch, drawn only: the row it is in takes the tap and says the state ([SwitchRow]). On,
 * its track is the accent and its knob white; off, the selected glass and a grey knob; when it
 * cannot be used, plain glass and the disabled grey (Controls board).
 */
@Composable
fun VeloSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val offKnob = VeloTheme.colors.textOnRaised
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(SwitchMillis), label = "Switch")
    Box(
        modifier.size(s.switchWidth, s.switchHeight).drawBehind {
            val round = CornerRadius(size.height / 2)
            drawRoundRect(if (enabled) lerp(c.glassSelected, c.accent, on) else c.glass, cornerRadius = round)
            val radius = size.height / 2 - s.switchInset.toPx()
            val centre = Offset(size.height / 2 + on * (size.width - size.height), size.height / 2)
            if (enabled) drawCircle(KnobShade, radius, centre.copy(y = centre.y + density))
            drawCircle(if (enabled) lerp(offKnob, Knob, on) else c.disabled, radius, centre)
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
 * One choice of a few (Controls board, sample-segmented): a glass track with an even rim whose
 * selected cell is the lighter glass. The cells are as wide as their words; where the control
 * is given more room they share it, and where it is given less they are cut short alike. In the
 * settings sheet the choices wrap instead of being cut. The whole height of the track takes a
 * cell's tap; the lighter cell is drawn inside it. [optionTag] gives each cell its test tag,
 * such as `appearance-size-medium`; the control's own is set on [modifier].
 */
@Composable
fun <T> VeloSegmented(
    options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, optionTag: ((T) -> String)? = null, onPick: (T) -> Unit,
) {
    val s = VeloTheme.sizes
    fun tag(option: T) = if (optionTag != null) Modifier.testTag(optionTag(option)) else Modifier
    if (LocalSettingsPanel.current) {
        SettingsChoices(options, selected, label, modifier, ::tag, onPick)
        return
    }
    Layout(
        content = { for (option in options) SegmentCell(label(option), option == selected, tag(option).fillMaxHeight()) { onPick(option) } },
        modifier = modifier.height(s.control).glassControl(Capsule).selectableGroup().padding(horizontal = s.cellInset),
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

/** One cell of a segmented control: the tap takes its whole height, the lighter glass is drawn [VeloSizes.cellInset] inside it. */
@Composable
private fun SegmentCell(text: String, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Box(
        modifier.heightIn(min = s.control).selectable(current, role = Role.RadioButton, onClick = onClick).padding(vertical = s.cellInset)
            .clip(Capsule).background(if (current) c.glassSelected else Color.Transparent).padding(horizontal = VeloTheme.space.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = VeloTheme.type.control, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
            color = if (current) c.onGlass else c.secondary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Keep choices together on the track, but wrap whole choices instead of abbreviating their names. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SettingsChoices(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier, tag: (T) -> Modifier, onPick: (T) -> Unit) {
    // The pill radius is half the track's height: one line reads as a capsule, two as a rounded box.
    FlowRow(modifier.glassControl(rounded(VeloTheme.radii.pill)).selectableGroup().padding(horizontal = VeloTheme.sizes.cellInset)) {
        for (option in options) SegmentCell(label(option), option == selected, tag(option)) { onPick(option) }
    }
}

/**
 * A button that opens a menu of choices ([SelectMenu]) and shows the current one: a glass pill
 * with a chevron (Controls board, sample-menu-button: 44 high, kept at the least touch target).
 * [contentDescription] says what the choice is for, as "Sound, Attention".
 */
@Composable
fun MenuButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, contentDescription: String? = null,
    /** The test tag of the drawn pill, inside the least touch target. */
    tag: String? = null,
) {
    val c = VeloTheme.surface
    Row(
        modifier.minimumInteractiveComponentSize().then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .heightIn(min = VeloTheme.medium(44.dp)).glassControl(Capsule)
            .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .padding(start = VeloTheme.space.s16, end = VeloTheme.space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Text(text, style = VeloTheme.type.value, color = if (enabled) c.onGlass else c.disabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(VeloTheme.space.s16), tint = c.secondary)
    }
}

/**
 * A key that is on the key bar or not, in the settings (Controls board, key chips): the key's
 * label in a pill, tinted with the accent when [selected], a rim alone when not.
 */
@Composable
fun KeyChoice(key: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val frame = if (selected) Modifier.clip(Capsule).background(c.accent.copy(alpha = 0.16f)).border(s.edge, c.accent.copy(alpha = 0.55f), Capsule)
    else Modifier.clip(Capsule).border(s.edge, c.glassEdge, Capsule)
    Box(
        modifier.minimumInteractiveComponentSize().heightIn(min = VeloTheme.medium(44.dp)).widthIn(min = VeloTheme.medium(44.dp)).then(frame)
            .selectable(selected, role = Role.Checkbox, onClick = onClick)
            .semantics { contentDescription = key }
            .padding(horizontal = VeloTheme.medium(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        KeyLegend(key, if (selected) c.onGlass else c.secondary)
    }
}

/**
 * The round button that closes a sheet: glass with a light line along its top (the redesign's
 * settings header, 48). [small] is the plain glass disc of a page's header, 44 (the settings
 * sheet's and a page's header); a touch still finds it 48 wide, as Compose widens a small target.
 */
@Composable
fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Close", small: Boolean = false) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Box(
        modifier.size(if (small) VeloTheme.medium(44.dp).coerceAtLeast(MinKey) else s.control).clip(Capsule)
            .drawBehind {
                // A line along the top: the rim, with the fill drawn over it one line lower.
                if (!small) drawCircle(c.glassEdge)
                drawCircle(c.glass, center = if (small) center else center.copy(y = center.y + s.edge.toPx()))
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Close, contentDescription, Modifier.size(if (small) VeloTheme.medium(18.dp) else VeloTheme.space.s20), tint = c.onGlass) }
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

