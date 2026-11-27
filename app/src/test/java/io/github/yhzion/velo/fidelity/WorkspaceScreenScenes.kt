package io.github.yhzion.velo.fidelity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.ActivityReadState
import io.github.yhzion.velo.ui.KeyboardPanePicker
import io.github.yhzion.velo.ui.MoveNotice
import io.github.yhzion.velo.ui.MovePlace
import io.github.yhzion.velo.ui.moveNoticeAfter
import io.github.yhzion.velo.ui.OverviewCarry
import io.github.yhzion.velo.ui.OverviewState
import io.github.yhzion.velo.ui.PaneCaption
import io.github.yhzion.velo.ui.PaneNotices
import io.github.yhzion.velo.ui.PaneRect
import io.github.yhzion.velo.ui.paneRow
import io.github.yhzion.velo.ui.railPage
import io.github.yhzion.velo.ui.RailScrubber
import io.github.yhzion.velo.ui.row
import io.github.yhzion.velo.ui.shownWindow
import io.github.yhzion.velo.ui.startPaneMove
import io.github.yhzion.velo.ui.StripRemote
import io.github.yhzion.velo.ui.StripSource
import io.github.yhzion.velo.ui.paneFrame
import io.github.yhzion.velo.ui.rememberOverviewState
import io.github.yhzion.velo.ui.sessionChips
import io.github.yhzion.velo.ui.stripRemotes
import io.github.yhzion.velo.ui.tabBlocks
import io.github.yhzion.velo.ui.tabOf
import io.github.yhzion.velo.ui.TabStage
import io.github.yhzion.velo.ui.TerminalInputView
import io.github.yhzion.velo.ui.UndoNotice
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.wantsAttention
import io.github.yhzion.velo.ui.wheelWindows
import io.github.yhzion.velo.ui.zoomMap
import io.github.yhzion.velo.ui.zoomPaneName
import kotlinx.coroutines.flow.first

// The screens no board draws (#174) and their other states (#188), drawn over the workspace's frame
// (WorkspaceScenes.kt, [WorkspaceFrame]) from the same sample sessions, with every tab of the agents
// session a window: the overview, its grid and its drum, a pane move, its notice, a zoomed tab's
// minimap and its picker, and the hardware keys' pane picker. The board scenes do not draw these, so
// a change here leaves their captures as they are.

/** The screens no board draws, for [WorkspaceScenes]. */
val WorkspaceScreenScenes: Map<String, @Composable () -> Unit> = linkedMapOf(
    // The screens no board draws (#174): the rail's window wheel, the overview with its session strip, the overview as
    // the wheel's drum, a pane move's overview, and a zoomed tab's minimap.
    "workspace-wheel" to { ScreenScene(SceneView.Wheel) },
    "workspace-overview" to { ScreenScene(SceneView.Overview) },
    "workspace-drum" to { ScreenScene(SceneView.Drum) },
    "workspace-pane-move" to { ScreenScene(SceneView.PaneMove) },
    "workspace-zoom" to { ScreenScene(SceneView.Zoom) },
    // The other states of those screens (#188): the overview as a grid with a close waiting for Undo, a move of a tab's
    // only pane, the notice after a move, a zoomed tab's pane under a floating one, and the hardware keys' pane picker.
    "workspace-overview-grid" to { ScreenScene(SceneView.Grid) },
    "workspace-pane-move-alone" to { ScreenScene(SceneView.MoveAlone) },
    "workspace-move-undo" to { ScreenScene(SceneView.Moved) },
    "workspace-zoom-covered" to { ScreenScene(SceneView.Covered) },
    "workspace-pane-picker" to { ScreenScene(SceneView.Picker) },
)

/**
 * What a screen scene ([ScreenScene]) shows in the pane column: every tab of the agents session is a window ([withTabs]),
 * and the rail's strip is the window wheel ([RailScrubber]), a slider a screen reader turns while the overview is shut.
 */
