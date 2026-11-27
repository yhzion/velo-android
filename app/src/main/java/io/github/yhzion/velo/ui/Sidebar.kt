package io.github.yhzion.velo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.AddToPhotos
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.PushPin as PushPinOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.R
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.isSshLocator
import io.github.yhzion.velo.core.remoteLabel
import io.github.yhzion.velo.ui.theme.*
import io.github.yhzion.velo.ui.theme.MenuItem as VeloMenuItem
import kotlinx.coroutines.delay

/**
 * One remote row as the sidebar draws it: the remote, its connection's [state] and the reports seen
 * on it ([readState]); [removed] is the active remote the user removed. [needsAnswer]: its SSH login
 * waits for the user's answer, or for Reconnect after none came ([io.github.yhzion.velo.SshQuestions.needsAnswer]).
 */
internal class SidebarHost private constructor(
    val remote: Remote, private val stateNow: () -> UiState, val readState: ActivityReadState, val active: Boolean, val removed: Boolean,
    val needsAnswer: Boolean,
) {
    /** Read where the sidebar draws it, so that a change of another remote's state redraws the sidebar, not the workspace. */
    val state: UiState get() = stateNow()

    constructor(remote: Remote, conn: HostConnection, active: Boolean, removed: Boolean, needsAnswer: Boolean = false) :
        this(remote, { conn.state }, conn.activityReadState, active, removed, needsAnswer)

    /** A remote of a fixed [state], such as a fidelity scene draws. */
    constructor(remote: Remote, state: UiState, readState: ActivityReadState, active: Boolean, removed: Boolean, needsAnswer: Boolean = false) :
        this(remote, { state }, readState, active, removed, needsAnswer)
}

/**
 * Status of the local phone, shown above (not as part of) the remote list: its [name], the [line]
 * under it, the node's [state] word, and its dot ([chip]): the Tailscale page's words and colour.
 */
data class DeviceRow(val name: String, val line: String, val state: String, val chip: TailnetChip) {
    /**
     * What the row says to a screen reader before its dot: its name and the line's address, not
     * the line's state word, so that the state is said once, in the dot's word (#134).
     */
    val description: String get() {
        val address = if (line == state) "" else line.removeSuffix(" · $state")
        return listOfNotNull("This Device", name.takeUnless { it == "This Device" }, address.ifBlank { null }).joinToString(", ")
    }
}

/**
 * The This Device row of the node's [state]. Its line says the dot's word, the Tailscale page's
 * chip ([tailnetChip]), except that a running node says Running; so while Android holds the network
 * back ([paused]) both say Paused, unless the node is off.
 */
fun deviceRow(state: TailnetState, paused: Boolean): DeviceRow {
    // The dot is the Tailscale page's chip, so its words and colour agree with that page in every state.
    val chip = tailnetChip(state, paused)
    val running = state as? TailnetState.Running
    val word = if (running != null && !paused) "Running" else chip.text
    val name = running?.name?.trim()?.takeUnless { it.isEmpty() }
        ?: "This Device"
    val ip = running?.ips?.firstOrNull()
    return DeviceRow(name, if (ip.isNullOrBlank()) word else "$ip · $word", word, chip)
}

/**
 * What the sidebar can ask the workspace to do. Each action acts on the unit its control sits
 * on: the app (toolbar), This Device, a remote, a session of it or a tab of that session.
 */
