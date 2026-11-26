package io.github.yhzion.velo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import kotlin.math.abs
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.theme.GlassCapsule
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded

/**
 * What the pane area says of the connection [status]: its phase's words ([connectionPhase]). The
 * empty pane area and the capsule over paused panes ([inputPausedText]) both read it, so the two
 * say what the rail and the sidebar say.
 */
internal fun statusText(status: String) = connectionPhase(status, removed = false).words

/** Why the pane area of a connected remote has no tab to draw. */
private enum class EmptyPanes { Loading, NoTabs, NoneChosen, NoSessions }

/**
 * Why the pane area of a connected remote is empty. Selecting a session drops the view of the
 * one shown before at once; `windows` stays empty until the attach answer brings the new one's
 * own view. Until then the session's row has no tabs, the tabs the core read while it followed
 * the session, or no row at all (a session just made). So its tabs are known only when
 * `windows` has them or its row has none. A row with none before the answer comes from the
 * followed view, which the session's events keep current: New Tab is right then too.
 */
private fun emptyPanes(state: UiState): EmptyPanes {
    // Sessions listed and none selected: the attach was refused, or the user detached.
    val session = state.session ?: return if (state.sessions.isEmpty()) EmptyPanes.NoSessions else EmptyPanes.NoneChosen
    val known = state.windows.isNotEmpty() || state.sessions.firstOrNull { it.id == session }?.tabs?.isEmpty() == true
    return if (known) EmptyPanes.NoTabs else EmptyPanes.Loading
}

/** What the pane area says when it has no tab. [remote]: the label this phone saved the remote under. */
internal fun emptyText(state: UiState, remote: String) = when (state.status) {
    "connected" -> when (emptyPanes(state)) {
        EmptyPanes.Loading -> "Loading…"
        EmptyPanes.NoTabs -> "No tabs in this session"
        EmptyPanes.NoneChosen -> "Choose a session in the sidebar."
        EmptyPanes.NoSessions -> if (remote.isEmpty()) "No sessions" else "No sessions on $remote"
    }
    else -> statusText(state.status)
}

/** A button of the empty pane area, with its [label]. */
internal enum class EmptyAction(val label: String) {
    NewSession("New Session"), NewTab("New Tab"), Reconnect("Reconnect"), CopyCommand("Copy command"), ShowRemotes("Show Remotes"),
}

/**
 * The buttons of the empty pane area, so that the way out is on the screen and not in a menu. A
 * connected remote makes a tab in the selected session that has none, and a session when none
 * is listed. While the selected session's tabs are not known it offers nothing: New Tab would
 * make a real tab in a session that may have some. With sessions listed and none selected,
 * Show Remotes opens the sidebar to choose one, and New Session stays beside it. A remote that
 * is down offers Reconnect when its phase does, and Show Remotes, which opens the sidebar to go
 * to another one. The [removed] remote can only be left. Show Remotes is not offered while the
 * [sidebar] is open: it already shows the remotes. A remote that is down for a failure with a
 * one-line fix ([copyCommand], [failureNotice]) offers Copy command beside Reconnect
 * (convenience C).
 */
internal fun emptyActions(state: UiState, removed: Boolean, sidebar: Boolean, copyCommand: Boolean = false): List<EmptyAction> {
    val phase = connectionPhase(state.status, removed)
    val showRemotes = EmptyAction.ShowRemotes.takeIf { !sidebar }
    if (phase == ConnectionPhase.Connected) return when (emptyPanes(state)) {
        EmptyPanes.Loading -> emptyList()
        EmptyPanes.NoTabs -> listOf(EmptyAction.NewTab)
        EmptyPanes.NoneChosen -> listOfNotNull(showRemotes, EmptyAction.NewSession)
        EmptyPanes.NoSessions -> listOf(EmptyAction.NewSession)
    }
    val fix = EmptyAction.CopyCommand.takeIf { copyCommand && !removed }
    return listOfNotNull(EmptyAction.Reconnect.takeIf { phase.canReconnect }, fix, showRemotes)
}

/** What the capsule over the panes says while their input is paused ([paneInputPaused]). */
internal fun inputPausedText(status: String) = "${statusText(status)} · Input is paused"

/**
 * What lies over the panes while their input is paused ([paneInputPaused]): the scrim, and at
 * the top centre a capsule with the connection's [mark] (the rail's) and [inputPausedText] for
 * its [status]. The scrim takes no touch, so the panes under it still scroll and their
 * scrollback can be read. The capsule takes the touches in its bounds and does nothing with
 * them: a tap on its text reaches no pane.
 */