private enum class SceneView {
    /** The pane cards, and the rail's strip as the window wheel. */
    Wheel,
    /** The overview open on the session's tabs ([TabStage]), with its session strip. */
    Overview,
    /** The overview as the rail wheel's drum ([OverviewState.openWheel]): the rail's slider names its tabs, the cards say nothing. */
    Drum,
    /** The overview of a pane move in pick mode, as Move Pane to Tab… or a screen reader starts it: each pane a place. */
    PaneMove,
    /** The shown tab zoomed on its first pane: the caption's minimap ([ZoomMinimap]), whose tap opens its picker. */
    Zoom,
    /** The overview as a grid of cards (the Overview setting, #281), with a tab's close waiting for Undo under it ([RowNote]). */
    Grid,
    /** The overview of a pick-mode move of a tab's only pane: its card says the tab closes, the New Tab card that it is alone. */
    MoveAlone,
    /** The pane cards after a swap of the shown tab's first two panes, with the move's notice and its Undo ([UndoNotice]). */
    Moved,
    /** [Zoom] with the first pane under a floating one, so the minimap's picker lists it in a row under its map. */
    Covered,
    /** The pane cards with the hardware keys' pane picker open ([KeyboardPanePicker]), as its shortcut opens it. */
    Picker,
    ;

    val overview: Boolean get() = this == Overview || this == Drum || this == PaneMove || this == Grid || this == MoveAlone

    /** The shown tab is zoomed on its first pane, and the caption has its minimap. */
    val zoomed: Boolean get() = this == Zoom || this == Covered
}

/**
 * A screen no board draws (#174), or another state of one (#188), with every tab of the agents session a window: the
 * pane column shows [view], and the rail's strip is the window wheel as the workspace draws it.
 */
