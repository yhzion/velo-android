package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo

/**
 * What each unit's menu holds, and what the command palette lists, as pure functions. One
 * rule: a menu acts on the unit it sits on, so none of them holds an item of another unit.
 */

/** One item of a menu: [enabled] false keeps it listed, greyed out; a [note] says why, under the label. */
data class MenuItem<out A>(val action: A, val label: String, val enabled: Boolean = true, val note: String? = null)

enum class RemoteAction { NewSession, Rename, SshDetails, Remove }

/**
 * A remote row's `…` menu. A session can only be made on a connected remote; renaming and
 * removing change the phone's list and need no connection. An [ssh] remote also has "SSH
 * details" ([SshDetailsPanel]): what the phone remembers for its login, each with a Forget,
 * which needs no connection either. The removed remote has no menu.
 */
fun remoteMenu(status: String, removed: Boolean, ssh: Boolean = false): List<MenuItem<RemoteAction>> = if (removed) emptyList() else listOf(
    MenuItem(RemoteAction.NewSession, "New Session", enabled = status == "connected"),
    MenuItem(RemoteAction.Rename, "Rename…"),
) + sshDetailsItems(ssh) + MenuItem(RemoteAction.Remove, RemoveRemoteCommand)

/** A saved remote's `…` menu in Settings: a remote row's ([remoteMenu]) without New Session. */
fun savedRemoteMenu(ssh: Boolean): List<MenuItem<RemoteAction>> =
    listOf(MenuItem(RemoteAction.Rename, "Rename…")) + sshDetailsItems(ssh) + MenuItem(RemoteAction.Remove, RemoveRemoteCommand)

/** "SSH details" for an [ssh] remote; nothing for any other. */
private fun sshDetailsItems(ssh: Boolean): List<MenuItem<RemoteAction>> =
    if (ssh) listOf(MenuItem(RemoteAction.SshDetails, SSH_DETAILS)) else emptyList()

enum class SessionAction { Rename, Close }

/** A session header's `…` menu. Both items ask the remote, so they need its connection. */
fun sessionMenu(connected: Boolean = true): List<MenuItem<SessionAction>> = listOf(
    MenuItem(SessionAction.Rename, "Rename…", connected),
    MenuItem(SessionAction.Close, "Close Session", connected),
)

enum class TabAction { Rename, MoveForward, MoveBackward, Close }

const val MoveTabForwardCommand = "Move Tab Forward"
const val MoveTabBackwardCommand = "Move Tab Backward"

/**
 * A tab row's long-press menu. A tab moves one place toward the end of its session's order
 * ([forward]) or toward the start ([backward]) only while there is a place on that side
 * ([tabStep]); each caller says, as no default fits all of them.
 */
fun tabMenu(connected: Boolean, forward: Boolean, backward: Boolean): List<MenuItem<TabAction>> = listOf(
    MenuItem(TabAction.Rename, "Rename…", connected),
    MenuItem(TabAction.MoveForward, MoveTabForwardCommand, connected && forward),
    MenuItem(TabAction.MoveBackward, MoveTabBackwardCommand, connected && backward),
    MenuItem(TabAction.Close, "Close Tab", connected),
)

enum class PaneSplit(val label: String, val wireName: String) : PaneAction, PaletteAction {
    Right("Split Right", "right"), Below("Split Below", "below"), Left("Split Left", "left"),
    Above("Split Above", "above"), Auto("Split Auto", "auto"),
}

/** In dp, including the divider between two equally sized children. */
internal fun automaticSplit(width: Float, height: Float, gap: Float): PaneSplit? {
    val minWidth = 96f
    val minHeight = 72f
    val right = (width - gap) / 2 >= minWidth && height >= minHeight
    val below = (height - gap) / 2 >= minHeight && width >= minWidth
    return when {
        right && (width >= height || !below) -> PaneSplit.Right
        below -> PaneSplit.Below
        else -> null
    }
}