class SidebarActions(
    // App: the toolbar.
    val toggleSidebar: () -> Unit,
    val openSettings: () -> Unit,
    val openPalette: () -> Unit,
    /** Tapped the This Device row: its own screen. */
    val openDevice: () -> Unit,
    // Remote: its row, its `…` menu and the rows after the list.
    val selectRemote: (Remote) -> Unit,
    val newSession: (Remote) -> Unit,
    /** "Rename…": ask for the name it has on this phone. */
    val renameRemote: (Remote) -> Unit,
    /** "Remove Remote…": ask, then forget it on the phone. */
    val removeRemote: (Remote) -> Unit,
    /** "SSH details" of an ssh remote: what the phone remembers for its login, each with a Forget ([SshDetailsPanel]). */
    val sshDetails: (Remote) -> Unit,
    /** "Add Remote…": the dialog with a label, a URL and the QR scanner. */
    val addRemote: () -> Unit,
    /** Rex servers on the tailnet that are not saved yet ("Found on tailnet"). */
    val found: List<DiscoveredServer>,
    /** Tapped a found server: save it and connect. */
    val addFound: (DiscoveredServer) -> Unit,
    // Session: its header.
    /** Tapped the header of a session with no tab row to tap ([headerTap]): show that session. */
    val selectSession: (Remote, session: String) -> Unit,
    val newTab: (Remote, session: String) -> Unit,
    val renameSession: (Remote, SessionGroup) -> Unit,
    /** "Close Session": always asks first. */
    val closeSession: (Remote, SessionGroup) -> Unit,
    // Tab: its row.
    val selectTab: (Remote, TabRow) -> Unit,
    val renameTab: (Remote, TabRow) -> Unit,
    /** "Move Tab Forward" (one place toward the end of its session) or "Move Tab Backward". */
    val moveTab: (Remote, TabRow, forward: Boolean) -> Unit,
    /** Close the tab: at once when it is known to hold only a shell, else after asking ([closeTabQuestion]). */
    val closeTab: (Remote, TabRow) -> Unit,
    /** App: the toolbar's pin, where the window can dock the sidebar. Docks it beside the panes or undocks it ([toggleSidebarDock]). */
    val toggleDock: () -> Unit = {},
)

/** The server as Rex for Mac names a remote: `account@name`. */
fun hostName(state: UiState): String {
    val host = if (state.server.isBlank()) "Rex" else remoteLabel(state.server)
    return if (state.account.isEmpty()) host else "${state.account}@$host"
}

/**
 * The second line of a remote row: the words of its connection's phase ([connectionPhase]) in
 * lower case, as a row's note reads, and nothing while it is connected. "Removed" stays as it
 * is, for the active remote the user removed. The core retries by itself, so a row needs no tap.
 * An SSH login that [needsAnswer] says so instead: its question is on screen, or went
 * unanswered and waits for Reconnect, which the pool reports as a cancel the user never made.
 */
fun remoteRowNote(status: String, removed: Boolean, needsAnswer: Boolean = false): String? = when (val phase = connectionPhase(status, removed)) {
    ConnectionPhase.Connected -> null
    ConnectionPhase.Removed -> phase.words
    else -> if (needsAnswer) NEEDS_ANSWER_NOTE else phase.words.lowercase()
}

/**
 * What the sidebar keeps while it is open: whether Activity is on, the filter's text, the remotes
 * ([collapsed], by id) and sessions ([collapsedSessions], by the server's ids: one set serves every
 * remote) folded away, and the one tab row that shows Close after a left swipe ([revealed]).
 */
@Stable
internal class SidebarState(collapsedSessions: Set<String> = emptySet()) {
    var activityOnly by mutableStateOf(false)
    var query by mutableStateOf("")
    var collapsed by mutableStateOf(emptySet<String>())
    var collapsedSessions by mutableStateOf(collapsedSessions)
    var revealed by mutableStateOf<String?>(null)
}

/** A length the Sidebar board gives, at the current size ([VeloTheme.medium]). */
private val Int.bd: Dp @Composable @ReadOnlyComposable get() = VeloTheme.medium(this.dp)

/**
 * The sidebar of Rex for Mac, as the Sidebar board draws it: a toolbar of app-level controls
 * (sidebar, the pin that docks it where the window can, activity, command palette, settings), then This Device, one block per saved remote
 * (its row and, when expanded, a group per session: a header and one row per tab), the Rex servers
 * found on the tailnet and "Add Remote…", over the filter. Activity and the filter cover every remote.
 * Every part carries the board's name as its test tag.
 */