@Composable
private fun ScreenScene(view: SceneView) = SceneLayer {
    VeloTheme(UiSize.Medium) {
        val basis = rememberSceneBasis(sidebar = null)
        val shown = basis.shown
        val panes = remember(basis, view) {
            val tabs = withTabs(basis.state, basis.count, basis.now).let {
                when (view) {
                    SceneView.Covered -> withFloating(it, basis.count)
                    SceneView.Picker -> withTitle(it)
                    else -> it
                }
            }
            if (!view.zoomed) tabs else tabs.copy(windows = tabs.windows.map { if (it.id == shown) it.copy(zoomed = tabs.focused) else it })
        }
        val conn = rememberFakeConnection(panes)
        val overview = rememberOverviewState(conn, remember { OverviewCarry() }) {}
        // Opened once the core's state is in and the stage has its tabs, as a person or a screen reader opens it in the app.
        // A screen that does not open fails the scene, so no scan reads a shut stage as the screen.
        if (view.overview) LaunchedEffect(conn, view) {
            snapshotFlow { conn.connected }.first { it }
            // Its tabs are no snapshot state, so they are asked each frame, a bounded number of times.
            repeat(120) { if (overview.tabs.isEmpty()) withFrameNanos {} }
            check(overview.tabs.isNotEmpty()) { "The stage got no tabs" }
            when (view) {
                SceneView.Overview -> overview.open()
                SceneView.Drum -> checkNotNull(overview.openWheel()) { "The drum did not open" }
                SceneView.PaneMove -> check(overview.startPaneMove(conn.state.windows, conn.connected, checkNotNull(conn.state.focused), root = null)) { "The pane move did not start" }
                SceneView.Grid -> closeForUndo(overview, conn, basis.state, shown)
                SceneView.MoveAlone -> moveAlone(overview, conn)
                else -> error("Unhandled overview view: $view")
            }
        }
        // The hardware keys' pane picker, opened once the core's state is in, as the pane menu is ([WorkspaceFrame]); shut
        // while MenuSpeechTest scans, which has no key to open it.
        var picking by remember { mutableStateOf(false) }
        if (view == SceneView.Picker && !LocalMenusShut.current) LaunchedEffect(conn) { snapshotFlow { conn.connected }.first { it }; picking = true }
        val focused = panes.windows.single { it.id == shown }.layout!!.blocks().first()
        // As the workspace hands the rail its state: from the overview, not from the scene's view.
        WorkspaceFrame(basis, sidebar = null, panes, conn, down = false, paneMenu = false, overviewOpen = overview.showing && !overview.wheel,
            // The shown tab's place among the session's, or the drum's front tab's.
            page = railPage(panes.windows.map { it.id }, shown, front = if (overview.wheel) overview.wheelFront else -1),
            paneArea = {
                when {
                    view.overview -> Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (conn.connected) SceneStage(conn, overview, basis.state, grid = view == SceneView.Grid)
                    }
                    view.zoomed -> ZoomedPane(basis.shape)
                    view == SceneView.Moved -> MovedPanes(basis, conn, panes)
                    else -> ScenePanes(basis.shape, basis.threePanes, docked = false)
                }
            },
            caption = { modifier ->
                if (view.zoomed) ZoomCaption(panes, basis.readState, modifier)
                else PaneCaption(tabOf(basis.state, Agents, shown), connected = true, basis.readState, host = Host, modifier = modifier)
            },
            // As the workspace draws it: it turns only while the overview is shut, and a turn sends nothing here.
            strip = {
                RailScrubber(wheelWindows(conn.state, conn.activityReadState, conn.connected), shown = shown, scrolls = !overview.showing,
                    turns = conn.connected && !overview.showing && overview.paneMove == null && !overview.pinching, haptics = false, pane = { focused }, touchEpoch = { 0 }, pressed = { false },
                    onScroll = { _, _ -> false }, onOpen = { overview.openWheel() }, onTurn = overview::turnWheel,
                    onPick = { overview.pickWheel(it, select = false) }, onSelect = {}, modifier = Modifier.weight(1f))
            })
        if (picking) {
            // As the workspace opens it: the shown tab's panes, each named by its place, the focused one marked, and the
            // one with a title ([withTitle]) named with it too.
            val blocks = tabBlocks(panes.windows.single { it.id == shown })
            KeyboardPanePicker(blocks, blocks, conn.state.focused, conn.state.blocks, onDismiss = { picking = false }) {}
        }
    }
}

/**
 * The grid's close that waits for Undo (#228): once the grid is open, the last card but the shown tab's flies away as a
 * screen reader's Close tab sends it, and its tab is closed as one that holds only a shell is, without a question. The
 * close is never sent here. A close that does not wait fails the scene.
 */
private suspend fun closeForUndo(overview: OverviewState, conn: HostConnection, state: UiState, shown: String) {
    overview.open()
    val id = overview.tabs.last { it != shown }
    repeat(240) { if (!overview.liftable(id)) withFrameNanos {} }
    check(overview.stage?.grid != null) { "The overview is no grid" }
    check(overview.dismiss(id) { gone -> overview.closeLater(gone, tabOf(state, Agents, gone)?.name.orEmpty()) { conn.connected } }) { "The card did not fly" }
    repeat(240) { if (overview.undoName == null) withFrameNanos {} }
    checkNotNull(overview.undoName) { "The close does not wait for Undo" }
}

/**
 * A move of the only pane of the last tab that has one, as a screen reader's Move to Tab on its card's handle starts it:
 * the move empties its tab. Then the screen reader scrolls the row on, a card at a time, to its last place, the New Tab
 * card after that tab's, which cannot take a pane that is alone already. A move that does not start fails the scene.
 */
