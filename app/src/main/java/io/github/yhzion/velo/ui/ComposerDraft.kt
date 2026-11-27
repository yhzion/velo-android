package io.github.yhzion.velo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.ComposerFailure
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import java.io.IOException
import java.io.InputStream

internal class ComposerAttachment(val name: String, val bytes: ByteArray)

private const val MAY_HAVE_WRITTEN = "Some content may already have reached the pane. Check it before sending again."
private const val STALE = "The pane changed or disconnected. Copy the draft, then reopen Compose in the intended pane."
/** A draft without text has nothing for Copy draft to take, so the message does not offer it. */
private const val STALE_NO_TEXT = "The pane changed or disconnected. Reopen Compose in the intended pane."

/**
 * The draft's target as the line under the panes names it: the remote's label (the server's
 * name when it has none), then the shown tab's title. The URL and the ids mean little to the user.
 */
internal fun composerTargetLabel(remoteLabel: String?, state: UiState): String {
    val host = remoteLabel?.ifEmpty { null } ?: hostName(state)
    val tab = state.windows.firstOrNull { it.id == state.window } ?: return host
    return "$host · ${tabTitle(state, tab)}"
}

internal fun UiState.showsComposerPane(sessionId: String, block: String): Boolean {
    if (status != "connected" || session != sessionId) return false
    val shown = windows.firstOrNull { it.id == window } ?: return false
    if (focused != null && focused != block) return false
    // Match the rail's first-leaf fallback when the core hasn't published focus yet.
    return if (focused == null) shown.layout?.blocks()?.firstOrNull() == block
        else shown.layout.containsPane(block) || shown.floating.any { it.block == block }
}

private fun LayoutNode?.containsPane(id: String): Boolean = this != null &&
    (block == id || first.containsPane(id) || second.containsPane(id))

internal fun draftProblem(text: String): String? = when {
    text.any { it.isISOControl() && it != '\n' && it != '\t' } -> "Remove control characters before sending."
    else -> null
}

internal fun usableAttachmentName(name: String?): Boolean = !name.isNullOrBlank() && name != "." && name != ".." &&
    name.length <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() } && name.encodeToByteArray().size <= 255

internal const val FILE_TOO_LARGE = "Choose a file of 8 MiB or less."
internal const val PROVIDER_STALLED = "The file provider stopped returning data."

/** SIZE can be absent or wrong. Never store more than the limit; probe one byte past it for EOF. */
internal fun readComposerBytes(input: InputStream, reportedSize: Long?): ByteArray {
    val limit = 8 * 1024 * 1024
    fun tooLarge(): Nothing = throw IOException(FILE_TOO_LARGE)
    if (reportedSize != null && reportedSize > limit) tooLarge()
    var bytes = ByteArray(reportedSize?.takeIf { it >= 0 }?.toInt() ?: DEFAULT_BUFFER_SIZE)
    var used = 0
    while (true) {
        if (used == bytes.size) {
            val next = input.read()
            if (next < 0) return bytes
            if (used == limit) tooLarge()
            bytes = bytes.copyOf((used * 2).coerceIn(DEFAULT_BUFFER_SIZE, limit))
            bytes[used++] = next.toByte()
        } else {
            val n = input.read(bytes, used, bytes.size - used)
            if (n < 0) return bytes.copyOf(used)
            if (n == 0) throw IOException(PROVIDER_STALLED)
            used += n
        }
    }
}

/** What the attach control's menu does: open the document picker for [mime], or take the attachment away. */
internal enum class AttachAction(val mime: String?) { Image("image/*"), File("*/*"), Remove(null) }

/**
 * The attach control's menu. A draft holds one attachment, so choosing again replaces it;
 * Remove is there once a file is attached or its load failed ([ComposerDraft.removable]).
 */
internal fun attachMenu(removable: Boolean): List<MenuItem<AttachAction>> = listOfNotNull(
    MenuItem(AttachAction.Image, "Image…"),
    MenuItem(AttachAction.File, "File…"),
    if (removable) MenuItem(AttachAction.Remove, "Remove") else null,
)

