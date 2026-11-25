package io.github.yhzion.velo.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import io.github.yhzion.velo.R
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.yhzion.velo.ui.screenSafeDrawing

// What opens over the panes: a menu, a dialog, a popover, the command palette. Each is an
// OverlaySurface (Glass.kt) in a window of its own that covers the screen's window, with the
// scrim under it; a tap on the scrim or Back closes it.

private tailrec fun Context.activity(): Activity? = this as? Activity ?: (this as? ContextWrapper)?.baseContext?.activity()

/**
 * The window of an overlay: as large as the screen's, the scrim over all of it, [content]
 * placed inside the safe area. It keeps the system bars as the screen's window has them, so
 * that opening an overlay moves and resizes nothing behind it. Back and a tap on the scrim call
 * [onDismissRequest], unless [closesOnBack] or [closesOnTapOutside] is false: then they do nothing.
 */
@Composable
internal fun OverlayHost(
    onDismissRequest: () -> Unit, keepImeTarget: Boolean = false, closesOnBack: Boolean = true, closesOnTapOutside: Boolean = true,
    /** The test tag of the scrim, such as `dialog-scrim`. */
    scrimTag: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val layer = LocalOverlayLayer.current
    if (layer != null) {
        val body by rememberUpdatedState<@Composable () -> Unit> { OverlayScrim(onDismissRequest, closesOnTapOutside, scrimTag, content) }
        DisposableEffect(layer) {
            val entry: @Composable () -> Unit = { body() }
            layer.open += entry
            onDispose { layer.open -= entry }
        }
        return
    }
    val activity = LocalContext.current.activity()
    Dialog(onDismissRequest, DialogProperties(dismissOnBackPress = closesOnBack, dismissOnClickOutside = closesOnTapOutside,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The scrim is the token's, drawn here; the platform's own dimming would add to it.
            window.setDimAmount(0f)
            // A pane menu takes key focus but never edits text. Keep the IME attached to the terminal below.
            if (keepImeTarget) window.addFlags(FLAG_ALT_FOCUSABLE_IM)
            val bars = WindowInsetsCompat.Type.systemBars()
            val shown = activity?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }?.isVisible(bars) ?: true
            if (!shown) WindowCompat.getInsetsController(window, view).run {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(bars)
            }
        }
        OverlayScrim(onDismissRequest, closesOnTapOutside, scrimTag, content)
    }
}

/** The scrim of an overlay's window, with the overlay's [content] inside the safe area. */
@Composable
private fun OverlayScrim(onDismissRequest: () -> Unit, closesOnTapOutside: Boolean, scrimTag: String?, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalOverlayWindow provides true) {
        // This window has haptics of its own: the setting gates them here too.
        ProvideHaptics {
            Box(
                Modifier.fillMaxSize().then(if (scrimTag != null) Modifier.testTag(scrimTag) else Modifier).background(VeloTheme.colors.scrim)
                    .pointerInput(onDismissRequest, closesOnTapOutside) { detectTapGestures { if (closesOnTapOutside) onDismissRequest() } }
                    .windowInsetsPadding(WindowInsets.screenSafeDrawing),
                content = content,
            )
        }
    }
}

/**
 * The overlays open over [OverlayLayerBox], each drawn in the window it opens from instead of a
 * window of its own: a test that captures one window then sees them ([LocalOverlayLayer]).
 */
class OverlayLayer {
    internal val open = mutableStateListOf<@Composable () -> Unit>()
}

/** Where an overlay opens: in a window of its own while this is null, as in the app; over [OverlayLayerBox] in a test. */
val LocalOverlayLayer = staticCompositionLocalOf<OverlayLayer?> { null }

/** [content], with every overlay it opens drawn over it ([OverlayLayer]). */
@Composable
fun OverlayLayerBox(layer: OverlayLayer, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverlayLayer provides layer) {
        Box(modifier) {
            content()
            for (overlay in layer.open) key(overlay) { overlay() }
        }
    }
}

/**
 * Lets what follows scroll wherever it is placed. In an overlay's window the height is the
 * window's; laid out in a page that scrolls itself there is none, and a scrolling column
 * refuses to be measured without one. Then it is given one that no content reaches.
 */
