package io.github.yhzion.velo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.R
import io.github.yhzion.velo.SshKey
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.isSshLocator
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.MenuDivider
import io.github.yhzion.velo.ui.theme.MenuItem
import io.github.yhzion.velo.ui.theme.RowIconButton
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.statusColor
import kotlinx.coroutines.withContext

/** What the Remotes row of the phone key says while the keys are [list]: nothing while they load. */
internal fun phoneKeyLine(list: KeyList): String = when (list) {
    KeyList.Loading -> ""
    KeyList.Unavailable -> PHONE_KEY_UNAVAILABLE
    is KeyList.Ready -> list.keys.firstOrNull { it.phone }?.let(::keySummary) ?: PHONE_KEY_NOT_MADE
}

/** The phone key row when the keys were read and the phone has none yet. */
internal const val PHONE_KEY_NOT_MADE = "Made when a login first needs it"

/** The phone key row when the Keystore cannot open the keys now ([KEYS_UNAVAILABLE] on the key page). */
internal const val PHONE_KEY_UNAVAILABLE = "Can't be read right now"

/**
 * What the SSH key row says of [key]: its type without its prefix or curve ("ed25519", "rsa",
 * "ecdsa"), and the ends of its fingerprint, which tell keys apart at a glance.
 */
fun keySummary(key: SshKey): String {
    val type = key.publicLine.trim().substringBefore(' ').removePrefix("ssh-").substringBefore('-')
    val print = key.fingerprint.removePrefix("SHA256:")
    val shown = when {
        print.isEmpty() -> null
        print.length <= 8 -> key.fingerprint
        else -> "SHA256:${print.take(4)}…${print.takeLast(4)}"
    }
    return listOfNotNull(type, shown).joinToString(" · ")
}

/** The phone key's fingerprint on its card: the first twelve and the last five of its digest, or all of a short one. */
fun cardFingerprint(key: SshKey): String {
    val print = key.fingerprint.removePrefix("SHA256:")
    return if (print.length <= 17) key.fingerprint else "SHA256:${print.take(12)}…${print.takeLast(5)}"
}

/**
 * The Tailscale row's dot: the Tailscale page's chip ([tailnetChip]), as the sidebar's This Device
 * dot is, so the three agree in every state; none while the node is off.
 */
internal fun remotesDotStatus(state: TailnetState, paused: Boolean): Status? =
    if (state == TailnetState.Off) null else tailnetChip(state, paused).status

/**
 * Remotes (canvas SetRemotes block): This Device's Tailscale node and the phone's SSH key, each a
 * row that opens its page, then the saved remotes with their menus and forwarded ports, and Add
 * Remote… as the group's last row (owner decision: a row, not a button).
 */
@Composable
internal fun RemoteSettings(host: SettingsHost, openDevice: () -> Unit, openSshKey: () -> Unit) {
    val opened = LocalSettingsOpen.current
    var adding by rememberSaveable { mutableStateOf(opened.addingRemote != null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    val c = VeloTheme.surface
    val state = host.tailnetState
    // Reading the phone key is Keystore work: off the main thread, as the key page reads it.
    var reads by remember { mutableIntStateOf(0) }
    val keys by produceState<KeyList>(KeyList.Loading, host, reads) {
        value = withContext(host.io) { readKeys(host.sshSecrets::keys) }
    }
    // Back on screen: a key the Keystore could not open is read again, as the key page does.
    LifecycleResumeEffect(Unit) {
        if (keys == KeyList.Unavailable) reads++
        onPauseOrDispose {}
    }
    Column(Modifier.fillMaxWidth().testTag("remotes-content").padding(bottom = VeloTheme.space.s24)) {
        GroupHeader("This Device", Modifier.testTag("remotes-this-device-header"), first = true)
        GroupCard(Modifier.testTag("remotes-this-device-group")) {
            PageRow(
                "remotes-tailscale", "Tailscale", tailnetStatusLine(state, host.tailnetPaused),
                painterResource(R.drawable.ic_brand_tailscale), c.text, openDevice,
            ) {
                remotesDotStatus(state, host.tailnetPaused)?.let {
                    Box(Modifier.testTag("remotes-tailscale-dot").size(VeloTheme.space.s8).clip(Capsule).background(statusColor(it)))
                }
            }
        }
        // The key ssh:// logins offer first: one for the phone, whatever remote it logs in to.
        GroupHeader(SSH_GROUP, Modifier.testTag("remotes-ssh-header"))
        GroupCard(Modifier.testTag("remotes-ssh-group")) {
            PageRow("remotes-ssh-key", SSH_KEY_ROW, phoneKeyLine(keys),
                rememberVectorPainter(Icons.Default.Key), c.text, openSshKey, line = "fingerprint")
        }
        GroupHeader("Saved", Modifier.testTag("remotes-saved-header"))
        GroupCard(Modifier.testTag("remotes-saved-group")) {
            for (remote in host.saved) {
                key(remote.id) {
                    SavedRemoteRow(remote, menuOpen = opened.remoteMenu == remote.id) { action ->
                        when (action) {
                            RemoteAction.Rename -> renamingId = remote.id
                            RemoteAction.SshDetails -> detailsId = remote.id
                            RemoteAction.Remove -> removingId = remote.id
                            RemoteAction.NewSession -> Unit
                        }
                    }
                    // The remote's ports forwarded to the phone (#226): they stay until stopped here.
                    for (forward in host.forwards(remote)) {
                        key(forward.block) {
                            Row(
                                Modifier.groupBleed().fillMaxWidth().heightIn(min = VeloTheme.sizes.row).groupInset().padding(start = VeloTheme.space.s16),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = VeloTheme.space.s8)) {
                                    Text(forward.label, style = VeloTheme.type.body, color = c.text)
                                    Text(forwardDetail(forward), style = VeloTheme.type.caption, color = c.secondary)
                                }
                                RowIconButton(Icons.Default.Close, "Stop forwarding ${forward.label}", { host.stopForward(remote, forward.block) })
                            }
                            GroupDivider()
                        }
                    }
                }
            }
            AddRemoteRow { adding = true }
        }
    }
    if (adding) {
        val draft = opened.addingRemote ?: RemoteDraft()
        AddRemoteDialog(add = host::addRemote, onDismiss = { adding = false }, label = draft.label, address = draft.address)
    }
    host.saved.firstOrNull { it.id == removingId }?.let { remote ->
        RemoveRemoteDialog(remote, remove = { host.removeRemote(remote.id) }, onDismiss = { removingId = null })
    }
    host.saved.firstOrNull { it.id == renamingId }?.let { remote ->
        RenameDialog(
            "Rename Remote", remote.label, allowEmpty = false,
            onRename = { host.renameRemote(remote.id, it) }, onDismiss = { renamingId = null },
        )
    }
    // A remote removed meanwhile takes its panel with it.
    host.saved.firstOrNull { it.id == detailsId }?.let { remote ->
        host.SshDetails(remote, onDismiss = { detailsId = null })
    }
}