sealed interface PaneAction {
    data object Float : PaneAction
    data object Dock : PaneAction
    data object RestoreInNewTab : PaneAction
    /** Stashes the pane: minimizes its floating layer, floating it first when it is tiled. */
    data object Minimize : PaneAction
    /** Opens the picker that asks which tab takes the pane. */
    data object MoveToTab : PaneAction
    data object MoveToNewTab : PaneAction
    data object Zoom : PaneAction
    data object Restore : PaneAction
    data object Copy : PaneAction
    data object Paste : PaneAction
    data object Compose : PaneAction
    data object TextLarger : PaneAction
    data object TextSmaller : PaneAction
    data object TextReset : PaneAction
    data object Close : PaneAction
}

private const val DockPaneLabel = "Dock Pane in Split Layout"
private const val RestoreInNewTabLabel = "Restore Pane in New Tab"
private const val MoveToTabLabel = "Move Pane to Tab…"
private const val MoveToNewTabLabel = "Move Pane to New Tab"

/**
 * A pane's menu (long press on it, the rail's `…`). Closing its only pane also ends the tab.
 * Without a [connected] server the items that send it a command stay listed and are disabled;
 * Copy and the text size, which this phone does alone, stay enabled. The move items follow
 * Float or Dock: [canMoveToTab] lists `Move Pane to Tab…` (its caller has a picker for it and
 * somewhere to move the pane to), and [canMoveToNewTab] lists `Move Pane to New Tab`. A floating
 * pane alone in its tab has `Restore Pane in New Tab` for that, so it is not listed twice.
 * `Float and Minimize` (`Minimize Floating Window` for a floating pane) follows Float or Dock: it is
 * enabled when the pane can be [canStash]ed, and a connected menu that cannot says why in a note.
 */
fun paneMenu(
    floating: Boolean, zoomed: Boolean, paneCount: Int, canCompose: Boolean = true, hasTiledPane: Boolean = true, minimized: Boolean = false,
    connected: Boolean = true, canMoveToNewTab: Boolean = false, canMoveToTab: Boolean = false, canStash: Boolean = false,
): List<MenuItem<PaneAction>> = PaneSplit.entries.map { MenuItem<PaneAction>(it, it.label, connected) } + listOf<MenuItem<PaneAction>?>(
    when {
        !floating -> MenuItem(PaneAction.Float, "Float", connected)
        hasTiledPane -> MenuItem(PaneAction.Dock, DockPaneLabel, connected)
        else -> MenuItem(PaneAction.RestoreInNewTab, RestoreInNewTabLabel, connected)
    },
    MenuItem(
        PaneAction.Minimize, if (floating) MinimizeFloatingLabel else FloatAndMinimizeLabel, connected && canStash,
        note = if (connected && !canStash) StashBlockedNote else null,
    ),
    if (canMoveToTab) MenuItem(PaneAction.MoveToTab, MoveToTabLabel, connected) else null,
    if (canMoveToNewTab && (!floating || hasTiledPane)) MenuItem(PaneAction.MoveToNewTab, MoveToNewTabLabel, connected) else null,
    when {
        zoomed -> MenuItem(PaneAction.Restore, "Exit Full Screen", connected)
        paneCount > 1 -> MenuItem(PaneAction.Zoom, "Zoom", connected)
        else -> null
    },
    MenuItem(PaneAction.Copy, "Copy"),
    MenuItem(PaneAction.Paste, "Paste", connected),
    MenuItem(PaneAction.Compose, "Compose…", canCompose && connected),
    MenuItem(PaneAction.TextLarger, "Text Size Larger"),
    MenuItem(PaneAction.TextSmaller, "Text Size Smaller"),
    MenuItem(PaneAction.TextReset, "Text Size Reset"),
    when {
        paneCount > 1 -> MenuItem(PaneAction.Close, "Close Pane", connected)
        paneCount == 1 && !minimized -> MenuItem(PaneAction.Close, "Close Tab", connected)
        else -> null
    },
).filterNotNull()

/** Panes drawn in the tab, in layout order followed by floating panes. */
internal fun keyboardPaneBlocks(window: WindowInfo): List<String> {
    val zoomed = window.zoomed ?: return tabBlocks(window)
    return (listOf(zoomed) + window.floating.map { it.block }).distinct()
}

