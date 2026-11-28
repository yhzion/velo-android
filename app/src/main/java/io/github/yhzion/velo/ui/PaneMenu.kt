package io.github.yhzion.velo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.R
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.MenuDivider
import io.github.yhzion.velo.ui.theme.MenuItem
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.VeloTheme

/** A paste the menu asks about before it is sent: the dialog's [title] ([pasteQuestion]) and the clipboard's [text]. */
private class PasteAsked(val title: String, val text: String)

/**
 * The menu of the pane [block] ([paneMenu]), opened by the keyboard picker or the rail.
 * Every item acts on that pane. A paste that must be confirmed ([pasteQuestion]) shows its dialog
 * in the menu's place: the menu counts as open until the dialog is answered. [onMoveToNewTab]
 * moves the pane out as the overview's New Tab card does, so that its notice and Undo follow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaneMenu(
    window: WindowInfo, block: String, onDismiss: () -> Unit, conn: HostConnection,
    onAutoSplit: (String) -> Unit, onFind: (String) -> Unit, onNotifications: (String) -> Unit, onClose: (String) -> Unit, onCompose: (String, String) -> Unit, showing: (() -> Unit) -> () -> Unit,
    keyboard: Boolean = false, expanded: Boolean = true, onMoveToTab: ((String) -> Unit)? = null, onMoveToNewTab: (String) -> Unit,
    beforePaste: () -> Boolean = { false },
) {
    val panes = tabBlocks(window)
    val label = paneLabel(panes, block) ?: return
    val context = LocalContext.current
    val floating = window.floating.any { it.block == block }
    val zoomed = window.zoomed == block
    // Without the titles ([HostConnection.steady]), which the menu does not show: a new one does not compose it again.
    val session = conn.steady.session
    val canCompose = expanded && session != null && conn.steady.showsComposerPane(session, block)
    // Kept by the menu, which stays open (unseen) while it asks; closing the menu, or another pane, ends the question.
    var pasteAsked by remember(block, expanded) { mutableStateOf<PasteAsked?>(null) }
    // Every action changes or reads the panes, so it follows the compact rule; Close only opens its dialog.
    fun act(f: () -> Unit) = showing { onDismiss(); f() }()
    fun run(action: PaneAction) = when (action) {
        is PaneSplit, PaneAction.Float, PaneAction.Dock, PaneAction.RestoreInNewTab,
        PaneAction.Minimize, PaneAction.Zoom, PaneAction.Restore -> act { runPaneLayoutAction(action, block, conn::op, onClose, onAutoSplit) }
        // A pane move, which tells of itself and offers Undo as a move across the overview does.
        PaneAction.MoveToNewTab -> act { onMoveToNewTab(block) }
        // The picker takes over from the menu, so the menu only closes; it is no layout command.
        PaneAction.MoveToTab -> { onDismiss(); onMoveToTab?.invoke(block) }
        PaneAction.Copy -> act { copyPane(context, conn, block) }
        // What the keyboard composes goes first ([TerminalInputView.finishCompositionBeforePaste]); a keyboard
        // paste that waits for its question keeps the pane, and the menu only closes.
        PaneAction.Paste -> if (beforePaste()) onDismiss() else {
            // A file another app copied goes as the toolbar's Paste sends it (#109); a text clip as before.
            val file = clipboardFile(context)
            if (file != null) act { pasteClipboardFile(context, conn, block, file) } else {
                val text = clipboardText(context)
                val title = pasteQuestion(text, conn.bracketedPaste(block))
                if (title != null) pasteAsked = PasteAsked(title, text)
                else act { if (text.isNotEmpty()) conn.paste(block, text, confirmed = false) }
            }
        }
        PaneAction.Compose -> { onDismiss(); session?.let { onCompose(it, block) } }
        PaneAction.TextLarger -> act { conn.textSize(block) { it * HostConnection.TEXT_SIZE_STEP } }
        PaneAction.TextSmaller -> act { conn.textSize(block) { it / HostConnection.TEXT_SIZE_STEP } }
        PaneAction.TextReset -> act { conn.textSize(block) { 1f } }
        PaneAction.Close -> { onDismiss(); runPaneLayoutAction(action, block, conn::op, onClose, onAutoSplit) }
    }
    pasteAsked?.let { asked ->
        // Asked while connected: a connection lost meanwhile has nothing to paste into.
        VeloDialog(asked.title, onDismiss, confirmText = "Paste", focusCancel = true, onConfirm = { act { conn.paste(block, asked.text, confirmed = true) } },
            text = PasteRunsLinesText, confirmEnabled = conn.connected)
    }
    VeloMenu(expanded && pasteAsked == null, onDismissRequest = onDismiss, anchor = if (keyboard) ({ null }) else null, keepImeTarget = true,
        modifier = if (keyboard) Modifier.dismissWithEscape(onDismiss) else Modifier,
    ) {
        val first = remember { FocusRequester() }
        Text(label, Modifier.padding(horizontal = VeloTheme.sizes.rowPadding, vertical = VeloTheme.sizes.menuPadding).semantics { heading() },
            color = VeloTheme.surface.secondary, style = VeloTheme.type.body)
        MenuDivider()
        // Commands go to the server: without the connection they are listed and disabled.
        val connected = conn.connected
        val items = paneMenu(floating, zoomed, panes.size, canCompose, window.layout != null, minimized = window.minimized,
            connected = connected, canMoveToNewTab = canMoveToNewTab(window, block),
            canMoveToTab = onMoveToTab != null && showsMoveHandle(conn.steady.windows), canStash = canStash(window, block))
        Text("Split", Modifier.padding(horizontal = VeloTheme.sizes.rowPadding), style = VeloTheme.type.caption)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (direction in listOf(PaneSplit.Left, PaneSplit.Right, PaneSplit.Above, PaneSplit.Below, PaneSplit.Auto)) {
                // A keyboard opens the menu only on a pane it can type into, so connected: it starts on Split Right.
                val focus = if (keyboard && direction == PaneSplit.Right) Modifier.focusRequester(first) else Modifier
                Box(focus.sizeIn(minWidth = 48.dp, minHeight = VeloTheme.sizes.menuItem)
                    .semantics { contentDescription = direction.label }
                    .clickable(enabled = connected, role = Role.Button) { run(direction) }.padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    // The button's name is its content description: the icon says which side the new pane takes.
                    Icon(direction.glyph(), null, Modifier.size(VeloTheme.sizes.icon),
                        tint = if (connected) VeloTheme.surface.text else VeloTheme.surface.disabled)
                }
            }
        }
        for (item in items.filter { it.action !is PaneSplit && it.action != PaneAction.Close }) {
            MenuItem(text = item.label, enabled = item.enabled, glyph = item.action.glyph(), note = item.note, onClick = { run(item.action) })
        }
        MenuItem("Find in Pane…", { act { onFind(block) } }, icon = Icons.Default.Search,
            enabled = connected)
        MenuItem("Pane notifications…", { onDismiss(); onNotifications(block) }, icon = Icons.Default.Notifications,
            enabled = connected)
        items.firstOrNull { it.action == PaneAction.Close }?.let { item ->
            MenuDivider()
            MenuItem(text = item.label, enabled = item.enabled, destructive = true, glyph = item.action.glyph(), onClick = { run(item.action) })
        }
        if (keyboard) LaunchedEffect(block) { first.requestFocus() }
    }
}

@Composable
internal fun KeyboardPanePicker(
    panes: List<String>, tabPanes: List<String>, focused: String?, blocks: Map<String, BlockInfo>,
    onDismiss: () -> Unit, onPick: (String) -> Unit,
) {
    if (panes.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    VeloMenu(true, onDismiss, modifier = Modifier.dismissWithEscape(onDismiss), anchor = { null }) {
        val first = remember { FocusRequester() }
        for ((index, block) in panes.withIndex()) {
            val label = keyboardPaneLabel(tabPanes, block, focused, blocks[block]) ?: continue
            MenuItem(label, onClick = { onPick(block) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier)
        }
        LaunchedEffect(panes.first()) { first.requestFocus() }
    }
}

/**
 * An item of the hardware keys' pane picker: [block]'s place in [tabPanes] ([paneLabel]), " (focused)" for the
 * [focused] one, then its name by the one rule ([paneName], #208), as the palette's Focus Pane names it. Null for a
 * pane not in [tabPanes].
 */
