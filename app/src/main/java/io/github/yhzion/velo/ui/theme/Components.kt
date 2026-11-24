package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.ui.KeyLegend
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The controls and rows the screens are built from. Each reads the colours of the surface it is
// on (VeloTheme.surface), so the same row is right in the dark sidebar and in a light dialog.
// docs/design-tokens.md lists, for each, what it replaces.

/** A round glass button that holds one icon. [selected] is the on state of a toggle. */
@Composable
fun GlassIconButton(
    icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, selected: Boolean = false,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Box(
        modifier.size(s.control).glass(Capsule, selected)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (selected) this.selected = true },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(s.icon), tint = if (enabled) c.onGlass else c.disabled) }
}

private val LocalCapsuleVertical = staticCompositionLocalOf { false }

/** One glass capsule that holds several icons ([CapsuleIcon]), side by side or, [vertical], one under the other. */
@Composable
fun GlassCapsule(modifier: Modifier = Modifier, vertical: Boolean = false, content: @Composable () -> Unit) {
    val thickness = VeloTheme.sizes.capsule
    CompositionLocalProvider(LocalCapsuleVertical provides vertical) {
        if (vertical) Column(modifier.width(thickness).glass(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        else Row(modifier.height(thickness).glass(), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * An icon inside a [GlassCapsule]. [selected] fills its cell; [badge] puts the raised accent
 * dot on its corner ([AttentionBadge]). [stateDescription] says a toggle's state aloud.
 */
@Composable
fun CapsuleIcon(
    icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, selected: Boolean = false, badge: Boolean = false,
) = CapsuleIcon(rememberVectorPainter(icon), contentDescription, onClick, modifier, enabled, selected, badge)

/** A [CapsuleIcon] whose icon is a drawable: the toolbar's own eye, gear and command glyphs. */
@Composable
fun CapsuleIcon(
    icon: Painter, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, selected: Boolean = false, badge: Boolean = false, stateDescription: String? = null,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val vertical = LocalCapsuleVertical.current
    Box(
        modifier.size(if (vertical) s.capsule else s.capsuleCell, if (vertical) s.capsuleCell else s.capsule)
            .padding(s.cellInset).clip(Capsule).background(if (selected) c.glassSelected else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (selected) this.selected = true
                if (stateDescription != null) this.stateDescription = stateDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box {
            Icon(icon, contentDescription, Modifier.size(s.icon), tint = if (enabled) c.onGlass else c.disabled)
            if (badge) AttentionBadge(c.accent, Modifier.align(Alignment.TopEnd).offset(x = s.mark / 4, y = -s.mark / 4).size(s.mark))
        }
    }
}

/**
 * The raised dot Rex for Mac puts on its Activity button (`StatusBadgeDrawing`): a shadow a
 * point below that fades over two, the [tint] lit from above by a gradient, and a thin dark rim.
 */
@Composable
fun AttentionBadge(tint: Color, modifier: Modifier = Modifier) {
    // Black at 16 % on a light surface, 28 % on a dark one.
    val shadow = Color.Black.copy(alpha = if (VeloTheme.colors.dark) .28f else .16f)
    Canvas(modifier) {
        val r = size.minDimension / 2
        val blur = 2.dp.toPx()
        val below = center + Offset(0f, 1.dp.toPx())
        drawCircle(Brush.radialGradient(0f to shadow, r / (r + blur) to shadow, 1f to Color.Transparent,
            center = below, radius = r + blur), r + blur, below)
        // Gloss: the tint blended 24 % to white at the top, to 8 % black at the bottom.
        drawCircle(Brush.verticalGradient(listOf(lerp(tint, Color.White, .24f), tint, lerp(tint, Color.Black, .08f)),
            startY = center.y - r, endY = center.y + r), r)
        drawCircle(Color.Black.copy(alpha = .12f), r - .375.dp.toPx(), style = Stroke(.75.dp.toPx()))
    }
}

/**
 * A full-width row of a list: [leading], the [title] with an optional [subtitle] under it, and
 * [trailing]. The selected row has the pill behind it. The list gives the rows their inset
 * from its edge (`VeloTheme.sizes.rowInset`); a row adds nothing outside its pill.
 * [dimmed] draws the title as secondary text: a remote that is offline, a tab that is not the current one.
 * [titleFont] draws the title and subtitle in another family: a font list shows each font in itself.
 * [titleContent] draws in the title's place (a tab's report that alternates with its name); it
 * must keep to the title's one line. A screen reader still reads [title], not what it draws.
 */
@Composable
fun ListRow(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, selected: Boolean = false, enabled: Boolean = true, dimmed: Boolean = false, strong: Boolean = false,
    onClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null,
    titleFont: FontFamily? = null, titleContent: (@Composable () -> Unit)? = null,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val type = VeloTheme.type
    Row(
        modifier.fillMaxWidth().heightIn(min = s.row).clip(Capsule)
            .background(if (selected) c.selected else Color.Transparent)
            .combinedClickable(enabled = enabled, onClickLabel = onClickLabel, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
            .semantics { if (selected) this.selected = true }
            .padding(horizontal = s.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(s.rowGap))
        }
        Column(Modifier.weight(1f)) {
            if (titleContent != null) Box(Modifier.clearAndSetSemantics { text = AnnotatedString(title) }) { titleContent() }
            else Text(title, style = if (strong) type.bodyStrong else type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (!enabled) c.disabled else if (dimmed) c.secondary else c.text, fontFamily = titleFont)
            if (subtitle != null) Text(subtitle, style = type.caption, color = if (enabled) c.secondary else c.disabled, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontFamily = titleFont)
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.rowGap / 2)) { trailing() }
    }
}

/** The tile at the start of a row that stands for what the row is: a pane's prompt glyph, a program's mark. */
@Composable
fun RowTile(glyph: String, modifier: Modifier = Modifier, color: Color = VeloTheme.surface.ok) {
    val s = VeloTheme.sizes
    Box(modifier.size(s.tile, s.tile * 3 / 4).clip(rounded(VeloTheme.radii.tile)).background(VeloTheme.surface.glass), contentAlignment = Alignment.Center) {
        Text(glyph, style = VeloTheme.type.tileGlyph, color = color, maxLines = 1)
    }
}

/**
 * An icon at the start of a row, in the row's own colours: a remote's computer, This Device's
 * phone. It takes the width of a [RowTile], so the titles of rows with an icon and of rows with
 * a tile start on one line, as on the Duo.
 */
@Composable
fun RowIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = VeloTheme.surface.text) {
    Box(modifier.width(VeloTheme.sizes.tile), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, Modifier.size(VeloTheme.sizes.icon), tint = tint)
    }
}

/** A small round button inside a row or a header: its "…", its chevron, its "+". The touch target is the control size. */
@Composable
fun RowIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = VeloTheme.surface
    Box(modifier.size(VeloTheme.sizes.control).clip(Capsule).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, Modifier.size(VeloTheme.sizes.iconSmall), tint = if (enabled) c.secondary else c.disabled)
    }
}

/** The heading of a group of rows: secondary text, with the group's own buttons at its end ([RowIconButton]). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val s = VeloTheme.sizes
    Row(
        modifier.fillMaxWidth().height(s.sectionHeader).clip(Capsule)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(start = s.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = VeloTheme.type.header, color = VeloTheme.surface.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
    }
}

/** What a [StatusMark] says. */
enum class Status { Ok, Warning, Danger, Active, Off }

/**
 * A state, shown and not tappable: a dot, or [icon] in the state's colour. [contentDescription]
 * says the state in words ("Connected"); it is all a screen reader gets.
 */
@Composable
fun StatusMark(status: Status, contentDescription: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = VeloTheme.surface
    val color = when (status) {
        Status.Ok -> c.ok
        Status.Warning -> c.warning
        Status.Danger -> c.danger
        Status.Active -> c.accent
        Status.Off -> c.secondary
    }
    if (icon != null) Icon(icon, contentDescription, modifier.size(VeloTheme.sizes.iconSmall), tint = color)
    else Box(modifier.size(VeloTheme.sizes.mark).clip(Capsule).background(color).semantics { this.contentDescription = contentDescription })
}

/**
 * A key on the key bar. [label] names the key, not the glyph drawn on it. An [armed] modifier is filled with the accent.
 * A key that is not [enabled] is drawn in the disabled colour, without that fill, and takes no tap.
 * With [holdRepeat], a hold sends [onClick] at the long-press timeout, then [onRepeat] until release or cancellation.
 */
@Composable
fun KeyCap(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    armed: Boolean = false, contentDescription: String? = null, enabled: Boolean = true,
    holdRepeat: Boolean = false, onRepeat: () -> Unit = onClick,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val lit = armed && enabled
    val color = if (!enabled) c.disabled else if (lit) c.onAccent else c.text
    Box(
        modifier.widthIn(min = s.key).height(s.keyHeight).clip(Capsule)
            .background(if (lit) c.accent else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button) { if (enabled) onClick() }
            .then(if (enabled && holdRepeat) repeatKeyPress(label, onClick, onRepeat) else Modifier)
            .semantics { if (lit) selected = true; this.contentDescription = contentDescription ?: label }
            .padding(horizontal = s.rowGap / 2),
        contentAlignment = Alignment.Center,
    ) {
        KeyLegend(label, color)
    }
}

private const val KeyRepeatIntervalMillis = 60L

// Inside clickable: own the pointer down/up, leaving accessibility and keyboard clicks to clickable.
@Composable
private fun repeatKeyPress(label: String, onClick: () -> Unit, onRepeat: () -> Unit): Modifier {
    val click by rememberUpdatedState(onClick)
    val repeat by rememberUpdatedState(onRepeat)
    return Modifier.pointerInput(label) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                down.consume()
                var held = false
                val timer = launch {
                    delay(viewConfiguration.longPressTimeoutMillis)
                    held = true
                    click()
                    while (true) {
                        delay(KeyRepeatIntervalMillis)
                        repeat()
                    }
                }
                try {
                    // Do not consume movement: a parent scroll cancels both the tap and the timer.
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    up.consume()
                    if (!held) click()
                } finally {
                    timer.cancel()
                }
            }
        }
    }
}

