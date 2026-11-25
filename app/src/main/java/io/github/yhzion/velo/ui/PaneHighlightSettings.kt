package io.github.yhzion.velo.ui

import androidx.compose.foundation.Canvas
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.Capsule
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.VeloSlider
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded

/** A 0xRRGGBB colour, opaque. */
fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

/** The hue track of the custom picker: red through the hues back to red. */
private val HueTrack = listOf(0f, 60f, 120f, 180f, 240f, 300f, 359f).map { opaque(Hsv(it, 1f, 1f).rgb()) }

/** The picker's place across a closed and reopened sheet: its hue stays where it was at grey and black. */
private val HsvSaver = listSaver<MutableState<Hsv>, Float>(
    save = { listOf(it.value.h, it.value.s, it.value.v) },
    restore = { mutableStateOf(Hsv(it[0], it[1], it[2])) },
)

/** The ring of the Custom swatch: the presets' hues around it. */
private const val HexDigits = "0123456789ABCDEF"

private val CustomRing = Brush.sweepGradient(
    listOf(0xFF453A, 0xFFD60A, 0x30D158, 0x64D2FF, 0x0A84FF, 0xDA77F2, 0xFF453A).map(::opaque),
)

/**
 * Appearance → Active pane: the colour of the edge of the pane that takes the keys. The sheet
 * covers the panes, so a small picture of two panes shows the edge as it will look. Under it are
 * Rex for Mac's choices ([HighlightPreset]) and Custom, which opens its picker in place. As every
 * setting of the sheet, a choice applies at once (canvas SetAppearance block).
 */
@Composable
internal fun PaneHighlightSettings(settings: Settings) {
    val highlight = settings.paneHighlight
    val space = VeloTheme.space
    // Here, not in the picker: choosing a preset and Custom again keeps the hue a grey or black left.
    val picker = rememberSaveable(saver = HsvSaver) { mutableStateOf(hsvOf(highlight.custom ?: highlight.rgb)) }
    GroupHeader("Active pane", Modifier.testTag("appearance-active-pane-header"))
    GroupCard(Modifier.testTag("appearance-active-pane-group")) {
        Row(
            Modifier.groupBleed().testTag("appearance-highlight-row").fillMaxWidth().heightIn(min = VeloTheme.sizes.row).groupInset().padding(end = space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Highlight color", Modifier.testTag("appearance-highlight-label"), style = VeloTheme.type.body, color = VeloTheme.surface.text)
            Text(highlight.preset?.label ?: "Custom", Modifier.testTag("appearance-highlight-value"), style = VeloTheme.type.value, color = VeloTheme.surface.secondary)
        }
        // The picture and the swatches keep 16 from both sides of the group, its rows 12 at the end.
        Column(Modifier.padding(end = space.s4, bottom = space.s16)) {
            HighlightPreview(opaque(highlight.rgb))
            Swatches(highlight, settings::savePaneHighlight)
        }
        val custom = highlight.custom
        if (highlight.isCustom && custom != null) {
            GroupDivider()
            CustomHighlightPicker(custom, picker) { settings.savePaneHighlight(PaneHighlight(null, it)) }
        }
    }
}

/** Two panes on the backdrop: the left one takes the keys, so it has the edge, and its last line has the colour. */
@Composable
private fun HighlightPreview(colour: Color) {
    val palette = VeloTheme.colors
    val line = VeloTheme.surface.divider
    val space = VeloTheme.space
    val paneRadius = VeloTheme.space.s8
    Row(
        Modifier.fillMaxWidth().testTag("appearance-highlight-preview").height(VeloTheme.medium(108.dp)).clip(rounded(VeloTheme.radii.group))
            .background(palette.backdrop.surface).padding(VeloTheme.medium(10.dp))
            .semantics { contentDescription = "Preview of the focused pane's edge" },
        horizontalArrangement = Arrangement.spacedBy(space.s8),
    ) {
        @Composable
        fun Pane(tag: String, lines: List<Float>, focused: Boolean) {
            val edge = if (focused) VeloTheme.sizes.paneFocus else VeloTheme.sizes.edge
            val ring = if (focused) colour.copy(alpha = PaneHighlightAlpha) else line
            Column(
                Modifier.weight(1f).fillMaxHeight().testTag(tag).clip(rounded(paneRadius)).background(palette.sidebar.surface)
                    .border(edge, ring, rounded(paneRadius)).padding(space.s12),
                verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(7.dp)),
            ) {
                lines.forEachIndexed { i, share ->
                    val last = focused && i == lines.lastIndex
                    Box(Modifier.fillMaxWidth(share).height(VeloTheme.medium(5.dp)).clip(Capsule).background(if (last) colour else line))
                }
            }
        }
        Pane("appearance-preview-active-pane", listOf(0.72f, 0.5f, 0.62f, 0.34f), focused = true)
        Pane("appearance-preview-other-pane", listOf(0.64f, 0.4f, 0.55f), focused = false)
    }
}