@Composable
internal fun Sidebar(
    device: DeviceRow, hosts: List<SidebarHost>, modifier: Modifier, actions: SidebarActions,
    /** The gear's dot: an update was found that the Updates page has not shown yet. */
    settingsBadge: Boolean = false,
    /** The pin beside the toggle ([dockSwitch]): whether the sidebar is docked beside the panes; null draws no pin. */
    docked: Boolean? = null,
    /** The notices it holds under its Filter field while it covers the panes ([Workspace]): the list makes room for them. */
    notices: (@Composable ColumnScope.() -> Unit)? = null,
    state: SidebarState = remember { SidebarState() },
) = VeloSurface(VeloTheme.colors.sidebar) {
    val c = VeloTheme.surface
    var activityOnly by state::activityOnly
    var query by state::query
    var collapsed by state::collapsed
    var collapsedSessions by state::collapsedSessions
    var revealed by state::revealed

    // The Filter's band: the field and the board's 12 around it. The notices go under it while the list keeps that much.
    SidebarColumn(searchFieldHeight + 24.bd, modifier.testTag("sidebar").clip(rounded(28.bd)).background(c.surface), head = { Column {
        // Rex for Mac lights the eye's dot only while some tab's row draws its unread dot (a
        // finished, failed or waiting report not yet seen); seeing the last one puts it out.
        val anyUnread = hosts.any { host ->
            val connected = host.state.status == "connected"
            !host.removed && connected && sessionGroups(host.state, host.active, emptySet(), true, "") { row ->
                wantsAttention(host.readState.activity(row, true))
            }.isNotEmpty()
        }
        // Each block of the sidebar keeps the board's height as its least, its content within it: the
        // half pixels of the board's paddings at this density do not add up from one block to the next.
        Row(Modifier.testTag("sidebar-toolbar").fillMaxWidth().heightIn(min = 72.bd).padding(start = 12.bd, end = 12.bd, top = 10.bd, bottom = 6.bd),
            verticalAlignment = Alignment.CenterVertically) {
            // Rex for Mac's hide-sidebar glyph is an SF Symbol like the capsule's, so it is drawn for this app too.
            GlassIconButton(ImageVector.vectorResource(R.drawable.ic_toolbar_sidebar), "Toggle Sidebar", actions.toggleSidebar,
                Modifier.testTag("sidebar-toggle"))
            // Only where the window can dock the sidebar (the unfolded screen upright): a push pin, filled while docked.
            if (docked != null) {
                Spacer(Modifier.width(8.bd))
                GlassIconSwitch(if (docked) Icons.Filled.PushPin else Icons.Outlined.PushPinOutline, DockSidebarSwitch, docked,
                    { actions.toggleDock() }, Modifier.testTag("sidebar-dock"))
            }
            Spacer(Modifier.weight(1f))
            GlassCapsule(Modifier.testTag("sidebar-actions")) {
                // Rex for Mac draws eye while Activity is off and eye.fill while it is on.
                CapsuleIcon(painterResource(if (activityOnly) R.drawable.ic_toolbar_eye_fill else R.drawable.ic_toolbar_eye),
                    "Toggle Activity", { activityOnly = !activityOnly }, Modifier.testTag("activity-toggle"), selected = activityOnly,
                    badge = anyUnread, stateDescription = if (activityOnly) "On" else "Off")
                CapsuleIcon(painterResource(R.drawable.ic_toolbar_command), "Command Palette", actions.openPalette,
                    Modifier.testTag("command-palette-button"))
                CapsuleIcon(painterResource(R.drawable.ic_toolbar_gear), "Settings", actions.openSettings, Modifier.testTag("settings-gear"),
                    badge = settingsBadge, stateDescription = if (settingsBadge) "Update available" else null,
                    badgeModifier = Modifier.testTag("update-dot"))
            }
        }

        DeviceHeader(device, actions.openDevice)
    } }, body = {
        Box {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VeloTheme.sizes.field + 28.bd)) {
                for (host in hosts) {
                    val remote = host.remote
                    // A remote's id is its row key: distinct for every row, the removed one included.
                    val key = remote.id
                    val expanded = key !in collapsed
                    item(key = "h:$key") {
                        RemoteHeader(host, expanded, actions) { collapsed = if (key in collapsed) collapsed - key else collapsed + key }
                    }
                    if (!expanded || host.removed) continue
                    val state = host.state
                    val connected = state.status == "connected"
                    val readState = host.readState
                    fun tabRow(row: TabRow) = item(key = "t:$key${row.session}${row.tab}") {
                        val id = "$key${row.session}${row.tab}"
                        // The session's whole order, also while a filter hides rows: a hidden neighbour is still a place.
                        val order = sessionTabOrder(state, row.session)
                        TabRowView(row, connected, readState.activity(row, connected), tabMessage(state, row), revealed == id,
                            forward = tabStep(order, row.tab, true) != null, backward = tabStep(order, row.tab, false) != null,
                            onReveal = { show -> if (show) revealed = id else if (revealed == id) revealed = null },
                            onSelect = { revealed = null; actions.selectTab(remote, row) },
                            onRename = { actions.renameTab(remote, row) },
                            onMove = { forward -> actions.moveTab(remote, row, forward) },
                            onClose = { revealed = null; actions.closeTab(remote, row) })
                    }
                    // Activity on: Rex for Mac groups by priority (Blocked, Failed, Working, Done) across sessions.
                    val empty = if (activityOnly) {
                        val groups = activityGroups(state, host.active, query) { readState.activity(it, connected) }
                        for (group in groups) {
                            item(key = "p:$key${group.priority}") { GroupHeader(group.priority.title) }
                            group.rows.forEach(::tabRow)
                        }
                        groups.isEmpty()
                    } else {
                        val groups = sessionGroups(state, host.active, collapsedSessions, false, query)
                        for (group in groups) {
                            item(key = "s:$key${group.id}") {
                                SessionHeader(group, connected,
                                    onTap = {
                                        when (headerTap(group)) {
                                            HeaderTap.Collapse -> collapsedSessions = if (group.collapsed) collapsedSessions - group.id else collapsedSessions + group.id
                                            HeaderTap.Select -> actions.selectSession(remote, group.id)
                                        }
                                    },
                                    onNewTab = { actions.newTab(remote, group.id) },
                                    onRename = { actions.renameSession(remote, group) },
                                    onClose = { actions.closeSession(remote, group) })
                            }
                            group.rows.forEach(::tabRow)
                        }
                        groups.isEmpty()
                    }
                    // An offline row says so on its own line; under it only what it still has is listed.
                    if (connected && empty) item(key = "e:$key") {
                        Text(when {
                            state.sessions.isEmpty() -> "No sessions"
                            activityOnly -> "Nothing is working or waiting."
                            else -> "No matching tab"
                        }, color = c.secondary, style = VeloTheme.type.caption,
                            modifier = Modifier.padding(start = 20.bd, top = VeloTheme.sizes.cellInset, bottom = VeloTheme.sizes.menuPadding))
                    }
                }
                if (actions.found.isNotEmpty()) {
                    item(key = "found") {
                        Text("FOUND ON TAILNET", Modifier.testTag("found-on-tailnet-header").fillMaxWidth().semantics { heading() }
                            .heightIn(min = 36.bd).padding(start = 20.bd, end = 20.bd, top = 16.bd, bottom = 5.bd),
                            style = VeloTheme.type.eyebrow.naturalLines(), color = c.secondary, maxLines = 1)
                    }
                    items(actions.found, key = { "f:" + it.url }) { server ->
                        FoundServerRow(server, Modifier.testTag("found-row-${server.name}")) { actions.addFound(server) }
                    }
                }
                item(key = "add") {
                    ListRow(AddRemoteCommand, actions.addRemote, Modifier.testTag("add-remote-row"), minHeight = 48.bd,
                        leading = { Icon(Icons.Default.Add, null, Modifier.size(20.bd), tint = c.text) })
                }
            }
            // The field lies on a band of the sidebar's own colour, the board's 12 around it: rows that
            // scroll under it do not show beside or below it. The list's bottom padding keeps its last
            // row above the band.
            SearchField(query, { query = it }, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(c.surface).padding(12.bd).testTag("sidebar-filter"), placeholder = "Filter",
                inputModifier = Modifier.testTag("sidebar-filter-input").heightIn(min = 21.bd))
        }
    }, notices = notices?.let { content ->
        {
            // On the band under the field, the board's 12 from its edges: the list above grows shorter.
            Column(Modifier.fillMaxWidth().padding(start = 12.bd, end = 12.bd, bottom = 12.bd), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(VeloTheme.sizes.gap), content = content)
        }
    })
}

