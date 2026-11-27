package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.delay

/**
 * An open close dialog: what it would close ([target], ids only) and the connection that is on.
 * Its question is read from that connection's state while it is open ([closeQuestion]).
 * [onEnd] hears once, when the dialog has gone however it went, whether it closed its target.
 */
internal class Confirm(val conn: HostConnection, val target: CloseTarget, private val onEnd: (closed: Boolean) -> Unit = {}) {
    private var closed = false
    private var ended = false

    /** Its action was chosen: the target is being closed. */
    fun closed() {
        closed = true
    }

    /** It is gone from the screen. */
    fun end() {
        if (ended) return
        ended = true
        onEnd(closed)
    }
}

/** An open rename dialog: what it renames, on which connection, and the text its field starts with ([renameText]). */
internal class Renaming(val conn: HostConnection, val target: RenameTarget, val text: String)

/** [question] with [action] (what closes) and Cancel. */
@Composable
internal fun ConfirmDialog(question: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    VeloDialog(question, onDismiss, confirmText = action, onConfirm = { onConfirm(); onDismiss() }, destructive = true)
}

/**
 * A name for a unit. The field starts with [initial], selected, so typing replaces it. With
 * [allowEmpty] an empty name is sent too: the unit then goes back to its automatic name.
 */
@Composable
internal fun RenameDialog(title: String, initial: String, allowEmpty: Boolean, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val usable = allowEmpty || name.text.isNotBlank()
    fun rename() { if (usable) { onRename(name.text.trim()); onDismiss() } }
    VeloDialog(title, onDismiss, confirmText = "Rename", onConfirm = ::rename, confirmEnabled = usable, fieldFocus = focus) {
        // VeloTextField takes a String; this field must also retain the initial selection.
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, shape = Capsule,
            textStyle = VeloTheme.type.body, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { rename() }), modifier = Modifier.fillMaxWidth().focusRequester(focus))
        if (allowEmpty) Text("Leave it empty for the automatic name.", color = VeloTheme.surface.secondary, style = VeloTheme.type.caption)
    }
}

/**
 * Clears [HostConnection.error] after its time on screen, once per message. It is separate from
 * [ErrorNotice] so the time does not start over when the notice moves between the pane area, the
 * sidebar that covers the panes, and the full window (that sidebar with the keyboard up). A person who needs more time to read
 * gets it from the accessibility setting; 4 seconds is the time without one.
 */
@Composable
internal fun ErrorNoticeTimer(conn: HostConnection) {
    val accessibility = LocalAccessibilityManager.current
    conn.error?.let { msg ->
        // A renewal starts the time again although the text stays (the user's Reconnect, #277).
        val reports = conn.errorReports
        LaunchedEffect(conn, msg, reports) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(4000L, containsText = true) ?: 4000L)
            // Not a notice that came in as this one ended, before the effect was replaced.
            conn.expireError(msg, reports)
        }
    }
}

/**
 * The notice for [HostConnection.error]. The caller places it with [modifier] inside a box that is
 * clear of the soft keyboard and the system bars. It takes no touches outside its own bounds, and it
 * is a polite live region so a screen reader reads it out when it appears. It needs an
 * [ErrorNoticeTimer] for the same connection beside it, which clears the error after its time on
 * screen: the notice itself never does. [text] is what it says for the error: an SSH login that
 * only went unanswered is not called canceled ([errorNotice]).
 *
 * A live region is read out when it appears or its text changes, not when the same text is set
 * again. The failure that answers the user's Reconnect renews it ([HostConnection.errorReports])
 * whatever its text; the notice is then made anew, so a screen reader reads it even when the
 * notice on screen already says the same (#280). The core's own retries do not renew it, so they
 * are read as before.
 */
@Composable
internal fun ErrorNotice(conn: HostConnection, modifier: Modifier = Modifier, text: (String) -> String = { it }) {
    conn.error?.let { msg ->
        key(conn.errorReports) {
            VeloSurface(VeloTheme.colors.overlay) {
                val said = text(msg)
                // The Snackbar answers its widest size (600 dp and more) when asked how wide it is, whatever its text:
                // a row of notices asks its own width (#169). Its text style is the Snackbar's own.
                val own = rememberTextMeasurer().measure(said, MaterialTheme.typography.bodyMedium).size.width +
                    with(LocalDensity.current) { (SnackbarTextSide * 2).roundToPx() }
                Snackbar(modifier.ownWidth(own).semantics { liveRegion = LiveRegionMode.Polite },
                    containerColor = VeloTheme.surface.surface, contentColor = VeloTheme.surface.text) { Text(said) }
            }
        }
    }
}
