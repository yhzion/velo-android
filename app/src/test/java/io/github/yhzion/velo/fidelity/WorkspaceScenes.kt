package io.github.yhzion.velo.fidelity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.ActivityReadState
import io.github.yhzion.velo.ui.AttachControl
import io.github.yhzion.velo.ui.BatteryState
import io.github.yhzion.velo.ui.ComposerAttachment
import io.github.yhzion.velo.ui.ComposerDialog
import io.github.yhzion.velo.ui.ComposerDraft
import io.github.yhzion.velo.ui.ComposerTarget
import io.github.yhzion.velo.ui.DeviceNetwork
import io.github.yhzion.velo.ui.deviceRow
import io.github.yhzion.velo.ui.KeyBar
import io.github.yhzion.velo.ui.KeyboardPanePicker
import io.github.yhzion.velo.ui.MoveNotice
import io.github.yhzion.velo.ui.MovePlace
import io.github.yhzion.velo.ui.moveNoticeAfter
import io.github.yhzion.velo.ui.OverviewCarry
import io.github.yhzion.velo.ui.OverviewState
import io.github.yhzion.velo.ui.PaneCaption
import io.github.yhzion.velo.ui.PaneMenu
import io.github.yhzion.velo.ui.PaneNotices
import io.github.yhzion.velo.ui.PaneRect
import io.github.yhzion.velo.ui.paneRow
import io.github.yhzion.velo.ui.RailColumn
import io.github.yhzion.velo.ui.RailPage
import io.github.yhzion.velo.ui.railPage
import io.github.yhzion.velo.ui.RailScrubber
import io.github.yhzion.velo.ui.row
import io.github.yhzion.velo.ui.shownWindow
import io.github.yhzion.velo.ui.Sidebar
import io.github.yhzion.velo.ui.SidebarActions
import io.github.yhzion.velo.ui.SidebarHost
import io.github.yhzion.velo.ui.SidebarPlace
import io.github.yhzion.velo.ui.SidebarState
import io.github.yhzion.velo.ui.startPaneMove
import io.github.yhzion.velo.ui.StripRemote
import io.github.yhzion.velo.ui.StripSource
import io.github.yhzion.velo.ui.captionSlot
import io.github.yhzion.velo.ui.composerTargetLabel
import io.github.yhzion.velo.ui.connectionMark
import io.github.yhzion.velo.ui.keyBarSlot
import io.github.yhzion.velo.ui.paneColumnSlot
import io.github.yhzion.velo.ui.paneFrame
import io.github.yhzion.velo.ui.rememberOverviewState
import io.github.yhzion.velo.ui.sessionChips
import io.github.yhzion.velo.ui.sidebarSlot
import io.github.yhzion.velo.ui.stripRemotes
import io.github.yhzion.velo.ui.tabBlocks
import io.github.yhzion.velo.ui.tabOf
import io.github.yhzion.velo.ui.TabStage
import io.github.yhzion.velo.ui.TerminalInputView
import io.github.yhzion.velo.ui.UndoNotice
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.wantsAttention
import io.github.yhzion.velo.ui.wheelWindows
import io.github.yhzion.velo.ui.zoomMap
import io.github.yhzion.velo.ui.zoomPaneName
import kotlinx.coroutines.flow.first

// The workspace boards of page "3 작업 공간" (WsBarTerminal, WsBarSidebar, WsClosedTerminal,
// WsClosedSidebar, WsOpenDocked, WsOpenPanes), drawn from the boards' sample sessions without a
// core: the sidebar from a UiState and its read receipts, the rail from a fixed clock and battery,
// the key bar from the default keys (a tap sends nothing), and each pane as its card with the
// Pane board's sample text where the terminal would draw. The parts are placed with the
// workspace's own slots (Workspace.kt), at the Medium size the boards are drawn at.
//
// The rail's buttons open what they open in the app (#174): the connection mark its popover, and
// Pane menu the focused pane's menu (PaneMenu) over a real HostConnection on the fake core
// (FakeCore.kt), whose Paste asks its question and whose Compose opens the Compose dialog. The
// scenes after the boards' draw what no board does: that menu open, the remote down, the Compose
// dialog, the attach control of a draft with a file, and the screens of #174 ([SceneView]).