/** The pane's name uses the full tab order even when zoom hides part of the layout. */
internal fun paneLabel(panes: List<String>, block: String): String? =
    panes.indexOf(block).takeIf { it >= 0 }?.let { "Pane ${it + 1}" }

/** Dispatches layout commands or requests a close confirmation for the menu's target. */
internal fun runPaneLayoutAction(
    action: PaneAction,
    block: String,
    send: (String, Array<out Pair<String, Any?>>) -> Unit,
    onClose: (String) -> Unit,
    onAutoSplit: (String) -> Unit,
) {
    val target = "block" to block
    when (action) {
        PaneSplit.Auto -> onAutoSplit(block)
        is PaneSplit -> send("split", arrayOf(target, "dir" to action.wireName))
        PaneAction.Float -> send("float", arrayOf(target))
        PaneAction.Dock -> send("dock", arrayOf(target))
        PaneAction.RestoreInNewTab -> send("restoreInNewTab", arrayOf(target))
        PaneAction.Minimize -> send("minimizePane", arrayOf(target))
        PaneAction.Zoom -> send("zoom", arrayOf(target))
        PaneAction.Restore -> send("unzoom", arrayOf(target))
        PaneAction.Close -> onClose(block)
        else -> error("Unsupported pane layout action: $action")
    }
}

/**
 * The title of the dialog that asks before [text] is pasted into a pane, or null when it is
 * pasted at once (an empty text is not pasted at all). A terminal with [bracketed] paste hands
 * the text to its program as one paste, so nothing is asked. Any other terminal takes it as
 * typed keys: every line break runs a line, and a control character is a key or starts a
 * terminal sequence. A tab is not asked about: it completes or indents and ends no line. The
 * core applies the same rule when the paste arrives (`protocol.pasteInput`). One line whose
 * only control character is the line break that ends it arrives as that line and Enter, and
 * its title says so; any other control character keeps the general title.
 */
fun pasteQuestion(text: String, bracketed: Boolean): String? {
    if (bracketed || text.none { it.isISOControl() && it != '\t' }) return null
    val body = text.removeSuffix("\n").removeSuffix("\r")
    val endsLine = body.length < text.length
    // The line break that ends the text starts no line.
    val lines = text.lines().size - if (endsLine) 1 else 0
    return when {
        lines > 1 -> "Paste $lines lines?"
        endsLine && body.none { it.isISOControl() && it != '\t' } -> "Paste this line and press Enter?"
        else -> "Paste text with control characters?"
    }
}

/** The text of the dialog that asks before a paste ([pasteQuestion]). */
const val PasteRunsLinesText = "This terminal runs each line as soon as it arrives."

/**
 * True when [text] that the keyboard commits is pasted by the rule of the menu's Paste
 * ([pasteQuestion]) instead of being typed: it holds a line break or a control character other
 * than a tab, as a text pasted from the keyboard's clipboard does. One line break alone is the
 * Enter of a keyboard that commits it as text, and is typed.
 */
fun keyboardTextIsPaste(text: String): Boolean =
    text !in KeyboardEnterTexts && text.any { it.isISOControl() && it != '\t' }

private val KeyboardEnterTexts = setOf("\n", "\r", "\r\n")

/**
 * The units the command palette can act on: the [saved] remotes, and of the active remote
 * whether it can be removed ([removable]), is [connected] or can be connected again
 * ([canReconnect], [ConnectionPhase.canReconnect]), and whether it shows a [session], a [tab]
 * and a focused [pane] (with the tab's [paneCount] and that pane's state).
 */
data class PaletteUnits(
    val saved: List<Remote>,
    val removable: Boolean,
    val connected: Boolean,
    val session: Boolean,
    val tab: Boolean,
    val pane: Boolean,
    val paneCount: Int,
    val floating: Boolean,
    val zoomed: Boolean,
    val hasTiledPane: Boolean = true,
    val canReconnect: Boolean = false,
    /** The shown tab has a place on that side in its session's order ([tabStep]). */
    val tabForward: Boolean = false,
    val tabBackward: Boolean = false,
    /** The focused pane can be stashed ([canStash]). */
    val canStash: Boolean = false,
    /** What `Minimize Floating Windows` does to the shown tab ([floatingToggle]); null when it has nothing to do. */
    val floatingToggle: Boolean? = null,
    /** The directions the focused pane can move in, that is, where it has a neighbour to swap with ([paneToward]). */
    val moves: Set<PaneDirection> = emptySet(),
    /** The corner where the minimized-pane capsules are drawn now; its own move is disabled. */
    val capsuleCorner: CapsuleCorner = DefaultCapsuleCorner,
)

