package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.yhzion.velo.R
import io.github.yhzion.velo.TailnetState
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.RaisedCard
import io.github.yhzion.velo.ui.theme.StateChip
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloSwitch
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.hairline
import io.github.yhzion.velo.ui.theme.monoAt
import io.github.yhzion.velo.ui.theme.paragraph
import io.github.yhzion.velo.ui.theme.rounded
import io.github.yhzion.velo.ui.theme.textAt

/** Where the devices of the tailnet are managed and removed. */
private const val TAILSCALE_MACHINES_URL = "https://console.tailscale.com/admin/machines"

/** The node's state on the Tailscale page's chip: a word or two, and its colour. */
data class TailnetChip(val text: String, val status: Status)

/** What the node's switch says it does when off, while nothing about the node needs saying. */
const val NodeOffExplanation = "Off: Velo connects through the phone's network"

/** The chip of the node's [state]; while Android holds the network back ([paused]) it is Paused, whatever the state. */
fun tailnetChip(state: TailnetState, paused: Boolean): TailnetChip = when {
    paused && state != TailnetState.Off -> TailnetChip("Paused", Status.Warning)
    state is TailnetState.Running -> TailnetChip("Connected", Status.Ok)
    state == TailnetState.Starting -> TailnetChip("Starting", Status.Active)
    state is TailnetState.NeedsLogin -> TailnetChip("Needs login", Status.Warning)
    state == TailnetState.LoggedOut -> TailnetChip("Logged out", Status.Off)
    state is TailnetState.Error -> TailnetChip("Error", Status.Danger)
    else -> TailnetChip("Off", Status.Off)
}

/** The line under the node's switch: why the node is held back or failed, or else what turning it off does. */
fun nodeCaption(state: TailnetState, paused: Boolean): String = when {
    paused && state != TailnetState.Off -> tailnetStatusLine(state, paused = true)
    state is TailnetState.Error -> tailnetStatusLine(state, paused = false)
    else -> NodeOffExplanation
}

/**
 * Tailscale (canvas SetTailscale block): the node's card with its state and its switch, logging in
 * when it needs to; This node, its name with Rename and its addresses to copy; the admin console
 * for the other devices; and Log out, which asks first, as a rename does.
 */
@Composable
internal fun DeviceSettings(host: SettingsHost) {
    val s = host.settings
    val state = host.tailnetState
    val space = VeloTheme.space
    val c = VeloTheme.surface
    val uri = LocalUriHandler.current
    val login = rememberTailnetLogin(host.tailnetLogin)
    // The name is kept with the question: the answer is for what was asked.
    var asking by remember { mutableStateOf<Pair<TailnetAction, String>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val name = s.tailnetName.ifEmpty { (state as? TailnetState.Running)?.name.orEmpty() }
    Column(Modifier.fillMaxWidth().testTag("tailscale-content").padding(top = space.s8, bottom = space.s24)) {
        RaisedCard(Modifier.testTag("tailscale-status-card"), padding = VeloTheme.medium(18.dp) + VeloTheme.sizes.edge) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s12)) {
                Box(Modifier.testTag("tailscale-logo").size(VeloTheme.medium(44.dp)).clip(rounded(space.s12)).background(c.glass), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_tailscale_on), null, Modifier.size(VeloTheme.medium(24.dp)), tint = c.text)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
                    Text("Tailscale", Modifier.fillMaxWidth().testTag("tailscale-title"), style = textAt(20f, FontWeight.SemiBold), color = c.text)
                    Text("Velo's own node on this phone", Modifier.fillMaxWidth().testTag("tailscale-subtitle"), style = VeloTheme.type.caption, color = c.secondary)
                }
                val chip = tailnetChip(state, host.tailnetPaused)
                StateChip(chip.text, chip.status, Modifier.testTag("tailscale-state-chip"))
            }
            Box(Modifier.fillMaxWidth().height(hairline()).background(c.divider))
            NodeSwitch(s.tailnet, host::setTailnet, nodeCaption(state, host.tailnetPaused))
            if (state is TailnetState.NeedsLogin) {
                // Through the app's login, so the start screen shows the wait for this page too.
                VeloButton("Log in", { login.open(uri, state.url) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            }
            if (state == TailnetState.LoggedOut) VeloButton("Log in again", login::start, Modifier.fillMaxWidth(), kind = ButtonKind.Primary)
            // No browser took the login page: its address, to open elsewhere.
            login.unopened?.let {
                SelectionContainer { Text("Open in a browser: $it", style = VeloTheme.type.caption, color = c.secondary) }
            }
        }
        GroupHeader("This node", Modifier.testTag("tailscale-this-node-header"))
        GroupCard(Modifier.testTag("tailscale-this-node-group")) {
            Row(
                Modifier.groupBleed().testTag("tailscale-name-row").fillMaxWidth().heightIn(min = VeloTheme.sizes.row)
                    .padding(start = space.s16, end = space.s8),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s12),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
                    Text("Node name", Modifier.fillMaxWidth().testTag("tailscale-name-label"), style = VeloTheme.type.caption, color = c.secondary)
                    Text(name.ifEmpty { "Not named yet" }, Modifier.fillMaxWidth().testTag("tailscale-name-value"), style = VeloTheme.type.body, color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                VeloButton("Rename", { renaming = true }, kind = ButtonKind.Text, row = true, tag = "tailscale-rename-button")
            }
            if (state is TailnetState.Running && state.ips.isNotEmpty()) {
                GroupDivider()
                Addresses(state.ips)
            }
        }
        GroupHeader("Other devices", Modifier.testTag("tailscale-other-devices-header"))
        GroupCard(Modifier.testTag("tailscale-other-devices-group")) { AdminConsoleRow() }
        if (state is TailnetState.Running) Column(Modifier.padding(top = space.s24), verticalArrangement = Arrangement.spacedBy(space.s8)) {
            VeloButton("Log out", { asking = TailnetAction.LogOut to name }, Modifier.fillMaxWidth(), kind = ButtonKind.Destructive, tag = "tailscale-log-out-button")
            Text(tailnetQuestion(TailnetAction.LogOut, name).text, Modifier.fillMaxWidth().testTag("tailscale-log-out-caption").padding(horizontal = space.s8),
                style = VeloTheme.type.caption.paragraph(1.45.em), color = c.secondary, textAlign = TextAlign.Center)
        }
    }
    if (renaming) RenameDialog("Rename This Node", name, allowEmpty = false, onRename = { typed ->
        val wanted = typed.filter { ch -> ch.isLetterOrDigit() || ch == '-' }
        if (renameAsksFirst(wanted, s.tailnetName)) asking = TailnetAction.Rename to wanted
    }, onDismiss = { renaming = false })
    asking?.let { (action, asked) ->
        val q = tailnetQuestion(action, asked)
        VeloDialog(q.title, onDismissRequest = { asking = null }, text = q.text, confirmText = q.confirm, destructive = q.destructive,
            onConfirm = {
                asking = null
                when (action) {
                    TailnetAction.LogOut -> host.logoutTailnet()
                    TailnetAction.Rename -> host.setTailnetName(asked)
                }
            })
    }
}