private fun Modifier.boundedHeight(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(if (constraints.hasBoundedHeight) constraints else constraints.copy(maxHeight = UnboundedHeight))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private const val UnboundedHeight = 1 shl 15

/** Places [content] for the [anchor] it belongs to ([menuPosition]); without one, in the middle. */
@Composable
private fun Anchored(anchor: IntRect?, content: @Composable () -> Unit) {
    val margin = with(LocalDensity.current) { VeloTheme.sizes.overlayMargin.roundToPx() }
    var area by remember { mutableStateOf<IntRect?>(null) }
    Layout(content, Modifier.fillMaxSize().onGloballyPositioned { area = it.onScreen() }) { measurables, constraints ->
        val window = IntSize(constraints.maxWidth, constraints.maxHeight)
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = maxOf(0, window.width - 2 * margin), maxHeight = maxOf(0, window.height - 2 * margin)))
        layout(window.width, window.height) {
            val here = area
            val size = IntSize(placeable.width, placeable.height)
            if (anchor == null || here == null) placeable.place((window.width - size.width) / 2, (window.height - size.height) / 2)
            else placeable.place(menuPosition(anchor.translate(-here.left, -here.top), size, window, margin))
        }
    }
}

/**
 * Remembers where the composable this is called in is on the screen: the anchor of a menu or
 * popover declared beside its button, as `DropdownMenu` is.
 */
@Composable
private fun rememberAnchor(): () -> IntRect? {
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    Box(Modifier.onGloballyPositioned { anchor = it.parentLayoutCoordinates?.onScreen() })
    return { anchor }
}

/**
 * A menu. While [expanded] it is open under its [anchor], a rectangle of the screen (for a
 * long press: the place of the finger), or above it where there is no room below. Without an
 * [anchor] it belongs to the composable it is declared in: put it in the `Box` of its button.
 */
@Composable
fun VeloMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, anchor: (() -> IntRect?)? = null,
    keepImeTarget: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val at = anchor ?: rememberAnchor()
    if (expanded) OverlayHost(onDismissRequest, keepImeTarget) { Anchored(at()) { MenuSurface(modifier, content) } }
}

/**
 * The surface of a menu and its items ([MenuItem], [MenuDivider], [SelectMenuItem]): a pill of
 * the overlay (Overlays board: radius 24, 8 above and below the items). Taller than its place, it scrolls.
 */
@Composable
fun MenuSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = VeloTheme.sizes
    OverlaySurface(modifier.widthIn(min = s.menuMinWidth, max = s.menuWidth), shape = rounded(VeloTheme.radii.pill)) {
        Column(Modifier.boundedHeight().verticalScroll(rememberScrollState()).padding(vertical = VeloTheme.space.s8), content = content)
    }
}

/**
 * One item of a menu. One that is not [enabled] is dimmed and takes no tap; a [destructive]
 * one is drawn in the danger colour. A [note] is a line under the text that says more of it, such as why
 * the item is not enabled; a screen reader reads the text, then the note.
 */
@Composable
fun MenuItem(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, destructive: Boolean = false,
    note: String? = null,
) = MenuItem(text, onClick, glyph = icon?.let { rememberVectorPainter(it) }, modifier = modifier, enabled = enabled, destructive = destructive, note = note)

/** A [MenuItem] whose icon is a drawable ([glyph]): one Rex for Mac draws, tinted like the text. */
@Composable
fun MenuItem(
    text: String, onClick: () -> Unit, glyph: Painter?, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false,
    note: String? = null,
) {
    val icon = glyph
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val color = if (!enabled) c.disabled else if (destructive) c.dangerText else c.text
    // An item's icon is the secondary colour beside its words, the red with a destructive item's (Overlays board).
    val iconColor = if (!enabled || destructive) color else c.secondary
    Row(
        modifier.fillMaxWidth().heightIn(min = s.menuItem).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = VeloTheme.space.s20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(VeloTheme.space.s20), tint = iconColor)
            Spacer(Modifier.width(VeloTheme.medium(14.dp)))
        }
        Column(Modifier.padding(vertical = if (note != null) s.menuPadding else 0.dp)) {
            Text(text, style = VeloTheme.type.body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = VeloTheme.type.caption, color = c.secondary)
        }
    }
}

/** A line between two groups of menu items (Overlays board: 8 above and below). */
@Composable
fun MenuDivider(modifier: Modifier = Modifier) {
    val s = VeloTheme.sizes
    Spacer(modifier.padding(vertical = VeloTheme.space.s8).fillMaxWidth().height(s.edge).background(VeloTheme.surface.divider))
}