sealed interface PaletteAction {
    data class GoToRemote(val remote: Remote) : PaletteAction
    data object AddRemote : PaletteAction
    /** Connect the active remote again now ([ConnectionPhase.canReconnect]). */
    data object Reconnect : PaletteAction
    data object RemoveRemote : PaletteAction
    data object NewSession : PaletteAction
    data object NewTab : PaletteAction
    data object RenameSession : PaletteAction
    data object RenameTab : PaletteAction
    data object MoveTabForward : PaletteAction
    data object MoveTabBackward : PaletteAction
    data object CloseTab : PaletteAction
    data object CloseSession : PaletteAction
    /** Zoom the focused pane, or restore it when it is zoomed. */
    data object Zoom : PaletteAction
    /** Float the focused pane, or dock it when it floats. */
    data object Float : PaletteAction
    /** Stash the focused pane: minimize its floating layer, floating it first when it is tiled. */
    data object Minimize : PaletteAction
    /** Minimize every floating window of the shown tab, or show the minimized ones again ([PaletteUnits.floatingToggle]). */
    data object MinimizeAll : PaletteAction
    /** Draw the minimized-pane capsules in [corner]. */
    data class MoveCapsules(val corner: CapsuleCorner) : PaletteAction
    /** Swap the focused pane with its neighbour on that side. */
    data class MovePane(val direction: PaneDirection) : PaletteAction
    data object ClosePane : PaletteAction
    data object TextLarger : PaletteAction
    data object TextSmaller : PaletteAction
    data object TextReset : PaletteAction
    data object Settings : PaletteAction
}

const val AddRemoteCommand = "Add Remote…"
const val RemoveRemoteCommand = "Remove Remote…"
const val RenameSessionCommand = "Rename Session…"
const val RenameTabCommand = "Rename Tab…"

/**
 * The palette's commands on the remotes and on the active remote's session, tab and pane.
 * A command whose unit is not there is disabled. A lone pane hides the command to enter full screen.
 * The text size is the phone's own, as in the pane's menu: it needs a pane and no connection.
 */
