package io.github.yhzion.velo.fidelity

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.ui.ActivityReadState
import io.github.yhzion.velo.ui.BatteryState
import io.github.yhzion.velo.ui.DeviceNetwork
import io.github.yhzion.velo.ui.deviceRow
import io.github.yhzion.velo.ui.KeyBar
import io.github.yhzion.velo.ui.PaneCaption
import io.github.yhzion.velo.ui.RailColumn
import io.github.yhzion.velo.ui.RailPage
import io.github.yhzion.velo.ui.Sidebar
import io.github.yhzion.velo.ui.SidebarActions
import io.github.yhzion.velo.ui.SidebarHost
import io.github.yhzion.velo.ui.SidebarPlace
import io.github.yhzion.velo.ui.SidebarState
import io.github.yhzion.velo.ui.captionSlot
import io.github.yhzion.velo.ui.connectionMark
import io.github.yhzion.velo.ui.keyBarSlot
import io.github.yhzion.velo.ui.paneColumnSlot
import io.github.yhzion.velo.ui.paneFrame
import io.github.yhzion.velo.ui.sidebarSlot
import io.github.yhzion.velo.ui.tabOf
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme

// The workspace boards of page "3 작업 공간" (WsBarTerminal, WsBarSidebar, WsClosedTerminal,
// WsClosedSidebar, WsOpenDocked, WsOpenPanes), drawn from the boards' sample sessions without a
// core: the sidebar from a UiState and its read receipts, the rail from a fixed clock and battery,
// the key bar from the default keys (a tap sends nothing), and each pane as its card with the
// Pane board's sample text where the terminal would draw. The parts are placed with the
// workspace's own slots (Workspace.kt), at the Medium size the boards are drawn at.

/** The workspace boards' screens, for [FidelityScenes]. */
val WorkspaceScenes: Map<String, @Composable () -> Unit> = linkedMapOf(
    "workspace-terminal" to { WorkspaceScene(sidebar = null) },
    "workspace-sidebar" to { WorkspaceScene(sidebar = SidebarPlace.Covers) },
    "workspace-docked" to { WorkspaceScene(sidebar = SidebarPlace.Beside) },
    "workspace-panes" to { WorkspaceScene(sidebar = null) },
)

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

@Composable
private fun WorkspaceScene(sidebar: SidebarPlace?) = VeloTheme(UiSize.Medium) {
    val shape = shape()
    // The bar phone's and the docked boards show the Codex tab, the other two the Claude tab with three panes.
    val threePanes = shape != Shape.Bar && sidebar == null
    val shown = if (threePanes) "review-release-notes" else "port-parser-tests"
    val now = remember { System.currentTimeMillis() }
    val state = remember(shape, shown) { sampleState(shown, devRows = if (shape == Shape.Bar) 2 else 4, now) }
    val readState = remember(state) {
        ActivityReadState().apply {
            // The waiting tab's report has been seen: its row says it waits, its tile has no dot (Sidebar board).
            show(Any(), mapOf(PaneAddress(Agents, "review-release-notes", "block:review-release-notes") to
                BlockInfo(activity = "blocked", since = now - 40_000, kind = "question")))
        }
    }
    val gap = VeloTheme.sizes.gap
    Row(Modifier.testTag("screen-workspace").fillMaxSize().background(VeloTheme.colors.backdrop.surface).padding(gap)) {
        if (sidebar != null) {
            val host = SidebarHost(Remote("remote:studio", Host, "https://studio-mac.example.ts.net"), state, readState, active = true, removed = false)
            Sidebar(deviceRow(TailnetState.Running("velo-phone", listOf("100.101.12.7")), paused = false), listOf(host), sidebarSlot(sidebar), NoActions,
                // Only the docked board's window can dock: its pin is on (WsOpenDocked).
                settingsBadge = true, docked = if (sidebar == SidebarPlace.Beside) true else null, state = remember(shape) { SidebarState(if (shape == Shape.Closed) setOf(Dev) else emptySet()) })
            if (sidebar == SidebarPlace.Beside) Spacer(Modifier.width(gap))
        }
        if (sidebar != SidebarPlace.Covers) Column(paneColumnSlot()) {
            ScenePanes(shape, threePanes, sidebar == SidebarPlace.Beside)
            PaneCaption(tabOf(state, Agents, shown), connected = true, readState, host = Host, modifier = captionSlot(gap))
            KeyBar(Settings.DEFAULT_KEY_BAR.split(','), emptySet(), keyBarSlot(gap)) { _, _ -> }
        }
        Spacer(Modifier.width(gap))
        RailColumn("9:41", BatteryState(80, charging = true, powerSave = false), DeviceNetwork.Wifi,
            connectionMark("connected", removed = false), Host, failure = null, keyboard = false, onReconnect = {}, menuEnabled = true,
            fix = null, onCopyCommand = {}, overviewOpen = false, sidebarOpen = sidebar != null, keepScreenOn = false, onKeepScreenOn = {},
            keyboardAvailable = true, onMenu = {}, onKeyboard = {}, onSidebar = {}, onOverview = {}, menu = {},
            page = RailPage(if (threePanes) 2 else 1, 4), strip = { Spacer(Modifier.weight(1f)) })
    }
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