/** The strip the keys sit on: chrome, with the bar's round ends. */
@Composable
fun KeyStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val chrome = VeloTheme.colors.chrome
    val s = VeloTheme.sizes
    VeloSurface(chrome) {
        Row(modifier.fillMaxWidth().height(s.keyBar).clip(rounded(VeloTheme.radii.bar)).background(chrome.surface).padding(horizontal = s.rowGap / 2),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** How a [VeloButton] weighs: the one action of its place, an action among others, or one that destroys something. */
enum class ButtonKind { Primary, Plain, Destructive }

/** A text button: a capsule filled with the accent ([ButtonKind.Primary]), with danger, or glass. */
@Composable
fun VeloButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Plain, enabled: Boolean = true) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val fill = when {
        !enabled || kind == ButtonKind.Plain -> null
        kind == ButtonKind.Primary -> c.accent
        else -> c.danger
    }
    val label = when {
        !enabled -> c.disabled
        kind == ButtonKind.Primary -> c.onAccent
        kind == ButtonKind.Destructive -> c.onDanger
        else -> c.onGlass
    }
    Box(
        (if (fill != null) modifier.clip(Capsule).background(fill) else modifier.glass())
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = s.control).padding(horizontal = s.rowPadding, vertical = s.gapCompact),
        contentAlignment = Alignment.Center,
    ) {
        // Large text wraps to a second line instead of losing its end; the capsule grows to hold it.
        Text(text, style = VeloTheme.type.bodyStrong, color = label, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
