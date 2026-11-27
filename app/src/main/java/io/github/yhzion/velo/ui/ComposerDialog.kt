package io.github.yhzion.velo.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.ui.theme.ButtonKind
import io.github.yhzion.velo.ui.theme.MenuItem as VeloMenuItem
import io.github.yhzion.velo.ui.theme.OverlayHost
import io.github.yhzion.velo.ui.theme.OverlaySurface
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.glass
import io.github.yhzion.velo.ui.theme.saidBy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Where a draft goes. [label] names that pane for the user ([composerTargetLabel]), as it was when Compose opened. */
internal class ComposerTarget(val conn: HostConnection, val session: String, val block: String, val epoch: Long, val label: String)

/** How long the dialog shows the check and "Sent" after a confirmed send, before it closes by itself. */
private const val SENT_SHOWN_MS = 900L

private fun ContentResolver.loadDocument(uri: Uri): ComposerAttachment {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("Choose a document from the file picker.")
    val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    val (name, size) = query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
        val size = if (sizeColumn < 0 || cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn)
        val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) ?: return@use null
        name to size
    } ?: throw IOException("The file provider did not return a name.")
    if (!usableAttachmentName(name)) throw IOException("Choose a file with a valid name (up to 255 bytes).")
    val bytes = openInputStream(uri)?.use { readComposerBytes(it, size) } ?: throw IOException("Could not open the selected file.")
    return ComposerAttachment(name, bytes)
}

/**
 * The pane menu's Compose dialog: the draft, the attach control and Send. A confirmed send
 * puts the check and "Sent" where Send was for [SENT_SHOWN_MS], then the dialog closes; a
 * failed one stays with its error. Closing a draft that holds text or a file asks first.
 */
@Composable
internal fun ComposerDialog(target: ComposerTarget, notificationPending: Boolean, isCurrent: () -> Boolean, onClose: () -> Unit) {
    val draft = remember(target) { ComposerDraft() }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { fieldFocus.requestFocus() }
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val current = isCurrent()
    if (!current) SideEffect { draft.invalidate() }
    val editable = draft.editable && current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (!isCurrent()) draft.invalidate()
        if (uri == null || !draft.editable) {
            draft.loading = false
        } else {
            // Read on selection, never as a side effect of Send. Cancel/disposal cannot dispatch a late result.
            draft.attachment = null
            draft.loadError = null
            scope.launch {
                try {
                    val file = runInterruptible(Dispatchers.IO) { context.contentResolver.loadDocument(uri) }
                    if (draft.editable) draft.attachment = file
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    draft.loadError = e.localizedMessage ?: "Could not read the file."
                } finally {
                    draft.loading = false
                }
            }
        }
    }
    fun attach(action: AttachAction) {
        if (!isCurrent()) return
        val mime = action.mime ?: return draft.removeAttachment()
        if (draft.editable && !draft.loading) {
            draft.loading = true
            try { picker.launch(arrayOf(mime)) } catch (_: ActivityNotFoundException) {
                draft.loading = false
                draft.loadError = "No document picker is available."
            }
        }
    }
    // The scrim, Back and Cancel: a draft that has something in it asks first (ComposerDraft.requestClose).
    fun dismiss() { if (draft.requestClose()) onClose() }
    fun discard() { if (draft.discard()) onClose() }
    fun copy() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Velo", draft.text))
    }
    fun send() {
        if (!draft.beginSend(isCurrent())) return
        val file = draft.attachment
        target.conn.sendComposer(target.session, target.block, draft.text, file?.name, file?.bytes, target.epoch, draft::finish)
    }
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(draft.sent) {
        if (draft.sent) { delay(SENT_SHOWN_MS); close() }
    }
    val s = VeloTheme.sizes
    // A dialog as DialogSurface lays one out; its own, because the confirming button's place also holds the Sent mark.
    OverlayHost(::dismiss) {
        OverlaySurface(Modifier.align(Alignment.Center).padding(s.overlayMargin).width(s.dialogWidth)) {
            Column(Modifier.padding(s.overlayPadding), verticalArrangement = Arrangement.spacedBy(s.overlayPadding)) {
                Text("Compose", Modifier.padding(horizontal = s.menuPadding).padding(top = s.menuPadding)
                    .semantics { heading() },
                    style = VeloTheme.type.overlayTitle, color = VeloTheme.surface.text)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                    Text(target.label, style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
                    if (notificationPending) Text("The notification will open when you send or close this draft.",
                        style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
                    OutlinedTextField(draft.text, { if (draft.editable && isCurrent()) draft.text = it }, readOnly = !editable,
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).semantics { contentDescription = "Draft" },
                        minLines = 2, maxLines = 5, label = { Text("Draft") }, textStyle = VeloTheme.type.body)
                    draft.problem(current)?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = VeloTheme.surface.danger, style = VeloTheme.type.caption)
                    }
                    AttachControl(draft, enabled = editable && !draft.loading, ::attach)
                }
                // The question takes the buttons' place, in this window: a second dialog over the draft would hide it.
                if (draft.discarding) Column(verticalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                    Text("Discard this draft?", Modifier.padding(horizontal = s.menuPadding).semantics { liveRegion = LiveRegionMode.Polite },
                        style = VeloTheme.type.bodyStrong, color = VeloTheme.surface.text)
                    // One height for both buttons, as in DialogSurface: one wrapped label does not leave the other short.
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                        VeloButton("Keep", draft::keep, Modifier.weight(1f).fillMaxHeight())
                        VeloButton("Discard", ::discard, Modifier.weight(1f).fillMaxHeight(), ButtonKind.Destructive)
                    }
                } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                    VeloButton(if (draft.sent || draft.mayHaveWritten || draft.stale) "Close" else "Cancel", ::dismiss,
                        Modifier.weight(1f).fillMaxHeight(), enabled = !draft.sending)
                    when {
                        draft.sent -> SentMark(Modifier.weight(1f).fillMaxHeight())
                        // A stale draft can never be sent: the way to keep it is to copy it.
                        draft.copyable -> VeloButton("Copy draft", ::copy, Modifier.weight(1f).fillMaxHeight(), ButtonKind.Primary)
                        else -> VeloButton(if (draft.sending) "Sending…" else "Send", ::send, Modifier.weight(1f).fillMaxHeight(), ButtonKind.Primary, current && draft.ready)
                    }
                }
            }
        }
    }
}