/**
 * The sidebar's column: its [head] (the toolbar and This Device), then its [body] (the list, the Filter field at its
 * foot) in the height left, and the [notices] at its foot, which the body makes room for while it keeps [least] beside
 * them ([sidebarBodyHeight]); else they lie just above the Filter's band, over the list or, where it is shorter
 * than them, over the head ([sidebarNoticesTop]). They
 * are the same children whichever way they lie, so a keyboard that opens from the Filter field only places them again
 * (#195): they are not composed elsewhere, which a screen reader would read again, nor over the field, which Compose
 * then leaves out of what a screen reader sees, taking the field's focus away. Only a body too short for the band,
 * where the field has no room of its own (#209), or notices taller than the room above it, has them over the band.
 * The notices keep their own height: only the body is cut.
 */
@Composable
private fun SidebarColumn(least: Dp, modifier: Modifier, head: @Composable () -> Unit, body: @Composable () -> Unit,
                          notices: (@Composable () -> Unit)?) {
    Layout(listOf(head, body, notices ?: {}), modifier) { (heads, bodies, notes), constraints ->
        val width = constraints.maxWidth
        val top = heads.map { it.measure(Constraints(maxWidth = width, maxHeight = constraints.maxHeight)) }
        val topHeight = top.sumOf { it.height }
        val stack = notes.map { it.measure(Constraints(maxWidth = width)) }
        val stackHeight = stack.sumOf { it.height }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else topHeight + least.roundToPx() + stackHeight
        val bodyHeight = sidebarBodyHeight(height, topHeight, stackHeight, least.roundToPx())
        val inside = bodies.map { it.measure(Constraints(maxWidth = width, minHeight = bodyHeight, maxHeight = bodyHeight)) }
        val all = top + inside + stack
        layout(constraints.constrainWidth(all.maxOfOrNull { it.width } ?: 0), constraints.constrainHeight(height)) {
            var y = 0
            (top + inside).forEach { it.placeRelative(0, y); y += it.height }
            // Placed last, so over the body when it keeps the height under them.
            var at = sidebarNoticesTop(height, topHeight, stackHeight, least.roundToPx())
            stack.forEach { it.placeRelative(0, at); at += it.height }
        }
    }
}