/** One cell of [Swatches]: a preset, or Custom with its [ring]. */
private class SwatchChoice(val label: String, val selected: Boolean, val fill: Color?, val ring: Brush?, val pick: () -> Unit)

/** The presets and Custom, four to a row; the chosen one has a ring and a check. */
@Composable
private fun Swatches(highlight: PaneHighlight, onPick: (PaneHighlight) -> Unit) {
    val choices = HighlightPreset.entries.map { preset ->
        SwatchChoice(preset.label, highlight.preset == preset, opaque(preset.rgb), null) { onPick(PaneHighlight(preset, highlight.custom)) }
    } + SwatchChoice("Custom", highlight.isCustom, highlight.custom?.let(::opaque), CustomRing) {
        if (!highlight.isCustom) onPick(highlight.toCustom())
    }
    Column(
        Modifier.fillMaxWidth().padding(top = VeloTheme.space.s16).testTag("appearance-highlight-swatches").selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        for (row in choices.chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s8)) {
            for (choice in row) SwatchCell(choice, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SwatchCell(choice: SwatchChoice, modifier: Modifier) {
    val c = VeloTheme.surface
    Column(
        // The cell, not only the disc, takes the tap: a quarter of the card wide.
        modifier.testTag("appearance-highlight-${choice.label.lowercase()}").selectable(choice.selected, role = Role.RadioButton, onClick = choice.pick)
            .padding(vertical = VeloTheme.space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Swatch(choice.fill, choice.ring, choice.selected, empty = choice.ring != null && choice.fill == null)
        Text(choice.label, style = VeloTheme.type.caption, color = if (choice.selected) c.text else c.secondary, maxLines = 1)
    }
}

/**
 * A disc of [fill], or of [ring] with [fill] inside it (Custom). The chosen one has a ring in the
 * text colour around it, apart from it by the surface, and a check that reads on its fill;
 * Custom before a colour is set shows a plus.
 */
@Composable
private fun Swatch(fill: Color?, ring: Brush?, selected: Boolean, empty: Boolean) {
    val c = VeloTheme.surface
    val hairline = VeloTheme.sizes.edge
    val disc = VeloTheme.medium(40.dp)
    Box(Modifier.size(disc), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(disc)) {
            val centre = center
            val r = size.minDimension / 2
            // The chosen disc's ring sits outside it, 3 off and 2 wide, as the board's shadow rings it.
            if (selected) {
                drawCircle(c.text, r + 5.dp.toPx(), centre)
                drawCircle(c.surface, r + 3.dp.toPx(), centre)
            }
            if (ring != null) {
                drawCircle(ring, r, centre)
                drawCircle(c.surface, r * 0.7f, centre)
                if (fill != null) drawCircle(fill, r * 0.7f - 2.dp.toPx(), centre)
            } else if (fill != null) {
                drawCircle(fill, r, centre)
                // A hairline around the disc, so that Text's near-white reads as a disc on the card.
                drawCircle(Color.White.copy(alpha = 0.18f), r, centre, style = Stroke(hairline.toPx()))
            }
        }
        val mark = if (selected) Icons.Default.Check else if (empty) Icons.Default.Add else null
        val markTint = if (fill != null && fill.luminance() > 0.3f) c.onAccent else c.text
        if (mark != null) Icon(mark, null, Modifier.size(if (ring != null) VeloTheme.medium(14.dp) else VeloTheme.medium(18.dp)), tint = if (empty) c.text else markTint)
    }
}

/**
 * The custom colour's picker: saturation (across) and brightness (down) of the hue in a square,
 * the hue on a slider, and the colour as hex. The square and the hue keep their place when the
 * colour has no hue (grey, black); a hex typed in moves them. Below 3:1 on black a note says so.
 */
@Composable
private fun CustomHighlightPicker(rgb: Int, picker: MutableState<Hsv>, onPick: (Int) -> Unit) {
    val c = VeloTheme.surface
    val sizes = VeloTheme.sizes
    var hsv by picker
    var draft by remember { mutableStateOf(hexOf(rgb)) }
    // A colour that came from elsewhere moves the picker and the field; the picker's own colour does not.
    LaunchedEffect(rgb) {
        if (hsv.rgb() != rgb) hsv = hsvOf(rgb)
        if (parseHex(draft) != rgb) draft = hexOf(rgb)
    }
    fun set(next: Hsv) {
        hsv = next
        onPick(next.rgb())
    }
    Column(Modifier.fillMaxWidth().padding(vertical = sizes.menuPadding), verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
        SaturationBrightness(hsv, ::set)
        Text("Hue", style = VeloTheme.type.caption, color = c.secondary)
        VeloSlider(
            hsv.h, { set(hsv.copy(h = it)) },
            Modifier.fillMaxWidth().semantics { contentDescription = "Hue" },
            valueRange = 0f..359f, trackColors = HueTrack, knobColor = opaque(Hsv(hsv.h, 1f, 1f).rgb()),
        )
        // Said once six digits are typed (or a letter that is not one), not at every keystroke on the way.
        val digits = draft.trim().removePrefix("#")
        val wrong = parseHex(draft) == null && (digits.length >= 6 || digits.any { it.uppercaseChar() !in HexDigits })
        VeloTextField(
            draft,
            { text ->
                draft = text.uppercase()
                parseHex(text)?.let {
                    hsv = hsvOf(it)
                    onPick(it)
                }
            },
            Modifier.fillMaxWidth(),
            placeholder = "#7FA6F5", label = "Hex",
            error = if (wrong) "Enter six hex digits, like #7FA6F5." else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        )
        if (isLowContrast(rgb)) Row(horizontalArrangement = Arrangement.spacedBy(sizes.menuPadding), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, null, Modifier.size(sizes.iconSmall), tint = c.warning)
            Text("Hard to see on dark panes. Choose a lighter or brighter color.", style = VeloTheme.type.caption, color = c.warning)
        }
    }
}

/**
 * The square of the custom picker: white to the hue across, to black down. A tap or a drag sets
 * saturation and brightness. A screen reader skips it: the hue slider and the hex field reach every colour.
 */
@Composable
private fun SaturationBrightness(hsv: Hsv, onChange: (Hsv) -> Unit) {
    val current by rememberUpdatedState(hsv)
    val change by rememberUpdatedState(onChange)
    val hue = opaque(Hsv(hsv.h, 1f, 1f).rgb())
    Box(
        Modifier.fillMaxWidth().height(148.dp).clip(rounded(VeloTheme.radii.paneCompact))
            .clearAndSetSemantics {}
            // A tap sets the colour when the finger lifts, a drag once it has moved past the touch slop:
            // a finger put down here on the way to scrolling the sheet changes nothing.
            .pointerInput(Unit) { detectTapGestures { change(current.at(it, size.width, size.height)) } }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { change(current.at(it, size.width, size.height)) }) { pointer, _ ->
                    pointer.consume()
                    change(current.at(pointer.position, size.width, size.height))
                }
            }
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(Color.White, hue)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val knob = Offset(size.width * hsv.s, size.height * (1f - hsv.v))
                drawCircle(Color.Black.copy(alpha = 0.45f), 13.dp.toPx(), knob)
                drawCircle(Color.White, 12.dp.toPx(), knob)
                drawCircle(opaque(hsv.rgb()), 9.dp.toPx(), knob)
            },
    )
}

/** The colour at [position] of the square: saturation across it, brightness up it, the hue kept. */
private fun Hsv.at(position: Offset, width: Int, height: Int) = copy(
    s = (position.x / width).coerceIn(0f, 1f),
    v = 1f - (position.y / height).coerceIn(0f, 1f),
)