@Composable
internal fun BoxScope.InputPausedCover(mark: ConnectionMark, status: String, radius: Dp) {
    val sizes = VeloTheme.sizes
    Box(Modifier.matchParentSize().clip(rounded(radius)).background(VeloTheme.colors.scrim))
    GlassCapsule(Modifier.align(Alignment.TopCenter).padding(sizes.overlayMargin).pointerInput(Unit) {}) {
        Row(Modifier.padding(horizontal = sizes.rowPadding), verticalAlignment = Alignment.CenterVertically) {
            ConnectionMarkIcon(mark, ink = VeloTheme.surface.onGlass)
            Spacer(Modifier.width(sizes.rowGap / 2))
            // A live region: a reader hears the status change (Reconnecting… to Connection failed) while the cover stays.
            Text(inputPausedText(status), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = VeloTheme.surface.onGlass, style = VeloTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * True when the pane area draws the [window]'s panes ([WindowPanes]) and not its empty text: the
 * tab has a docked or a floating pane. A tab whose panes all float has no layout and is drawn.
 * The server keeps a visible pane in every window, so a shown tab is never all minimized.
 */
internal fun drawsPanes(window: WindowInfo): Boolean = tabBlocks(window).isNotEmpty()

/**
 * The floating panes that [WindowPanes] draws over the docked ones. A zoomed floating pane is
 * drawn once, as the zoomed pane: a second view of its block would cover the live one.
 */
internal fun drawnFloating(window: WindowInfo): List<FloatingLayer> = window.floating.filter { it.block != window.zoomed }

/**
 * One tab's panes in their layout. [preview]: the tab is drawn but is not the one of the
 * workspace, so its panes are previews to the core ([PaneSurfaceView.preview]). A pane's view
 * stays the same view when that changes. Without [controls] no split draws its drag handle: the
 * connection is down, and a drag is a command to its core.
 * [haptics]: the Haptic feedback setting, which a pane's long press follows.
 * [handles]: what a pane's handle does; null draws no handle on any pane.
 * [returning]: the blocks of panes that are shown again
 * where they were before the overview ([ReturnMark]). [focusMark]: how strong the focused pane's edge is,
 * read while drawing (it fades as the tab becomes a card). [onFinger] names the pane a finger
 * went down on.
 */
@Composable
internal fun WindowPanes(
    conn: HostConnection, window: WindowInfo, focused: String?, gap: Dp, radius: Dp, input: TerminalInputView,
    preview: Boolean, controls: Boolean, haptics: Boolean, handles: PaneHandles?, returning: Set<String>, focusMark: () -> Float, onFinger: (String) -> Unit, onFocus: (String) -> Unit,
) {
    // A second finger may land on an AndroidView or a different split. Observe all
    // descendants before dispatch, without consuming their pinch or tab gestures.
    val multiTouch = remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) multiTouch.intValue++
            }
        }
    }) {
        val zoomed = window.zoomed
        // Keyed like the panes of the tree: a view is bound to one block for its whole life.
        if (zoomed != null) key(zoomed) { PaneCard(conn, window, zoomed, zoomed == focused, radius, input, preview, haptics, handles, returning, focusMark, onFinger, onFocus, floating = false, Modifier.fillMaxSize()) }
        else window.layout?.let {
            LayoutTree(conn, window, it, multiTouch, focused, gap, radius, input, preview, controls, haptics, handles, returning, focusMark, onFinger, onFocus, Modifier.fillMaxSize())
        }
        val floats = drawnFloating(window)
        if (floats.isNotEmpty()) {
            Box(Modifier.fillMaxSize().clip(rounded(radius)).background(VeloTheme.colors.scrim))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // This loop and the docked tree must stay separate call sites, so that a pane changing between docked and floating gets a new view.
                for (f in floats) key(f.block) {
                    PaneCard(conn, window, f.block, f.block == focused, radius, input, preview, haptics, handles, returning, focusMark, onFinger, onFocus, floating = true,
                        Modifier.offset(maxWidth * f.x, maxHeight * f.y).size(maxWidth * f.w, maxHeight * f.h))
                }
            }
        }
    }
}