/** The node's switch in its card: the whole row takes the tap; the line under it says what off does, or what is wrong. */
@Composable
private fun NodeSwitch(on: Boolean, onChange: (Boolean) -> Unit, caption: String) {
    val c = VeloTheme.surface
    Row(
        Modifier.fillMaxWidth().testTag("tailscale-node-row").toggleable(on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        // The words are as wide as the wider of them, as the board sets them.
        Box(Modifier.weight(1f)) {
            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(3.dp))) {
                Text("Connect through Velo's own Tailscale node", Modifier.fillMaxWidth().testTag("tailscale-node-label"), style = VeloTheme.type.body, color = c.text)
                Text(caption, Modifier.fillMaxWidth().testTag("tailscale-node-caption"), style = VeloTheme.type.caption, color = c.secondary)
            }
        }
        VeloSwitch(on, Modifier.testTag("tailscale-node-switch"))
    }
}

/** The node's addresses, each with a button that copies it. */
@Composable
private fun Addresses(ips: List<String>) {
    val c = VeloTheme.surface
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.groupBleed().testTag("tailscale-addresses").fillMaxWidth()
            .padding(start = VeloTheme.space.s16, end = VeloTheme.space.s8, top = VeloTheme.space.s12, bottom = VeloTheme.space.s8),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp)),
    ) {
        Text("Addresses", Modifier.fillMaxWidth().testTag("tailscale-addresses-label"), style = VeloTheme.type.caption, color = c.secondary)
        for (ip in ips) {
            val id = if (':' in ip) "ipv6" else "ipv4"
            Row(
                Modifier.fillMaxWidth().testTag("tailscale-address-$id").heightIn(min = VeloTheme.medium(44.dp)),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s8),
            ) {
                Text(ip, Modifier.weight(1f).testTag("tailscale-address-$id-value"), style = monoAt(14f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(
                    Modifier.testTag("tailscale-address-$id-copy").size(VeloTheme.medium(44.dp)).clip(rounded(VeloTheme.medium(22.dp)))
                        .clickable(role = Role.Button, onClickLabel = "Copy $ip") { clipboard.setText(AnnotatedString(ip)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.ContentCopy, "Copy $ip", Modifier.size(VeloTheme.medium(18.dp)), tint = VeloTheme.colors.textOnRaised) }
            }
        }
    }
}

/** Where the other devices of the tailnet are removed: Tailscale's admin console, in the browser. */
@Composable
private fun AdminConsoleRow() {
    val c = VeloTheme.surface
    val uri = LocalUriHandler.current
    var browserUnavailable by remember { mutableStateOf(false) }
    Row(
        Modifier.groupBleed().testTag("tailscale-admin-console-link").fillMaxWidth().heightIn(min = VeloTheme.sizes.row)
            .clickable(role = Role.Button) {
                try {
                    uri.openUri(TAILSCALE_MACHINES_URL)
                } catch (_: IllegalArgumentException) {
                    browserUnavailable = true
                }
            }.groupInset().padding(end = VeloTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12),
    ) {
        Text("Open Tailscale admin console", Modifier.weight(1f), style = VeloTheme.type.body, color = c.text)
        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(VeloTheme.space.s16), tint = c.secondary)
    }
    if (browserUnavailable) SelectionContainer {
        Text("Open in a browser: $TAILSCALE_MACHINES_URL", Modifier.padding(bottom = VeloTheme.space.s12), style = VeloTheme.type.caption, color = c.secondary)
    }
}
