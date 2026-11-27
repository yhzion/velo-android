package io.github.yhzion.velo.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.isSshLocator
import io.github.yhzion.velo.core.sshHostPort
import io.github.yhzion.velo.core.usableUrl
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.OverlayHost
import io.github.yhzion.velo.ui.theme.PopoverSurface
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/*
 * "SSH details": what the phone remembers for an ssh remote's login, each with a Forget. The
 * words are the Global Constraints'; the models are pure, so the JVM tests read them.
 */

/** The panel's title, and the item of an ssh remote's menu that opens it. */
internal const val SSH_DETAILS = "SSH details"

/** The panel's one line when its first read threw: with it the panel is no empty box with a title. */
internal const val SSH_DETAILS_UNREAD = "Velo couldn't read what this phone remembers for this remote."

/** What the panel shows instead of rows: [SSH_DETAILS_UNREAD] once the first read is [loaded] and left nothing; else null (rows, or still reading). */
internal fun sshDetailsUnread(loaded: Boolean, kept: SshKept?): String? = if (loaded && kept == null) SSH_DETAILS_UNREAD else null

/** A row's value while the phone remembers nothing of it: a host never trusted, a socket never found. */
internal const val NOT_KNOWN_YET = "Not known yet"

/**
 * The Host key row's second line while the phone keeps two different keys for the host, under two
 * cases of its name ([SshSecrets.HOST_KEYS_DIFFER], #292): neither is shown as the host's key,
 * and logins stop as "the key changed" until both are forgotten.
 */
internal const val HOST_KEYS_DIFFER_TEXT = "This phone keeps two different keys for this host. Forget them, and the next login asks you to check the key."

/**
 * The Host key row's last line while it offers Forget (#29): a saved password belongs to the host
 * whose key accepted it, so the Forget takes the passwords of every login to this `host:port`
 * with the key ([SshSecrets.forgetHostKey]), and the row says so before the user taps it.
 */
internal const val HOST_KEY_FORGET_TEXT = "Forget also forgets the passwords saved for this host."

/** The Password row's value while one is saved: sealed with the Keystore's key ([SshSecrets]). */
internal const val PASSWORD_SAVED = "Saved on this phone, encrypted"

/** The Password row's value while none is saved. */
internal const val PASSWORD_NOT_SAVED = "Not saved"

/** The label of each row's Forget button; a screen reader hears [SshDetail.forgetDescription]. */
internal const val FORGET = "Forget"

/**
 * What the phone remembers for an ssh remote, one row each: its [label], what a screen reader
 * says of its Forget button ([forgetDescription]), and the notice once it is forgotten ([notice]).
 * No Forget asks first: each only makes the next login ask or probe again.
 */
internal enum class SshDetail(val label: String, val forgetDescription: String, val notice: String) {
    /**
     * The key trusted for the remote's `host:port`. Forgotten, the next login asks the user to check
     * it again, and to type the password: the passwords saved for the host go with its key (#29).
     */
    HostKey("Host key", "Forget host key and the passwords saved for this host", "Forgot the host key and the passwords saved for this host"),
    /** The pool's server socket and a refusal of its forward. Forgotten, the next login probes again. */
    ServerSocket("Server socket", "Forget server socket", "Forgot the server socket"),
    /** The saved password. Forgotten, the next login that needs one asks for it. */
    Password("Password", "Forget password", "Forgot the password"),
}

/**
 * What the notice says after [detail]'s Forget, whose result was [rc]: its [SshDetail.notice] for
 * 0, else that it did not happen, with the code ([io.github.yhzion.velo.SshNative.forgetSocket]'s;
 * the others are prefs), or without one when the Forget threw (null).
 */
internal fun forgetNotice(detail: SshDetail, rc: Int?): String = when (rc) {
    0 -> detail.notice
    null -> "Velo couldn't forget the ${detail.label.lowercase()}."
    else -> "Velo couldn't forget the ${detail.label.lowercase()} (error $rc)."
}

/**
 * What the pool remembers for a login ([io.github.yhzion.velo.SshNative.remembered]): its server
 * [socket] ("" when none), and until when its forward is refused, in unix seconds ([refusedUntil]; 0: not refused).
 */
@Serializable
internal data class RememberedSocket(val socket: String = "", @SerialName("refused_until") val refusedUntil: Long = 0L)

private val rememberedJson = Json { ignoreUnknownKeys = true }