/**
 * One choice of a menu that picks a value ([SelectMenu]): its words, and a check in the accent
 * when it is the current value (Overlays board, select-menu).
 */
@Composable
fun SelectMenuItem(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    Row(
        modifier.fillMaxWidth().heightIn(min = VeloTheme.sizes.menuItem).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = VeloTheme.space.s20, end = VeloTheme.space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = VeloTheme.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Default.Check, null, Modifier.size(VeloTheme.medium(18.dp)), tint = c.accent)
    }
}

/**
 * A menu that picks one of [options], opened from a [MenuButton]: the current one has a check.
 * Picking one calls [onPick] and closes the menu. [optionTag] gives each choice its test tag
 * (`select-menu-option-<id>`); the menu's own is set on [modifier].
 */
@Composable
fun <T> SelectMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit,
    modifier: Modifier = Modifier, anchor: (() -> IntRect?)? = null, optionTag: ((T) -> String)? = null,
) {
    VeloMenu(expanded, onDismissRequest, modifier.selectableGroup(), anchor) {
        for (option in options) {
            SelectMenuItem(
                label(option), option == selected, onClick = { onPick(option); onDismissRequest() },
                modifier = if (optionTag != null) Modifier.testTag(optionTag(option)) else Modifier,
            )
        }
    }
}

/** A popover: free [content] on an overlay surface, placed as a [VeloMenu] is. */
@Composable
fun VeloPopover(
    expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, anchor: (() -> IntRect?)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val at = anchor ?: rememberAnchor()
    if (expanded) OverlayHost(onDismissRequest) { Anchored(at()) { PopoverSurface(modifier, content) } }
}

/** The surface of a popover. */
@Composable
fun PopoverSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = VeloTheme.sizes
    OverlaySurface(modifier.widthIn(max = s.dialogWidth)) { Column(Modifier.padding(s.overlayPadding), content = content) }
}

/**
 * A dialog in the middle of the window: [title], an optional [text], then [content] (fields,
 * a preview), then Cancel and the confirming button, which is the accent's or, [destructive],
 * the danger's. Back and a tap beside it cancel, unless [closesOnBack] or [closesOnTapOutside]
 * is false: then only Cancel does, and they do nothing.
 */
@Composable
fun VeloDialog(
    title: String, onDismissRequest: () -> Unit, confirmText: String, onConfirm: () -> Unit, modifier: Modifier = Modifier,
    text: String? = null, cancelText: String = "Cancel", destructive: Boolean = false, confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    /** The caller's field focus, requested on open; absent means a button takes it. */
    fieldFocus: FocusRequester? = null,
    /** Focus the Cancel button instead of the confirming one on open (a destructive confirm). */
    focusCancel: Boolean = false,
    closesOnBack: Boolean = true,
    closesOnTapOutside: Boolean = true,
    /**
     * Null: the dialog takes key focus on open ([fieldFocus], or a button). Otherwise nothing is
     * focused on open, and the buttons take key focus only while it returns true: a dialog that
     * comes up uninvited must not catch keys typed for what is under it.
     */
    keyFocus: (() -> Boolean)? = null,
    /** The test tags of the dialog's parts ([DialogSurface]); the dialog's own is set on [modifier]. */
    tagPrefix: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    OverlayHost(onDismissRequest, closesOnBack = closesOnBack, closesOnTapOutside = closesOnTapOutside) {
        DialogSurface(title, confirmText, onConfirm, onCancel = onDismissRequest, modifier.align(Alignment.Center).padding(VeloTheme.sizes.overlayMargin),
            text, cancelText, destructive, confirmEnabled, cancelEnabled, fieldFocus, focusCancel, keyFocus, tagPrefix, content)
    }
}

