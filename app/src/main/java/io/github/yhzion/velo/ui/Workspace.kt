package io.github.yhzion.velo.ui

import io.github.yhzion.velo.PaneNotificationGate
import io.github.yhzion.velo.PaneNotificationLaunches
import io.github.yhzion.velo.PaneNotificationNotice
import io.github.yhzion.velo.Tailnet
import io.github.yhzion.velo.notYetRows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Text
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.ToastSurface
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.feel

/** Fold posture as observed by the activity. */
data class FoldInfo(val tabletop: Boolean = false, val hingeTopPx: Int = 0)

/**
 * The active remote's session: [conn] is the connection the panes, key bar and palette act on.
 * [onSettings] opens the app's settings (the sidebar's gear, the palette) and [onTailscale]
 * This Device's own screen (its row).
 */
@OptIn(ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun Workspace(
    app: AppController, conn: HostConnection, input: TerminalInputView, fold: FoldInfo, notification: PaneNotificationGate?,
    launches: PaneNotificationLaunches, settingsOpen: Boolean, onSettings: () -> Unit, onTailscale: () -> Unit,
) {
    // The state without the titles, which an agent changes many times a second: the workspace is not composed
    // again for each. What draws a title reads [HostConnection.state] itself: the caption, the rail's wheel, the
    // overview's cards, the minimized panes, the pane picker, the palette and the dialogs.
    val state = conn.steady
    val s = app.settings
    val view = LocalView.current
    var sidebar by remember { mutableStateOf(false) }
    // State that names a tab or pane belongs to one host: a host switch starts it afresh. Only a switch
    // made on the overview's strip hands the next host's overview what it opens with (#236).
    val overviewCarry = remember { OverviewCarry() }
    val overview = rememberOverviewState(conn, overviewCarry) { id -> if (id != conn.state.window) notification?.leave?.invoke() }
    val density = LocalDensity.current
    // An input method's inset that covers the whole window takes no room (#325); whether one is shown is read as it is.
    val imeInsets = WindowInsets.screenIme
    val keyboard = WindowInsets.isImeVisible
    var palette by remember(conn) { mutableStateOf(false) }
    // The search field owns a separate IME while the palette is open.
    var terminalImeOccupiedSpace by remember(conn) { mutableStateOf(false) }
    var terminalKeyboardBeforePalette by remember(conn) { mutableStateOf(false) }
    /** Open the palette, from the sidebar or the hardware shortcut: both remember whether the terminal's keyboard was up. */
    fun openPalette() {
        terminalKeyboardBeforePalette = keyboard && input.hasFocus()
        // A hardware keyboard may leave the IME visible with a zero-height frame.
        terminalImeOccupiedSpace = imeInsets.getBottom(density) > 0 && input.hasFocus()
        palette = true
    }
    var confirm by remember(conn) { mutableStateOf<Confirm?>(null) }
    // However a close dialog ends (Cancel, Back, another dialog, its target gone, another host), it
    // tells whoever asked. Watched here and not where it is drawn: a screen that covers the panes
    // (a notification) leaves the dialog out for a while, and it is still open.
    val openConfirm = confirm
    DisposableEffect(openConfirm) { onDispose { openConfirm?.end() } }
    var composer by remember { mutableStateOf<ComposerTarget?>(null) }
    fun openComposer(session: String, block: String) {
        if (notification?.canUsePane() != false && composer == null && app.active === conn && app.removed == null && conn.state.showsComposerPane(session, block)) {
            val epoch = conn.composerTarget(session, block)
            if (epoch != 0L) {
                launches.draftOpened()
                composer = ComposerTarget(conn, session, block, epoch, composerTargetLabel(app.activeRemote?.label, conn.state))
            } else conn.error = "The pane is no longer available. Reopen its menu."
        }
    }
    var renaming by remember { mutableStateOf<Renaming?>(null) }
    // A paste from the keyboard that waits for its question ([TerminalInputView.onPasteQuestion]).
    var keyboardPaste by remember(conn) { mutableStateOf<KeyboardPaste?>(null) }
    // A link a pane printed that launches another app ([LinkTap.Ask]), one a program set under
    // other text ([LinkTap.Hidden]) or one that needs a new forward ([LinkTap.Forward]), waiting for its question.
    var linkQuestion by remember(conn) { mutableStateOf<LinkQuestion?>(null) }
    val fileContext = LocalContext.current
    val files = remember { RemoteFileOpener.of(fileContext) }
    DisposableEffect(fileContext) {
        val activity = fileContext as? android.app.Activity
        files.activity = activity
        onDispose { if (files.activity === activity) files.activity = null }
    }
    // The key bar's sticky keys (Ctrl, Alt) that wait for the next key. They wait for a key of
    // this remote: they are let go when another remote is drawn, and when this screen goes.
    var armed by remember(conn) { mutableStateOf(emptySet<String>()) }
    DisposableEffect(input, conn) {
        onDispose {
            if (input.connection != null && input.connection !== conn) return@onDispose
            if (input.hasFocus()) input.hideTerminalKeyboard()
            input.disconnectInput()
            input.stickyMeta = 0
            input.onStickyConsumed = {}
        }
    }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Remote?>(null) }
    // The remote whose SSH details are open ([SshDetailsPanel]).
    var sshDetailsFor by remember { mutableStateOf<Remote?>(null) }
    // Opening the sidebar sweeps the tailnet for Rex servers (at most every few seconds).
    LaunchedEffect(sidebar) { if (sidebar) Tailnet.sweepSoon() }
    val config = LocalConfiguration.current
    val compact = isCompact(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
    // The margins really used around the row: the width left beside the sidebar depends on them.
    val sizes = VeloTheme.sizes
    val compactStyle = s.paneStyle == "compact"
    val gap = if (compactStyle) sizes.gapCompact else sizes.gap
    val outerY = if (compactStyle) with(density) { sizes.compactOuterPx.toDp() } else gap
    val outerX = if (compactStyle && !compact) with(density) { sizes.compactWideSidePx.toDp() } else outerY
    // Where the open sidebar covers the panes: on a small window, and where it would leave them too narrow,
    // unless the user docked it beside them where there is room for both (the unfolded screen upright).
    val fit = sidebarFit(s.sidebarDocked, gap, outerX)
    val dockable = fit.dockable
    val covers = fit.place == SidebarPlace.Covers
    val panesHidden = covers && sidebar
    // Whether the sidebar that covers the panes holds notices ([Sidebar]'s notices), with the keyboard up or not (#195).
    // Read as whether some notice shows, so that a new upload or error text composes the notices and not the workspace (#335).
    val anyNotice by remember(conn) { derivedStateOf { conn.uploading != null || conn.error != null } }
    // What a screen reader has heard of the notices that move between the pane column and that sidebar (#195).
    val heard = remember(conn) { HeardNotices() }
    /** The palette's docking command and the sidebar's pin (sidebar-dock): [toggleSidebarDock]. */
    fun toggleDock() { sidebar = toggleSidebarDock(s.sidebarDocked, s::saveSidebarDocked) }
    /**
     * The one rule for the sidebar that covers the panes ([covers]): an action that changes
     * what the panes show runs, then the sidebar closes so its result is seen. Toggling the sidebar and opening a
     * dialog are not such actions; a dialog's confirming action may be.
     */
    fun showing(action: () -> Unit): () -> Unit = { action(); if (covers) sidebar = false }

    val activeRemote = app.activeRemote
    // The active remote the user removed: its row and this empty workspace stay until they leave it.
    val removedActive = app.removed != null
    // SSH logins that wait for the user's answer, or for Reconnect after none came: they say so, not "canceled".
    val needsAnswer by app.sshQuestions.needsAnswer.collectAsState()
    val remoteName = activeRemote?.label?.ifEmpty { null } ?: "Remote"
    // A connected remote keeps no failure notice, whatever asks meanwhile.
    val waitsForAnswer = !removedActive && state.status != "connected" && conn.url in needsAnswer
    // Why the remote is down, and the one-line command that fixes it ("Copy command", convenience C).
    val notice = failureNotice(conn.failure, waitsForAnswer, remoteName)
    fun copyFix() { notice?.fix?.let { copyPlainText(fileContext, it) } }
    fun noticeText(error: String) = errorNotice(error, conn.failure, waitsForAnswer, remoteName)
    val connected = !removedActive && state.status == "connected"
    // One mark for the connection: the rail and the cover over paused panes both show it.
    val mark = connectionMark(state.status, removedActive)
    // While the connection is down, nothing typed reaches a pane ([paneInputPaused]).
    val paused = paneInputPaused(state.status)
    // Paused panes lie under InputPausedCover and draw no controls: each control is a command to
    // a core that is down. The cover is not drawn for a removed remote, because its empty pane
    // area already says what happened.
    val covered = paused && !removedActive
    /** Save a remote; when that changes the remote drawn here (the removed one saved again), the rule of [showing] applies. */
    fun addRemote(label: String, url: String): String? {
        val taken = app.addRemote(label, url)
        if (app.active !== conn) showing {}()
        return taken
    }

    /**
     * Close [target] on [c] now. When that changes what the panes show ([closeChangesThePanes]:
     * the shown tab, the shown session, a pane) the rule of [showing] applies; closing anything else
     * edits the sidebar's list, which stays open.
     */
    fun close(c: HostConnection, target: CloseTarget) {
        if ((target is CloseTarget.Pane || target is CloseTarget.LastPane) && closeQuestion(c.state, target) == null) return
        val seen = closeChangesThePanes(c.state, active = c === conn, target)
        when (target) {
            is CloseTarget.Pane -> c.op("close", "block" to target.block)
            is CloseTarget.LastPane -> c.op("closeLastPane", "session" to target.session, "window" to target.tab, "block" to target.block)
            is CloseTarget.Tab -> c.op("closeWindow", "session" to target.session, "window" to target.tab)
            is CloseTarget.Session -> c.op("closeSession", "session" to target.session)
        }
        if (seen) showing {}()
    }
    fun askToClose(target: CloseTarget) {
        if (closeQuestion(conn.state, target) != null) confirm = Confirm(conn, target)
    }
    fun askToClosePane(block: String) = askToClose(CloseTarget.Pane(block))
    fun askToCloseFromPaneMenu(block: String) {
        val current = conn.state
        val session = current.session
        val tab = current.windows.firstOrNull { tabBlocks(it).singleOrNull() == block }
        if (session != null && tab != null) askToClose(CloseTarget.LastPane(session, tab.id, block))
        else askToClosePane(block)
    }

    /**
     * Move a tab one place on in its session. The step is taken from the connection's state as it is
     * now, so a menu that was opened before the order changed cannot send a stale place.
     */
    fun moveTabOnePlace(c: HostConnection, session: String, tab: String, forward: Boolean) {
        tabStep(sessionTabOrder(c.state, session), tab, forward)?.let { c.moveTab(session, it) }
    }

    /** Close a tab: at once when it is known to hold only a shell, else after asking ([closeTabQuestion]). */
    fun closeTab(c: HostConnection, row: TabRow) {
        val target = CloseTarget.Tab(row.session, row.tab)
        if (closeTabQuestion(row.name, row.program, row.panes, row.minimized) == null) close(c, target) else confirm = Confirm(c, target)
    }
    /**
     * A card of the overview was lifted away and is out of sight (#216): its tab closes as by every
     * other close control, unasked when it holds only a shell (after a while for Undo, #228), else
     * after asking ([closeTabQuestion]). When nothing closes, the card comes back.
     */
    fun closeFlownTab(id: String) {
        val session = conn.state.session
        val row = session?.let { tabOf(conn.state, it, id) }
        if (row == null || !conn.connected) return overview.flownAway(id, closed = false)
        val target = CloseTarget.Tab(row.session, row.tab)
        if (closeTabQuestion(row.name, row.program, row.panes, row.minimized) == null) {
            // Nobody is asked, so the close waits a while for Undo before it is sent (#228).
            overview.closeLater(id, row.name) {
                // Asked again when it is sent: meanwhile a program may have started in the tab.
                val now = tabOf(conn.state, row.session, id)
                if (!conn.connected || now == null || closeTabQuestion(now.name, now.program, now.panes, now.minimized) != null) false
                else {
                    close(conn, target)
                    true
                }
            }
        } else {
            confirm = Confirm(conn, target) { closed -> overview.flownAway(id, closed, row.name) }
        }
    }
    /** Open the rename dialog of [target]: every control that renames it starts from the same text ([renameText]). */
    fun rename(c: HostConnection, target: RenameTarget) { renaming = Renaming(c, target, renameText(target, app.saved, c.state)) }

    /**
     * The overview's session strip (#236): the sessions of every remote it shows ([stripRemotes]). A
     * chip of another remote makes that remote the active one with that session, as the sidebar does,
     * and the overview stays open there ([Arrival]). Rename and Close act on the chip's own remote.
     */
    fun stripRemote(remote: StripRemote): Remote? = app.rows.firstOrNull { it.id == remote.id }
    val stripRemotesNow by rememberUpdatedState({
        stripRemotes(chosen = overview.chosen, remotes = app.rows.mapNotNull { r ->
            app.connectionOf(r)?.let { c ->
                val online = c.connected
                StripRemote(r.id, r.label.ifEmpty { "Remote" }, active = c === conn, connected = online,
                    chips = sessionChips(c.state) { row -> wantsAttention(c.activityReadState.activity(row, online)) })
            }
        })
    })
    val showElsewhereNow by rememberUpdatedState(showElsewhere@{ remote: StripRemote, chip: SessionChip ->
        val r = stripRemote(remote)
        val c = r?.let(app::connectionOf)
        if (r != null && c != null && c !== conn && c.connected && overview.isOpen) {
            val arrival = overview.leaveForRemote(chip.id)
            // A card that landed may ask before its tab closes (a program runs in it): that question is
            // this remote's, so the remote stays, and the chip can be tapped again once it is answered.
            if (confirm?.conn === conn) return@showElsewhere
            overviewCarry.next = arrival
            notification?.leave?.invoke()
            app.activate(r.url)
            // A remote that did not become the active one gets nothing handed on.
            if (app.active !== c) overviewCarry.next = null
            else if (c.state.session != chip.id) c.selectSession(chip.id)
        }
    })
    val renameChipNow by rememberUpdatedState({ remote: StripRemote, chip: SessionChip ->
        stripRemote(remote)?.let(app::connectionOf)?.let { rename(it, RenameTarget.OfSession(chip.id)) }
    })
    val closeChipNow by rememberUpdatedState({ remote: StripRemote, chip: SessionChip ->
        stripRemote(remote)?.let(app::connectionOf)?.let { confirm = Confirm(it, CloseTarget.Session(chip.id)) }
    })
    // Remembered, with the actions read through their current state as [paneHandles] are: the stage and the
    // strip see the same object whenever the workspace runs again, and can skip (#335).
    val strip = remember {
        StripSource(
            remotes = { stripRemotesNow() },
            showElsewhere = { remote, chip -> showElsewhereNow(remote, chip) },
            rename = { remote, chip -> renameChipNow(remote, chip) },
            close = { remote, chip -> closeChipNow(remote, chip) },
        )
    }

    val window = shownWindow(state)
    // The sidebar that covers the panes removes their views, and that closes no pane: back from
    // under it they are shown as they were and take no terminal ([PaneCover]). Told while
    // composing, because the views ask as this composition removes them or makes them.
    if (panesHidden) conn.cover.begin() else conn.cover.draws(window?.let(::tabBlocks).orEmpty().toSet())
    // Another remote is drawn from now on: this one's panes were left, not covered, and selecting
    // it again shows them by the usual rules. A screen that covers the workspace (Settings)
    // leaves the remote active, and its cover stays.
    DisposableEffect(conn) { onDispose { if (app.active !== conn) conn.cover.forget() } }
    var alertTarget by remember(conn) { mutableStateOf<PaneAddress?>(null) }
    fun editAlerts(block: String) {
        val session = state.session ?: return
        if (window != null && block in tabBlocks(window)) alertTarget = PaneAddress(session, window.id, block)
    }
    var menuFor by remember(conn, state.session, window?.id) { mutableStateOf<String?>(null) }
    // The last pane move, kept until its notice is done; not keyed by the tab, because a move changes the active tab.
    var moveNotice by remember(conn, state.session) { mutableStateOf<MoveNotice?>(null) }
    // A pane move was sent while the tabs were [before]: from the overview or from the pane menu, it is told alike.
    fun noteMove(block: String, place: MovePlace, before: List<WindowInfo>) {
        val live = conn.state
        moveNotice = moveNoticeAfter(moveNotice, block, place, before) { it.row(live, live.session.orEmpty(), false).ownLabel }
    }
    // The pane menu's Move Pane to New Tab: the move the overview's New Tab card sends, with the same notice and Undo.
    fun moveToNewTab(block: String) {
        val before = conn.state.windows
        conn.sendMove(block, MovePlace.NewTab, rtl = false)
        noteMove(block, MovePlace.NewTab, before)
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Move Pane Left/Right/Up/Down: the swap the overview sends for a drop on a pane's middle, with the same notice and Undo.
    // Nothing is sent, and false comes back, when the pane has no neighbour on that side.
    // A move sent from here that has not shown in the state yet: the layout is still the old one, so no other move goes
    // out until it lands, fails to land within the wait, or the tab, the session or the connection changes.
    var pendingMove by remember(conn, state.session, window?.id) { mutableStateOf<PendingMove?>(null) }
    fun moveBusy(windows: List<WindowInfo>) = moveBusy(pendingMove, SystemClock.uptimeMillis(), windows)
    // The shown tab as the state has it now. The composition's [window] can be a frame behind a layout that just landed,
    // and a move computed from it, next to a busy check that read the new state, would swap the wrong panes.
    fun liveWindow(windows: List<WindowInfo>) = window?.id?.let { id -> windows.firstOrNull { it.id == id } }
    pendingMove?.let { sent ->
        LaunchedEffect(sent) {
            withTimeoutOrNull(LandWaitMillis) {
                snapshotFlow { moveLanded(sent.before, conn.state.windows, sent.block, sent.place) }.first { it }
            }
            if (pendingMove === sent) pendingMove = null
        }
    }
    fun movePaneToward(block: String, direction: PaneDirection): Boolean {
        val before = conn.state.windows
        if (moveBusy(before)) return false
        val shown = liveWindow(before) ?: return false
        val rect = paneToward(shown, block, direction, rtl) ?: return false
        val place = MovePlace.Swap(shown.id, rect.block, rect)
        conn.sendMove(block, place, rtl)
        noteMove(block, place, before)
        pendingMove = PendingMove(block, place, before, SystemClock.uptimeMillis())
        return true
    }
    var pickingPane by remember(conn, state.session, window?.id) { mutableStateOf(false) }
    val visiblePanes = window?.let(::keyboardPaneBlocks).orEmpty()
    val focused = state.focused?.takeIf { it in visiblePanes } ?: visiblePanes.firstOrNull()
    var autoSplitTarget by remember(conn, state.session, window?.id) { mutableStateOf<String?>(null) }
    var autoSplitIme by remember(conn) { mutableStateOf<Boolean?>(null) }
    fun autoSplit(block: String) {
        autoSplitIme = null
        autoSplitTarget = block
    }
    val targetInsets = WindowInsets.imeAnimationTarget.unlessWholeWindow()
    val workspaceFocus = LocalWindowInfo.current
    // A compact sidebar removes the target surface. Wait for its real size after the
    // palette closes. Leaving this tab or losing the target cancels the pending action.
    LaunchedEffect(autoSplitTarget, panesHidden, palette, visiblePanes, connected) {
        val block = autoSplitTarget ?: return@LaunchedEffect
        if (!connected || block !in visiblePanes) {
            autoSplitTarget = null
            return@LaunchedEffect
        }
        if (panesHidden || palette) return@LaunchedEffect
        val size = withTimeoutOrNull(3000) {
            // The palette owns its IME. Its closing animation can briefly give a restored
            // portrait pane a landscape-shaped surface. Read the settled terminal layout.
            snapshotFlow {
                val inset = imeInsets.getBottom(density)
                val ready = autoSplitIme == null || (workspaceFocus.isWindowFocused &&
                    (inset > 0) == autoSplitIme && inset == targetInsets.getBottom(density))
                if (ready) conn.shownPaneSize(block) else null
            }.debounce(200).filterNotNull().first()
        }
        val direction = size?.let { automaticSplit(it.width / density.density, it.height / density.density, gap.value) }
        autoSplitTarget = null
        if (direction != null) conn.op("split", "block" to block, "dir" to direction.wireName)
        else conn.error = if (size == null) "The pane is not visible yet. Try again."
            else "This pane is too small to split automatically."
    }
    var searchFor by remember(conn, state.session, window?.id) { mutableStateOf<String?>(null) }
    var pendingSearch by remember(conn, state.session, window?.id) { mutableStateOf<String?>(null) }
    var restoreSearchKeyboard by remember { mutableStateOf(false) }
    fun openSearch(block: String) {
        restoreSearchKeyboard = keyboard && input.hasFocus()
        if (block == focused) searchFor = block
        else {
            notification?.leave?.invoke()
            conn.op("focus", "block" to block)
            pendingSearch = block
        }
    }
    LaunchedEffect(pendingSearch) {
        val target = pendingSearch ?: return@LaunchedEffect
        val focusedTarget = withTimeoutOrNull(3000) {
            snapshotFlow { conn.state.focused }.first { it == target }
        }
        if (focusedTarget != null) searchFor = target
        pendingSearch = null
    }
    // A focus update while the menu is open must not retarget its commands.
    var railMenuFor by remember(conn, state.session, window?.id) { mutableStateOf<String?>(null) }
    val tabPanes = window?.let(::tabBlocks).orEmpty()
    // A tap opens the pane's menu as the rail's `…` does, for that pane and without focusing it: the menu's
    // commands carry the block. The long press drags the pane across the overview (#217); Move Pane to Tab…, from
    // the menu or the handle's screen reader action, picks its place there without a drag ([startPaneMove]).
    // Remembered, with the actions read through their current state, so that the panes see the same object on
    // every recomposition and can skip.
    val openPaneMenuOf by rememberUpdatedState({ block: String -> railMenuFor = block })
    val movePaneOf by rememberUpdatedState({ block: String, root: Offset? -> overview.startPaneMove(conn.state.windows, conn.connected, block, root) })
    fun pickPlaceFor(block: String) = showing { movePaneOf(block, null) }()
    val paneHandles = remember {
        PaneHandles(onTap = { openPaneMenuOf(it) }, onLongPress = { block, root -> movePaneOf(block, root) }, onMoveToTab = { movePaneOf(it, null) })
    }
    val paneCount = tabPanes.size
    val backdrop = VeloTheme.colors.backdrop
    val radius = if (compactStyle) VeloTheme.radii.paneCompact else VeloTheme.radii.pane
    // Whether the overview shows, which its motion writes only when it comes or goes (#335, #347).
    val overviewVisible = overview.showing

    fun haptic(feel: Feel = Feel.Key) { if (s.haptics) view.feel(feel) }

    composer?.let { target ->
        key(target) {
            DisposableEffect(target) { onDispose { launches.draftClosed() } }
            ComposerDialog(target, notificationPending = launches.pending != null, isCurrent = {
                notification?.canUsePane() != false && app.active === target.conn && app.removed == null &&
                    target.conn.state.showsComposerPane(target.session, target.block)
            }, onClose = { if (composer === target) composer = null })
        }
    }

    if (notification != null && !notification.canUsePane()) {
        SideEffect {
            input.disconnectInput()
            // The pane the paste was typed into is left: its question ends unsent and does not come back.
            if (keyboardPaste != null) {
                keyboardPaste = null
                input.pasteAnswered()
            }
        }
        PaneNotificationNotice(notification)
        return
    }

    alertTarget?.let { target ->
        if (activeRemote != null) PaneNotificationSettings(app, activeRemote, target) { alertTarget = null }
    }

    val terminalPanes = if (connected && !overview.isOpen && !overviewVisible && !overview.pinching) {
        visiblePanes
    } else emptyList()
    val keyboardPanes = if (panesHidden) emptyList() else terminalPanes
    val owner = remember(conn) { Any() }
    val session = state.session
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val paneReports = if (session == null || window == null || !windowFocused || composer != null || settingsOpen) emptyMap() else keyboardPanes.associate { block ->
        PaneAddress(session, window.id, block) to state.blocks[block]
    }
    LifecycleResumeEffect(conn, paneReports) {
        conn.visibleActivityPanes = conn.activityReadState.show(owner, paneReports)
        onPauseOrDispose { conn.visibleActivityPanes = conn.activityReadState.hide(owner) }
    }
    // What is on screen is read: its notifications leave the shade and the icon's badge. Keyed
    // by the panes alone: their reports change with every title the terminal sets.
    LifecycleResumeEffect(activeRemote, paneReports.keys) {
        activeRemote?.let { app.clearSeenNotifications(it, paneReports.keys) }
        onPauseOrDispose { }
    }
    val activeSearch = searchFor?.takeIf { it == focused && it in keyboardPanes }
    if (searchFor != null && activeSearch == null) LaunchedEffect(searchFor) { searchFor = null }
    val requestedMenu = menuFor
    val openPaneMenu = requestedMenu?.takeIf { it in keyboardPanes }
    val railTarget = railMenuFor?.takeIf { it in visiblePanes }
    if (railMenuFor != null && railTarget == null) {
        LaunchedEffect(railMenuFor) { railMenuFor = null }
    }
    if (requestedMenu != null && openPaneMenu == null) {
        LaunchedEffect(requestedMenu) { if (menuFor == requestedMenu) menuFor = null }
    }
    // An overlay of the workspace is open: the palette, a dialog, a menu of a pane (its own or
    // the rail's) or the pane picker. Each is a window of its own over the panes, which takes the
    // keys and the soft keyboard. A pane being moved across the overview holds them back as well.
    val overlayOpen = pendingSearch != null || activeSearch != null || pickingPane || openPaneMenu != null || railTarget != null || palette || alertTarget != null ||
        confirm != null || composer != null || renaming != null || adding || removing != null || keyboardPaste != null ||
        linkQuestion != null || files.download?.shown == true || files.question != null || overview.paneMove != null
    val canAcceptInput = keyboardPanes.isNotEmpty() && !overlayOpen
    SideEffect {
        // Bind input only once the workspace that displays this connection is committed.
        input.connection = conn
        input.target = {
            val live = conn.state
            val tab = live.windows.firstOrNull { it.id == live.window }
            val pane = focused?.takeIf { block ->
                notification?.canUsePane() != false && app.active === conn && live.session == state.session && live.window == state.window &&
                    live.focused == state.focused && tab != null && block in tabBlocks(tab)
            }
            // No key goes to a pane the sidebar covers, nor to one whose connection is down. The
            // status is read when a key comes, so the keys reach the pane again as soon as it is
            // connected, without another tap.
            inputTarget(panesHidden, paneInputPaused(live.status), pane)
        }
        // Panes on screen whose connection is down: a hardware key then goes nowhere
        // (`hardwareKeyRoute`). [shownFirstPane] reads the tab drawn here ([shownWindow]).
        input.panesPaused = {
            val live = conn.state
            panesPaused(panesHidden, paneInputPaused(live.status), shownFirstPane(live))
        }
        input.onStickyConsumed = { armed = emptySet() }
        input.workspaceAcceptsInput = canAcceptInput
        input.refreshEditorTarget()
        input.onPaneMenuShortcut = if (input.workspaceAcceptsInput) ({ pickingPane = true }) else null
        // The palette also has commands for a workspace without panes or a connection, so it needs neither.
        input.onPaletteShortcut = if (!overlayOpen) ({ openPalette() }) else null
        // Ctrl+Alt+Shift+arrow swaps the focused pane with its neighbour. Without one, or with the connection down,
        // no action is offered, and the key follows its usual route. While the previous move is not on screen yet the
        // chord is taken and does nothing ([moveBusy]).
        input.onMovePaneShortcut = { direction ->
            val live = conn.state.windows
            if (movePaneChord(canAcceptInput, overlayOpen, conn.connected, liveWindow(live), focused, direction, rtl, moveBusy(live))) {
                { focused?.let { movePaneToward(it, direction) }; Unit }
            } else null
        }
        input.onPasteQuestion = { keyboardPaste = it }
        // A key a tap gesture sends is felt as a key bar key is (#309).
        input.onTapKey = { haptic() }
        // Tap gestures follow the setting at each tap. A screen reader that explores by touch
        // takes the taps: no gesture makes a key then.
        input.taps.keys = {
            val exploring = input.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                ?.isTouchExplorationEnabled == true
            if (s.tapGestures && !exploring) s.doubleTapKey to s.tripleTapKey else null
        }
    }
    if (pickingPane) {
        KeyboardPanePicker(keyboardPanes, tabPanes, focused, conn.state.blocks, onDismiss = { pickingPane = false }) { block ->
            pickingPane = false
            menuFor = block
        }
    }

    // BACK dismisses what is open; with nothing open it backgrounds the app (the default
    // would finish the activity and drop the connection).
    val activity = LocalContext.current as? android.app.Activity
    val imeRestore = remember(activity) { activity?.window?.let(::TerminalImeRestoreGuard) }
    DisposableEffect(imeRestore) { onDispose { imeRestore?.release() } }
    // Keep this across target changes: losing the searched pane removes the find field
    // just like Close, and the newly shown terminal needs its own input connection.
    var searchHadFocus by remember { mutableStateOf(false) }
    fun closeSearch() { searchFor = null }
    LaunchedEffect(activeSearch, canAcceptInput) {
        if (activeSearch != null) searchHadFocus = true
        else if (searchHadFocus && canAcceptInput) {
            if (restoreSearchKeyboard) input.showTerminalKeyboard()
            else {
                input.requestFocus()
                input.hideTerminalKeyboard()
            }
            searchHadFocus = false
        }
    }
    androidx.activity.compose.BackHandler {
        when {
            activeSearch != null -> closeSearch()
            palette -> palette = false
            adding -> adding = false
            removing != null -> removing = null
            renaming != null -> renaming = null
            confirm != null -> confirm = null
            // A sidebar that covers the panes goes before what is open behind it.
            panesHidden -> sidebar = false
            overview.isOpen -> overview.close()
            sidebar -> sidebar = false
            keyboard -> input.hideTerminalKeyboard()
            else -> activity?.moveTaskToBack(true)
        }
    }

    val canType = keyboardPanes.isNotEmpty() && focused != null
    val canAutoOpen = canAcceptInput && !sidebar && focused != null
    var initialKeyboardHandled by remember(conn) { mutableStateOf(false) }
    LaunchedEffect(canAutoOpen) {
        if (canAutoOpen && !initialKeyboardHandled) {
            initialKeyboardHandled = true
            if (s.keepKeyboard) input.showTerminalKeyboard()
        }
    }
    var paletteImeRequest by remember(conn, state.session, window?.id, focused) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(paletteImeRequest, windowFocused, canAcceptInput) {
        val show = paletteImeRequest ?: return@LaunchedEffect
        if (!windowFocused || !canAcceptInput) return@LaunchedEffect
        // The palette dialog releases its editor when its window is removed.
        withFrameNanos { }
        if (show) input.showTerminalKeyboard() else input.hideTerminalKeyboard()
        paletteImeRequest = null
    }
    fun toggleKeyboard() {
        if (keyboard) input.hideTerminalKeyboard() else if (canType) input.showTerminalKeyboard()
    }

    // The workspace's touch count and whether a finger is down anywhere: every pointer of the workspace passes
    // the root's view, so the count rises with each finger that lands and pressedNow follows them all (#384).
    // Nothing in composition reads pressedNow, so keeping it costs no recomposition.
    var touchEpoch by remember { mutableIntStateOf(0) }
    var pressedNow by remember { mutableStateOf(false) }
    // Edge to edge: keep panes, key bar and rail clear of the soft keyboard and system bars.
    Row(
        Modifier.testTag("screen-workspace").fillMaxSize().background(backdrop.surface).windowInsetsPadding(WindowInsets.screenSafeDrawing)
            .padding(horizontal = outerX, vertical = outerY)
            // The Initial pass sees every event before a child consumes it: any finger that lands raises the
            // count, whatever else is on the glass, and pressedNow says whether any of them is down now.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) touchEpoch++
                        pressedNow = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        // A new tab needs a session to live in; without one, make the session.
        // From the overview left open on an empty session (#247), the overview goes: the new tab is what to see.
        val newTab = {
            if (overview.isOpen) overview.close()
            conn.op(if (state.session == null) "newSession" else "newWindow")
        }
        // Where the sidebar covers the panes, the two take turns: the sidebar fills the width.
        if (sidebar) {
            // Selecting a row, or making a session on it, makes it the active row first.
            fun on(remote: Remote, f: (HostConnection) -> Unit) {
                if (remote != activeRemote) notification?.leave?.invoke()
                app.activate(remote.url)
                app.connectionOf(remote)?.let(f)
            }
            val hosts = app.rows.mapNotNull { remote ->
                app.connectionOf(remote)?.let {
                    SidebarHost(remote, it, active = remote == activeRemote, removed = remote == app.removed, needsAnswer = remote.url in needsAnswer)
                }
            }
            val device = deviceRow(Tailnet.state, Tailnet.paused)
            Sidebar(device, hosts, sidebarSlot(fit.place), SidebarActions(
                toggleSidebar = { sidebar = false },
                openSettings = onSettings,
                openPalette = ::openPalette,
                openDevice = onTailscale,
                selectRemote = { remote -> showing { on(remote) {} }() },
                newSession = { remote -> showing { on(remote) { it.op("newSession") } }() },
                renameRemote = { rename(conn, RenameTarget.OfRemote(it.id)) },
                removeRemote = { removing = it },
                sshDetails = { sshDetailsFor = it },
                addRemote = { adding = true },
                found = notYetRows(Tailnet.discovered, app.saved),
                // One tap saves it on the phone and connects; its row appears above.
                addFound = { addRemote(it.name, it.url) },
                selectSession = { remote, session -> showing { on(remote) { if (it.state.session != session) notification?.leave?.invoke(); it.selectSession(session) } }() },
                // The core shows the new tab (it selects the tab's session when another was shown), so this is a row action like New Session.
                newTab = { remote, session -> showing { on(remote) { it.op("newWindow", "session" to session) } }() },
                renameSession = { remote, group -> app.connectionOf(remote)?.let { rename(it, RenameTarget.OfSession(group.id)) } },
                closeSession = { remote, group -> app.connectionOf(remote)?.let { confirm = Confirm(it, CloseTarget.Session(group.id)) } },
                // The row's remote becomes the active one; while it is down its tab cannot be selected,
                // so nothing is asked and the sidebar that covers the panes stays: nothing changed under it.
                selectTab = { remote, row ->
                    on(remote) {
                        if (it.connected) showing {
                            if (it.state.session != row.session || it.state.window != row.tab) notification?.leave?.invoke()
                            it.op("selectWindow", "session" to row.session, "window" to row.tab)
                        }()
                    }
                },
                renameTab = { remote, row -> app.connectionOf(remote)?.let { rename(it, RenameTarget.OfTab(row.session, row.tab)) } },
                moveTab = { remote, row, forward -> app.connectionOf(remote)?.let { moveTabOnePlace(it, row.session, row.tab, forward) } },
                closeTab = { remote, row -> app.connectionOf(remote)?.let { closeTab(it, row) } },
                toggleDock = ::toggleDock,
            ), settingsBadge = updateDot(), docked = dockSwitch(dockable, s.sidebarDocked),
                // Without the panes there is no pane area to hold the notices: the sidebar that covers them does, under
                // its Filter field, so that they lie over neither the field nor the rail (#165). With the keyboard
                // up too: they stay where they are, so the keyboard neither covers the Filter field with them nor makes
                // a screen reader read them again (#195). A sidebar too short for its head beside them folds the head into
                // its list (#209), so they stay off the Filter field and the toolbar's buttons.
                notices = if (panesHidden && anyNotice) {
                    {
                        UploadToast(conn, heard)
                        ErrorNotice(conn, text = ::noticeText, heard = heard)
                    }
                } else null)
            if (!covers) Spacer(Modifier.width(gap))
        }
        if (!panesHidden) Box(paneColumnSlot()) {
            // The pane area's box in the column, which places the notices ([PaneNotices]). Read only to place them.
            val paneBox = remember { mutableStateOf<Rect?>(null) }
            // And the key bar's, which two or more notices over a short pane area stay above (#169).
            val keyBarBox = remember { mutableStateOf<Rect?>(null) }
            val keyBarShown = keyboard || s.keyBar.isNotEmpty() && focused != null
            // The minimized floating windows, at the chosen corner. Not in the overview, which has no panes to bring one back to.
            // Their capsules are named by their titles: read only where there are some.
            val capsuleBlocks = if (window?.minimizedLayers.isNullOrEmpty()) state.blocks else conn.state.blocks
            val minimized = shownCapsules(window, capsuleBlocks, overviewVisible, overview.paneMove != null)
            val corner = shownCorner(s.capsuleCorner)
            Column(Modifier.fillMaxSize()) {
                if (activeSearch != null) key(conn, activeSearch) {
                    PaneSearchBar(conn, activeSearch, paneLabel(tabPanes, activeSearch) ?: "Pane", ::closeSearch)
                }
                val paneMod = if (fold.tabletop && fold.hingeTopPx > 0)
                    Modifier.fillMaxWidth().height(with(density) { fold.hingeTopPx.toDp() } - outerY * 2)
                else Modifier.weight(1f).fillMaxWidth()
                Box(paneMod.onPlaced { paneBox.value = it.boundsInParent() }) {
                    when {
                        // Every pane minimized: nothing to say, the capsules bring one back.
                        blankStage(window) -> Unit
                        window == null || !drawsPanes(window) -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (removedActive) "${activeRemote?.label ?: "This remote"} was removed from this phone" else emptyText(state, activeRemote?.label.orEmpty()),
                                color = backdrop.secondary, style = VeloTheme.type.body)
                            // Why the remote is down, after the notice that said it has gone. A connected
                            // remote, and the removed one (its connection is destroyed), keep none.
                            notice?.let {
                                Text(it.text, Modifier.padding(horizontal = sizes.overlayPadding), color = backdrop.secondary,
                                    style = VeloTheme.type.caption, textAlign = TextAlign.Center)
                            }
                            // Nothing to show: the way out is on the screen, not in a menu.
                            val actions = emptyActions(state, removedActive, sidebar = sidebar, copyCommand = notice?.fix != null)
                            if (actions.isNotEmpty()) {
                                Spacer(Modifier.height(sizes.rowGap))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(sizes.gap, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
                                    for ((i, action) in actions.withIndex()) VeloButton(action.label, {
                                        when (action) {
                                            EmptyAction.NewSession, EmptyAction.NewTab -> newTab()
                                            // What the rail's popover does.
                                            EmptyAction.Reconnect -> app.reconnect(conn)
                                            EmptyAction.CopyCommand -> copyFix()
                                            EmptyAction.ShowRemotes -> sidebar = true
                                        }
                                    }, kind = if (i == 0) ButtonKind.Primary else ButtonKind.Secondary)
                                }
                            }
                        }
                        // A pane view belongs to one core: another host's panes are new views.
                        else -> key(conn) {
                            val linkContext = LocalContext.current
                            // One handler for the panes' whole life: a new one would redraw every pane.
                            val linkTaps = remember(linkContext, files) {
                                LinkTaps { c, block, tapped, notAFile ->
                                    fun remote() = app.activeRemote?.label?.ifEmpty { null } ?: "the remote"
                                    // The remote's loopback: forwarded to the phone first, then opened there. A new forward
                                    // lets every app on this phone reach that port of the remote, so it asks first (#28).
                                    fun forward(link: LoopbackLink, hidden: Boolean) {
                                        forwardTap(link, hidden, c.state.forwards, remote()) {
                                            c.openForward(block, link) { answer ->
                                                val failure = forwardFailure(answer, link)
                                                if (failure != null) c.error = failure else openTerminalLink(linkContext, c, link.onPhone(answer.port))
                                            }
                                        }?.let { linkQuestion = it }
                                    }
                                    fun open(tap: LinkTap) {
                                        when (tap) {
                                            is LinkTap.Open -> openTerminalLink(linkContext, c, tap.uri)
                                            is LinkTap.Ask -> linkQuestion = LinkQuestion(linkQuestionTitle(tap), linkQuestionText(tap)) {
                                                openTerminalLink(linkContext, c, tap.uri)
                                            }
                                            is LinkTap.File -> files.open(c, block, tap.target, notAFile)
                                            is LinkTap.Forward -> forward(tap.link, hidden = false)
                                            // The terminal did not show where it goes: the question does, and Open goes on as for a shown link.
                                            is LinkTap.Hidden -> when (val then = tap.then) {
                                                is LinkTap.Open -> linkQuestion = LinkQuestion(hiddenLinkTitle(then), hiddenLinkText(then)) { open(then) }
                                                is LinkTap.File -> {
                                                    // A link to another machine's file is no file here, as for a shown one.
                                                    val path = remoteFilePath(then.target, remoteNames(c.url)) ?: return notAFile()
                                                    linkQuestion = LinkQuestion(HIDDEN_FILE_TITLE, hiddenFileText(path, remote())) { open(then) }
                                                }
                                                is LinkTap.Forward -> forward(then.link, hidden = true)
                                                else -> open(then)
                                            }
                                        }
                                    }
                                    open(tapped)
                                }
                            }
                            CompositionLocalProvider(LocalLinkTaps provides linkTaps) {
                                TabStage(conn, state, window, overview, focused, gap, radius, input, controls = !covered, haptics = s.haptics,
                                    grid = s.overviewLayout == OverviewLayout.Grid, fontSize = s.fontSize,
                                    handles = paneHandles.takeIf { showsMoveHandle(state.windows) && conn.connected }, strip = strip,
                                    // A card flown away replaces the move's notice: its own Undo (#228) shows in the same place.
                                    onDismiss = { id ->
                                        moveNotice = null
                                        closeFlownTab(id)
                                    },
                                    onMoved = ::noteMove,
                                    onFocus = { block ->
                                        // Without the connection the focus stays: the pane takes no keys then ([paneInputPaused]).
                                        if (block != state.focused && conn.connected) {
                                            notification?.leave?.invoke()
                                            conn.op("focus", "block" to block)
                                        }
                                    })
                            }
                        }
                    }
                    // Another session chosen in the overview (#229): the server drops the shown session's view
                    // before the new one's comes, and the stage goes for that while. The strip stays in sight,
                    // and stays for a session without tabs, to go on to another one. Not in a pane move's overview, which
                    // a switch of session ends ([OverviewState.forMove]).
                    // Read as [overviewVisible], which changes when the overview comes or goes, not with each frame of its motion (#335).
                    if ((window == null || !drawsPanes(window)) && overviewVisible && !overview.forMove) {
                        SessionStrip(strip, overview, Modifier.align(Alignment.TopCenter))
                        // The overview left open on a session without tabs (#247): the tab it closed is said to be closed here.
                        if (state.windows.isEmpty()) {
                            LaunchedEffect(overview, state.session) { overview.tabsGone() }
                            RowNote(overview, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                    // The panes stay to be read; the cover says that nothing typed reaches them.
                    if (covered && window != null && drawsPanes(window)) InputPausedCover(mark, state.status, radius)
                    // The row of capsules is a child of this box: a drag drops it at the nearest of its four places in this box.
                    // At a top corner the row starts below the panes' move handles and the input-paused capsule (top centre).
                    val topClearance = maxOf(PaneHandleStrip, sizes.overlayMargin + sizes.capsule)
                    // One row per tab: another tab shown during a drag ends it, and the row goes back with nothing saved.
                    if (window != null && minimized.isNotEmpty()) key(window.id) {
                        MinimizedPanes(minimized, conn.connected,
                            onShow = { c -> state.session?.let { conn.showMinimized(it, window.id, c.block) } },
                            corner = corner, onMoveTo = s::saveCapsuleCorner, haptics = s.haptics,
                            insets = CapsuleInsets(side = sizes.overlayPadding, top = topClearance + sizes.gapCompact),
                            modifier = Modifier.align(corner.alignment()))
                    }
                    if (window != null) openPaneMenu?.let { b ->
                        PaneMenu(window, b, onDismiss = { menuFor = null }, conn = conn,
                            onAutoSplit = ::autoSplit, onFind = ::openSearch, onNotifications = ::editAlerts, onCompose = ::openComposer,
                            onClose = ::askToCloseFromPaneMenu, showing = ::showing, keyboard = true, onMoveToTab = ::pickPlaceFor, onMoveToNewTab = ::moveToNewTab,
                            beforePaste = input::finishCompositionBeforePaste)
                    }
                }
                if (fold.tabletop) Spacer(Modifier.weight(1f))
                // Which machine the keys go to, then the tab (pane-caption), a gap under the panes.
                val hostLabel = activeRemote?.label?.ifEmpty { null } ?: hostName(state)
                ShownCaption(conn, connected, host = hostLabel, modifier = captionSlot(gap), compact = compactStyle, notification = notification)
                if (keyBarShown) {
                    KeyBar(s.keyBar, armed, keyBarSlot(gap).onPlaced { keyBarBox.value = it.boundsInParent() },
                        enabled = !paused && activeSearch == null && pendingSearch == null) { k, repeated ->
                        // A modifier is felt turning on or off, any other key as a key (#309).
                        if (!repeated) haptic(barKeyFeel(k, armed))
                        if (k in StickyKeys) {
                            val next = toggleSticky(armed, k)
                            // Ctrl or Alt is for the key that follows it. What the keyboard composed
                            // before goes first and without it, and the next key arrives alone ([armedLatinKey]).
                            if (takesComposedKey(stickyMeta(next)) && !takesComposedKey(input.stickyMeta)) input.finishBarComposition()
                            armed = next
                            input.stickyMeta = stickyMeta(armed)
                        } else if (!input.finishBarComposition()) {
                            // A composition that was a paste waits for its question; the key waits with it, unsent.
                            val code = KEY_CODES[k]
                            if (code != null) input.sendKey(code, 0, barKeyChar(code)) else input.sendText(k)
                        }
                    }
                }
            }
            // Over the whole column, so that a pane area too short for them (landscape with the keyboard up) does not
            // cut them: they go over its caption then ([noticeTop]). The column is inside the safe-drawing insets, so
            // they stay clear of the soft keyboard. Two or more go side by side there, above the key bar. Last, so
            // they cover the pause layer.
            PaneNotices({ paneBox.value }, { if (keyBarShown) keyBarBox.value else null }, sizes.overlayPadding, sizes.gap,
                lift = noticeLift(minimized.isNotEmpty(), corner, MinimizedCapsuleHeight, sizes.gap), modifier = Modifier.matchParentSize()) {
                UndoNotice(conn, moveNotice, onDone = { moveNotice = null })
                // These two wait behind newer notices where the row has no room (#192); the Undo never does.
                UploadToast(conn, heard, waits = true)
                ErrorNotice(conn, text = ::noticeText, heard = heard, waits = true)
            }
        }
        Spacer(Modifier.width(gap))
        RightRail(mark, remoteName, notice?.text, keyboard,
            onReconnect = { app.reconnect(conn) },
            fix = notice?.fix, onCopyCommand = ::copyFix,
            // The rail's `…` is the focused pane's menu: without a pane there is nothing for it to act on.
            menuEnabled = focused != null,
            // The rail's window wheel opens the overview as its drum: that is no overview the button shows.
            overviewOpen = overviewVisible && !panesHidden && !overview.wheel, sidebarOpen = sidebar,
            keepScreenOn = s.keepScreenOn, onKeepScreenOn = { s.saveKeepScreenOn(!s.keepScreenOn); haptic() },
            onMenu = { railMenuFor = focused },
            keyboardAvailable = activeSearch == null && (canType || keyboard),
            onKeyboard = { toggleKeyboard(); haptic() },
            onSidebar = { sidebar = !sidebar; haptic() },
            page = railPage(state.windows.map { it.id }, window?.id, front = if (overview.wheel) overview.wheelFront else -1),
            // Behind the sidebar that covers the panes the overview is not seen: the button shows it.
            onOverview = showing {
                if (panesHidden) overview.open() else overview.toggle()
                haptic()
            },
            menu = {
                if (window != null && focused != null) {
                    PaneMenu(window, railTarget ?: focused, onDismiss = { railMenuFor = null }, conn = conn,
                        onAutoSplit = ::autoSplit, onFind = ::openSearch, onNotifications = ::editAlerts, onCompose = ::openComposer,
                        onClose = ::askToCloseFromPaneMenu, showing = ::showing, expanded = railTarget != null, onMoveToTab = ::pickPlaceFor,
                        onMoveToNewTab = ::moveToNewTab, beforePaste = input::finishCompositionBeforePaste)
                }
            },
            strip = {
                // The free strip (#304): a plain drag scrolls the focused pane as a finger on it would; a long press turns
                // a wheel of the session's tabs over the overview's drum, and the tab in front when the finger lifts is selected.
                // Each tab's tile has the brand its sidebar row's has: of the agent its report names, else of its program.
                // The names are the titles: derived from the state with them, apart from the workspace, and the strip
                // is composed again only when a name or a tile changes, not for each title. The workspace's [state]
                // is without the titles: the wheel reads [HostConnection.state].
                val wheelWindows by remember(conn, connected) {
                    derivedStateOf { wheelWindows(conn.state, conn.activityReadState, connected) }
                }
                RailScrubber(wheelWindows, shown = window?.id,
                    scrolls = focused != null && !panesHidden && !overviewVisible,
                    turns = connected && window != null && !panesHidden && !overviewVisible && overview.paneMove == null && !overview.pinching,
                    // The pane the strip's scroll is for now; a coast keeps the one it began for, and ends when it has gone.
                    pane = { focused },
                    // Every new touch in the workspace raises this; a coast ends when it has.
                    touchEpoch = { touchEpoch },
                    // Whether a finger is down anywhere in the workspace: the strip's coast starts only while
                    // none is — the strip cannot see the panes' own fingers, the root can.
                    pressed = { pressedNow },
                    haptics = s.haptics,
                    onScroll = { block, dy ->
                        // A pane that has gone — closed, its tab switched away, the panes hidden or under the
                        // overview — takes no scroll, and the coast feeding it ends.
                        if (block == null || panesHidden || overviewVisible || block !in visiblePanes) false else {
                            // At the pane's middle: a program that takes the mouse gets the wheel there.
                            val size = conn.shownPaneSize(block)
                            conn.pan(block, 0f, dy, (size?.width ?: 0) / 2f, (size?.height ?: 0) / 2f)
                            true
                        }
                    },
                    onOpen = { overview.openWheel() },
                    onTurn = overview::turnWheel,
                    onPick = { id -> overview.pickWheel(id, select = id != state.window) },
                    onSelect = { id ->
                        state.session?.let { session ->
                            notification?.leave?.invoke()
                            conn.op("selectWindow", "session" to session, "window" to id)
                        }
                    },
                    modifier = Modifier.weight(1f))
            })
    }

    if (palette) {
        // The commands name panes and tabs by their titles. The workspace reads them while the palette is open.
        val live = conn.state
        val pane = focused
        val floating = pane != null && window?.floating?.any { it.block == pane } == true
        val zoomed = pane != null && window?.zoomed == pane
        val tab = shownTab(state)
        val session = state.session
        val units = PaletteUnits(
            saved = app.saved, removable = activeRemote != null && !removedActive, connected = connected,
            session = state.session != null, tab = tab != null, pane = pane != null, paneCount = paneCount, floating = floating, zoomed = zoomed,
            hasTiledPane = window?.layout != null, canReconnect = mark.canReconnect, capsuleCorner = shownCorner(s.capsuleCorner),
            tabForward = tab?.let { tabStep(sessionTabOrder(state, it.session), it.tab, true) } != null,
            tabBackward = tab?.let { tabStep(sessionTabOrder(state, it.session), it.tab, false) } != null,
            canStash = pane != null && window != null && canStash(window, pane),
            floatingToggle = window?.let(::floatingToggle),
            moves = if (pane != null && window != null) {
                PaneDirection.entries.filter { paneToward(window, pane, it, rtl) != null }.toSet()
            } else emptySet(),
        )
        fun textSize(scale: (Float) -> Float) { pane?.let { conn.textSize(it, scale) } }
        fun run(action: PaletteAction) {
            when (action) {
                is PaletteAction.GoToRemote -> {
                    if (action.remote != activeRemote) notification?.leave?.invoke()
                    app.activate(action.remote.url)
                }
                PaletteAction.AddRemote -> adding = true
                PaletteAction.Reconnect -> app.reconnect(conn)
                PaletteAction.RemoveRemote -> removing = activeRemote
                PaletteAction.NewSession -> conn.op("newSession")
                PaletteAction.NewTab -> conn.op("newWindow")
                PaletteAction.RenameSession -> session?.let { rename(conn, RenameTarget.OfSession(it)) }
                PaletteAction.RenameTab -> tab?.let { rename(conn, RenameTarget.OfTab(it.session, it.tab)) }
                PaletteAction.MoveTabForward -> tab?.let { moveTabOnePlace(conn, it.session, it.tab, true) }
                PaletteAction.MoveTabBackward -> tab?.let { moveTabOnePlace(conn, it.session, it.tab, false) }
                PaletteAction.CloseTab -> tab?.let { closeTab(conn, it) }
                PaletteAction.CloseSession -> session?.let { confirm = Confirm(conn, CloseTarget.Session(it)) }
                is PaneSplit -> pane?.let { target ->
                    runPaneLayoutAction(action, target, conn::op, ::askToClosePane) {
                        autoSplitIme = terminalImeOccupiedSpace
                        autoSplitTarget = it
                    }
                }
                PaletteAction.Zoom -> conn.op(if (zoomed) "unzoom" else "zoom", "block" to pane)
                PaletteAction.Float -> pane?.let {
                    val paneAction = when {
                        !floating -> PaneAction.Float
                        units.hasTiledPane -> PaneAction.Dock
                        else -> PaneAction.RestoreInNewTab
                    }
                    runPaneLayoutAction(paneAction, it, conn::op, ::askToClosePane, ::autoSplit)
                }
                PaletteAction.Minimize -> pane?.let {
                    runPaneLayoutAction(PaneAction.Minimize, it, conn::op, ::askToClosePane, ::autoSplit)
                }
                PaletteAction.MinimizeAll -> window?.let { w ->
                    floatingToggle(w)?.let { minimize -> conn.op("minimizeFloating", "window" to w.id, "minimize" to minimize) }
                }
                is PaletteAction.MoveCapsules -> s.saveCapsuleCorner(action.corner)
                is PaletteAction.MovePane -> pane?.let { movePaneToward(it, action.direction) }
                PaletteAction.ClosePane -> pane?.let(::askToClosePane)
                PaletteAction.TextLarger -> textSize { it * HostConnection.TEXT_SIZE_STEP }
                PaletteAction.TextSmaller -> textSize { it / HostConnection.TEXT_SIZE_STEP }
                PaletteAction.TextReset -> textSize { 1f }
                PaletteAction.Settings -> {
                    // The palette's window has the focus here, so the input method ignores the hide
                    // the workspace asks for as it goes. Without the view's focus the terminal input
                    // is no editor the keyboard comes back to when the main window has the focus
                    // again, on a screen that draws no pane.
                    input.clearFocus()
                    onSettings()
                }
            }
        }
        val commands = paletteItems(units).map { item -> PaletteCommand(item.label, item.enabled, item.note) { run(item.action) } } + listOf(
            PaletteCommand(ToggleSidebarCommand) { sidebar = !sidebar },
        ) + listOfNotNull(dockCommand(dockable, s.sidebarDocked)?.let { title ->
            PaletteCommand(title, run = ::toggleDock)
        }) + listOf(
            PaletteCommand("Show Tab Overview", state.windows.isNotEmpty()) { overview.open() },
        ) + listOfNotNull(if (focused != null && focused in terminalPanes) {
            PaletteCommand(if (terminalKeyboardBeforePalette) "Hide Keyboard" else "Show Keyboard") {
                if (terminalKeyboardBeforePalette) imeRestore?.suppressRestore()
                paletteImeRequest = !terminalKeyboardBeforePalette
            }
        } else null) + window?.let { focusPaneCommands(it, live, focused) }.orEmpty().map { c ->
            // What a tap on that pane does: it leaves the pane a notification opened, then moves the focus.
            PaletteCommand(c.label, connected) { notification?.leave?.invoke(); conn.op("focus", "block" to c.block) }
        } + window?.let { fullScreenPaneCommands(it, live) }.orEmpty().map { c ->
            // What the minimap's picker does with that pane (#157): it leaves the pane a notification opened, too.
            PaletteCommand(c.label, connected) { notification?.leave?.invoke(); conn.op("zoom", "block" to c.block) }
        } + state.sessions.filter { it.id != state.session }.map { s ->
            PaletteCommand("Switch to Session: ${s.label.ifEmpty { "Session" }}") { notification?.leave?.invoke(); conn.selectSession(s.id) }
        } + state.windows.filter { it.id != state.window }.map { w ->
            PaletteCommand("Go to Tab: ${tabTitle(live, w)}", connected) { notification?.leave?.invoke(); conn.op("selectWindow", "window" to w.id) }
        }
        CommandPalette(
            // A sidebar that covers the panes hides what commands change: they close it.
            if (covers) closingSidebarAfter(commands) { sidebar = false } else commands,
            onDismiss = { palette = false },
        )
    }

    confirm?.let { c ->
        // The question is what closing would end now, not at the tap; an unavailable target ends the dialog.
        val question = closeQuestion(c.conn.state, c.target)
        if (question == null) LaunchedEffect(c) { if (confirm === c) confirm = null }
        else ConfirmDialog(question, when (c.target) {
            is CloseTarget.Pane -> "Close Pane"
            is CloseTarget.LastPane, is CloseTarget.Tab -> "Close Tab"
            is CloseTarget.Session -> "Close Session"
        }, onConfirm = {
            c.closed()
            close(c.conn, c.target)
        }, onDismiss = { confirm = null })
    }
    files.download?.takeIf { it.shown }?.let { d ->
        val got = Formatter.formatShortFileSize(fileContext, d.received)
        val of = d.size?.let { " of " + Formatter.formatShortFileSize(fileContext, it) }.orEmpty()
        // Hidden, the download goes on and the file opens when it is here.
        VeloDialog("Downloading ${d.name}", files::hide, confirmText = "Stop", onConfirm = files::stop, cancelText = "Hide",
            destructive = true, text = "$got$of")
    }
    files.question?.let { q ->
        VeloDialog("Install ${q.file.name}?", { files.question = null }, confirmText = "Open", focusCancel = true,
            text = "A program in the terminal printed this path. Opening an app package starts installing it.",
            onConfirm = { files.answer(q) })
    }
    linkQuestion?.let { q ->
        VeloDialog(q.title, { linkQuestion = null }, confirmText = q.confirm, focusCancel = true,
            text = q.text, onConfirm = {
                linkQuestion = null
                q.open()
            })
    }
    keyboardPaste?.let { asked ->
        fun end(): Boolean {
            if (keyboardPaste !== asked) return false
            keyboardPaste = null
            input.pasteAnswered()
            return true
        }
        // Asked about the pane it was typed into: once that pane takes no keys, the question ends unsent.
        if (asked.conn !== conn || asked.block !in keyboardPanes) LaunchedEffect(asked) { end() }
        else VeloDialog(asked.title, { end() }, confirmText = "Paste", focusCancel = true, text = PasteRunsLinesText, onConfirm = {
            // Read again at the tap: the pane may have gone from the shown tab since the dialog was drawn.
            val live = asked.conn.state
            val shown = !paneInputPaused(live.status) && shownWindow(live)?.let { asked.block in keyboardPaneBlocks(it) } == true
            // A pane a notification opened takes keys only while that notification allows it, as typed keys do.
            if (end() && shown && app.active === asked.conn && notification?.canUsePane() != false) {
                asked.conn.paste(asked.block, asked.text, confirmed = true)
            }
        })
    }
    renaming?.let { r ->
        when (val t = r.target) {
            // A remote's name is the phone's own: a blank one changes nothing, so it cannot be sent.
            is RenameTarget.OfRemote -> RenameDialog("Rename Remote", r.text, allowEmpty = false,
                onRename = { app.renameRemote(t.id, it) }, onDismiss = { renaming = null })
            is RenameTarget.OfSession -> RenameDialog("Rename Session", r.text, allowEmpty = true,
                onRename = { r.conn.op("renameSession", "session" to t.session, "label" to it) }, onDismiss = { renaming = null })
            is RenameTarget.OfTab -> RenameDialog("Rename Tab", r.text, allowEmpty = true,
                onRename = { r.conn.op("renameWindow", "session" to t.session, "window" to t.tab, "label" to it) }, onDismiss = { renaming = null })
        }
    }
    // Both dialogs act on the sidebar's rows: a sidebar that covers the panes stays open behind them.
    if (adding) AddRemoteDialog(add = ::addRemote, onDismiss = { adding = false })
    removing?.let { remote ->
        // Removing the remote drawn here empties the panes: that follows the rule of showing().
        val remove = if (remote == activeRemote) showing { app.removeRemote(remote.id) } else ({ app.removeRemote(remote.id) })
        RemoveRemoteDialog(remote, remove = remove, onDismiss = { removing = null })
    }
    // Over the sidebar that opened it; a remote removed meanwhile takes its panel with it.
    sshDetailsFor?.let { asked ->
        val remote = app.saved.firstOrNull { it.id == asked.id }
        if (remote != null) SshDetailsPanel(app, remote, onDismiss = { sshDetailsFor = null })
        else LaunchedEffect(asked) { sshDetailsFor = null }
    }
    ErrorNoticeTimer(conn)
}

/**
 * A clipboard file's toast, beside the error notice wherever that is drawn. No timer: it stays until the send ends (#109).
 * A toast that only moved there, which [heard] knows, is no live region: it was read where it first showed (#195).
 * Where it [waits] behind newer notices in the pane column ([NoticeWait], #192), one that comes back is not read again.
 */
@Composable
internal fun UploadToast(conn: HostConnection, heard: HeardNotices? = null, waits: Boolean = false, modifier: Modifier = Modifier) {
    conn.uploading?.let {
        val key = UploadToastKey(it)
        val wait = if (waits) rememberNoticeWait(heard, key) else null
        ToastSurface(it, modifier.waitsIn(wait), icon = Icons.Default.Upload, live = liveNotice(heard, key, wait))
    }
}

/** One showing of the upload toast ([HeardNotices]): its words. */
private data class UploadToastKey(val said: String)

// Where each part of the workspace goes in its row (WsBar*, WsClosed*, WsOpen* boards): the open sidebar, the pane
// column, and under the panes the caption and the key bar, [gap] apart. The fidelity scenes place their parts with these too.

/** The open sidebar's place in the workspace's row: the whole width beside the rail where it covers the panes, else its own width. */
/**
 * The caption of the shown tab ([PaneCaption]), composed apart from the [Workspace]: it reads the state with the
 * titles, so that a new title composes the caption again and not the workspace.
 */
@Composable
private fun ShownCaption(
    conn: HostConnection, connected: Boolean, host: String, modifier: Modifier, compact: Boolean, notification: PaneNotificationGate?,
) {
    // Derived: a title that leaves the tab's row as it was (a tab named by its label) composes nothing again.
    val row by remember(conn) {
        derivedStateOf { conn.state.let { shownWindow(it)?.row(it, it.session.orEmpty(), true) } }
    }
    // The zoomed tab's minimap (#141). One `zoom` switches: the server focuses the pane, which ends the old zoom,
    // and zooms it in the same call, so the split tab is never shown between them. It holds no pane's name, so a
    // hidden pane's new title composes nothing; the open picker reads the names.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val zoom by remember(conn, connected, rtl) {
        derivedStateOf {
            conn.state.let { s ->
                shownWindow(s)?.let { w ->
                    zoomMap(w, rtl) { block -> conn.activityReadState.activity(w.paneRow(s, s.session.orEmpty(), block), connected).badge }
                }
            }
        }
    }
    PaneCaption(row, connected, conn.activityReadState, host = host, modifier = modifier, compact = compact,
        zoom = zoom, paneName = { block -> conn.state.let { s -> shownWindow(s)?.let { zoomPaneName(s, it, block) }.orEmpty() } },
        // A zoom moves the focus, so it leaves the pane a notification opened, as a tap on another pane does.
        onZoom = { notification?.leave?.invoke(); conn.op("zoom", "block" to it) })
}

@Composable
internal fun RowScope.sidebarSlot(place: SidebarPlace): Modifier =
    if (place == SidebarPlace.Covers) Modifier.weight(1f).fillMaxHeight() else Modifier.width(VeloTheme.sizes.sidebar).fillMaxHeight()

/** The pane column (pane-area): the panes, the caption and the key bar, in what the sidebar and the rail leave. */
internal fun RowScope.paneColumnSlot(): Modifier = Modifier.testTag("pane-area").weight(1f).fillMaxHeight()

/** The caption's place: [gap] under the panes. */
internal fun captionSlot(gap: Dp): Modifier = Modifier.padding(top = gap)

/** The key bar's place: [gap] under the caption, at most the board's width, in the middle of a wide pane column (WsOpenPanes). */
@Composable
internal fun ColumnScope.keyBarSlot(gap: Dp): Modifier =
    Modifier.padding(top = gap).widthIn(max = VeloTheme.sizes.keyBarMax).align(Alignment.CenterHorizontally)

/** True on the folded (cover) screen and on split-screen halves: the short side of the window is under 600dp. */
fun isCompact(widthDp: Float, heightDp: Float): Boolean = minOf(widthDp, heightDp) < 600f

/**
 * The least width the panes keep beside the open sidebar. At the terminal's text size 480dp holds
 * about 56 columns; with less, the server's terminal is resized to a column count programs wrap badly at.
 */
const val MinPanesBesideSidebarDp = 480f

/**
 * True when the open sidebar covers the panes instead of sitting beside them: the window is
 * [isCompact], or the width left for the panes would be under [MinPanesBesideSidebarDp]. The caller
 * passes the sidebar and rail widths and the margins [Workspace] really lays out: [gapDp] between
 * the sidebar and the panes (and the rail), [outerDp] on each side of the row.
 */
fun sidebarCoversPanes(widthDp: Float, heightDp: Float, sidebarDp: Float, railDp: Float, gapDp: Float, outerDp: Float): Boolean =
    isCompact(widthDp, heightDp) || widthDp - sidebarDp - railDp - 2 * gapDp - 2 * outerDp < MinPanesBesideSidebarDp

/**
 * The least width the panes keep beside a docked sidebar: the bar phone's pane column (309 at
 * Medium) and a little less, which programs are written for. The unfolded screen upright leaves 409.
 */
const val MinPanesDockedDp = 300f

/** Where the open sidebar goes: over the panes, which it hides, or beside them. */
enum class SidebarPlace { Covers, Beside }

/** The width the panes keep beside the sidebar: the window less the sidebar, the rail, a gap beside each and the outer margins. */
fun dockedPanesWidth(widthDp: Float, sidebarDp: Float, railDp: Float, gapDp: Float, outerDp: Float): Float =
    widthDp - sidebarDp - railDp - 2 * gapDp - 2 * outerDp

/**
 * Whether the user may dock the sidebar beside the panes (WsOpenDocked): where it would cover them
 * ([sidebarCoversPanes]) on a window that is not [isCompact], and the panes keep [MinPanesDockedDp]
 * beside it. That is the unfolded screen upright; the bar phone and the folded screen never dock.
 */
fun sidebarDockable(widthDp: Float, heightDp: Float, sidebarDp: Float, railDp: Float, gapDp: Float, outerDp: Float): Boolean =
    !isCompact(widthDp, heightDp) && sidebarCoversPanes(widthDp, heightDp, sidebarDp, railDp, gapDp, outerDp) &&
        dockedPanesWidth(widthDp, sidebarDp, railDp, gapDp, outerDp) >= MinPanesDockedDp

/**
 * Where the open sidebar goes: beside the panes where they keep [MinPanesBesideSidebarDp], or where
 * the user [docked] it and it is [sidebarDockable]; over them anywhere else.
 */
fun sidebarPlace(widthDp: Float, heightDp: Float, sidebarDp: Float, railDp: Float, gapDp: Float, outerDp: Float, docked: Boolean): SidebarPlace = when {
    !sidebarCoversPanes(widthDp, heightDp, sidebarDp, railDp, gapDp, outerDp) -> SidebarPlace.Beside
    docked && sidebarDockable(widthDp, heightDp, sidebarDp, railDp, gapDp, outerDp) -> SidebarPlace.Beside
    else -> SidebarPlace.Covers
}

const val ToggleSidebarCommand = "Toggle Sidebar"
const val DockSidebarCommand = "Dock Sidebar Beside Panes"
const val UndockSidebarCommand = "Undock Sidebar"
/** The name of the sidebar's pin, an on/off switch: the same whether it is on or off. */
const val DockSidebarSwitch = "Dock Sidebar"

/** The palette's docking command: only where the window can dock ([sidebarDockable]), the one that changes what is [docked]. */
fun dockCommand(dockable: Boolean, docked: Boolean): String? = when {
    !dockable -> null
    docked -> UndockSidebarCommand
    else -> DockSidebarCommand
}

/** The sidebar's pin (sidebar-dock): null where the window cannot dock ([sidebarDockable]) and no pin is drawn, else whether it is [docked]. */
fun dockSwitch(dockable: Boolean, docked: Boolean): Boolean? = if (dockable) docked else null

/**
 * Docks the sidebar beside the panes or undocks it, for the palette's command ([dockCommand]) and the
 * sidebar's pin alike: [save]s the other choice than [docked] and says whether the sidebar is open after.
 * Docking shows it beside the panes at once; undocking closes it rather than leave it over them.
 */
fun toggleSidebarDock(docked: Boolean, save: (Boolean) -> Unit): Boolean = (!docked).also(save)

/** Whether the window can dock the sidebar ([sidebarDockable]) and where the open sidebar goes ([sidebarPlace]). */
internal data class SidebarFit(val dockable: Boolean, val place: SidebarPlace)

/** [SidebarFit] for this window at the current sizes, with [gap] beside the sidebar and [outerX] at the window's sides. */
@Composable
internal fun sidebarFit(docked: Boolean, gap: Dp, outerX: Dp): SidebarFit {
    val config = LocalConfiguration.current
    val sizes = VeloTheme.sizes
    val w = config.screenWidthDp.toFloat()
    val h = config.screenHeightDp.toFloat()
    return SidebarFit(
        dockable = sidebarDockable(w, h, sizes.sidebar.value, sizes.rail.value, gap.value, outerX.value),
        place = sidebarPlace(w, h, sizes.sidebar.value, sizes.rail.value, gap.value, outerX.value, docked),
    )
}

/**
 * Palette commands that leave the sidebar that covers the panes open: the toggle itself, docking
 * it (it moves beside the panes) and the commands that open a dialog about one of the sidebar's
 * rows (a remote, a session or a tab).
 */
val SidebarKeepingCommands = setOf(ToggleSidebarCommand, DockSidebarCommand, AddRemoteCommand, RemoveRemoteCommand, RenameSessionCommand, RenameTabCommand)

/** [commands] with [close] run after each one not in [SidebarKeepingCommands]: what they change is behind the sidebar that covers the panes. */
fun closingSidebarAfter(commands: List<PaletteCommand>, close: () -> Unit): List<PaletteCommand> =
    commands.map { c -> if (c.title in SidebarKeepingCommands) c else c.copy(run = { c.run(); close() }) }