/** What the pool's JSON [json] says; null when there is none (no pool, a failure) or it cannot be read. */
internal fun parseRemembered(json: String?): RememberedSocket? {
    if (json == null) return null
    return try {
        rememberedJson.decodeFromString<RememberedSocket>(json)
    } catch (_: IllegalArgumentException) { // SerializationException is one
        null
    }
}

/**
 * What the phone keeps for an ssh remote's login, read for the panel: the [hostPort] its host key
 * is kept under, that [hostKey] (`TYPE SHA256:…`, null when none was trusted; [SshSecrets.hostKey]), the pool's
 * [remembered] JSON (null when the pool knows nothing), whether a [password] is saved, and
 * whether the pool runs ([poolRuns]): it alone forgets a server socket, since it keeps their file.
 */
internal data class SshKept(val hostPort: String?, val hostKey: String?, val remembered: String?, val password: Boolean, val poolRuns: Boolean = true)

/**
 * What the phone keeps for the login [url], from [secrets] (plain prefs: nothing is opened) and
 * the pool ([remembered], given [url]; [poolRuns]: whether it runs). Null for a remote that is
 * not an ssh login.
 */
internal fun readSshKept(url: String, secrets: SshSecrets, poolRuns: Boolean = true, remembered: (String) -> String?): SshKept? {
    if (!isSshLocator(url)) return null
    val hostPort = sshHostPort(url)
    return SshKept(hostPort, hostPort?.let(secrets::hostKey), remembered(url), secrets.hasPassword(url), poolRuns)
}

/**
 * Forget the host key [secrets] trusts for the login [url], under the `host:port` the pool asks
 * about ([sshHostPort]): the next login shows the key's fingerprint and waits for the user's
 * Trust, as for a host the phone never met (OpenSSH after `ssh-keygen -R`). A key kept under
 * another case of the host's name goes with it, and so do the passwords saved for every login to
 * that `host:port` (#29): OpenSSH has the user type the password again for a host whose key it
 * no longer knows. Other ports and hosts keep theirs. Prefs only: any thread.
 */
internal fun forgetTrustedHostKey(secrets: SshSecrets, url: String) {
    sshHostPort(url)?.let(secrets::forgetHostKey)
}

/** The pool's file in its state directory (`files/ssh`): each login's server socket and refusal (`endpointFile` in probe.go). */
internal const val ENDPOINTS_FILE = "host-endpoints.json"

/** The largest [ENDPOINTS_FILE] the panel reads; the pool writes a few hundred bytes a login. */
internal const val MAX_ENDPOINTS_BYTES = 1024 * 1024

/** How long the pool holds a refusal of the forward (`refusalMemory` in probe.go), in seconds. */
private const val REFUSAL_SECONDS = 24 * 60 * 60L

/** [ENDPOINTS_FILE] as the pool writes it; a map the pool left empty may read `null`. */
@Serializable
private data class EndpointFile(
    val version: Int = 0,
    @SerialName("ssh_sockets") val sshSockets: Map<String, String>? = null,
    val refused: Map<String, Long>? = null,
)

private val rememberedOut = Json { encodeDefaults = true }

/**
 * What the pool would say for the login [url] ([io.github.yhzion.velo.SshNative.remembered]'s JSON),
 * read from the text of its [file] ([ENDPOINTS_FILE]) at [now] (unix seconds); null when the
 * file holds nothing for it, or is none the pool would read (another version, not JSON). The
 * file keys a login by its canonical form, port 22 left out (`Target.String` in target.go):
 * the form a saved remote's URL already has ([usableUrl]). A refusal counts for a day, as for
 * the pool, which drops older ones when it loads the file.
 */
internal fun rememberedInEndpoints(file: String?, url: String, now: Long): String? {
    if (file == null) return null
    val key = usableUrl(url)?.takeIf(::isSshLocator) ?: return null
    val f = try {
        rememberedJson.decodeFromString<EndpointFile>(file)
    } catch (_: IllegalArgumentException) { // SerializationException is one
        return null
    }
    if (f.version != 1) return null
    val socket = f.sshSockets?.get(key).orEmpty()
    val until = f.refused?.get(key)?.let { it + REFUSAL_SECONDS }?.takeIf { it > now } ?: 0L
    if (socket.isEmpty() && until == 0L) return null
    return rememberedOut.encodeToString(RememberedSocket(socket, until))
}

/**
 * What the phone remembers of the login [url]'s server socket, as the pool's JSON: the [pool]'s
 * answer once it runs ([poolStarted]); until then its [file], read here ([rememberedInEndpoints]),
 * since the pool loads it only when the first ssh connection starts it (#284: the row said
 * "Not known yet" although the file had the socket).
 */