private suspend fun moveAlone(overview: OverviewState, conn: HostConnection) {
    val windows = conn.state.windows
    val alone = checkNotNull(windows.lastOrNull { tabBlocks(it).size == 1 }) { "No tab has one pane" }
    check(overview.startPaneMove(windows, conn.connected, tabBlocks(alone).single(), root = null)) { "The move of a tab's only pane did not start" }
    repeat(240) { if (overview.stage == null || overview.progress < 1f) withFrameNanos {} }
    repeat(windows.size + 1) {
        if (!overview.scrollCards(1f)) return
        repeat(240) { if (overview.rowMoving) withFrameNanos {} }
    }
}

/**
 * The shown tab's panes after its first pane was swapped with its second, with the move's notice over them as the
 * workspace places it ([PaneNotices]). The notice keeps its place for the scan: its time running out does nothing.
 */
@Composable
private fun ColumnScope.MovedPanes(basis: SceneBasis, conn: HostConnection, panes: UiState) {
    val notice = remember(panes) { swapNotice(panes) }
    var box by remember { mutableStateOf<Rect?>(null) }
    val sizes = VeloTheme.sizes
    Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { box = Rect(Offset.Zero, it.size.toSize()) }) {
        Column(Modifier.fillMaxSize()) { ScenePanes(basis.shape, basis.threePanes, docked = false) }
        PaneNotices({ box }, sizes.overlayPadding, sizes.gap, lift = 0.dp, modifier = Modifier.matchParentSize()) {
            UndoNotice(conn, notice, onDone = {})
        }
    }
}

/**
 * The notice of a swap of the shown tab's first pane with its second ([moveNoticeAfter]): the tabs of [state] were the
 * tabs before it but for those two panes, the other way round. So the move has landed, and its Undo swaps them back.
 */
private fun swapNotice(state: UiState): MoveNotice {
    val shown = checkNotNull(state.window)
    val blocks = checkNotNull(state.windows.single { it.id == shown }.layout).blocks()
    val before = state.windows.map { if (it.id == shown) it.copy(layout = layoutOf(listOf(blocks[1], blocks[0]) + blocks.drop(2))) else it }
    return moveNoticeAfter(null, blocks[0], MovePlace.Swap(shown, blocks[1], PaneRect(blocks[1], 0f, 0f, 0f, 0f)), before) { "" }
}

/**
 * [state] with a floating pane over the shown tab's first pane, as large as it is in [layoutOf]'s split of [count]
 * panes (the left half of three, the top half of two): the minimap's picker leaves that pane no finger's target, so
 * it lists it in a row under its map ([CoveredCells]).
 */
private fun withFloating(state: UiState, count: Int): UiState {
    val shown = checkNotNull(state.window)
    val block = "block:$shown-floating"
    val over = if (count >= 3) FloatingLayer(block, 0f, 0f, 0.5f, 1f) else FloatingLayer(block, 0f, 0f, 1f, 0.5f)
    return state.copy(windows = state.windows.map { if (it.id == shown) it.copy(floating = listOf(over)) else it },
        blocks = state.blocks + (block to BlockInfo(program = "zsh", cwd = "~")))
}

/**
 * [state] with a title on the shown tab's second pane, as a program sets its terminal's title: the hardware keys' pane
 * picker ([KeyboardPanePicker]) names that pane by its place and its title.
 */
private fun withTitle(state: UiState): UiState {
    val shown = checkNotNull(state.window)
    val block = checkNotNull(state.windows.single { it.id == shown }.layout).blocks()[1]
    return state.copy(blocks = state.blocks + (block to checkNotNull(state.blocks[block]).copy(title = "tail -f build.log")))
}

/**
 * [state] as the core publishes it with every tab of the agents session a window: the shown tab has [count] panes, the
 * others as many as their rows say (three at most). Each tab's first pane runs its program and has its report; the
 * others run zsh, and the shown tab's second has finished a run 26 s ago that no one has seen, so its minimap marks it.
 */
