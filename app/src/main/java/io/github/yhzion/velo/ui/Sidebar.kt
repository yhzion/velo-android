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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
 * One remote row as the sidebar draws it; [removed] is the active remote the user removed.
 * [needsAnswer]: its SSH login waits for the user's answer, or for Reconnect after none came
 * ([io.github.yhzion.velo.SshQuestions.needsAnswer]).
 */
class SidebarHost(val remote: Remote, val conn: HostConnection, val active: Boolean, val removed: Boolean, val needsAnswer: Boolean = false)

/** Status of the local phone, shown above (not as part of) the remote list. */
data class DeviceRow(val name: String, val line: String, val state: String)

fun deviceRow(state: TailnetState): DeviceRow {
    val word = when (state) {
        TailnetState.Off -> "Off"
        TailnetState.Starting -> "Starting"
        is TailnetState.NeedsLogin -> "Needs login"
        TailnetState.LoggedOut -> "Logged out"
        is TailnetState.Running -> "Running"
        is TailnetState.Error -> "Error"
    }
    val running = state as? TailnetState.Running
    val name = running?.name?.trim()?.takeUnless { it.isEmpty() }
        ?: "This Device"
    val ip = running?.ips?.firstOrNull()
    return DeviceRow(name, if (ip.isNullOrBlank()) word else "$ip · $word", word)
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
 * The sidebar of Rex for Mac: a toolbar of app-level controls (sidebar, activity, command
 * palette, settings), then This Device, one block per saved remote (its row and, when expanded,
 * a group per session: a header and one row per tab), the Rex servers found on the tailnet
 * and "Add Remote…". Activity and the filter cover every remote.
 */
@Composable
fun Sidebar(
    device: DeviceRow, hosts: List<SidebarHost>, modifier: Modifier, actions: SidebarActions,
    /** The gear's dot: an update was found that the Updates page has not shown yet. */
    settingsBadge: Boolean = false,
) = VeloSurface(VeloTheme.colors.sidebar) {
    val s = VeloTheme.sizes
    var activityOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    // Session IDs are the server's UUIDs: one set serves every remote.
    var collapsedSessions by remember { mutableStateOf(emptySet<String>()) }
    // The one tab row that shows Close after a left swipe.
    var revealed by remember { mutableStateOf<String?>(null) }

    Column(modifier.clip(rounded(VeloTheme.radii.card)).background(VeloTheme.surface.surface).padding(horizontal = s.rowInset)) {
        // Rex for Mac lights the eye's dot only while some tab's row draws its unread dot (a
        // finished, failed or waiting report not yet seen); seeing the last one puts it out.
        val anyUnread = hosts.any { host ->
            val connected = host.conn.state.status == "connected"
            !host.removed && connected && sessionGroups(host.conn.state, host.active, emptySet(), true, "") { row ->
                wantsAttention(host.conn.activityReadState.activity(row, true))
            }.isNotEmpty()
        }
        Row(Modifier.fillMaxWidth().padding(vertical = s.menuPadding), verticalAlignment = Alignment.CenterVertically) {
            // Rex for Mac's hide-sidebar glyph is an SF Symbol like the capsule's, so it is drawn for this app too.
            GlassIconButton(ImageVector.vectorResource(R.drawable.ic_toolbar_sidebar), "Toggle Sidebar", actions.toggleSidebar)
            Spacer(Modifier.weight(1f))
            GlassCapsule {
                // Rex for Mac draws eye while Activity is off and eye.fill while it is on.
                CapsuleIcon(painterResource(if (activityOnly) R.drawable.ic_toolbar_eye_fill else R.drawable.ic_toolbar_eye),
                    "Toggle Activity", { activityOnly = !activityOnly }, selected = activityOnly, badge = anyUnread,
                    stateDescription = if (activityOnly) "On" else "Off")
                CapsuleIcon(painterResource(R.drawable.ic_toolbar_command), "Command Palette", actions.openPalette)
                CapsuleIcon(painterResource(R.drawable.ic_toolbar_gear), "Settings", actions.openSettings, badge = settingsBadge,
                    stateDescription = if (settingsBadge) "Update available" else null)
            }
        }

        DeviceHeader(device, actions.openDevice)
        HorizontalDivider(thickness = s.edge, color = VeloTheme.surface.divider)
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = s.field + s.gap * 2)) {
                for (host in hosts) {
                    val remote = host.remote
                    // A remote's id is its row key: distinct for every row, the removed one included.
                    val key = remote.id
                    val expanded = key !in collapsed
                    item(key = "h:$key") {
                        RemoteHeader(host, expanded, actions) { collapsed = if (key in collapsed) collapsed - key else collapsed + key }
                    }
                    if (!expanded || host.removed) continue
                    val state = host.conn.state
                    val connected = state.status == "connected"
                    val readState = host.conn.activityReadState
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
                            item(key = "p:$key${group.priority}") { SectionHeader(group.priority.title, Modifier.padding(start = s.gap)) }
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
                        }, color = VeloTheme.surface.secondary, style = VeloTheme.type.caption,
                            modifier = Modifier.padding(start = s.rowPadding, top = s.cellInset, bottom = s.menuPadding))
                    }
                }
                if (actions.found.isNotEmpty()) {
                    item(key = "found") { SectionHeader("FOUND ON TAILNET") }
                    items(actions.found, key = { "f:" + it.url }) { server -> FoundServerRow(server) { actions.addFound(server) } }
                }
                item(key = "add") {
                    ListRow(AddRemoteCommand, actions.addRemote, strong = true, leading = { RowIcon(Icons.Default.Add, null) })
                }
            }
            // The field lies on a band of the sidebar's own colour, from a gap above it to the
            // bottom: rows that scroll under it do not show beside or below it. The list's bottom
            // padding (the field and the band's two gaps) keeps its last row above the band.
            SearchField(query, { query = it }, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(VeloTheme.surface.surface).padding(vertical = s.gap), placeholder = "Filter")
        }
    }
}