/**
 * A row that opens a page: its icon, its name over a [line] about it, what [trailing] adds, and a
 * chevron. [tag] names its parts (`-row`, `-logo`, `-label`, `-status` or the given [line]).
 */
@Composable
private fun PageRow(
    tag: String, label: String, status: String, icon: Painter, tint: Color, onClick: () -> Unit, line: String = "status",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = VeloTheme.surface
    Row(
        Modifier.groupBleed().testTag("$tag-row").fillMaxWidth().heightIn(min = VeloTheme.medium(64.dp)).clickable(role = Role.Button, onClick = onClick)
            .groupInset().padding(vertical = VeloTheme.space.s8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        Icon(icon, null, Modifier.testTag("$tag-logo").size(VeloTheme.medium(22.dp)), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
            Text(label, Modifier.fillMaxWidth().testTag("$tag-label"), style = VeloTheme.type.body, color = c.text)
            Text(status, Modifier.fillMaxWidth().testTag("$tag-$line"), style = VeloTheme.type.caption, color = c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(VeloTheme.space.s16), tint = c.secondary)
    }
}

/**
 * A saved remote: its name over its address, and its "…" menu, which marks the row while open.
 * A line under it parts it from the next.
 */
@Composable
private fun SavedRemoteRow(remote: Remote, menuOpen: Boolean, onAction: (RemoteAction) -> Unit) {
    val c = VeloTheme.surface
    val name = remote.label.ifEmpty { "Remote" }
    var menu by remember { mutableStateOf(menuOpen) }
    Row(
        // The row whose menu is open stays marked while the menu covers the page around it.
        Modifier.groupBleed().testTag("saved-remote-row-$name").fillMaxWidth().heightIn(min = VeloTheme.medium(64.dp))
            .background(if (menu) c.selected else Color.Transparent)
            // The menu button keeps 4 from the border; the row's words 16, as every row's.
            .padding(start = VeloTheme.space.s16, end = VeloTheme.space.s4, top = VeloTheme.space.s8, bottom = VeloTheme.space.s8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        Icon(Icons.Default.Computer, null, Modifier.size(VeloTheme.medium(22.dp)), tint = c.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
            Text(name, Modifier.fillMaxWidth().testTag("saved-remote-name-$name"), style = VeloTheme.type.body, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(remote.url, Modifier.fillMaxWidth().testTag("saved-remote-address-$name"), style = VeloTheme.type.caption, color = c.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            RowIconButton(Icons.Default.MoreHoriz, "Actions for $name", { menu = true }, Modifier.testTag("saved-remote-menu-$name"))
            VeloMenu(menu, { menu = false }, Modifier.testTag("remote-menu")) {
                // An ssh remote shows its SSH details, as in the sidebar's menu; Remove stands apart.
                for (item in savedRemoteMenu(isSshLocator(remote.url))) {
                    if (item.action == RemoteAction.Remove) MenuDivider()
                    MenuItem(item.label, onClick = {
                        menu = false
                        onAction(item.action)
                    }, Modifier.testTag("remote-menu-${remoteActionTag(item.action)}"), icon = remoteActionIcon(item.action), enabled = item.enabled,
                        destructive = item.action == RemoteAction.Remove)
                }
            }
        }
    }
    GroupDivider(full = true)
}

/** A remote menu item's name in test tags, as the boards name it. */
private fun remoteActionTag(action: RemoteAction) = when (action) {
    RemoteAction.Rename -> "rename"
    RemoteAction.SshDetails -> "ssh-details"
    RemoteAction.Remove -> "remove"
    RemoteAction.NewSession -> "new-session"
}

/** Add Remote…: the saved group's last row, in the accent, as a link to the dialog. */
@Composable
private fun AddRemoteRow(onClick: () -> Unit) {
    val c = VeloTheme.surface
    Row(
        Modifier.groupBleed().testTag("remotes-add-remote-row").fillMaxWidth().heightIn(min = VeloTheme.medium(56.dp))
            .clickable(role = Role.Button, onClick = onClick).groupInset().padding(end = VeloTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        Icon(Icons.Default.Add, null, Modifier.size(VeloTheme.medium(22.dp)), tint = c.accent)
        Text(AddRemoteCommand, style = VeloTheme.type.body, fontWeight = FontWeight.Medium, color = c.accent)
    }
}