internal fun keyboardPaneLabel(tabPanes: List<String>, block: String, focused: String?, info: BlockInfo?): String? {
    val place = paneLabel(tabPanes, block) ?: return null
    return place + (if (block == focused) " (focused)" else "") + " · " + paneName(info)
}

private fun Modifier.dismissWithEscape(onDismiss: () -> Unit): Modifier = onPreviewKeyEvent {
    if (it.key != Key.Escape) return@onPreviewKeyEvent false
    if (it.type == KeyEventType.KeyUp) onDismiss()
    true
}

/** Local vectors for pane layout actions, Material icons for the rest. */
@Composable
internal fun PaneAction.glyph(): Painter = when (this) {
    PaneAction.Float -> painterResource(R.drawable.ic_pane_float)
    PaneAction.Dock -> painterResource(R.drawable.ic_pane_dock)
    PaneSplit.Left -> painterResource(R.drawable.ic_split_left)
    PaneSplit.Right -> painterResource(R.drawable.ic_split_right)
    PaneSplit.Above -> painterResource(R.drawable.ic_split_above)
    PaneSplit.Below -> painterResource(R.drawable.ic_split_below)
    PaneSplit.Auto -> painterResource(R.drawable.ic_split_auto)
    else -> rememberVectorPainter(icon)
}

private val PaneAction.icon
    get() = when (this) {
        is PaneSplit, PaneAction.Float, PaneAction.Dock -> error("drawn from a drawable: $this")
        PaneAction.RestoreInNewTab, PaneAction.MoveToNewTab -> Icons.Default.OpenInNew
        PaneAction.Minimize -> Icons.Default.Minimize
        PaneAction.MoveToTab -> Icons.Default.DragIndicator
        PaneAction.Zoom -> Icons.Default.Fullscreen
        PaneAction.Restore -> Icons.Default.FullscreenExit
        PaneAction.Copy -> Icons.Default.ContentCopy
        PaneAction.Paste -> Icons.Default.ContentPaste
        PaneAction.Compose -> Icons.Default.Edit
        PaneAction.TextLarger -> Icons.Default.TextIncrease
        PaneAction.TextSmaller -> Icons.Default.TextDecrease
        PaneAction.TextReset -> Icons.Default.FormatSize
        PaneAction.Close -> Icons.Default.Close
    }
