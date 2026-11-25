package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ui.theme.monoAt
import io.github.yhzion.velo.ui.theme.lineBox
import io.github.yhzion.velo.ui.theme.Eyebrow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.remoteLabel

import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded

/**
 * "Add Remote…" (canvas DlgAddRemote block): a label and a typed address (any locator the core
 * accepts), or a scanned HTTPS URL from the QR button in the address field, with examples under
 * it. [add] returns the label of the remote that already has the URL (the dialog stays open and
 * says so), else null once saved. A blank label becomes the machine name. The address, which a
 * remote needs, has the keys first. [label] and [address] fill it as it opens.
 */
@Composable
fun AddRemoteDialog(add: (label: String, url: String) -> String?, onDismiss: () -> Unit, label: String = "", address: String = "") {
    val context = LocalContext.current
    var name by remember { mutableStateOf(label) }
    var typed by remember { mutableStateOf(RemoteAddressInput(address)) }
    var taken by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scanning = it; cameraDenied = !it }
    val accepted = typed.acceptedUrl

    VeloDialog("Add Remote", onDismiss, confirmText = "Add", modifier = Modifier.testTag("add-remote-dialog"), confirmEnabled = accepted != null,
        fieldFocus = fieldFocus, tagPrefix = "add-remote", scrimTag = "dialog-scrim", onConfirm = {
            val url = typed.acceptedUrl
            if (url != null) {
                val have = add(name.trim(), url)
                if (have != null) taken = have else onDismiss()
            }
        }) {
        VeloTextField(name, { name = it; taken = null }, Modifier.fillMaxWidth(), label = "Label",
            placeholder = accepted?.let(::remoteLabel).orEmpty(), tagPrefix = "add-remote-label")
        VeloTextField(typed.text, { typed = RemoteAddressInput(it); taken = null }, Modifier.fillMaxWidth().focusRequester(fieldFocus),
            label = "Address", help = ADDRESS_PLACEHOLDER, mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            error = typed.problem ?: taken?.let { "Already added as $it" }, tagPrefix = "add-remote-address",
            trailing = {
                // Read here, inside the dialog: its surface has the overlay's colours.
                val glass = VeloTheme.surface
                Box(
                    Modifier.testTag("add-remote-scan-qr").size(VeloTheme.medium(40.dp)).clip(CircleShape).background(glass.glassSelected)
                        .clickable(role = Role.Button, onClickLabel = null) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                scanning = true
                                cameraDenied = false
                            } else permission.launch(Manifest.permission.CAMERA)
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.QrCodeScanner, "Scan QR code", Modifier.size(VeloTheme.medium(18.dp)), tint = glass.text) }
            })
        if (cameraDenied) CameraDeniedNotice { cameraDenied = false }
        if (scanning) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(rounded(VeloTheme.radii.card))) {
                QrScanner { payloads ->
                    scanning = false
                    typed = typed.scan(payloads)
                    taken = null
                }
            }
        }
        AddressExamples()
    }
}

/** What an address looks like: a Rex server's and an ssh login's, in a box under the fields. */
@Composable
private fun AddressExamples() {
    val c = VeloTheme.surface
    val mono = monoAt(13f)
    Column(
        Modifier.fillMaxWidth().testTag("add-remote-examples").clip(rounded(VeloTheme.space.s16)).background(c.glass)
            .padding(horizontal = VeloTheme.medium(14.dp), vertical = VeloTheme.space.s12),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp)),
    ) {
        Eyebrow("EXAMPLES", color = c.secondary)
        Text("https://rex.example.ts.net", Modifier.fillMaxWidth().testTag("add-remote-example-https").lineBox(mono), style = mono, color = c.text)
        Text("ssh://user@rex.example.ts.net", Modifier.fillMaxWidth().testTag("add-remote-example-ssh").lineBox(mono), style = mono, color = c.text)
    }
}

/** "Remove Remote…": asks first. Removing forgets the remote on this phone; no server is told. */
@Composable
fun RemoveRemoteDialog(remote: Remote, remove: () -> Unit, onDismiss: () -> Unit) {
    VeloDialog("Remove ${remote.label.ifEmpty { "Remote" }}?", onDismiss, confirmText = "Remove",
        destructive = true, onConfirm = { remove(); onDismiss() }) {
        Text(remote.url, style = VeloTheme.type.body, color = VeloTheme.surface.text)
        Text("It is removed from this phone only. Its sessions keep running on the computer.",
            style = VeloTheme.type.body, color = VeloTheme.surface.secondary)
    }
}