@Composable
private fun LayoutTree(
    conn: HostConnection, window: WindowInfo, node: LayoutNode, multiTouch: IntState, focused: String?, gap: Dp, radius: Dp, input: TerminalInputView,
    preview: Boolean, controls: Boolean, haptics: Boolean, handles: PaneHandles?, returning: Set<String>, focusMark: () -> Float, onFinger: (String) -> Unit, onFocus: (String) -> Unit,
    modifier: Modifier,
) {
    val leaf = node.block
    if (leaf != null) {
        key(leaf) { PaneCard(conn, window, leaf, leaf == focused, radius, input, preview, haptics, handles, returning, focusMark, onFinger, onFocus, floating = false, modifier) }
        return
    }
    val a = node.first
    val b = node.second
    if (a == null || b == null) {
        (a ?: b)?.let { LayoutTree(conn, window, it, multiTouch, focused, gap, radius, input, preview, controls, haptics, handles, returning, focusMark, onFinger, onFocus, modifier) }
        return
    }
    val before = a.lastBlock()
    val after = b.firstBlock()
    val column = node.dir == "column"
    val serverRatio = node.ratio.coerceIn(0.05f, 0.95f)
    var dragged by remember(conn, before, after, column, preview) { mutableStateOf<Float?>(null) }
    var pending by remember(conn, before, after, column, preview, serverRatio) { mutableStateOf<Float?>(null) }
    // Return to the authoritative layout if the server rejects the request.
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(1500)
            pending = null
        }
    }
    val r = dragged ?: pending ?: serverRatio
    BoxWithConstraints(modifier) {
        if (node.dir == "column") Column(Modifier.fillMaxSize()) {
            LayoutTree(conn, window, a, multiTouch, focused, gap, radius, input, preview, controls, haptics, handles, returning, focusMark, onFinger, onFocus, Modifier.weight(r).fillMaxWidth())
            Spacer(Modifier.height(gap))
            LayoutTree(conn, window, b, multiTouch, focused, gap, radius, input, preview, controls, haptics, handles, returning, focusMark, onFinger, onFocus, Modifier.weight(1f - r).fillMaxWidth())
        } else Row(Modifier.fillMaxSize()) {
            LayoutTree(conn, window, a, multiTouch, focused, gap, radius, input, preview, controls, haptics, handles, returning, focusMark, onFinger, onFocus, Modifier.weight(r).fillMaxHeight())
            Spacer(Modifier.width(gap))
            LayoutTree(conn, window, b, multiTouch, focused, gap, radius, input, preview, controls, haptics, handles, returning, focusMark, onFinger, onFocus, Modifier.weight(1f - r).fillMaxHeight())
        }
        // No handle while the panes draw no controls: a drag is a command to a core that is down.
        if (!preview && controls && before != null && after != null) {
            val axis = if (column) maxHeight else maxWidth
            val space = (axis - gap).coerceAtLeast(0.dp)
            val hit = 32.dp.coerceAtMost(axis)
            val start = (space * r + gap / 2 - hit / 2).coerceIn(0.dp, axis - hit)
            val pixels = with(LocalDensity.current) { space.toPx() }
            val sign = if (!column && LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
            val commit: (Float) -> Unit = { value ->
                if (value != serverRatio || pending != null) {
                    pending = value
                    conn.op("resizePane", "before" to before, "after" to after,
                        "direction" to if (column) "vertical" else "horizontal", "ratio" to value)
                }
            }
            val currentRatio by rememberUpdatedState(r)
            val currentCommit by rememberUpdatedState(commit)
            val bounds = if (column) Modifier.offset(y = start).fillMaxWidth().height(hit)
                else Modifier.offset(x = start).width(hit).fillMaxHeight()
            Box(bounds.semantics {
                contentDescription = if (column) "Resize panes above and below" else "Resize panes left and right"
                progressBarRangeInfo = ProgressBarRangeInfo(r, 0.05f..0.95f)
                setProgress { value ->
                    currentCommit(value.coerceIn(0.05f, 0.95f))
                    true
                }
            }.pointerInput(before, after, column, pixels, sign) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val generation = multiTouch.intValue
                        val initial = currentRatio
                        var travel = Offset.Zero
                        var dragging = false
                        var finished = false
                        fun track(event: PointerEvent) {
                            val change = event.changes.singleOrNull()
                            if (change == null || change.id != down.id || change.isConsumed || generation != multiTouch.intValue) {
                                finished = true
                                return
                            }
                            travel += change.positionChange()
                            val distance = if (column) travel.y else travel.x * sign
                            if (abs(distance) > viewConfiguration.touchSlop) dragging = true
                            if (dragging && pixels > 0f) dragged = (initial + distance / pixels).coerceIn(0.05f, 0.95f)
                            change.consume()
                            if (!change.pressed) {
                                if (dragging) dragged?.let(currentCommit)
                                finished = true
                            }
                        }
                        while (!finished) track(awaitPointerEvent())
                        dragged = null
                    }
                } finally {
                    dragged = null
                }
            }, contentAlignment = Alignment.Center) {
                val grip = if (column) Modifier.size(32.dp, 3.dp) else Modifier.size(3.dp, 32.dp)
                Spacer(grip.background(VeloTheme.paneFocus.copy(alpha = if (dragged == null) 0.5f else 1f),
                    androidx.compose.foundation.shape.CircleShape))
            }
        }
    }
}

/**
 * A pane's card (Pane board): black with round corners of [radius], its content clipped to them. The
 * pane that takes the keys ([focused]) has the highlight's edge, as strong as [focusMark] says (it
 * fades as the tab becomes a card); any other pane a hairline, which tells it from the gutters.
 */
