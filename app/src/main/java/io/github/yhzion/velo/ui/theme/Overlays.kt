package io.github.yhzion.velo.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
    content: @Composable BoxScope.() -> Unit,
) {
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
        CompositionLocalProvider(LocalOverlayWindow provides true) {
            // This window has haptics of its own: the setting gates them here too.
            ProvideHaptics {
                Box(
                    Modifier.fillMaxSize().background(VeloTheme.colors.scrim)
                        .pointerInput(onDismissRequest, closesOnTapOutside) { detectTapGestures { if (closesOnTapOutside) onDismissRequest() } }
                        .windowInsetsPadding(WindowInsets.screenSafeDrawing),
                    content = content,
                )
            }
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

/** The surface of a menu and its items ([MenuItem], [MenuDivider]); taller than its place, it scrolls. */
@Composable
fun MenuSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val s = VeloTheme.sizes
    OverlaySurface(modifier.widthIn(min = s.menuMinWidth, max = s.menuWidth)) {
        Column(Modifier.boundedHeight().verticalScroll(rememberScrollState()).padding(vertical = s.menuPadding), content = content)
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
    val color = if (!enabled) c.disabled else if (destructive) c.danger else c.text
    Row(
        modifier.fillMaxWidth().heightIn(min = s.menuItem).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = s.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(s.iconSmall), tint = color)
            Spacer(Modifier.width(s.rowGap))
        }
        Column(Modifier.padding(vertical = if (note != null) s.menuPadding else 0.dp)) {
            Text(text, style = VeloTheme.type.body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = VeloTheme.type.caption, color = c.secondary)
        }
    }
}

/** A line between two groups of menu items. */
@Composable
fun MenuDivider(modifier: Modifier = Modifier) {
    val s = VeloTheme.sizes
    Spacer(modifier.padding(vertical = s.menuPadding).fillMaxWidth().height(s.edge).background(VeloTheme.surface.divider))
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
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    OverlayHost(onDismissRequest, closesOnBack = closesOnBack, closesOnTapOutside = closesOnTapOutside) {
        DialogSurface(title, confirmText, onConfirm, onCancel = onDismissRequest, modifier.align(Alignment.Center).padding(VeloTheme.sizes.overlayMargin),
            text, cancelText, destructive, confirmEnabled, cancelEnabled, fieldFocus, focusCancel, keyFocus, content)
    }
}

/** The surface of a dialog, without its window. */
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
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val s = VeloTheme.sizes
    val fallback = remember { FocusRequester() }
    // With the caller's field focus the field takes the keys; otherwise the safe-default button does.
    val focus = fieldFocus ?: fallback
    if (keyFocus == null) LaunchedEffect(Unit) { focus.requestFocus() }
    val gate = if (keyFocus != null) Modifier.focusProperties { canFocus = keyFocus() } else Modifier
    OverlaySurface(modifier.width(s.dialogWidth)) {
        // Bounded here, not at the content: a column without a height gives a weighted child none.
        Column(Modifier.boundedHeight().padding(s.overlayPadding), verticalArrangement = Arrangement.spacedBy(s.overlayPadding)) {
            // The title scrolls with the content, so a long one cannot push the buttons out of the surface.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(s.overlayPadding)) {
                // The words sit inside the corner's curve; the fields and buttons reach the padding.
                Column(Modifier.padding(horizontal = s.menuPadding).padding(top = s.menuPadding), verticalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                    Text(title, modifier = Modifier.semantics { heading() }, style = VeloTheme.type.overlayTitle, color = VeloTheme.surface.text)
                    if (text != null) Text(text, style = VeloTheme.type.body, color = VeloTheme.surface.secondary)
                }
                if (content != null) Column(verticalArrangement = Arrangement.spacedBy(s.menuPadding), content = content)
            }
            // One height for both buttons: when only one label wraps at large text, the other grows with it.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                // A destructive confirm opens on Cancel, the safer default; other dialogs open on the confirming button.
                val cancelFocus = if ((focusCancel || destructive) && fieldFocus == null) Modifier.focusRequester(fallback) else Modifier
                val confirmFocus = if (!focusCancel && !destructive && fieldFocus == null) Modifier.focusRequester(fallback) else Modifier
                VeloButton(cancelText, onCancel, gate.then(cancelFocus).weight(1f).fillMaxHeight(), enabled = cancelEnabled)
                VeloButton(confirmText, onConfirm, gate.then(confirmFocus).weight(1f).fillMaxHeight(), if (destructive) ButtonKind.Destructive else ButtonKind.Primary, confirmEnabled)
            }
        }
    }
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