fun paletteItems(u: PaletteUnits): List<MenuItem<PaletteAction>> {
    val session = u.connected && u.session
    val tab = session && u.tab
    val pane = tab && u.pane
    val shownPane = u.session && u.tab && u.pane
    return u.saved.map { MenuItem<PaletteAction>(PaletteAction.GoToRemote(it), "Go to Remote: ${it.label.ifEmpty { "Remote" }}") } + listOf<MenuItem<PaletteAction>>(
        MenuItem(PaletteAction.AddRemote, AddRemoteCommand),
        MenuItem(PaletteAction.RemoveRemote, RemoveRemoteCommand, u.removable),
        MenuItem(PaletteAction.Reconnect, "Reconnect", u.canReconnect),
        MenuItem(PaletteAction.NewSession, "New Session", u.connected),
        MenuItem(PaletteAction.NewTab, "New Tab", session),
        MenuItem(PaletteAction.RenameSession, RenameSessionCommand, session),
        MenuItem(PaletteAction.RenameTab, RenameTabCommand, tab),
        MenuItem(PaletteAction.MoveTabForward, MoveTabForwardCommand, tab && u.tabForward),
        MenuItem(PaletteAction.MoveTabBackward, MoveTabBackwardCommand, tab && u.tabBackward),
        MenuItem(PaletteAction.CloseTab, "Close Tab", tab),
        MenuItem(PaletteAction.CloseSession, "Close Session", session),
    ) + PaneSplit.entries.map { MenuItem<PaletteAction>(it, it.label, pane) } + listOf<MenuItem<PaletteAction>?>(
        if (u.zoomed || u.paneCount != 1) {
            MenuItem(PaletteAction.Zoom, if (u.zoomed) "Exit Full Screen" else "Zoom Pane", pane && (u.zoomed || u.paneCount > 1))
        } else null,
        MenuItem(
            PaletteAction.Float,
            when {
                !u.floating -> "Float Pane"
                u.hasTiledPane -> DockPaneLabel
                else -> RestoreInNewTabLabel
            },
            pane,
        ),
        MenuItem(
            PaletteAction.Minimize, if (u.floating) MinimizeFloatingLabel else FloatAndMinimizeLabel, pane && u.canStash,
            note = if (pane && !u.canStash) StashBlockedNote else null,
        ),
        MenuItem(PaletteAction.MinimizeAll, MinimizeAllFloatingLabel, tab && u.floatingToggle != null),
        // The corner moves come after the pane moves: "move" and Enter runs the first enabled match, a pane move (#220).
    ).filterNotNull() + PaneDirection.entries.map { MenuItem<PaletteAction>(PaletteAction.MovePane(it), it.label, pane && it in u.moves) } +
        CapsuleCorner.entries.map { MenuItem<PaletteAction>(PaletteAction.MoveCapsules(it), moveCornerLabel(it), it != u.capsuleCorner) } + listOf<MenuItem<PaletteAction>?>(
        if (u.paneCount != 1) MenuItem(PaletteAction.ClosePane, "Close Pane", pane && u.paneCount > 1) else null,
        MenuItem(PaletteAction.TextLarger, "Text Size Larger", shownPane),
        MenuItem(PaletteAction.TextSmaller, "Text Size Smaller", shownPane),
        MenuItem(PaletteAction.TextReset, "Text Size Reset", shownPane),
        MenuItem(PaletteAction.Settings, "Settings…"),
    ).filterNotNull()
}

/** A palette command that moves the focus to [block]. */
data class FocusPaneCommand(val label: String, val block: String)

/**
 * The palette's `Focus Pane` commands: one for every pane the shown tab [window] draws
 * ([keyboardPaneBlocks]: a zoomed tab draws only the zoomed pane and the floating ones), but the
 * [focused] one. Each is named as the pane picker names it: the pane's label ([paneLabel], its
 * number in the whole tab, which the pane menu shows as its target), then its title in [blocks]
 * when it has one.
 */
fun focusPaneCommands(window: WindowInfo, blocks: Map<String, BlockInfo>, focused: String?): List<FocusPaneCommand> {
    val tabPanes = tabBlocks(window)
    return keyboardPaneBlocks(window).filter { it != focused }.mapNotNull { block ->
        paneCommandLabel("Focus Pane", tabPanes, block, blocks)?.let { FocusPaneCommand(it, block) }
    }
}

/** A palette command that makes [block] the full screen pane in place of the one that is (#157). */
data class FullScreenPaneCommand(val label: String, val block: String)

/**
 * The palette's `Full Screen Pane` commands while the shown tab [window] is zoomed: one for every other pane of the
 * tab, tiled or floating, in the order the minimap's picker has them ([tabBlocks]), named as the `Focus Pane` ones
 * are. They are the picker's taps for a keyboard: the zoom hides the tiled panes, so no `Focus Pane` reaches them.
 */
fun fullScreenPaneCommands(window: WindowInfo, blocks: Map<String, BlockInfo>): List<FullScreenPaneCommand> {
    val zoomed = window.zoomed ?: return emptyList()
    val tabPanes = tabBlocks(window)
    if (zoomed !in tabPanes) return emptyList()
    return tabPanes.filter { it != zoomed }.mapNotNull { block ->
        paneCommandLabel("Full Screen Pane", tabPanes, block, blocks)?.let { FullScreenPaneCommand(it, block) }
    }
}

/** "[verb]: Pane n", then " · " and the pane's title in [blocks] when it has one; null for a pane not in [tabPanes]. */
private fun paneCommandLabel(verb: String, tabPanes: List<String>, block: String, blocks: Map<String, BlockInfo>): String? {
    val label = paneLabel(tabPanes, block) ?: return null
    val title = blocks[block]?.title.orEmpty()
    return "$verb: $label" + if (title.isEmpty()) "" else " · $title"
}