@Composable
internal fun Modifier.paneFrame(focused: Boolean, radius: Dp, focusMark: () -> Float = { 1f }): Modifier {
    val focusColor = VeloTheme.paneFocus
    val focusWidth = VeloTheme.sizes.paneFocus
    val hairline = VeloTheme.colors.chrome.selected
    val edge = VeloTheme.sizes.edge
    return clip(rounded(radius)).background(VeloTheme.colors.backdrop.surface).drawWithContent {
        drawContent()
        val width = (if (focused) focusWidth else edge).toPx()
        val color = if (focused) focusColor.copy(alpha = focusColor.alpha * focusMark()) else hairline
        drawRoundRect(color, Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
            CornerRadius(radius.toPx() - width / 2), Stroke(width))
    }
}

private tailrec fun LayoutNode.firstBlock(): String? = block ?: first?.firstBlock()
private tailrec fun LayoutNode.lastBlock(): String? = block ?: second?.lastBlock()

@Composable
private fun PaneCard(
    conn: HostConnection, window: WindowInfo, block: String, focused: Boolean, radius: Dp, input: TerminalInputView,
    preview: Boolean, haptics: Boolean, handles: PaneHandles?, returning: Set<String>, focusMark: () -> Float, onFinger: (String) -> Unit, onFocus: (String) -> Unit,
    floating: Boolean, modifier: Modifier,
) {
    val context = LocalContext.current
    val linkTaps = LocalLinkTaps.current
    var reading by remember(conn, block, preview) { mutableStateOf(false) }
    val label = (paneLabel(tabBlocks(window), block) ?: "Pane") + if (focused) ", input target" else ""
    if (reading && !preview) {
        TerminalReader(conn, block, label) { reading = false }
    }
    // The Pane board names a tab's panes pane-1, pane-2, … in layout order; a preview in the overview is no pane of the screen.
    val number = tabBlocks(window).indexOf(block) + 1
    Box(modifier.then(if (!preview && number > 0) Modifier.testTag("pane-$number") else Modifier).paneFrame(focused, radius, focusMark)) {
        AndroidView(
            factory = { ctx -> PaneSurfaceView(ctx, conn, floating) },
            update = { v ->
                // Before the block: a view never tells the core "shown" for a pane that is a preview.
                v.returns = block in returning
                v.inputTarget = focused
                v.preview = preview
                v.block = block
                v.haptics = haptics
                v.importantForAccessibility = if (preview) View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    else View.IMPORTANT_FOR_ACCESSIBILITY_YES
                v.contentDescription = if (preview) null else label
                v.onRead = { reading = true }
                v.onFinger = {
                    input.forgetImeContext()
                    onFinger(block)
                }
                v.onTap = {
                    onFocus(block)
                    if (input.workspaceAcceptsInput) input.showTerminalKeyboard()
                }
                // The app's run of tap gestures: it counts only on the pane that takes keys.
                v.taps = input.taps
                v.onTextFocus = { onFocus(block) }
                v.onCopySelection = { copyToClipboard(context, conn.selectedText(block)) }
                v.onLink = { tap, notAFile -> linkTaps.tap(conn, block, tap, notAFile) }
                v.onPasteSelection = {
                    if (input.connection === conn && input.workspaceAcceptsInput && conn.connected) {
                        val text = clipboardText(context)
                        if (text.isNotEmpty() && !input.finishCompositionBeforePaste()) input.pasteTextIntoPane(conn, block, text)
                    }
                }
            },
            onRelease = { v -> v.block = "" },
            modifier = Modifier.fillMaxSize(),
        )
        // The last child, so that it lies above the terminal view and takes its touches. A preview draws none.
        if (handles != null && !preview) {
            PaneHandle(paneLabel(tabBlocks(window), block) ?: "Pane", haptics, onTap = { handles.onTap(block) }, onLongPress = { handles.onLongPress(block, it) },
                onMoveToTab = handles.onMoveToTab?.let { move -> { move(block) } })
        }
    }
}

/**
 * What a copy of a pane puts on the clipboard: the pane's selection or screen ([HostConnection.copy]),
 * or null to keep the clipboard as it is. The core gives no text when it cannot read the pane
 * (it is not mirrored yet, or it went), and when the screen or the selection holds only blanks:
 * it trims them. Neither is a copy, and the user's clipboard stays.
 */
internal fun paneCopyText(text: String): String? = text.ifEmpty { null }

/** The explicit pane-menu Copy may copy the screen; the floating toolbar may not. */
internal fun copyPane(context: Context, conn: HostConnection, block: String) = copyToClipboard(context, conn.copy(block))

private fun copyToClipboard(context: Context, value: String) {
    val text = paneCopyText(value) ?: return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Velo", text))
}

internal fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    return if (clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}