/**
 * A session's header: its label (a tap collapses or expands its tabs, or selects a session
 * that shows none: [headerTap]), `+` for a new tab in it, and a `…` menu ([sessionMenu]). The
 * remote must be [connected] for `+` and the menu's items.
 */
@Composable
private fun SessionHeader(
    group: SessionGroup, connected: Boolean, onTap: () -> Unit, onNewTab: () -> Unit, onRename: () -> Unit, onClose: () -> Unit,
) {
    val folds = headerTap(group) == HeaderTap.Collapse
    var menu by remember { mutableStateOf(false) }
    SectionHeader(group.label, Modifier.padding(start = VeloTheme.sizes.gap), onClick = onTap,
        onClickLabel = if (!folds) "Select" else if (group.collapsed) "Expand" else "Collapse") {
        if (folds) Icon(if (group.collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, null,
            tint = VeloTheme.surface.secondary, modifier = Modifier.size(VeloTheme.sizes.iconSmall))
        RowIconButton(Icons.Default.Add, newTabDescription(group.label), onNewTab, enabled = connected)
        Box {
            RowIconButton(Icons.Default.MoreHoriz, sessionMenuDescription(group.label), { menu = true })
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
 * A tab's row: icon, name, folder and what its program reports. A tap selects it, a long
 * press opens its menu ([tabMenu]). The shown tab's row has an X; any other shows Close after
 * a left swipe ([TabRow.closeControl], [closeRevealed]) until a right swipe or a tap takes it away.
 * As in Rex for Mac, the icon of a tab with an unread waiting or failed report bounces
 * ([bouncesIcon]) and a waiting program's [message] takes turns with the name ([showsTicker]).
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
    // One status for the row: the mark draws it and the second line says it.
    val mark = activity.indicator
    val subtitle = tabRowSubtitle(mark, age, row.folder)
    val motion = rememberAnimatorScale()
    val ticker = message?.takeIf { connected && showsTicker(activity.badge, it) }
    val type = VeloTheme.type
    Box {
        ListRow(row.name, onSelect, Modifier.padding(start = VeloTheme.sizes.gap).pointerInput(threshold) {
            var drag = 0f
            detectHorizontalDragGestures(onDragStart = { drag = 0f },
                onDragEnd = { reveal(closeRevealed(shown, drag, threshold)) }) { _, dx ->
                if (swipes) revealFeel(shown, drag, drag + dx, threshold)?.let(feel)
                drag += dx
            }
        }, subtitle = subtitle, selected = row.shown, dimmed = !row.shown, onLongClick = { menu = true },
            titleContent = if (ticker != null) {
                { TitleTicker(row.name, ticker, type.body, if (row.shown) VeloTheme.surface.text else VeloTheme.surface.secondary, motion) }
            } else null,
            leading = {
                BouncingMark(connected && bouncesIcon(activity.badge), motion) {
                    TabProgramMark(activity.icon, tile = true, badge = activity.badge, status = mark)
                }
            }, trailing = {
                mark?.let { TabStatusMark(it) }
                when (row.closeControl) {
                    CloseControl.X -> RowIconButton(Icons.Default.Close, closeTabDescription(row.name), onClose, enabled = connected)
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

/** A Rex server found on the tailnet: one tap saves it as a remote. */
@Composable
fun FoundServerRow(server: DiscoveredServer, onAdd: () -> Unit) {
    ListRow(server.name, onAdd, onClickLabel = "Add remote", leading = { RowIcon(Icons.Default.Add, null) })
}

/** This Device: the app's own tailnet node. It has no connection and no sessions; a tap opens its own screen. */
@Composable
private fun DeviceHeader(device: DeviceRow, onClick: () -> Unit) {
    val s = VeloTheme.sizes
    val c = VeloTheme.surface
    val status = when (device.state) {
        "Running" -> Status.Ok
        "Starting", "Needs login" -> Status.Warning
        "Error" -> Status.Danger
        else -> Status.Off
    }
    // A compact two-line control above the scrolling remote list, not a full-height list row.
    Row(Modifier.fillMaxWidth().heightIn(min = s.control).clip(Capsule)
        .clickable(role = Role.Button, onClickLabel = "Tailscale settings", onClick = onClick)
        .semantics {
            contentDescription = if (device.name == "This Device") "This Device, ${device.line}"
                else "This Device, ${device.name}, ${device.line}"
        }.padding(horizontal = s.menuPadding),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Smartphone, null, tint = c.secondary, modifier = Modifier.size(s.iconSmall))
        Spacer(Modifier.width(s.gap))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = c.text, style = VeloTheme.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.line, color = c.secondary, style = VeloTheme.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusMark(status, "Node ${device.state}")
    }
}

/**
 * A remote's row: icon, label, connection dot, chevron and a `…` menu ([remoteMenu]). A remote
 * that is not connected is dimmed with its note ([remoteRowNote]); it can still be selected,
 * renamed and removed, and an ssh remote shows its SSH details.
 */
@Composable
private fun RemoteHeader(host: SidebarHost, expanded: Boolean, actions: SidebarActions, onChevron: () -> Unit) {
    val remote = host.remote
    val status = host.conn.state.status
    val note = remoteRowNote(status, host.removed, host.needsAnswer)
    val items = remoteMenu(status, host.removed, isSshLocator(remote.url))
    var menu by remember { mutableStateOf(false) }
    val name = remote.label.ifEmpty { "Remote" }
    // The row's pill is for the tab the panes show; the active remote is marked by its weight and icon, and says so here.
    ListRow(name, { actions.selectRemote(remote) }, Modifier.semantics { if (host.active) selected = true }, subtitle = note, strong = host.active,
        dimmed = note != null, enabled = !host.removed, leading = {
            val c = VeloTheme.surface
            RowIcon(Icons.Outlined.LaptopMac, "Remote", tint = when {
                host.active -> c.accent
                note != null -> c.secondary
                else -> c.text
            })
        }, trailing = {
            if (!host.removed) {
                ConnectionDot(status)
                RowIconButton(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    remoteFoldDescription(expanded, name), onChevron)
            }
            if (items.isNotEmpty()) Box {
                RowIconButton(Icons.Default.MoreHoriz, remoteMenuDescription(name), { menu = true })
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
        })
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
private fun ConnectionDot(status: String) {
    val mark = connectionMark(status, removed = false)
    StatusMark(levelStatus(mark.level), mark.word)
}
