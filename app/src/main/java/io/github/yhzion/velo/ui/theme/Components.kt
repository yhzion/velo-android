package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/**
 * One glass capsule that holds several icons ([CapsuleIcon]), side by side or, [vertical], one under
 * the other. Its round controls keep the same distance from its ends as from its sides (Sidebar
 * board, sidebar-actions: 2 around 48).
 */
@Composable
fun GlassCapsule(modifier: Modifier = Modifier, vertical: Boolean = false, content: @Composable () -> Unit) {
    val thickness = VeloTheme.sizes.capsule
    val end = ((thickness - VeloTheme.sizes.control) / 2).coerceAtLeast(0.dp)
    CompositionLocalProvider(LocalCapsuleVertical provides vertical) {
        if (vertical) Column(modifier.width(thickness).glass().padding(vertical = end), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        else Row(modifier.height(thickness).glass().padding(horizontal = end), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/**
 * An icon inside a [GlassCapsule]: a round control (Sidebar board, 48). [selected] fills its cell;
 * [badge] puts the raised accent dot on its corner ([AttentionBadge]; [badgeModifier] goes on the
 * dot). [stateDescription] says a toggle's state aloud.
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
    badgeModifier: Modifier = Modifier,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Box(
        modifier.size(s.control).clip(Capsule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (selected) this.selected = true
                if (stateDescription != null) this.stateDescription = stateDescription
            },
    ) {
        // The selected cell stays a little inside the control, as a segmented track's cell does.
        Box(Modifier.matchParentSize().padding(s.cellInset).clip(Capsule).background(if (selected) c.glassSelected else Color.Transparent),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(s.icon), tint = if (enabled) c.onGlass else c.disabled)
        }
        // The dot 9 in from the control's top right (Sidebar board, update-dot).
        val inset = VeloTheme.medium(9.dp)
        if (badge) AttentionBadge(c.accent, Modifier.align(Alignment.TopEnd).offset(x = -inset, y = inset).then(badgeModifier).size(s.dot), ring = c.glass)
    }
}

/**
 * The update dot (Controls board, sample-update-dot): a dot of [tint] in a ring of [ring], the
 * colour of what it sits on, so that it reads as a dot where it overlaps an edge. [modifier]
 * sizes the dot (`VeloTheme.sizes.dot`); the ring is drawn outside it.
 */
@Composable
fun AttentionBadge(tint: Color, modifier: Modifier = Modifier, ring: Color = VeloTheme.surface.surface) {
    val width = VeloTheme.medium(2.dp)
    Canvas(modifier) {
        drawCircle(ring, size.minDimension / 2 + width.toPx())
        drawCircle(tint)
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
 * [minHeight] is the row's least height: a settings row's unless given (`sizes.tabRow` for a tab's row).
 */
@Composable
fun ListRow(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    subtitle: String? = null, selected: Boolean = false, enabled: Boolean = true, dimmed: Boolean = false, strong: Boolean = false,
    onClickLabel: String? = null, onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null,
    titleFont: FontFamily? = null, titleContent: (@Composable () -> Unit)? = null, minHeight: Dp = VeloTheme.sizes.row,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val type = VeloTheme.type
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight.coerceAtLeast(MinTouch)).clip(Capsule)
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

/** The heading of a group of rows in the sidebar: a session's name, with the group's own buttons at its end ([RowIconButton]) (Sidebar board). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onClickLabel: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val s = VeloTheme.sizes
    Row(
        modifier.fillMaxWidth().height(s.sectionHeader).clip(Capsule)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(start = s.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = VeloTheme.type.header, color = VeloTheme.colors.textOnRaised, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * A key on the key bar (KeyBar board). [label] names the key, not the glyph drawn on it. The key
 * takes the taps in all the room its bar gives it; its cap, the board's button, stands in that room
 * at [capAlignment]: as wide as its label with 4 on each side, at least `sizes.key`, and as high as
 * the key. A [latching] key (a modifier) is a pill 40 high, 8 on each side of its label, filled with
 * the accent while [armed]. [capModifier] goes on the cap. A key that is not [enabled] is drawn in
 * the disabled colour, without that fill, and takes no tap. With [holdRepeat], a hold sends
 * [onClick] at the long-press timeout, then [onRepeat] until release or cancellation.
 */
@Composable
fun KeyCap(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    armed: Boolean = false, contentDescription: String? = null, enabled: Boolean = true,
    holdRepeat: Boolean = false, onRepeat: () -> Unit = onClick,
    latching: Boolean = false, capAlignment: Alignment = Alignment.Center, capModifier: Modifier = Modifier,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val lit = armed && enabled
    val color = if (!enabled) c.disabled else if (lit) c.onAccent else c.text
    val pill = VeloTheme.medium(40.dp)
    Box(
        modifier.height(s.keyHeight).clip(Capsule)
            .clickable(enabled = enabled, role = Role.Button) { if (enabled) onClick() }
            .then(if (enabled && holdRepeat) repeatKeyPress(label, onClick, onRepeat) else Modifier)
            .semantics { if (lit) selected = true; this.contentDescription = contentDescription ?: label },
        contentAlignment = capAlignment,
    ) {
        Box(
            capModifier.widthIn(min = s.key).height(if (latching) minOf(pill, s.keyHeight) else s.keyHeight)
                // A latched key is a pill of the accent (Controls board, sample-key-latched).
                .then(if (lit) Modifier.clip(Capsule).background(c.accent) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // A cap the bar narrowed below its label's room keeps the label whole: it takes the padding.
            KeyLegend(label, color, Modifier.wrapContentWidth(unbounded = true).padding(horizontal = VeloTheme.medium(if (latching) 8.dp else 4.dp)))
        }
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

/**
 * How a [VeloButton] weighs (Controls board): the one action of its place, an action among
 * others, one that destroys something, or a word that acts as a link. A screen has one primary
 * button at most.
 */
enum class ButtonKind { Primary, Secondary, Destructive, Text }

/**
 * A button (Controls board): [ButtonKind.Primary] is filled with the accent; [ButtonKind.Secondary]
 * is glass with a rim; [ButtonKind.Destructive] is a rim of the red with red words; [ButtonKind.Text]
 * is the accent's words alone. A button that is not [enabled] is the selected fill with grey words.
 * [small] is the 40 high button of a list row, such as Connect; [row] the 44 high one at the end of
 * a settings row, such as Edit order. [icon] goes before the words, or
 * after them with [iconAtEnd] (a link that leaves the app). Every button keeps the least touch target.
 */
@Composable
fun VeloButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Secondary, enabled: Boolean = true,
    small: Boolean = false, icon: Painter? = null, iconAtEnd: Boolean = false, row: Boolean = false,
    /** A [ButtonKind.Text] button's words in the red, for one that removes (an imported key's Remove). */
    danger: Boolean = false,
    /** The test tag of the drawn button, inside the least touch target. */
    tag: String? = null,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val type = VeloTheme.type
    val words = kind == ButtonKind.Text
    val frame = when {
        words -> Modifier
        !enabled -> Modifier.clip(Capsule).background(c.selected)
        kind == ButtonKind.Primary -> Modifier.clip(Capsule).background(c.accent)
        kind == ButtonKind.Secondary -> Modifier.glassControl(Capsule)
        else -> Modifier.clip(Capsule).border(s.edge, c.danger.copy(alpha = 0.5f), Capsule)
    }
    val label = when {
        !enabled -> c.disabled
        kind == ButtonKind.Primary -> c.onAccent
        kind == ButtonKind.Secondary -> c.onGlass
        kind == ButtonKind.Destructive || danger -> c.dangerText
        else -> c.accent
    }
    val height = when {
        words || row -> VeloTheme.medium(44.dp)
        small -> VeloTheme.medium(40.dp)
        else -> s.control
    }
    val style = (if (words || small || row) type.value else type.body)
        .copy(fontWeight = if (kind == ButtonKind.Primary) FontWeight.SemiBold else FontWeight.Medium)
    val padding = when {
        // Words at the end of a settings row keep 12 at their sides (Rename); beside a status, 8 (Check for updates).
        words -> if (row) VeloTheme.space.s12 else VeloTheme.space.s8
        small || row -> VeloTheme.medium(18.dp)
        else -> VeloTheme.space.s20
    }
    // The caller's modifier goes inside the least touch target: a small button's box (its test tag, its semantics) is
    // the button drawn, 40 high, while the row still gives it 48 to take a tap. Words alone and row buttons are as tall
    // as the boards draw them, 44: Compose's hit testing still takes a touch 48 wide around them.
    Row(
        (if (words || row) Modifier else Modifier.minimumInteractiveComponentSize()).then(modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .heightIn(min = height).then(frame)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = padding, vertical = VeloTheme.space.s4),
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && !iconAtEnd) Icon(icon, null, Modifier.size(VeloTheme.medium(18.dp)), tint = label)
        // Large text wraps to a second line instead of losing its end; the button grows to hold it.
        Text(text, style = style, color = label, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (icon != null && iconAtEnd) Icon(icon, null, Modifier.size(VeloTheme.space.s16), tint = label)
    }
}

/**
 * A state in words, on a tint of its colour, with its dot (Controls board, sample-state-chip:
 * Connected). It takes no tap; a screen reader reads [text].
 */
@Composable
fun StateChip(text: String, status: Status, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val tint = statusColor(status, c)
    Row(
        modifier.clip(Capsule).background(tint.copy(alpha = 0.12f)).padding(horizontal = VeloTheme.medium(10.dp), vertical = VeloTheme.medium(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Box(Modifier.size(VeloTheme.medium(7.dp)).clip(Capsule).background(tint))
        Text(text, style = VeloTheme.type.caption, color = stateChipText(status, tint, c), maxLines = 1)
    }
}

/** The green of a chip's words (Controls board); the other states' words are their colour lightened as much. */
private val OkChipText = Color(0xFF7EE29A)

private fun stateChipText(status: Status, tint: Color, c: SurfaceColors): Color = when (status) {
    Status.Ok -> OkChipText
    Status.Off -> c.secondary
    else -> lerp(tint, Color.White, 0.4f)
}

private fun statusColor(status: Status, c: SurfaceColors): Color = when (status) {
    Status.Ok -> c.ok
    Status.Warning -> c.warning
    Status.Danger -> c.danger
    Status.Active -> c.accent
    Status.Off -> c.secondary
}

/** A short name on glass, such as a license's (Controls board, sample-license-badge: Apache-2.0). */
@Composable
fun TagBadge(text: String, modifier: Modifier = Modifier) {
    val type = VeloTheme.type
    Text(
        text, modifier.clip(rounded(VeloTheme.radii.badge)).background(VeloTheme.surface.glass)
            .padding(horizontal = VeloTheme.medium(9.dp), vertical = VeloTheme.space.s4),
        style = type.caption.copy(fontSize = type.eyebrow.fontSize, lineHeight = type.eyebrow.lineHeight),
        color = VeloTheme.colors.textOnRaised, maxLines = 1,
    )
}

/** A small heading in capitals over what it names, such as NEW VERSION (Tokens board "eyebrow"). Write [text] in capitals. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = VeloTheme.surface.accent) {
    Text(text, modifier.lineBox(VeloTheme.type.eyebrow).semantics { heading() }, style = VeloTheme.type.eyebrow, color = color, maxLines = 1)
}