internal fun rememberedFor(url: String, poolStarted: Boolean, pool: (String) -> String?, file: () -> String?, now: Long): String? =
    if (poolStarted) pool(url) else rememberedInEndpoints(file(), url, now)

/**
 * The text of the pool's [ENDPOINTS_FILE] in [sshDir]; null when there is none, it is larger than
 * [MAX_ENDPOINTS_BYTES], or it cannot be read. Only read: the pool alone writes it (atomically,
 * by a rename), so a read sees a whole file. Disk work: off the main thread.
 */
internal fun readEndpointsFile(sshDir: File): String? = try {
    val file = File(sshDir, ENDPOINTS_FILE)
    if (!file.isFile || file.length() > MAX_ENDPOINTS_BYTES) null
    else file.readBytes().takeIf { it.size <= MAX_ENDPOINTS_BYTES }?.decodeToString()
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/**
 * [detail]'s Forget, start to end: [forget] (its result code, [forgetNotice]), then [say] with
 * the notice. A Forget that throws (a prefs or library failure) is said as a failure. [idle]
 * gives the panel's buttons back however it ends (#284: a throw left them disabled), a step of
 * [say] that fails or the panel closing included; those are not caught.
 */
internal suspend fun forgetAndSay(detail: SshDetail, forget: suspend () -> Int, say: suspend (String) -> Unit, idle: () -> Unit) {
    try {
        val rc = try {
            forget()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
        say(forgetNotice(detail, rc))
    } finally {
        idle()
    }
}

/**
 * The row that can still take the focus once the focus was asked to move to [row] after its
 * Forget: [row] while it has the focus ([focused]: the row that has it, if one does). A row the
 * focus never reached (the window lost the focus, the row was not placed) would stay a keyboard
 * stop for good, since only the focus leaving it ends that (#292).
 */
internal fun focusRowAfterMove(row: SshDetail, focused: SshDetail?): SshDetail? = row.takeIf { it == focused }

/** One line of a row's value; a [code] line (a fingerprint, a path) is monospaced. */
internal data class DetailLine(val text: String, val code: Boolean = false)

/** One row of the panel: what [detail] is now ([lines]), and whether there is anything to forget. */
internal data class SshDetailRow(val detail: SshDetail, val lines: List<DetailLine>, val canForget: Boolean)

/** What the Server socket row says while the pool reaches the server through `dial-stdio` because the host refused the forward. */
internal fun refusalLine(until: String) = "Velo reaches it through rex … dial-stdio until $until"

/**
 * The panel's rows, in their order: Host key, Server socket, Password. The host key is shown
 * as its [hostPort], its type as the host key dialog names it ([keyTypeName]) and its
 * fingerprint, or as [HOST_KEYS_DIFFER_TEXT] while two keys are kept for it; either way its last
 * line is [HOST_KEY_FORGET_TEXT], since its Forget forgets the host's passwords too (#29). The server socket is the pool's [remembered] socket, and while a refusal of the
 * forward holds at [now] (unix seconds) the time it ends, as [time] writes it. What is not kept
 * is [NOT_KNOWN_YET] (Review Focus 4) or [PASSWORD_NOT_SAVED], with no Forget. The server socket
 * has no Forget either unless it is [socketForgettable] (the pool runs): before that it is read
 * from the pool's file ([rememberedFor]), and a Forget would forget nothing.
 */
internal fun sshDetailRows(hostPort: String?, hostKey: String?, remembered: String?, password: Boolean, now: Long, time: (Long) -> String, socketForgettable: Boolean = true): List<SshDetailRow> {
    val key = hostKey?.takeIf { hostPort != null }?.let { kept ->
        val lines = if (kept == SshSecrets.HOST_KEYS_DIFFER) listOf(DetailLine("$hostPort"), DetailLine(HOST_KEYS_DIFFER_TEXT))
            else listOf(DetailLine("$hostPort · ${keyTypeName(kept.substringBefore(' '))}"), DetailLine(kept.substringAfter(' '), code = true))
        lines + DetailLine(HOST_KEY_FORGET_TEXT)
    }
    val pool = parseRemembered(remembered)
    val socket = buildList {
        if (pool != null && pool.socket.isNotEmpty()) add(DetailLine(pool.socket, code = true))
        if (pool != null && pool.refusedUntil > now) add(DetailLine(refusalLine(time(pool.refusedUntil))))
    }
    return listOf(
        SshDetailRow(SshDetail.HostKey, key ?: listOf(DetailLine(NOT_KNOWN_YET)), canForget = key != null),
        SshDetailRow(SshDetail.ServerSocket, socket.ifEmpty { listOf(DetailLine(NOT_KNOWN_YET)) }, canForget = socket.isNotEmpty() && socketForgettable),
        SshDetailRow(SshDetail.Password, listOf(DetailLine(if (password) PASSWORD_SAVED else PASSWORD_NOT_SAVED)), canForget = password),
    )
}

/** [seconds] (unix) as the phone writes a date and a time: a refusal holds for a day, so the day is said too. */
private fun dateAndTime(context: Context, seconds: Long): String =
    DateUtils.formatDateTime(context, seconds * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

/**
 * How long each step after a Forget waits before the next one ([SshDetailsPanel]). Compose
 * reports what changed in the semantics in batches about 100 ms apart: two changes this far
 * apart are two events, so a screen reader hears each one.
 */
private const val NOTICE_STEP_MS = 200L

/**
 * "SSH details" of the ssh remote [remote], from its menu in the sidebar or in Settings: what the
 * phone remembers for its login ([sshDetailRows]), each with a Forget that asks nothing. What
 * touches the pool or the prefs runs off the main thread; the password goes through
 * [io.github.yhzion.velo.SshQuestions], which lives on the main thread and only edits the prefs there
 * (their write to disk is Android's own, off the main thread).
 *
 * After a Forget the rows are read again and the row takes the focus its Forget had (the Forget
 * may be gone, and a disabled button loses the focus), so a screen reader says what the row holds
 * now. Then the notice under the rows says what was forgotten, or that it could not be
 * ([forgetNotice]). The notice is a polite live region that is always there, empty between
 * notices: a screen reader is told when what it says changes, not when a node appears. It is
 * emptied when a Forget starts, so the same words again are a change again, and it is set after
 * the focus move, whose reading would cut it short. A Forget that throws says so, and the buttons
 * come back however it ends ([forgetAndSay]).
 *
 * Measured with accessibility events on an Android 17 emulator (#284): at rest the empty notice
 * is a polite live region with no words and no focus, reported only in subtree changes; after a
 * Forget the row's focus event comes first and the notice's content-description change (with its
 * words) about 0.4 s later, and the notice empties 4 s after that.
 *
 * A row has no action of its own, so it is no keyboard stop: only the row of the last Forget can
 * take the focus, from that Forget until the focus leaves it ([DetailRow]), or at once when the
 * focus never reached it ([focusRowAfterMove]).
 */
@Composable
internal fun SshDetailsPanel(app: AppController, remote: Remote, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = VeloTheme.sizes
    val c = VeloTheme.surface
    // Of this remote: a panel given another remote starts from nothing, not from the last one's rows.
    var kept by remember(remote.url) { mutableStateOf<SshKept?>(null) }

    /** What the phone keeps now, read off the main thread; [before] when it cannot be read (the rows stay as they were). */
    suspend fun readKept(before: SshKept?): SshKept? = withContext(Dispatchers.IO) {
        try {
            app.sshKept(remote.url)
        } catch (_: Exception) {
            before
        } catch (_: LinkageError) {
            before
        }
    }
    var loaded by remember(remote.url) { mutableStateOf(false) }
    // Read again once the pool runs (#292): the panel may open before it does, and until then
    // the server socket has no Forget ([SshKept.poolRuns]).
    val poolReady = app.sshPoolReady
    LaunchedEffect(remote.url, poolReady) {
        kept = readKept(kept)
        loaded = true
    }
    var forgetting by remember { mutableStateOf(false) }
    // The last notice, numbered, so each one gets its own time on screen; null: the notice is empty.
    var notice by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var notices by remember { mutableIntStateOf(0) }
    val rowFocus = remember { SshDetail.entries.associateWith { FocusRequester() } }
    // The row of the last Forget, which alone can take the focus until the focus leaves it.
    var focusRow by remember { mutableStateOf<SshDetail?>(null) }
    // The row that has the focus now; read by the Forget's steps only, so it recomposes nothing.
    val focused = remember { arrayOfNulls<SshDetail>(1) }

    fun forget(detail: SshDetail) {
        if (forgetting) return
        forgetting = true
        notice = null
        focusRow = null
        scope.launch {
            forgetAndSay(detail, forget = {
                when (detail) {
                    SshDetail.HostKey -> withContext(Dispatchers.IO) { app.forgetHostKey(remote.url) }.let { 0 }
                    SshDetail.ServerSocket -> withContext(Dispatchers.IO) { app.forgetServerSocket(remote.url) }
                    SshDetail.Password -> app.forgetPassword(remote.url).let { 0 }
                }
            }, say = { words ->
                val now = readKept(kept)
                kept = now
                // Set a step before the move: the row can take the focus once it is composed so.
                focusRow = detail
                delay(NOTICE_STEP_MS)
                if (now != null) try {
                    rowFocus.getValue(detail).requestFocus()
                } catch (_: IllegalStateException) {
                    // The row is not on screen (the panel is closing): there is no focus to move.
                }
                delay(NOTICE_STEP_MS)
                // By now the move has happened, or it never will.
                if (focusRow == detail) focusRow = focusRowAfterMove(detail, focused[0])
                notice = ++notices to words
            }, idle = { forgetting = false })
        }
    }

    // As long as the app's other notices stay ([ErrorNoticeTimer]), longer with the accessibility setting.
    val accessibility = LocalAccessibilityManager.current
    notice?.let { shown ->
        LaunchedEffect(shown) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(4000L, containsText = true) ?: 4000L)
            if (notice == shown) notice = null
        }
    }

    val rows = kept?.let { k ->
        sshDetailRows(k.hostPort, k.hostKey, k.remembered, k.password, System.currentTimeMillis() / 1000, time = { dateAndTime(context, it) }, socketForgettable = k.poolRuns)
    }
    OverlayHost(onDismiss) {
        PopoverSurface(Modifier.align(Alignment.Center).padding(s.overlayMargin).width(s.dialogWidth)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(s.menuPadding), verticalArrangement = Arrangement.spacedBy(s.menuPadding)) {
                Text(SSH_DETAILS, Modifier.semantics { heading() }, style = VeloTheme.type.overlayTitle, color = c.text)
                Text(listOf(remote.label, sshLogin(remote.url)).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                    style = VeloTheme.type.caption, color = c.secondary)
                sshDetailsUnread(loaded, kept)?.let { Text(it, style = VeloTheme.type.body, color = c.text) }
                for (row in rows.orEmpty()) {
                    GroupDivider()
                    DetailRow(row, rowFocus.getValue(row.detail), takesFocus = focusRow == row.detail,
                        onFocus = { has ->
                            if (has) focused[0] = row.detail else if (focused[0] == row.detail) focused[0] = null
                            if (!has && focusRow == row.detail) focusRow = null
                        }, forgetEnabled = !forgetting) { forget(row.detail) }
                }
                // Always there, a line high, so the Done button does not move when a notice comes. A screen
                // reader hears its content description: Compose reports a change of it with the words
                // (CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION), and a change of a Text's text without them.
                val words = notice?.second.orEmpty()
                Text(words, Modifier.fillMaxWidth().clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    if (words.isNotEmpty()) contentDescription = words
                }, style = VeloTheme.type.body, color = c.text)
                VeloButton("Done", onDismiss)
            }
        }
    }
}

