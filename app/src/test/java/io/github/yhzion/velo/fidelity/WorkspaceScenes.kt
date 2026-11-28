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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.BlockInfo
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
import io.github.yhzion.velo.ui.ErrorNotice
import io.github.yhzion.velo.ui.deviceRow
import io.github.yhzion.velo.ui.KeyBar
import io.github.yhzion.velo.ui.PaneCaption
import io.github.yhzion.velo.ui.PaneMenu
import io.github.yhzion.velo.ui.RailColumn
import io.github.yhzion.velo.ui.RailPage
import io.github.yhzion.velo.ui.row
import io.github.yhzion.velo.ui.Sidebar
import io.github.yhzion.velo.ui.SidebarActions
import io.github.yhzion.velo.ui.SidebarHost
import io.github.yhzion.velo.ui.SidebarPlace
import io.github.yhzion.velo.ui.SidebarState
import io.github.yhzion.velo.ui.captionSlot
import io.github.yhzion.velo.ui.composerTargetLabel
import io.github.yhzion.velo.ui.connectionMark
import io.github.yhzion.velo.ui.keyBarSlot
import io.github.yhzion.velo.ui.paneColumnSlot
import io.github.yhzion.velo.ui.paneFrame
import io.github.yhzion.velo.ui.sidebarSlot
import io.github.yhzion.velo.ui.tabOf
import io.github.yhzion.velo.ui.UploadToast
import io.github.yhzion.velo.ui.theme.OverlayLayer
import io.github.yhzion.velo.ui.theme.OverlayLayerBox
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
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
// dialog, the attach control of a draft with a file, and, from WorkspaceScreenScenes.kt, the
// screens of #174 and their other states (#188), drawn over the same frame ([WorkspaceFrame]).

/** The workspace boards' screens, for [FidelityScenes]. */
val WorkspaceScenes: Map<String, @Composable () -> Unit> = linkedMapOf(
    "workspace-terminal" to { WorkspaceScene(sidebar = null) },
    "workspace-sidebar" to { WorkspaceScene(sidebar = SidebarPlace.Covers) },
    // No board draws this: the sidebar that covers the panes holds an error notice and an upload notice (#195).
    "workspace-sidebar-notices" to { WorkspaceScene(sidebar = SidebarPlace.Covers, notices = true) },
    "workspace-docked" to { WorkspaceScene(sidebar = SidebarPlace.Beside) },
    "workspace-panes" to { WorkspaceScene(sidebar = null) },
    // No board draws these: the rail's pane menu open (shut while MenuSpeechTest opens it), the remote
    // down with a failure that has a fix, the Compose dialog and the attach control of a draft with a
    // file.
    "workspace-pane-menu" to { WorkspaceScene(sidebar = null, paneMenu = !LocalMenusShut.current) },
    "workspace-offline" to { WorkspaceScene(sidebar = null, down = true) },
    "workspace-compose" to { ComposeScene() },
    "compose-attachment" to { AttachmentScene() },
    *WorkspaceScreenScenes.toList().toTypedArray(),
)

/** The device shape a capture is drawn for, told by its width (393, 475, 821 dp). */
internal enum class Shape { Bar, Closed, Open }

@Composable
private fun shape(): Shape {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 800 -> Shape.Open
        w >= 450 -> Shape.Closed
        else -> Shape.Bar
    }
}