/** How a remote's connection reads: the colour and the glyph of its mark. */
enum class ConnectionLevel { Up, Busy, Down }

/**
 * Where a remote's connection is, in the one wording of every surface that shows it: the
 * sidebar's dot and second line, the rail's mark, the empty pane area and the capsule over
 * paused panes. [words] name it, [level] colours it, and [canReconnect] says whether
 * "Reconnect" is offered. It is offered where the core waits to retry by itself, and tries
 * again at once. A first attempt that is under way has nothing to restart.
 */
enum class ConnectionPhase(val words: String, val level: ConnectionLevel, val canReconnect: Boolean) {
    Connected("Connected", ConnectionLevel.Up, canReconnect = false),
    Connecting("Connecting…", ConnectionLevel.Busy, canReconnect = false),
    Reconnecting("Reconnecting…", ConnectionLevel.Busy, canReconnect = true),
    Failed("Connection failed", ConnectionLevel.Down, canReconnect = true),
    Offline("Offline", ConnectionLevel.Down, canReconnect = true),
    /** The active remote the user removed: its connection is gone. */
    Removed("Removed", ConnectionLevel.Down, canReconnect = false),
}

/**
 * The phase of a connection whose core reports [status]. `idle` is the status before the core's
 * first report, so it is the first attempt too. A status this app does not know is Offline:
 * it is not taken for a working connection. The [removed] remote is Removed whatever it says.
 */
fun connectionPhase(status: String, removed: Boolean): ConnectionPhase = when {
    removed -> ConnectionPhase.Removed
    status == "connected" -> ConnectionPhase.Connected
    status == "connecting" || status == "idle" -> ConnectionPhase.Connecting
    status == "reconnecting" -> ConnectionPhase.Reconnecting
    status == "error" -> ConnectionPhase.Failed
    else -> ConnectionPhase.Offline
}

/** A connection's mark: its [level], the [word] for it, and whether "Reconnect" is offered. */
data class ConnectionMark(val level: ConnectionLevel, val word: String, val canReconnect: Boolean)

/**
 * The mark for a remote's connection [status] ([connectionPhase]). The rail draws it, and so do
 * the capsule over paused panes and the sidebar's dot, so all three read alike.
 */
fun connectionMark(status: String, removed: Boolean): ConnectionMark =
    connectionPhase(status, removed).let { ConnectionMark(it.level, it.words, it.canReconnect) }

/**
 * Whether the panes take no input because their connection [status] is down. A key typed then
 * would be dropped without a word, or reach the shell in part after the reconnect. Every
 * status but `connected` pauses, also one this app does not know.
 */
fun paneInputPaused(status: String): Boolean = status != "connected"

/**
 * The tab the workspace draws: the one the state names, else the first. The state may name no
 * tab yet, or one that has just closed.
 */
fun shownWindow(state: UiState): WindowInfo? = state.windows.firstOrNull { it.id == state.window } ?: state.windows.firstOrNull()

/**
 * The first pane of the tab the workspace draws ([shownWindow]), docked or floating
 * ([tabBlocks]): what [panesPaused] calls the shown tab's first pane. Null without a tab or a pane.
 */
fun shownFirstPane(state: UiState): String? = shownWindow(state)?.let(::tabBlocks)?.firstOrNull()

/**
 * The block the terminal input (the soft keyboard, the key bar, a hardware keyboard) sends to:
 * [pane], the pane the workspace on screen takes input for, when it has one. There is none
 * while the sidebar covers the panes ([panesHidden]): no key goes to a pane the user does not
 * see. There is none while the input is [paused] ([paneInputPaused]) either.
 */
fun inputTarget(panesHidden: Boolean, paused: Boolean, pane: String?): String? =
    if (panesHidden || paused) null else pane

/**
 * Whether panes are on screen while their input is [paused] ([paneInputPaused]): the shown tab
 * has a [firstPane] and the sidebar does not cover it ([panesHidden]). A key of a
 * hardware keyboard then goes nowhere (`hardwareKeyRoute`).
 */
fun panesPaused(panesHidden: Boolean, paused: Boolean, firstPane: String?): Boolean =
    paused && !panesHidden && firstPane != null