/** The heading of one of Activity's groups (Blocked, Failed, Working, Done), as a session's header reads. */
@Composable
private fun GroupHeader(text: String) {
    Text(text, Modifier.fillMaxWidth().heightIn(min = 44.bd).semantics { heading() }.padding(start = 20.bd, top = 13.bd, bottom = 13.bd),
        style = VeloTheme.type.header.naturalLines(), color = VeloTheme.colors.textOnRaised, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A session's header (Sidebar board, session-header): its label (a tap collapses or expands its
 * tabs, or selects a session that shows none: [headerTap]) with the chevron that says which, `+`
 * for a new tab in it, and a `…` menu ([sessionMenu]). The remote must be [connected] for `+` and
 * the menu's items.
 */
@Composable
private fun SessionHeader(
    group: SessionGroup, connected: Boolean, onTap: () -> Unit, onNewTab: () -> Unit, onRename: () -> Unit, onClose: () -> Unit,
) {
    val folds = headerTap(group) == HeaderTap.Collapse
    val label = group.label
    var menu by remember { mutableStateOf(false) }
    val button = Modifier.size(40.bd, 44.bd)
    Row(
        Modifier.testTag("session-header-$label").fillMaxWidth().height(44.bd)
            .clickable(onClickLabel = if (!folds) "Select" else if (group.collapsed) "Expand" else "Collapse", onClick = onTap)
            .semantics { heading() }
            .padding(start = 20.bd, end = 4.bd),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.bd),
    ) {
        Text(label, Modifier.weight(1f).testTag("session-name-$label"), style = VeloTheme.type.header.naturalLines(),
            color = VeloTheme.colors.textOnRaised, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // The chevron says what a tap on the header does; the header takes the tap.
        if (folds) Box(button.testTag("session-collapse-$label"), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ExpandMore, null, Modifier.size(VeloTheme.sizes.iconSmall).rotate(if (group.collapsed) -90f else 0f),
                tint = VeloTheme.surface.secondary)
        }
        RowIconButton(Icons.Default.Add, newTabDescription(label), onNewTab, button.testTag("session-new-tab-$label"), enabled = connected)
        Box {
            RowIconButton(Icons.Default.MoreHoriz, sessionMenuDescription(label), { menu = true }, button.testTag("session-menu-$label"))
            VeloMenu(menu, onDismissRequest = { menu = false }) {
                for (item in sessionMenu(connected)) {
                    val close = item.action == SessionAction.Close
                    VeloMenuItem(item.label, { menu = false; if (close) onClose() else onRename() },
                        icon = if (close) Icons.Default.Close else Icons.Default.Edit, enabled = item.enabled, destructive = close)
                }
            }
        }
    }
}