internal const val Agents = "session:agents"
private const val Dev = "session:dev"
internal const val Host = "studio-mac"

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
internal class SceneBasis(val shape: Shape, val threePanes: Boolean, val now: Long) {
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
internal fun rememberSceneBasis(sidebar: SidebarPlace?): SceneBasis {
    val shape = shape()
    val now = remember { System.currentTimeMillis() }
    val threePanes = shape != Shape.Bar && sidebar == null
    return remember(shape, threePanes) { SceneBasis(shape, threePanes, now) }
}

/** A scene over a layer of its own, as the settings scenes have: a menu or dialog opens in this window, where the scans read it. */
@Composable
internal fun SceneLayer(content: @Composable () -> Unit) {
    val layer = remember { OverlayLayer() }
    OverlayLayerBox(layer, Modifier.fillMaxSize()) { content() }
}

/**
 * A workspace board's screen: the shown tab's pane cards, the rail's strip blank. [paneMenu]: the rail's pane menu is
 * open; [down]: the remote is down with a failure that has a one-line fix, so the rail's popover offers Reconnect and
 * Copy command. These scenes are compared with the boards, so their captures change only with the boards.
 */
@Composable
private fun WorkspaceScene(sidebar: SidebarPlace?, paneMenu: Boolean = false, down: Boolean = false, notices: Boolean = false) = SceneLayer {
    VeloTheme(UiSize.Medium) {
        val basis = rememberSceneBasis(sidebar)
        // The core's state of the same sessions, with the shown tab alone and its panes as the scene splits them.
        val panes = remember(basis) { withPanes(basis.state, basis.count) }
        val conn = rememberFakeConnection(if (down) panes.copy(status = "error") else panes)
        // Shown once the core's state is in: the connection clears its error when it connects.
        if (notices) LaunchedEffect(conn) {
            snapshotFlow { conn.connected }.first { it }
            conn.error = SidebarError
            conn.uploading = SidebarUpload
        }
        WorkspaceFrame(basis, sidebar, panes, conn, down = down, paneMenu = paneMenu, overviewOpen = false, notices = notices,
            page = RailPage(if (basis.threePanes) 2 else 1, 4),
            paneArea = { ScenePanes(basis.shape, basis.threePanes, sidebar == SidebarPlace.Beside) },
            caption = { modifier ->
                PaneCaption(tabOf(basis.state, Agents, basis.shown), connected = !down, basis.readState, host = Host, modifier = modifier)
            },
            strip = { Spacer(Modifier.weight(1f)) })
    }
}

/**
 * The workspace's frame as both kinds of scene draw it: the sidebar at [sidebar]'s place, the pane column ([paneArea],
 * the [caption] at its slot's modifier, the key bar), and the rail with [page] and [strip]. Its Pane menu opens the
 * focused pane's menu (open from the start with [paneMenu]), whose Compose opens the Compose dialog over the frame.
 */
@Composable
internal fun WorkspaceFrame(
    basis: SceneBasis, sidebar: SidebarPlace?, panes: UiState, conn: HostConnection, down: Boolean, paneMenu: Boolean,
    overviewOpen: Boolean, page: RailPage?,
    /** The sidebar holds [conn]'s notices, as the workspace's does while it covers the panes. */
    notices: Boolean = false,
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
                settingsBadge = true, docked = if (sidebar == SidebarPlace.Beside) true else null,
                notices = if (notices) { { UploadToast(conn); ErrorNotice(conn) } } else null,
                state = remember(shape) { SidebarState(if (shape == Shape.Closed) setOf(Dev) else emptySet()) })
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

/** The notices of the sidebar that covers the panes (workspace-sidebar-notices): a file the remote lacks, and a file sent. */
internal const val SidebarError = "The remote has no file at /data/local/nosuch/missing-file.txt."
internal const val SidebarUpload = "Uploading report.pdf…"

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
internal fun blocksOf(tab: String, count: Int) = (1..count).map { if (it == 1) "block:$tab" else "block:$tab-$it" }

/** [blocks] laid out as the scenes split them: one fills the tab, two lie one over the other, a third goes under the second. */
internal fun layoutOf(blocks: List<String>): LayoutNode {
    fun leaf(i: Int) = LayoutNode(block = blocks[i])
    return when (blocks.size) {
        1 -> leaf(0)
        2 -> LayoutNode(dir = "column", first = leaf(0), second = leaf(1))
        else -> LayoutNode(dir = "row", first = leaf(0), second = LayoutNode(dir = "column", first = leaf(1), second = leaf(2)))
    }
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

/** The panes of the shown tab as the boards split them, each a card with the Pane board's sample text. */
@Composable
internal fun ColumnScope.ScenePanes(shape: Shape, threePanes: Boolean, docked: Boolean) {
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
internal class Seg(val text: String, val color: Color = Ink, val bold: Boolean = false, val back: Color? = null)

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

internal val AgentLines = listOf(
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
internal fun PaneText(lines: List<List<Seg>>, fontSize: Float, modifier: Modifier) {
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

/**
 * Two working tabs of one remote (#250): one whose program reports 30% shows the pie and no working dots, the other
 * reports none and keeps its logo and dots.
 */
@Composable
internal fun ProgressSidebar() {
    val now = remember { System.currentTimeMillis() }
    val tabs = listOf(
        TabInfo("index", label = "Index the files", program = "claude", cwd = "~/src/api", activity = "working", since = now - 4_000,
            block = "block:index", active = true, progress = 30),
        TabInfo("plain", label = "Plan the move", program = "claude", cwd = "~/src/api", activity = "working", since = now - 4_000, block = "block:plain"),
    )
    val host = SidebarHost(Remote("remote:live", "live", "https://live.example.ts.net"),
        UiState(status = "connected", server = "live", sessions = listOf(SessionInfo("session:live", "live", tabs)), session = "session:live", window = "index"),
        ActivityReadState(), active = true, removed = false)
    Row(Modifier.fillMaxSize()) {
        Sidebar(deviceRow(TailnetState.Running("velo-phone", listOf("100.101.12.7")), paused = false), listOf(host), sidebarSlot(SidebarPlace.Covers), NoActions)
    }
}

/**
 * Remote rows in each state a note says (#201): a connection that failed, one that reconnects, one
 * that is offline, one that connects, and an SSH login that waits for an answer while it connects, beside a connected one.
 */
@Composable
internal fun RemoteStatesSidebar() {
    fun host(id: String, status: String, needsAnswer: Boolean = false) = SidebarHost(Remote("remote:$id", id, "https://$id.example.ts.net"),
        UiState(status = status, server = id), ActivityReadState(), active = status == "connected", removed = false, needsAnswer = needsAnswer)
    val hosts = listOf(host("live", "connected"), host("failed", "error"), host("again", "reconnecting"), host("away", "disconnected"),
        host("dialing", "connecting"), host("asking", "connecting", needsAnswer = true))
    Row(Modifier.fillMaxSize()) {
        Sidebar(deviceRow(TailnetState.Running("velo-phone", listOf("100.101.12.7")), paused = false), hosts, sidebarSlot(SidebarPlace.Covers), NoActions)
    }
}