/** The workspace boards' screens, for [FidelityScenes]. */
val WorkspaceScenes: Map<String, @Composable () -> Unit> = linkedMapOf(
    "workspace-terminal" to { WorkspaceScene(sidebar = null) },
    "workspace-sidebar" to { WorkspaceScene(sidebar = SidebarPlace.Covers) },
    "workspace-docked" to { WorkspaceScene(sidebar = SidebarPlace.Beside) },
    "workspace-panes" to { WorkspaceScene(sidebar = null) },
    // No board draws these: the rail's pane menu open (shut while MenuSpeechTest opens it), the remote
    // down with a failure that has a fix, the Compose dialog and the attach control of a draft with a
    // file.
    "workspace-pane-menu" to { WorkspaceScene(sidebar = null, paneMenu = !LocalMenusShut.current) },
    "workspace-offline" to { WorkspaceScene(sidebar = null, down = true) },
    "workspace-compose" to { ComposeScene() },
    "compose-attachment" to { AttachmentScene() },
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

/** The device shape a capture is drawn for, told by its width (393, 475, 821 dp). */
private enum class Shape { Bar, Closed, Open }

@Composable
private fun shape(): Shape {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 800 -> Shape.Open
        w >= 450 -> Shape.Closed
        else -> Shape.Bar
    }
}

private const val Agents = "session:agents"
private const val Dev = "session:dev"
private const val Host = "studio-mac"

/** The Sidebar board's sessions. [devRows] of the dev session's tabs are listed (the bar phone's board shows two). */
private fun sampleState(shown: String, devRows: Int, now: Long): UiState {
    fun tab(id: String, label: String, program: String, panes: Int, cwd: String, activity: String = "idle", ago: Long? = null, kind: String? = null) =
        TabInfo(id, label = label, panes = panes, program = program, cwd = cwd, activity = activity, since = ago?.let { now - it * 1000 },
            kind = kind, block = "block:$id", active = id == shown)
    val agents = listOf(
        tab("refactor-sync-queue", "Refactor the sync queue", "claude", 1, "~/src/api", "working", 2),
        tab("review-release-notes", "Review the release notes", "claude", 3, "~/src/web", "blocked", 40, kind = "question"),
        tab("port-parser-tests", "Port the parser tests", "codex", 2, "~/src/cli", "working", 12),
        tab("draft-migration-plan", "Draft the migration plan", "gemini", 1, "~/notes", "done", 26),
    )
    val dev = listOf(
        tab("nvim", "nvim", "nvim", 2, "~/src/api"),
        tab("zsh", "zsh", "zsh", 4, "~/src/web"),
        tab("docker-compose", "docker compose up", "docker", 1, "~/src/api"),
        tab("htop", "htop", "htop", 6, "~"),
    ).take(devRows)
    return UiState(status = "connected", server = Host, sessions = listOf(SessionInfo(Agents, "agents", agents), SessionInfo(Dev, "dev", dev)),
        session = Agents, window = shown)
}

/**
 * What every workspace scene starts from at the current [shape]: the tab it shows, the sidebar's sessions ([sampleState])
 * and their read receipts. [threePanes]: the shown tab is the Claude tab with three panes; the bar phone's and the docked
 * boards show the Codex tab with two.
 */
private class SceneBasis(val shape: Shape, val threePanes: Boolean, val now: Long) {
    val shown = if (threePanes) "review-release-notes" else "port-parser-tests"
    val count = if (threePanes) 3 else 2
    val state = sampleState(shown, devRows = if (shape == Shape.Bar) 2 else 4, now)
    val readState = ActivityReadState().apply {
        // The waiting tab's report has been seen: its row says it waits, its tile has no dot (Sidebar board).
        show(Any(), mapOf(PaneAddress(Agents, "review-release-notes", "block:review-release-notes") to
            BlockInfo(activity = "blocked", since = now - 40_000, kind = "question")))
    }
}