private fun withTabs(state: UiState, count: Int, now: Long): UiState {
    val shown = checkNotNull(state.window)
    val blocks = linkedMapOf<String, BlockInfo>()
    val windows = state.sessions.single { it.id == Agents }.tabs.orEmpty().map { tab ->
        val ids = blocksOf(tab.id, if (tab.id == shown) count else tab.panes.coerceAtMost(3))
        for ((i, block) in ids.withIndex()) blocks[block] = when {
            i == 0 -> BlockInfo(program = tab.program, cwd = tab.cwd, activity = tab.activity, since = tab.since, kind = tab.kind)
            i == 1 && tab.id == shown -> BlockInfo(program = "zsh", cwd = tab.cwd, activity = "done", since = now - 26_000)
            else -> BlockInfo(program = "zsh", cwd = tab.cwd)
        }
        WindowInfo(tab.id, label = tab.label, layout = layoutOf(ids))
    }
    return state.copy(windows = windows, blocks = blocks, focused = "block:$shown")
}

/**
 * The overview's stage ([TabStage]) as the workspace draws it in the pane area, on the core's state of [conn], as a row or
 * as a [grid]: its strip has [state]'s sessions and build-server's ([rememberSceneStrip]). A tap or a move sends nothing.
 */
@Composable
private fun SceneStage(conn: HostConnection, overview: OverviewState, state: UiState, grid: Boolean) {
    val live = conn.state
    val selected = live.windows.firstOrNull { it.id == live.window } ?: return
    val context = LocalContext.current
    val input = remember(context) { TerminalInputView(context) }
    TabStage(conn, live, selected, overview, live.focused, VeloTheme.sizes.gap, VeloTheme.radii.pane, input, controls = true, haptics = false,
        handles = null, strip = rememberSceneStrip(state), onFocus = {}, onDismiss = {}, onMoved = { _, _, _ -> }, grid = grid, fontSize = 13f)
}

/** The zoomed pane, alone in the pane area: a card with the Pane board's agent text. */
@Composable
private fun ColumnScope.ZoomedPane(shape: Shape) {
    Column(Modifier.weight(1f).fillMaxWidth().testTag("pane-1").paneFrame(true, VeloTheme.radii.pane).padding(12.dp)) {
        PaneText(AgentLines, if (shape == Shape.Open) 13f else 12f, Modifier.testTag("pane-1-text"))
    }
}

/**
 * The caption of [state]'s zoomed tab with its minimap, as the workspace draws it (its ShownCaption): the minimap marks
 * the panes whose report [readState] has not seen, and the picker names each pane alone ([zoomPaneName]).
 */
@Composable
private fun ZoomCaption(state: UiState, readState: ActivityReadState, modifier: Modifier) {
    val window = checkNotNull(shownWindow(state))
    val session = state.session.orEmpty()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val map = zoomMap(window, rtl) { block -> readState.activity(window.paneRow(state, session, block), true).badge }
    PaneCaption(window.row(state, session, true), connected = true, readState, host = Host, modifier = modifier,
        zoom = map, paneName = { zoomPaneName(state, window, it) })
}

/**
 * What the session strip shows: studio-mac's sessions of [state], then build-server's, which is connected too, under
 * its name. A tap or a menu's choice does nothing.
 */
@Composable
private fun rememberSceneStrip(state: UiState): StripSource = remember(state) {
    val readState = ActivityReadState()
    val build = UiState(status = "connected", server = "build-server", sessions = listOf(SessionInfo("session:main", "main",
        listOf(TabInfo("make", label = "make release", panes = 1, program = "make", cwd = "~/src/velo", block = "block:make", active = true)))),
        session = "session:main", window = "make")
    val remotes = listOf(
        StripRemote("remote:studio", Host, active = true, connected = true, chips = sessionChips(state) { wantsAttention(readState.activity(it, true)) }),
        StripRemote("remote:build", "build-server", active = false, connected = true, chips = sessionChips(build) { false }),
    )
    StripSource(remotes = { stripRemotes(remotes) }, showElsewhere = { _, _ -> }, rename = { _, _ -> }, close = { _, _ -> })
}