/**
 * The draft's one attachment: "Attach…" until a file is chosen, then the file's name. A tap
 * opens its menu ([attachMenu]): an image or any file from the document picker, which replaces
 * the attachment, and Remove.
 */
@Composable
internal fun AttachControl(draft: ComposerDraft, enabled: Boolean, onAction: (AttachAction) -> Unit) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    var menu by remember { mutableStateOf(false) }
    val name = draft.attachment?.name
    val description = name?.let { "Attachment $it" }
    val color = if (enabled) c.onGlass else c.disabled
    Box {
        Row(
            Modifier.fillMaxWidth().glass().clickable(enabled = enabled, role = Role.Button) { menu = true }
                .heightIn(min = s.control).padding(horizontal = s.rowPadding)
                .semantics(mergeDescendants = true) { if (description != null) contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(s.rowGap), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.AttachFile, null, Modifier.size(s.iconSmall), tint = color)
            Text(name ?: if (draft.loading) "Loading…" else "Attach…", Modifier.weight(1f).saidBy(description), style = VeloTheme.type.bodyStrong,
                color = color, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        VeloMenu(menu && enabled, onDismissRequest = { menu = false }) {
            for (item in attachMenu(draft.removable)) {
                VeloMenuItem(item.label, { menu = false; onAction(item.action) }, icon = when (item.action) {
                    AttachAction.Image -> Icons.Default.Image
                    AttachAction.File -> Icons.AutoMirrored.Filled.InsertDriveFile
                    AttachAction.Remove -> Icons.Default.Close
                })
            }
        }
    }
}

/** The confirmation in the Send button's place: the check in the Ok colour and "Sent", announced once. */
@Composable
private fun SentMark(modifier: Modifier = Modifier) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    Row(
        modifier.heightIn(min = s.control).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(s.menuPadding, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Check, null, Modifier.size(s.iconSmall), tint = c.ok)
        Text("Sent", style = VeloTheme.type.bodyStrong, color = c.text, maxLines = 1, softWrap = false)
    }
}