@Composable
private fun rememberSceneBasis(sidebar: SidebarPlace?): SceneBasis {
    val shape = shape()
    val now = remember { System.currentTimeMillis() }
    val threePanes = shape != Shape.Bar && sidebar == null
    return remember(shape, threePanes) { SceneBasis(shape, threePanes, now) }
}

/** A scene over a layer of its own, as the settings scenes have: a menu or dialog opens in this window, where the scans read it. */
@Composable
private fun SceneLayer(content: @Composable () -> Unit) {
    val layer = remember { OverlayLayer() }
    OverlayLayerBox(layer, Modifier.fillMaxSize()) { content() }
}

/**
 * A workspace board's screen: the shown tab's pane cards, the rail's strip blank. [paneMenu]: the rail's pane menu is
 * open; [down]: the remote is down with a failure that has a one-line fix, so the rail's popover offers Reconnect and
 * Copy command. These scenes are compared with the boards, so their captures change only with the boards.
 */
@Composable
private fun WorkspaceScene(sidebar: SidebarPlace?, paneMenu: Boolean = false, down: Boolean = false) = SceneLayer {
    VeloTheme(UiSize.Medium) {
        val basis = rememberSceneBasis(sidebar)
        // The core's state of the same sessions, with the shown tab alone and its panes as the scene splits them.
        val panes = remember(basis) { withPanes(basis.state, basis.count) }
        val conn = rememberFakeConnection(if (down) panes.copy(status = "error") else panes)
        WorkspaceFrame(basis, sidebar, panes, conn, down = down, paneMenu = paneMenu, overviewOpen = false,
            page = RailPage(if (basis.threePanes) 2 else 1, 4),
            paneArea = { ScenePanes(basis.shape, basis.threePanes, sidebar == SidebarPlace.Beside) },
            caption = { modifier ->
                PaneCaption(tabOf(basis.state, Agents, basis.shown), connected = !down, basis.readState, host = Host, modifier = modifier)
            },
            strip = { Spacer(Modifier.weight(1f)) })
    }
}

/**
 * A screen no board draws (#174), with every tab of the agents session a window: the pane column shows [view], and the
 * rail's strip is the window wheel as the workspace draws it.
 */