/**
 * One row: what is remembered, read as one item, and its Forget when there is something to
 * forget. A screen reader reaches the row as one item; the keyboard does not stop on it, since
 * it has no action. Only while [takesFocus] (after its Forget) can it take the focus ([focus])
 * its Forget had; [onFocus] says when it gets the focus (true) and when the focus leaves it again.
 *
 * Compose has no "focusable, but not a Tab stop" (HTML's `tabindex="-1"`), so the row stays a
 * focus target that can take the focus only while [takesFocus] (`canFocus`); Tab passes it at
 * every other time.
 */
@Composable
internal fun DetailRow(row: SshDetailRow, focus: FocusRequester, takesFocus: Boolean, onFocus: (Boolean) -> Unit, forgetEnabled: Boolean, onForget: () -> Unit) {
    val s = VeloTheme.sizes
    val c = VeloTheme.surface
    // Not read while composing: a change recomposes nothing.
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = s.row), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(s.rowGap)) {
        Column(Modifier.weight(1f).focusRequester(focus).focusProperties { canFocus = takesFocus }
            .onFocusChanged {
                if (focused != it.isFocused) onFocus(it.isFocused)
                focused = it.isFocused
            }
            .focusable().padding(vertical = s.cellInset).semantics(mergeDescendants = true) {}) {
            Text(row.detail.label, style = VeloTheme.type.bodyStrong, color = c.text)
            for (line in row.lines) {
                val style = if (line.code) VeloTheme.type.caption.copy(fontFamily = FontFamily.Monospace) else VeloTheme.type.caption
                Text(line.text, style = style, color = c.secondary)
            }
        }
        if (row.canForget) VeloButton(FORGET, onForget, enabled = forgetEnabled, contentDescription = row.detail.forgetDescription)
    }
}