/**
 * A tab's row ([TabListRow]): its tile ([tabTile]), name, what its program reports and its folder,
 * and at its end what the program does now ([rowMark]). A tap selects it, a long press opens its
 * menu ([tabMenu]). The shown tab's row has an X; any other shows Close after a left swipe
 * ([TabRow.closeControl], [closeRevealed]) until a right swipe or a tap takes it away. As in Rex for
 * Mac, the tile of a tab with an unread waiting or failed report bounces ([bouncesIcon]) and a
 * waiting program's [message] takes turns with the name ([showsTicker]).
 */
@Composable
private fun TabRowView(
    row: TabRow, connected: Boolean, activity: TabActivity, message: String?, revealed: Boolean,
    forward: Boolean, backward: Boolean, onReveal: (Boolean) -> Unit,
    onSelect: () -> Unit, onRename: () -> Unit, onMove: (forward: Boolean) -> Unit, onClose: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val shown by rememberUpdatedState(revealed)
    val feel = rememberFeel()
    // Felt only where letting go shows or hides Close: the shown tab has its X, and offline there is no Close (#309).
    val swipes by rememberUpdatedState(row.closeControl == CloseControl.Swipe && connected)
    val reveal by rememberUpdatedState(onReveal)
    val since = activity.since
    val age = if (connected && since != null && activity.indicator != TabIndicator.Idle) {
        val reportAge by produceState(reportAge(since, System.currentTimeMillis()), since) {
            while (true) {
                value = reportAge(since, System.currentTimeMillis())
                delay(1_000)
            }
        }
        reportAge
    } else {
        null
    }
    // One status for the row: the tile's dot, the mark at its end and the second line say it together.
    val mark = activity.indicator
    val subtitle = tabRowSubtitle(mark, age, row.folder)
    val motion = rememberAnimatorScale()
    val ticker = message?.takeIf { connected && showsTicker(activity.badge, it) }
    val type = VeloTheme.type
    val tile = tabTile(row, activity)
    val said = tabRowTileSpeech(tile, activity.icon ?: row.icon, row.name, activity.badge, mark)
    val id = row.tab
    Box {
        TabListRow(row.name, onSelect, Modifier.padding(horizontal = 8.bd).pointerInput(threshold) {
            var drag = 0f
            detectHorizontalDragGestures(onDragStart = { drag = 0f },
                onDragEnd = { reveal(closeRevealed(shown, drag, threshold)) }) { _, dx ->
                if (swipes) revealFeel(shown, drag, drag + dx, threshold)?.let(feel)
                drag += dx
            }
        }, subtitle = subtitle, selected = row.shown, onLongClick = { menu = true }, id = id, titleSaid = said.nameSaid,
            titleContent = if (ticker != null) {
                { TitleTicker(row.name, ticker, type.body.naturalLines(), VeloTheme.surface.text, motion) }
            } else null,
            tile = {
                BouncingMark(connected && bouncesIcon(activity.badge), motion) {
                    TabTile(tile.brand, panes = tile.panes, dot = tile.dot, ring = tabRowFill(row.shown), tag = "tab-tile-$id",
                        contentDescription = said.description, stateDescription = said.state)
                }
            }, trailing = {
                // The second line says what the program does now: the marks are drawn only (#150).
                when (rowMark(mark)) {
                    RowMark.Working -> WorkingDots(5.bd, 4.bd, Modifier.testTag("tab-working-$id").padding(end = 8.bd), description = null)
                    RowMark.Waiting -> Box(Modifier.padding(end = 10.bd).testTag("tab-attention-$id").size(9.bd)
                        .background(VeloTheme.surface.warning, CircleShape))
                    RowMark.Offline -> OfflineMark(description = null)
                    null -> Unit
                }
                when (row.closeControl) {
                    CloseControl.X -> RowIconButton(Icons.Default.Close, closeTabDescription(row.name), onClose,
                        Modifier.size(40.bd, 44.bd).testTag("tab-close-$id"), enabled = connected)
                    CloseControl.Swipe -> if (revealed && connected) VeloButton("Close", onClose, kind = ButtonKind.Destructive)
                }
            })
        VeloMenu(menu, onDismissRequest = { menu = false }) {
            for (item in tabMenu(connected, forward, backward)) {
                val close = item.action == TabAction.Close
                // The list runs top to bottom, so forward points down and backward up.
                val icon = when (item.action) {
                    TabAction.Rename -> Icons.Default.Edit
                    TabAction.MoveForward -> Icons.Default.ArrowDownward
                    TabAction.MoveBackward -> Icons.Default.ArrowUpward
                    TabAction.Close -> Icons.Default.Close
                }
                VeloMenuItem(item.label, {
                    menu = false
                    when (item.action) {
                        TabAction.Rename -> onRename()
                        TabAction.MoveForward -> onMove(true)
                        TabAction.MoveBackward -> onMove(false)
                        TabAction.Close -> onClose()
                    }
                }, icon = icon, enabled = item.enabled, destructive = close)
            }
        }
    }
}