/**
 * The surface of a dialog, without its window (Overlays board): the title, the [text] in the
 * overlay's secondary colour, the [content], then Cancel as words and the confirming button
 * filled with the accent, or with the red when [destructive], at the lower right. The buttons
 * wrap under each other when they do not fit side by side. [tagPrefix] names the parts for
 * tests: `<prefix>-title`, `-body`, `-actions`, `-cancel` and `-confirm`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogSurface(
    title: String, confirmText: String, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
    text: String? = null, cancelText: String = "Cancel", destructive: Boolean = false, confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    /** The caller's field focus, requested on open; absent means a button takes it. */
    fieldFocus: FocusRequester? = null,
    /** Focus the Cancel button instead of the confirming one on open (a destructive confirm). */
    focusCancel: Boolean = false,
    /** Null: focus on open as above. Otherwise no focus on open, and the buttons take key focus only while it returns true. */
    keyFocus: (() -> Boolean)? = null,
    tagPrefix: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val s = VeloTheme.sizes
    val space = VeloTheme.space
    val c = VeloTheme.colors.overlay
    val fallback = remember { FocusRequester() }
    // With the caller's field focus the field takes the keys; otherwise the safe-default button does.
    val focus = fieldFocus ?: fallback
    if (keyFocus == null) LaunchedEffect(Unit) { focus.requestFocus() }
    val gate = if (keyFocus != null) Modifier.focusProperties { canFocus = keyFocus() } else Modifier
    fun Modifier.part(name: String) = if (tagPrefix != null) testTag("$tagPrefix-$name") else this
    // A dialog with fields spaces them 16; a question alone keeps its words 12 apart.
    val gap = if (content != null) space.s16 else space.s12
    OverlaySurface(modifier.widthIn(max = s.dialogWidth).fillMaxWidth()) {
        // Bounded here, not at the content: a column without a height gives a weighted child none.
        Column(
            Modifier.boundedHeight().padding(start = space.s20, end = space.s20, top = VeloTheme.medium(22.dp), bottom = space.s16),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            // The title scrolls with the content, so a long one cannot push the buttons out of the surface.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                Text(title, modifier = Modifier.semantics { heading() }.part("title"), style = VeloTheme.type.overlayTitle, color = c.text)
                if (text != null) Text(text, Modifier.part("body"), style = VeloTheme.type.value.copy(lineHeight = 1.5.em), color = c.secondary)
                if (content != null) content()
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = VeloTheme.medium(if (content != null) 4.dp else 6.dp)).part("actions"),
                horizontalArrangement = Arrangement.spacedBy(space.s8, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(space.s8),
            ) {
                // A destructive confirm opens on Cancel, the safer default; other dialogs open on the confirming button.
                val cancelFocus = if ((focusCancel || destructive) && fieldFocus == null) Modifier.focusRequester(fallback) else Modifier
                val confirmFocus = if (!focusCancel && !destructive && fieldFocus == null) Modifier.focusRequester(fallback) else Modifier
                DialogButton(cancelText, onCancel, gate.then(cancelFocus).part("cancel"), fill = null, label = c.text, enabled = cancelEnabled)
                // The red of a destructive confirm is the sheet's, as the board fills it; the overlay's own red is lighter, for words.
                DialogButton(
                    confirmText, onConfirm, gate.then(confirmFocus).part("confirm"),
                    fill = if (destructive) VeloTheme.colors.sheet.danger else c.accent,
                    label = if (destructive) VeloTheme.colors.sheet.onDanger else c.onAccent, enabled = confirmEnabled,
                )
            }
        }
    }
}