@Composable
private fun ScreenScene(view: SceneView) = SceneLayer {
    VeloTheme(UiSize.Medium) {
        val basis = rememberSceneBasis(sidebar = null)
        val shown = basis.shown
        val panes = remember(basis, view) {
            val tabs = withTabs(basis.state, basis.count, basis.now).let { if (view == SceneView.Covered) withFloating(it, basis.count) else it }
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
            // As the workspace opens it: the shown tab's panes, the focused one marked, each named with its title.
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
 * The workspace's frame as both kinds of scene draw it: the sidebar at [sidebar]'s place, the pane column ([paneArea],
 * the [caption] at its slot's modifier, the key bar), and the rail with [page] and [strip]. Its Pane menu opens the
 * focused pane's menu (open from the start with [paneMenu]), whose Compose opens the Compose dialog over the frame.
 */
@Composable
private fun WorkspaceFrame(
    basis: SceneBasis, sidebar: SidebarPlace?, panes: UiState, conn: HostConnection, down: Boolean, paneMenu: Boolean,
    overviewOpen: Boolean, page: RailPage?,
    paneArea: @Composable ColumnScope.() -> Unit,
    caption: @Composable (Modifier) -> Unit,
    strip: @Composable ColumnScope.() -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // Opened once the core's state is in, as in the app: a menu open before it would keep the items it drew then.
    if (paneMenu) LaunchedEffect(conn) { snapshotFlow { conn.connected }.first { it }; menuOpen = true }
    var composer by remember { mutableStateOf<ComposerTarget?>(null) }
    val shape = basis.shape
    val gap = VeloTheme.sizes.gap
    Row(Modifier.testTag("screen-workspace").fillMaxSize().background(VeloTheme.colors.backdrop.surface).padding(gap)) {
        if (sidebar != null) {
            val host = SidebarHost(Remote("remote:studio", Host, "https://studio-mac.example.ts.net"), basis.state, basis.readState, active = true, removed = false)
            Sidebar(deviceRow(TailnetState.Running("velo-phone", listOf("100.101.12.7")), paused = false), listOf(host), sidebarSlot(sidebar), NoActions,
                // Only the docked board's window can dock: its pin is on (WsOpenDocked).
                settingsBadge = true, docked = if (sidebar == SidebarPlace.Beside) true else null, state = remember(shape) { SidebarState(if (shape == Shape.Closed) setOf(Dev) else emptySet()) })
            if (sidebar == SidebarPlace.Beside) Spacer(Modifier.width(gap))
        }
        if (sidebar != SidebarPlace.Covers) Column(paneColumnSlot()) {
            paneArea()
            caption(captionSlot(gap))
            KeyBar(Settings.DEFAULT_KEY_BAR.split(','), emptySet(), keyBarSlot(gap)) { _, _ -> }
        }
        Spacer(Modifier.width(gap))
        val window = panes.windows.single { it.id == basis.shown }
        val focused = window.layout!!.blocks().first()
        RailColumn("9:41", BatteryState(80, charging = true, powerSave = false), DeviceNetwork.Wifi,
            connectionMark(if (down) "error" else "connected", removed = false), Host,
            failure = if (down) DownFailure else null, keyboard = false, onReconnect = {}, menuEnabled = true,
            fix = if (down) DownFix else null, onCopyCommand = {},
            overviewOpen = overviewOpen,
            sidebarOpen = sidebar != null, keepScreenOn = false, onKeepScreenOn = {},
            keyboardAvailable = true, onMenu = { menuOpen = true }, onKeyboard = {}, onSidebar = {}, onOverview = {},
            // The focused pane's menu, as the workspace hands the rail: its Compose opens the dialog below.
            menu = {
                PaneMenu(window, focused, onDismiss = { menuOpen = false }, conn = conn, onAutoSplit = {}, onFind = {}, onNotifications = {},
                    onClose = {}, onCompose = { session, block -> composer = ComposerTarget(conn, session, block, 1L, composerTargetLabel(Host, panes)) },
                    showing = { it }, expanded = menuOpen, onMoveToTab = {}, onMoveToNewTab = {})
            },
            page = page, strip = strip)
    }
    composer?.let { target -> ComposerDialog(target, notificationPending = false, isCurrent = { true }, onClose = { composer = null }) }
}

/** The failure the down remote's rail says, and its one-line fix (the SSH pool's kind). */
private const val DownFailure = "The SSH agent offers no key that studio-mac accepts."
private const val DownFix = "ssh-add ~/.ssh/id_ed25519"

/**
 * [state] as the core publishes it, with the shown tab's [count] panes laid out as the scenes split
 * them: the first, the tab's own block, is focused; a third goes under the second.
 */
private fun withPanes(state: UiState, count: Int): UiState {
    val shown = checkNotNull(state.window)
    val blocks = blocksOf(shown, count)
    return state.copy(windows = listOf(WindowInfo(shown, label = tabOf(state, Agents, shown)?.name.orEmpty(), layout = layoutOf(blocks))), focused = blocks.first())
}

/** The blocks of a tab of [count] panes: the first is the tab's own block. */
private fun blocksOf(tab: String, count: Int) = (1..count).map { if (it == 1) "block:$tab" else "block:$tab-$it" }

/** [blocks] laid out as the scenes split them: one fills the tab, two lie one over the other, a third goes under the second. */
private fun layoutOf(blocks: List<String>): LayoutNode {
    fun leaf(i: Int) = LayoutNode(block = blocks[i])
    return when (blocks.size) {
        1 -> leaf(0)
        2 -> LayoutNode(dir = "column", first = leaf(0), second = leaf(1))
        else -> LayoutNode(dir = "row", first = leaf(0), second = LayoutNode(dir = "column", first = leaf(1), second = leaf(2)))
    }
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
 * The Compose dialog of the Codex tab's pane, as its pane menu opens it ([ComposerDialog]), over the
 * backdrop: the workspace under its scrim is drawn by the scenes before. The fake core never changes
 * the pane, so the draft stays current.
 */
@Composable
private fun ComposeScene() = VeloTheme(UiSize.Medium) {
    val now = remember { System.currentTimeMillis() }
    val panes = remember { withPanes(sampleState("port-parser-tests", devRows = 2, now), 2) }
    val conn = rememberFakeConnection(panes)
    val target = remember(conn) { ComposerTarget(conn, Agents, panes.focused!!, 1L, composerTargetLabel(Host, panes)) }
    val layer = remember { OverlayLayer() }
    OverlayLayerBox(layer, Modifier.testTag("screen-compose").fillMaxSize().background(VeloTheme.colors.backdrop.surface)) {
        ComposerDialog(target, notificationPending = false, isCurrent = { true }, onClose = {})
    }
}

/**
 * The attach control of a draft with a file attached ([AttachControl]), whose menu also offers
 * Remove. The dialog cannot be given a file without the document picker, so the control is drawn
 * alone, as ReadOnceTest draws it.
 */
@Composable
private fun AttachmentScene() = VeloTheme(UiSize.Medium) {
    val draft = remember { ComposerDraft().apply { attachment = ComposerAttachment("report.pdf", byteArrayOf(1)) } }
    val layer = remember { OverlayLayer() }
    OverlayLayerBox(layer, Modifier.testTag("screen-compose-attachment").fillMaxSize().background(VeloTheme.colors.backdrop.surface)) {
        Box(Modifier.fillMaxSize().padding(VeloTheme.sizes.overlayMargin), contentAlignment = Alignment.Center) {
            AttachControl(draft, enabled = true) {}
        }
    }
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

/** The panes of the shown tab as the boards split them, each a card with the Pane board's sample text. */
@Composable
private fun ColumnScope.ScenePanes(shape: Shape, threePanes: Boolean, docked: Boolean) {
    val gap = VeloTheme.sizes.gap
    val radius = VeloTheme.radii.pane
    @Composable
    fun Pane(n: Int, variant: List<List<Seg>>, focused: Boolean, fontSize: Float, modifier: Modifier) {
        Column(modifier.testTag("pane-$n").paneFrame(focused, radius).padding(12.dp)) {
            PaneText(variant, fontSize, Modifier.testTag("pane-$n-text"))
        }
    }
    if (!threePanes) {
        // WsBarTerminal 460 over 298, WsOpenDocked 560 over 392.
        val (top, bottom) = if (docked) 560f to 392f else 460f to 298f
        val fs = if (docked) 12.5f else 12f
        Column(Modifier.weight(1f).fillMaxWidth()) {
            Pane(1, AgentLines, true, fs, Modifier.weight(top).fillMaxWidth())
            Spacer(Modifier.height(gap))
            Pane(2, ShellLines, false, fs, Modifier.weight(bottom).fillMaxWidth())
        }
    } else {
        // WsClosedTerminal 191 beside 192 (340 over 275); WsOpenPanes 400 beside 329 (560 over 392).
        val open = shape == Shape.Open
        val (left, right) = if (open) 400f to 329f else 191f to 192f
        val (top, bottom) = if (open) 560f to 392f else 340f to 275f
        val fs = if (open) 13f else 11f
        val side = if (open) 12.5f else 11f
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Pane(1, AgentLines, true, fs, Modifier.weight(left).fillMaxSize())
            Spacer(Modifier.width(gap))
            Column(Modifier.weight(right).fillMaxSize()) {
                Pane(2, if (open) EditorLines else ShellLines, false, side, Modifier.weight(top).fillMaxWidth())
                Spacer(Modifier.height(gap))
                Pane(3, LogLines, false, side, Modifier.weight(bottom).fillMaxWidth())
            }
        }
    }
}

/** A run of the Pane board's sample text: its words, colour and weight. */
private class Seg(val text: String, val color: Color = Ink, val bold: Boolean = false, val back: Color? = null)

private val Ink = Color(0xFFE6E8EE)
private val Dim = Color(0xFF8C8F94)
private val Blue = Color(0xFF7FA6F5)
private val Green = Color(0xFF5FD068)
private val Yellow = Color(0xFFF2B866)
private val Red = Color(0xFFFF7B72)
private val Purple = Color(0xFFC792EA)
private val Orange = Color(0xFFE39A6F)
private val Rule = Color(0xFF2B2C30)
private val Cursor = Seg(" ", Color.Black, back = Blue)

private val AgentLines = listOf(
    listOf(Seg("✻ ", Orange), Seg("Refactoring the sync queue", bold = true)),
    listOf(),
    listOf(Seg("• ", Green), Seg("Read ", bold = true), Seg("src/queue/worker.ts", Dim)),
    listOf(Seg("• ", Green), Seg("Edit ", bold = true), Seg("src/queue/worker.ts", Dim)),
    listOf(Seg("  └ ", Dim), Seg("+18", Green), Seg(" "), Seg("−7", Red)),
    listOf(Seg("• ", Green), Seg("Run ", bold = true), Seg("npm test -- queue", Dim)),
    listOf(Seg("  └ ", Dim), Seg("✓ 42 passed", Green), Seg(" (3.1s)", Dim)),
    listOf(),
    listOf(Seg("• "), Seg("The retry backoff now caps at 30 s")),
    listOf(Seg("  and resets after a clean run.")),
    listOf(),
    listOf(Seg("✻ ", Orange), Seg("Checking the other callers… (6s)", Dim)),
    listOf(Seg("─".repeat(42), Rule)),
    listOf(Seg("> ", Dim), Cursor),
    listOf(Seg("─".repeat(42), Rule)),
    listOf(Seg("  auto-accept edits on", Purple)),
)

private val ShellLines = listOf(
    listOf(Seg("~/src/web", Blue, bold = true), Seg("  main", Purple), Seg(" ↑2", Dim)),
    listOf(Seg("$ ", Dim), Seg("git status -sb")),
    listOf(Seg("## "), Seg("main", Green), Seg("...origin/main [ahead 2]", Dim)),
    listOf(Seg(" M ", Yellow), Seg("src/app/routes.ts")),
    listOf(Seg("?? ", Red), Seg("src/app/flags.ts")),
    listOf(Seg("$ ", Dim), Seg("npm run build")),
    listOf(Seg("vite v6.3.5 building for production…", Dim)),
    listOf(Seg("✓ ", Green), Seg("214 modules transformed.")),
    listOf(Seg("dist/index.html          ", Dim), Seg("0.46 kB")),
    listOf(Seg("dist/assets/index.js   ", Dim), Seg("148.20 kB")),
    listOf(Seg("✓ ", Green), Seg("built in 1.84s")),
    listOf(Seg("$ ", Dim), Cursor),
)

private val EditorLines = listOf(
    listOf(Seg("  1 ", Dim), Seg("package ", Purple), Seg("queue")),
    listOf(Seg("  2 ", Dim)),
    listOf(Seg("  3 ", Dim), Seg("import ", Purple), Seg("\"time\"", Green)),
    listOf(Seg("  4 ", Dim)),
    listOf(Seg("  5 ", Dim), Seg("// Backoff caps retries at 30 seconds.", Dim)),
    listOf(Seg("  6 ", Dim), Seg("func ", Purple), Seg("Backoff", Blue), Seg("(n int) time.Duration {")),
    listOf(Seg("  7 ", Dim), Seg("  d := time.Second << n")),
    listOf(Seg("  8 ", Dim), Seg("  if ", Purple), Seg("d > 30*time.Second {")),
    listOf(Seg("  9 ", Dim), Seg("    return ", Purple), Seg("30 * time.Second")),
    listOf(Seg(" 10 ", Dim), Seg("  }")),
    listOf(Seg(" 11 ", Dim), Seg("  return ", Purple), Seg("d"), Cursor),
    listOf(Seg(" 12 ", Dim), Seg("}")),
    listOf(),
    listOf(Seg(" NORMAL ", Color(0xFF0B0C11), bold = true, back = Color(0xFF57A143)), Seg("  backoff.go"), Seg("      11,10", Dim)),
)

private val LogLines = listOf(
    listOf(Seg("api-1  ", Blue), Seg("| listening on :8080")),
    listOf(Seg("db-1   ", Purple), Seg("| ready to accept connections")),
    listOf(Seg("web-1  ", Green), Seg("| ready in 412 ms")),
    listOf(Seg("api-1  ", Blue), Seg("| GET /health 200 2ms")),
    listOf(Seg("api-1  ", Blue), Seg("| POST /sync 202 18ms")),
    listOf(Seg("api-1  ", Blue), Seg("| POST /sync 202 21ms")),
)

/**
 * Where a terminal would draw: [lines] in the monospace face at [fontSize], 1.55 apart, laid out as
 * the Pane board's block renders them. Its lines are `white-space: pre`, so the whitespace of its
 * template shows: each line is a blank line, the text after 8 spaces, and a line of 6 spaces. The
 * terminal draws this text in the app, not Compose, and it is left out of the element measures
 * (tools/fidelity/exceptions.json); drawn alike, it keeps the whole-screen comparison to the rest.
 */
@Composable
private fun PaneText(lines: List<List<Seg>>, fontSize: Float, modifier: Modifier) {
    val text = buildAnnotatedString {
        lines.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            append("\n" + " ".repeat(8))
            for (seg in line) withStyle(SpanStyle(color = seg.color, fontWeight = if (seg.bold) FontWeight.SemiBold else null, background = seg.back ?: Color.Unspecified)) {
                append(seg.text)
            }
            append("\n" + " ".repeat(6))
        }
    }
    Text(text, modifier, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = 1.55.em, color = Ink),
        softWrap = false, maxLines = Int.MAX_VALUE)
}

private val NoActions = SidebarActions(
    toggleSidebar = {}, openSettings = {}, openPalette = {}, openDevice = {},
    selectRemote = {}, newSession = {}, renameRemote = {}, removeRemote = {}, sshDetails = {}, addRemote = {},
    found = listOf(DiscoveredServer("build-server", "https://build-server.example.ts.net")), addFound = {},
    selectSession = { _, _ -> }, newTab = { _, _ -> }, renameSession = { _, _ -> }, closeSession = { _, _ -> },
    selectTab = { _, _ -> }, renameTab = { _, _ -> }, moveTab = { _, _, _ -> }, closeTab = { _, _ -> },
)

/**
 * Two remotes whose rows no board draws (ReadOnceTest): on the connected one, the shown tab has six
 * panes and a finish not yet seen; the other is not connected, and its tab has five panes.
 */
@Composable
internal fun RowCasesSidebar() {
    val now = remember { System.currentTimeMillis() }
    fun host(id: String, status: String, tab: TabInfo) = SidebarHost(Remote("remote:$id", id, "https://$id.example.ts.net"),
        UiState(status = status, server = id, sessions = listOf(SessionInfo("session:$id", id, listOf(tab))), session = "session:$id", window = tab.id),
        ActivityReadState(), active = status == "connected", removed = false)
    val live = host("live", "connected", TabInfo("build", label = "Build the release", panes = 6, program = "htop", cwd = "~/src/api",
        activity = "done", since = now - 26_000, block = "block:build", active = true))
    val offline = host("offline", "disconnected", TabInfo("watch", label = "Watch the logs", panes = 5, program = "htop", cwd = "~/var/log",
        activity = "working", since = now - 3_000, block = "block:watch", active = true))
    Row(Modifier.fillMaxSize()) {
        Sidebar(deviceRow(TailnetState.Running("velo-phone", listOf("100.101.12.7")), paused = false), listOf(live, offline), sidebarSlot(SidebarPlace.Covers), NoActions)
    }
}