/** A Rex server found on the tailnet (Sidebar board, found-row): one tap saves it as a remote. */
@Composable
fun FoundServerRow(server: DiscoveredServer, modifier: Modifier = Modifier, onAdd: () -> Unit) {
    ListRow(server.name, onAdd, modifier, onClickLabel = "Add remote", minHeight = 48.bd,
        leading = { Icon(Icons.Default.Add, null, Modifier.size(20.bd), tint = VeloTheme.surface.text) })
}

/**
 * This Device (Sidebar board, this-device-row): the app's own tailnet node, with a line under it.
 * It has no connection and no sessions; a tap opens its own screen.
 */
@Composable
internal fun DeviceHeader(device: DeviceRow, onClick: () -> Unit) {
    val c = VeloTheme.surface
    val type = VeloTheme.type
    val line = VeloTheme.sizes.edge
    // A compact two-line control above the scrolling remote list, not a full-height list row.
    Row(Modifier.padding(horizontal = 12.bd).testTag("this-device-row").fillMaxWidth()
        .drawBehind { drawRect(c.divider, Offset(0f, size.height - line.toPx()), Size(size.width, line.toPx())) }
        .clickable(role = Role.Button, onClickLabel = "Tailscale settings", onClick = onClick)
        // The row says the name and address, and the dot after it says the state; the two texts
        // are hidden from accessibility, or a screen reader would say them as well (#134).
        .semantics { contentDescription = device.description }.heightIn(min = 53.bd).padding(start = 4.bd, end = 4.bd, top = 6.bd, bottom = 12.bd),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bd)) {
        Icon(Icons.Outlined.Smartphone, null, Modifier.size(20.bd), tint = c.secondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.bd)) {
            Text(device.name, Modifier.testTag("this-device-name").fillMaxWidth().semantics { hideFromAccessibility() }, color = c.text, style = type.header.copy(fontWeight = FontWeight.SemiBold).naturalLines(),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.line, Modifier.testTag("this-device-status").fillMaxWidth().semantics { hideFromAccessibility() }, color = c.secondary, style = type.caption.naturalLines(),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusMark(device.chip.status, device.chip.text, Modifier.testTag("this-device-dot"))
    }
}