/** A dialog's button: words alone ([fill] null, Cancel) or a filled pill (the confirming button). */
@Composable
private fun DialogButton(text: String, onClick: () -> Unit, modifier: Modifier, fill: Color?, label: Color, enabled: Boolean) {
    val c = VeloTheme.surface
    val frame = if (fill == null) Modifier.clip(Capsule) else Modifier.clip(Capsule).background(if (enabled) fill else c.glass)
    Box(
        modifier.minimumInteractiveComponentSize().heightIn(min = VeloTheme.sizes.control).then(frame)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (fill == null) VeloTheme.space.s20 else VeloTheme.space.s24, vertical = VeloTheme.space.s4),
        contentAlignment = Alignment.Center,
    ) {
        // Large text wraps to a second line instead of losing its end; the button grows to hold it.
        Text(
            text, style = VeloTheme.type.body.copy(fontWeight = if (fill == null) FontWeight.Medium else FontWeight.SemiBold),
            color = if (enabled) label else c.disabled, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How long a toast stays: three seconds (Overlays board), longer where the accessibility settings ask for it. */
const val ToastMillis = 3000L

/**
 * A short note at the bottom of the screen (Overlays board, toast): an optional [icon] in
 * [iconTint] (the ok green unless given), the [message], and an optional action in the accent.
 * It is a polite live region, so a screen reader reads it out. Its test tags are the board's:
 * `toast`, `toast-message`, `toast-action`. The caller places it ([VeloToast] times it).
 */
@Composable
fun ToastSurface(
    message: String, modifier: Modifier = Modifier, icon: ImageVector? = null, iconTint: Color? = null,
    actionLabel: String? = null, onAction: (() -> Unit)? = null,
) {
    val c = VeloTheme.colors.overlay
    val s = VeloTheme.sizes
    VeloSurface(c) {
        Row(
            modifier.testTag("toast").shadow(s.overlayShadow, Capsule, clip = false).heightIn(min = s.control).clip(Capsule)
                .background(c.surface).border(s.edge, c.divider, Capsule)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(start = VeloTheme.medium(18.dp), end = VeloTheme.space.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(10.dp)),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(VeloTheme.medium(18.dp)), tint = iconTint ?: c.ok)
            Text(message, Modifier.weight(1f, fill = false).testTag("toast-message").padding(vertical = VeloTheme.space.s8),
                style = VeloTheme.type.value, color = c.text)
            if (actionLabel != null && onAction != null) {
                Box(
                    Modifier.testTag("toast-action").minimumInteractiveComponentSize().heightIn(min = VeloTheme.medium(40.dp)).clip(Capsule)
                        .clickable(role = Role.Button, onClick = onAction).padding(horizontal = VeloTheme.space.s12),
                    contentAlignment = Alignment.Center,
                ) { Text(actionLabel, style = VeloTheme.type.value, fontWeight = FontWeight.Medium, color = c.accent) }
            }
        }
    }
}

/**
 * A [ToastSurface] while [message] is set, which calls [onDismiss] after [ToastMillis], or after
 * as long as the accessibility settings ask for. A new message starts the time again.
 */
@Composable
fun VeloToast(
    message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, iconTint: Color? = null,
    actionLabel: String? = null, onAction: (() -> Unit)? = null,
) {
    if (message == null) return
    val accessibility = LocalAccessibilityManager.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        delay(accessibility?.calculateRecommendedTimeoutMillis(ToastMillis, containsIcons = icon != null, containsText = true, containsControls = onAction != null) ?: ToastMillis)
        dismiss()
    }
    ToastSurface(message, modifier, icon, iconTint, actionLabel, onAction)
}

/**
 * The command palette, at the top of the window: its field, then the matches ([PaletteRow]) in
 * [content]. The keyboard's Go calls [onGo]. Back and a tap beside it close it.
 */
@Composable
fun VeloPalette(
    query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "Command Palette", content: LazyListScope.() -> Unit,
) {
    OverlayHost(onDismissRequest) {
        PaletteSurface(query, onQueryChange, onGo, modifier.align(Alignment.TopCenter).padding(VeloTheme.sizes.overlayMargin), placeholder, focus = true, content)
    }
}

/** The surface of the command palette, without its window. [focus] gives its field the keyboard at once. */
@Composable
fun PaletteSurface(
    query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "Command Palette", focus: Boolean = false, content: LazyListScope.() -> Unit,
) {
    val s = VeloTheme.sizes
    val requester = remember { FocusRequester() }
    OverlaySurface(modifier.width(s.paletteWidth).heightIn(max = s.paletteHeight)) {
        Column(Modifier.padding(s.overlayPadding), verticalArrangement = Arrangement.spacedBy(s.menuPadding)) {
            VeloTextField(
                query, onQueryChange, Modifier.focusRequester(requester), placeholder = placeholder, icon = painterResource(R.drawable.ic_toolbar_command),
                // Without an explicit action the field reports none, and Enter does nothing.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }),
            )
            LazyColumn(content = content)
        }
    }
    if (focus) LaunchedEffect(Unit) { requester.requestFocus() }
}

/** One match of the palette. One that is not [enabled] stays listed, dimmed, with [note] saying why. */
@Composable
fun PaletteRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, note: String? = null) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Column(
        modifier.fillMaxWidth().heightIn(min = s.menuItem).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = s.rowPadding, vertical = s.menuPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = VeloTheme.type.body, color = if (enabled) c.text else c.disabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!enabled && note != null) Text(note, style = VeloTheme.type.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