/**
 * Main-thread state for one opening. A tap claims the draft synchronously, before calling the
 * transport. It ends [sent] (confirmed, shown for a moment), [stale], cancelled, or with a
 * [failure] that [mayHaveWritten]; none of these can be edited or sent again. A failure that
 * wrote nothing leaves the draft as it was, to edit and send again.
 */
internal class ComposerDraft {
    var text by mutableStateOf("")
    var attachment by mutableStateOf<ComposerAttachment?>(null)
    var loading by mutableStateOf(false)
    var loadError by mutableStateOf<String?>(null)
    var stale by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    /** Why the last send was not confirmed. The next send clears it. */
    var failure by mutableStateOf<ComposerFailure?>(null)
        private set
    /** The remote confirmed the send. The draft is cleared and stays closed to input while the dialog says so. */
    var sent by mutableStateOf(false)
        private set
    /** The dialog asks "Discard this draft?" in place of its buttons. */
    var discarding by mutableStateOf(false)
        private set
    private var closed by mutableStateOf(false)
    /** Part of the draft may already be in the pane: sending it again could type that part twice. */
    val mayHaveWritten get() = failure?.wroteNothing == false
    val editable get() = !closed && !sending && !sent && !mayHaveWritten && !stale
    val textProblem by androidx.compose.runtime.derivedStateOf { draftProblem(text) }
    val ready get() = editable && !loading && loadError == null && textProblem == null && (text.isNotEmpty() || attachment != null)
    /** There is a file, or a failed load that blocks Send, to take away. */
    val removable get() = attachment != null || loadError != null
    /** The pane cannot get the draft any more, but its text can still go to the clipboard. */
    val copyable get() = stale && !sent && !sending && text.isNotEmpty()

    /** The pane is no longer the one the draft was opened for. A confirmed send already reached it. */
    fun invalidate() { if (!sent) stale = true }

    fun beginSend(current: Boolean): Boolean {
        if (!current) invalidate()
        if (!ready) return false
        failure = null
        sending = true
        return true
    }

    /** The one result of a send: null confirms it, and only then is the draft cleared. */
    fun finish(failure: ComposerFailure?) {
        if (!sending) return
        sending = false
        if (failure != null) {
            this.failure = failure
            if (failure.targetGone) invalidate()
            return
        }
        sent = true
        attachment = null
        text = ""
    }

    /**
     * What the dialog says under the draft, or null. [current]: the pane is still the one the
     * draft was opened for. A problem of the draft as it is now goes before the last send's error.
     */
    fun problem(current: Boolean): String? {
        val failure = failure
        return when {
            // Confirmed: what the pane does afterwards is not a problem of this draft.
            sent -> null
            failure != null && mayHaveWritten -> "${failure.message}\n$MAY_HAVE_WRITTEN"
            !current || stale -> if (text.isNotEmpty()) STALE else STALE_NO_TEXT
            else -> loadError ?: textProblem ?: failure?.message
        }
    }

    /**
     * A tap on the scrim, Back, or the Cancel button. True when the dialog closes now: the draft
     * is empty or confirmed, so nothing is lost. A draft with text or an attachment asks first
     * ([discarding]), and a second request while it asks answers Keep. Nothing closes the dialog
     * while a send runs.
     */
    fun requestClose(): Boolean {
        if (sending) return false
        if (discarding) {
            keep()
            return false
        }
        if (sent || text.isEmpty() && attachment == null) {
            cancel()
            return true
        }
        discarding = true
        return false
    }

    /** Back to the draft as it was. */
    fun keep() { discarding = false }

    /** The answer Discard: the draft goes. True when the dialog closes. */
    fun discard(): Boolean {
        if (sending) return false
        discarding = false
        cancel()
        return true
    }

    /** Takes the attachment or its failed load away. Not while it is loading or on its way. */
    fun removeAttachment() {
        if (!editable || loading) return
        attachment = null
        loadError = null
    }

    fun cancel() {
        if (sending) return
        closed = true
        attachment = null
        text = ""
    }
}