/**
 * A remote's row (Sidebar board, remote-row): icon, label, connection dot, chevron and a `…` menu
 * ([remoteMenu]). A remote that is not connected is dimmed with its note ([remoteRowNote]); it can
 * still be selected, renamed and removed, and an ssh remote shows its SSH details.
 */
@Composable
private fun RemoteHeader(host: SidebarHost, expanded: Boolean, actions: SidebarActions, onChevron: () -> Unit) {
    val remote = host.remote
    val status = host.state.status
    val note = remoteRowNote(status, host.removed, host.needsAnswer)
    val items = remoteMenu(status, host.removed, isSshLocator(remote.url))
    var menu by remember { mutableStateOf(false) }
    val name = remote.label.ifEmpty { "Remote" }
    val c = VeloTheme.surface
    val button = Modifier.size(40.bd, 48.bd)
    // The row's pill is for the tab the panes show; the active remote is marked by its icon's colour, and says so here.
    Row(Modifier.testTag("remote-row-$name").fillMaxWidth()
        .clickable(enabled = !host.removed) { actions.selectRemote(remote) }
        .semantics { if (host.active) selected = true }
        .heightIn(min = 56.bd).padding(start = 18.bd, end = 4.bd, top = 4.bd),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bd)) {
        Icon(Icons.Outlined.LaptopMac, "Remote", Modifier.size(22.bd), tint = when {
            host.active -> c.accent
            note != null -> c.secondary
            else -> c.text
        })
        Column(Modifier.weight(1f)) {
            Text(name, Modifier.testTag("remote-name-$name").fillMaxWidth(), style = VeloTheme.type.bodyStrong.naturalLines(), maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = when {
                    host.removed -> c.disabled
                    note != null -> c.secondary
                    else -> c.text
                })
            if (note != null) Text(note, style = VeloTheme.type.caption.naturalLines(), color = if (host.removed) c.disabled else c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!host.removed) {
            ConnectionDot(status, Modifier.testTag("remote-dot-$name"))
            RowIconButton(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                remoteFoldDescription(expanded, name), onChevron, button.testTag("remote-collapse-$name"))
        }
        if (items.isNotEmpty()) Box {
            RowIconButton(Icons.Default.MoreHoriz, remoteMenuDescription(name), { menu = true }, button.testTag("remote-menu-$name"))
            VeloMenu(menu, onDismissRequest = { menu = false }) {
                for (item in items) VeloMenuItem(item.label, onClick = {
                    menu = false
                    when (item.action) {
                        RemoteAction.NewSession -> actions.newSession(remote)
                        RemoteAction.Rename -> actions.renameRemote(remote)
                        RemoteAction.SshDetails -> actions.sshDetails(remote)
                        RemoteAction.Remove -> actions.removeRemote(remote)
                    }
                }, icon = remoteActionIcon(item.action), enabled = item.enabled, destructive = item.action == RemoteAction.Remove)
            }
        }
    }
}

/** The icon of a remote menu's item; the Saved list in Settings gives its items the same. */
internal fun remoteActionIcon(action: RemoteAction) = when (action) {
    RemoteAction.NewSession -> Icons.Outlined.AddToPhotos
    RemoteAction.Rename -> Icons.Default.Edit
    RemoteAction.SshDetails -> Icons.Outlined.Key
    RemoteAction.Remove -> Icons.Default.Delete
}

/**
 * The connection's mark as a dot ([connectionMark]): the colour of its level and its words, the
 * same as on the rail. The removed remote's row has none.
 */
@Composable
private fun ConnectionDot(status: String, modifier: Modifier = Modifier) {
    val mark = connectionMark(status, removed = false)
    StatusMark(levelStatus(mark.level), mark.word, modifier)
}
