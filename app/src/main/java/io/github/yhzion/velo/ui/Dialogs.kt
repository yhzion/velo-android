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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.ui.theme.Capsule
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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
 * [ErrorNotice] so the time does not start over when the notice moves between the pane area and the
 * sidebar that covers the panes. A person who needs more time to read gets it from the accessibility
 * setting; 4 seconds is the time without one.
 *
 * The time counts only while the notice is on screen (#280): where it waits behind a newer notice in the pane column or
 * the sidebar ([NoticeWait], #192, #240), as behind an upload's toast that came after it, [heard] knows, and the time
 * stops until it shows again. Else an upload longer than its time would end it unseen.
 */
@Composable
internal fun ErrorNoticeTimer(conn: HostConnection, heard: HeardNotices? = null) {
    val accessibility = LocalAccessibilityManager.current
    conn.error?.let { msg ->
        // A renewal starts the time again although the text stays (the user's Reconnect, #277).
        val reports = conn.errorReports
        LaunchedEffect(conn, msg, reports, heard) {
            // Any showing of this renewal: its words can change while it shows, as an SSH login's answer does.
            val mine = { key: Any -> key is ErrorNoticeKey && key.reports == reports }
            var left = accessibility?.calculateRecommendedTimeoutMillis(4000L, containsText = true) ?: 4000L
            while (left > 0) {
                if (heard != null) snapshotFlow { heard.offScreen(mine) }.first { !it }
                val step = minOf(left, ErrorNoticeStepMs)
                delay(step)
                left -= step
            }
            // Not a notice that came in as this one ended, before the effect was replaced.
            conn.expireError(msg, reports)
        }
    }
}

/** How often [ErrorNoticeTimer] looks whether its notice waits: a step that starts on screen counts whole. */
private const val ErrorNoticeStepMs = 100L

/**
 * The notice for [HostConnection.error]. The caller places it with [modifier] inside a box that is
 * clear of the soft keyboard and the system bars. It takes no touches outside its own bounds, and it
 * is a polite live region so a screen reader reads it out when it appears. It needs an
 * [ErrorNoticeTimer] for the same connection beside it, which clears the error after its time on
 * screen: the notice itself never does. [text] is what it says for the error: an SSH login that
 * only went unanswered is not called canceled ([errorNotice]). A screen reader hears it as an error
 * ([errorNoticeSaid]).
 *
 * A live region is read out when it appears or its text changes, not when the same text is set
 * again. The failure that answers the user's Reconnect renews it ([HostConnection.errorReports])
 * whatever its text; the notice is then made anew, so a screen reader reads it even when the
 * notice on screen already says the same (#280). The core's own retries do not renew it, so they
 * are read as before. A notice that only moved to another place, which [heard] knows, is made anew
 * there too, but it is no live region: it was read where it first showed (#195). Where it [waits] behind
 * newer notices in the pane column or the sidebar ([NoticeWait], #192, #240), one that comes back is not read again either.
 *
 * Its text shows [lines] lines at most and ends with an ellipsis where it is longer, so that a long error at a large
 * text size does not cover the key bar (#227). That is [ErrorNoticeLines], unless the sidebar that holds it has less
 * room above its Filter field (#240, [LocalErrorNoticeLines]). A screen reader hears all of it.
 */
@Composable
internal fun ErrorNotice(
    conn: HostConnection, modifier: Modifier = Modifier, text: (String) -> String = { it }, heard: HeardNotices? = null, waits: Boolean = false,
    lines: Int = LocalErrorNoticeLines.current,
) {
    conn.error?.let { msg ->
        val reports = conn.errorReports
        key(reports) {
            VeloSurface(VeloTheme.colors.overlay) {
                val said = text(msg)
                val shown = ErrorNoticeKey(reports, said)
                val wait = if (waits) rememberNoticeWait(heard, shown) else null
                val live = liveNotice(heard, shown, wait)
                // The Snackbar answers its widest size (600 dp and more) when asked how wide it is, whatever its text:
                // a row of notices asks its own width (#169). Its text style is the Snackbar's own. Asked how tall it
                // is, it answers one line's height however its text wraps: the row asks its own height too (#192).
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.bodyMedium
                val density = LocalDensity.current
                val own = measurer.measure(said, style).size.width + with(density) { (SnackbarTextSide * 2).roundToPx() }
                val ownHeight = remember(measurer, said, style, density) { { width: Int -> snackbarHeight(measurer, said, style, density, width) } }
                // One node says it all, as a live region whose words name the error.
                // Nothing while it waits: [waitsIn] hides it.
                val waiting = wait?.waiting == true
                Snackbar(modifier.waitsIn(wait).ownWidth(own, ownHeight).clearAndSetSemantics {
                    if (!waiting) {
                        if (live) liveRegion = LiveRegionMode.Polite
                        contentDescription = errorNoticeSaid(msg, said)
                    }
                }, containerColor = VeloTheme.surface.surface, contentColor = VeloTheme.surface.text) {
                    Text(said, maxLines = lines, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The height of a Material Snackbar that holds only [text] in [style], at most [width] px wide, as it lays itself out
 * (Material3 1.5): 48 dp for one line; for more, at least 68 dp, its first baseline 30 dp from the top and 6 dp under
 * its last line. Its text is 32 dp narrower than the Snackbar, which is at most 624 dp wide, and takes
 * [ErrorNoticeLines] lines at most, as the error notice draws it (#227). Its own layout answers one line's height at
 * any width, so the error notice answers this instead ([ownWidth], #192).
 */
internal fun snackbarHeight(measurer: TextMeasurer, text: String, style: TextStyle, density: Density, width: Int): Int = with(density) {
    val textWidth = (minOf(width, SnackbarMaxWidth.roundToPx()) - (SnackbarTextSide * 2).roundToPx()).coerceAtLeast(0)
    val laid = measurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = ErrorNoticeLines,
        constraints = Constraints(maxWidth = textWidth))
    if (laid.lineCount < 2) maxOf(48.dp.roundToPx(), laid.size.height)
    else maxOf(68.dp.roundToPx(), (30.dp.roundToPx() - laid.firstBaseline.roundToInt()).coerceAtLeast(0) + laid.size.height + 6.dp.roundToPx())
}

/** The most lines the error notice's text shows (#227): the rest ends in an ellipsis. */
internal const val ErrorNoticeLines = 2

/**
 * The most lines an error notice shows where it is drawn ([ErrorNotice]): [ErrorNoticeLines], or one where the sidebar
 * holds it alone and it would cover the Filter field (#240). Its height as asked ([snackbarHeight]) stays that of
 * [ErrorNoticeLines] lines, so the sidebar's choice does not change with it.
 */
internal val LocalErrorNoticeLines = compositionLocalOf { ErrorNoticeLines }

/** The widest a Material Snackbar draws itself ([snackbarHeight]). */
private val SnackbarMaxWidth = 624.dp

/** One showing of the error notice ([HeardNotices]): its words, at the renewal that made it (#280). */
private data class ErrorNoticeKey(val reports: Long, val said: String)

/**
 * What a screen reader hears of the error notice that shows [said] for the error [error]: "Error: " before it, as
 * Tailscale's status line says its errors (#195). Not where [said] is other words than the error: the note that an
 * SSH login waits for the user's answer ([needsAnswerText]) is no failure.
 */
internal fun errorNoticeSaid(error: String, said: String): String = if (said == error) "Error: $said" else said
