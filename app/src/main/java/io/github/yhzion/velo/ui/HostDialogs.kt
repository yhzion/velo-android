package io.github.yhzion.velo.ui

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

import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded

/**
 * "Add Remote…": a label and a typed address (any locator the core accepts), or a scanned HTTPS URL. [add]
 * returns the label of the remote that already has the URL (the dialog stays open and says so),
 * else null once saved. A blank label becomes the machine name.
 */
@Composable
fun AddRemoteDialog(add: (label: String, url: String) -> String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(RemoteAddressInput()) }
    var taken by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scanning = it; cameraDenied = !it }
    val accepted = address.acceptedUrl

    VeloDialog("Add Remote", onDismiss, confirmText = "Add", confirmEnabled = accepted != null, fieldFocus = fieldFocus, onConfirm = {
        val url = address.acceptedUrl
        if (url != null) {
            val have = add(label.trim(), url)
            if (have != null) taken = have else onDismiss()
        }
    }) {
        VeloTextField(label, { label = it; taken = null }, Modifier.fillMaxWidth().focusRequester(fieldFocus), label = "Label",
            placeholder = accepted?.let(::remoteLabel).orEmpty())
        VeloTextField(address.text, { address = RemoteAddressInput(it); taken = null }, Modifier.fillMaxWidth(),
            label = "Address", placeholder = ADDRESS_PLACEHOLDER,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            error = address.problem ?: taken?.let { "Already added as $it" })
        VeloButton("Scan QR code", onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                scanning = true
                cameraDenied = false
            } else permission.launch(Manifest.permission.CAMERA)
        })
        if (cameraDenied) CameraDeniedNotice { cameraDenied = false }
        if (scanning) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(rounded(VeloTheme.radii.card))) {
                QrScanner { payloads ->
                    scanning = false
                    address = address.scan(payloads)
                    taken = null
                }
            }
        }
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
