package io.github.yhzion.velo.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.SshKey
import io.github.yhzion.velo.SshKeyProblem
import io.github.yhzion.velo.SshKeysNotSaved
import io.github.yhzion.velo.SshSecrets
import io.github.yhzion.velo.VARIATION_SELECTORS
import io.github.yhzion.velo.ui.theme.GroupCard
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.GroupHeader
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** The heading of the SSH key page's first group, and the phone key's name. */
internal const val PHONE_KEY_HEADER = "This phone's key"

/** The heading of the SSH key page's second group: the keys logins offer after the phone key. */
internal const val IMPORTED_KEYS_HEADER = "Imported keys"

/** Where to register the phone's public key for SSH sign-in. */
internal const val AUTHORIZED_KEYS_TEXT = "On the SSH host, append this public key to ~/.ssh/authorized_keys for each account you use."

/** What the SSH key page says in the keys' place while the Keystore cannot open the phone key ([SshSecrets.keys] throws). */
internal const val KEYS_UNAVAILABLE = "Velo can't read the keys right now. Logins ask for a password until it can."

/** The most keys a login offers, so a password still fits under OpenSSH's default `MaxAuthTries 6`. */
internal const val MAX_OFFERED_KEYS = 5

/** The keys as the SSH key page shows them: being read, in the order logins offer them, or not to be read now. */
internal sealed interface KeyList {
    data object Loading : KeyList
    data class Ready(val keys: List<SshKey>) : KeyList
    data object Unavailable : KeyList
}

/**
 * The keys, from [read] ([SshSecrets.keys], Keystore work: off the main thread).
 * [KeyList.Unavailable] when it throws: the Keystore cannot open the phone key now, or the phone
 * could not make one. No key is shown then, and none that may be stale.
 */
internal fun readKeys(read: () -> List<SshKey>): KeyList = try {
    KeyList.Ready(read())
} catch (_: Exception) {
    KeyList.Unavailable
} catch (_: LinkageError) {
    KeyList.Unavailable
}

/** A button of one key on the SSH key page, in its order: its line's, then Remove. */
internal enum class KeyAction(val label: String) {
    Copy("Copy"), CopyInstallCommand("Copy install command"), Share("Share"), Remove("Remove"),
}

/**
 * One key on the SSH key page: the [key], its [name], its buttons, and how a screen reader names
 * the key in them ([spoken]).
 */
internal data class KeyRow(val key: SshKey, val name: String, val actions: List<KeyAction>, val spoken: String)

/** The first 8 characters of [key]'s fingerprint after `SHA256:`: what tells two keys of one comment apart. */
private fun shortFingerprint(key: SshKey) = key.fingerprint.removePrefix("SHA256:").take(8)

/** An imported key without a comment: "Imported key" and [shortFingerprint]. */
private fun fingerprintName(key: SshKey) = "Imported key ${shortFingerprint(key)}".trimEnd()

/**
 * The comment that names [key] on the page; null when it has none, or one that reads like a name
 * the page gives itself (#292): the phone key's ([PHONE_KEY_HEADER]), or a fingerprint name
 * ("Imported key" and what follows, which could be another key's). Read as it looks: without
 * regard to case, to the kind and number of spaces between its words, to the apostrophe's shape,
 * and to variation selectors, which draw nothing after such characters. Such a key is named by
 * its own fingerprint, as one without a comment.
 */
internal fun namingComment(key: SshKey): String? {
    val comment = key.comment ?: return null
    val seen = StringBuilder()
    comment.codePoints().forEach { cp ->
        when {
            cp in VARIATION_SELECTORS -> Unit
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> seen.append(' ')
            cp in APOSTROPHES -> seen.append('\'')
            else -> seen.appendCodePoint(cp)
        }
    }
    val words = seen.toString().lowercase().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    val own = words == PHONE_KEY_HEADER.lowercase() || words == "imported key" || words.startsWith("imported key ")
    return comment.takeUnless { own }
}

/** Characters that draw like the apostrophe of [PHONE_KEY_HEADER]: the curly quotes, the modifier letter, the full-width one. */
private val APOSTROPHES = setOf(0x2018, 0x2019, 0x02BC, 0xFF07)

/**
 * The key as words that tell it from every other key: its name, and [shortFingerprint] after a
 * comment. ssh-keygen's default comment is user@host, so keys made on one machine share it; on
 * screen each row also shows its fingerprint, but a screen reader or Voice Access hears a button
 * or a question alone. Always added, not only when two keys share a comment, so the words do
 * not change when another key goes. A fingerprint name already holds those characters.
 */
internal fun keyLabel(row: KeyRow): String =
    if (row.key.phone || namingComment(row.key) == null) row.name else "${row.name} ${shortFingerprint(row.key)}".trimEnd()

/**
 * The rows of [keys], in the order logins offer them: the phone key ([PHONE_KEY_HEADER]), then
 * each imported key by its comment ([namingComment]), or by [fingerprintName] when it has none. A name belongs to
 * its key, so it stays when another key goes (#284: "Imported key 2" used to become "Imported
 * key 1"). Every key's line can be copied, or turned into its install command (convenience B),
 * copied or shared. Only an imported key can be removed.
 */
internal fun keyRows(keys: List<SshKey>): List<KeyRow> = keys.map { key ->
    val comment = namingComment(key)
    when {
        key.phone -> KeyRow(key, PHONE_KEY_HEADER, KeyAction.entries - KeyAction.Remove, PHONE_KEY_HEADER.replaceFirstChar { it.lowercase() })
        comment != null -> KeyRow(key, comment, KeyAction.entries, "imported key $comment ${shortFingerprint(key)}".trimEnd())
        else -> fingerprintName(key).let { KeyRow(key, it, KeyAction.entries, it.replaceFirstChar { c -> c.lowercase() }) }
    }
}

/** A change of the keys that the page's buttons start: each says why it failed under its own button. */
internal enum class KeyChange { Import, Remove, Replace }

/** Why the last change of the keys did not happen: the sentence ([text]), and the [change] it was. */
internal data class KeyPageProblem(val text: String, val change: KeyChange)

/** The two groups of the SSH key page: [PHONE_KEY_HEADER] and [IMPORTED_KEYS_HEADER]. */
internal enum class KeyPageGroup { PhoneKey, ImportedKeys }

/**
 * Why [change] failed with [error], told without the error's own words: a change the prefs file
 * did not take says so ([SshSecrets.KEYS_NOT_SAVED]); any other (the Keystore's) as before.
 */
internal fun keyChangeProblem(change: KeyChange, error: Throwable) = KeyPageProblem(when {
    error is SshKeysNotSaved -> SshSecrets.KEYS_NOT_SAVED
    change == KeyChange.Replace -> "Velo couldn't make a new key on this phone."
    change == KeyChange.Remove -> "Velo couldn't remove the key."
    else -> "Velo couldn't save the key on this phone."
}, change)

/**
 * What the problem line of [group] says ("": nothing) while the keys are [list]: [problem] where
 * its button is. Replace is under the phone key; Import and Remove are with the imported keys.
 * While the keys are not listed only the phone key's group is there, so it says every problem.
 */
internal fun problemUnder(group: KeyPageGroup, problem: KeyPageProblem?, list: KeyList): String {
    if (problem == null) return ""
    val under = if (list is KeyList.Ready && problem.change != KeyChange.Replace) KeyPageGroup.ImportedKeys else KeyPageGroup.PhoneKey
    return if (under == group) problem.text else ""
}

/** What a screen reader says for [action] of [row]: every key has the same buttons, so each names its key ([KeyRow.spoken]). */
internal fun keyActionDescription(action: KeyAction, row: KeyRow): String {
    val key = row.spoken
    return when (action) {
        KeyAction.Copy -> "Copy $key"
        KeyAction.CopyInstallCommand -> "Copy install command for $key"
        KeyAction.Share -> "Share install command for $key"
        KeyAction.Remove -> "Remove $key"
    }
}

/** The text [action] copies or shares for [key]: its line, or the command that installs it; null for Remove. */
internal fun keyActionText(action: KeyAction, key: SshKey): String? = when (action) {
    KeyAction.Copy -> key.publicLine
    KeyAction.CopyInstallCommand, KeyAction.Share -> sshInstallCommand(key.publicLine)
    KeyAction.Remove -> null
}

/** What the page says once there are more keys than a login offers ([MAX_OFFERED_KEYS]); null until then. */
internal fun keysNote(count: Int): String? = if (count > MAX_OFFERED_KEYS) "Velo offers the first $MAX_OFFERED_KEYS keys to a host." else null

/** A button of the SSH key page itself: Import adds a key after the ones kept; Replace makes a new phone key. */
internal enum class KeyPageAction(val label: String) { Import("Import a key…"), Replace("Replace key…") }

/**
 * The page's own buttons while the keys are [list]: none while they are read. While the
 * Keystore cannot open the phone key there is no Import ([SshSecrets.importKey] would refuse);
 * a new phone key can still be made.
 */
internal fun keyPageActions(list: KeyList): List<KeyPageAction> = when (list) {
    KeyList.Loading -> emptyList()
    is KeyList.Ready -> KeyPageAction.entries
    KeyList.Unavailable -> listOf(KeyPageAction.Replace)
}

/** What the SSH key page asks before an action changes the keys: [title], [text] and the confirming button. */
internal data class KeyQuestion(val title: String, val text: String, val confirm: String)

/**
 * What [action] asks first, or null. An import adds a key and breaks no login, so it asks
 * nothing (ruling P11 is retired). Replacing the phone key breaks the logins that use it.
 */
internal fun keyPageQuestion(action: KeyPageAction): KeyQuestion? = when (action) {
    KeyPageAction.Import -> null
    KeyPageAction.Replace -> KeyQuestion("Replace this phone's key?", "Hosts that know only the current key ask for a password again.", "Replace key")
}

/** What Remove asks before an imported key goes (Global Constraints). */
internal val REMOVE_KEY_QUESTION = KeyQuestion("Remove this key?", "Hosts that only accept it will ask for a password.", "Remove")

/**
 * What Remove asks before [row]'s key goes: [REMOVE_KEY_QUESTION], whose text first names the key
 * ([keyLabel]). The removal cannot be undone, and keys can share a comment.
 */
internal fun removeKeyQuestion(row: KeyRow): KeyQuestion = REMOVE_KEY_QUESTION.copy(text = "${keyLabel(row)}\n${REMOVE_KEY_QUESTION.text}")

/** Where an import stands after [SshSecrets.importKey]. */
internal sealed interface ImportStep {
    data object Done : ImportStep
    /** The key is locked: ask for its passphrase; [error] says the last one did not open it. */
    data class AskPassphrase(val error: String?) : ImportStep
    data class Failed(val message: String) : ImportStep
}

/**
 * The step an import's [result] leads to. A failure that is not the key's (the file did not take
 * it, the Keystore) is told without its own words ([keyChangeProblem]).
 */
internal fun importStep(result: Result<Any?>): ImportStep {
    val failure = result.exceptionOrNull() ?: return ImportStep.Done
    if (failure !is SshKeyProblem) return ImportStep.Failed(keyChangeProblem(KeyChange.Import, failure).text)
    return when (failure.kind) {
        SshKeyProblem.Kind.NeedsPassphrase -> ImportStep.AskPassphrase(null)
        SshKeyProblem.Kind.WrongPassphrase -> ImportStep.AskPassphrase(failure.message)
        else -> ImportStep.Failed(failure.message.orEmpty())
    }
}

/** Which buttons of the passphrase dialog work: Import and Cancel. */
internal data class PassphraseControls(val confirm: Boolean, val cancel: Boolean)

/**
 * The passphrase dialog's buttons while an import is [busy] or not: Import needs a [passphrase]
 * and no import under way; Cancel (and Back, and a tap beside it) waits for the import that runs.
 */
internal fun passphraseControls(busy: Boolean, passphrase: String) = PassphraseControls(confirm = !busy && passphrase.isNotEmpty(), cancel = !busy)

/** A private key file is a few kilobytes; a larger file is not one. */
private const val MAX_KEY_BYTES = 64 * 1024

/** The text of the key file at [uri]; null when it cannot be read, or is too large to be a key. */
private fun readKeyFile(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_KEY_BYTES) return@use null
            out.write(buffer, 0, n)
        }
        out.toString(Charsets.UTF_8.name())
    }
} catch (_: Exception) {
    null
}

/**
 * Settings → Remotes → SSH → SSH key: the keys logins offer, in their order ([keyRows]): this
 * phone's key, then the imported keys. Each key's line can be copied, or turned into the command
 * that installs it on a host (convenience B), copied or shared. "Import a key…" adds a private key
 * file after the keys kept; "Replace key…" makes a new phone key, after asking; Remove forgets an
 * imported key, after asking. Keystore and key work run off the main thread; a key being
 * imported and its passphrase are kept in memory only, while their dialog is open. Keys that
 * cannot be read now are read again when the page is shown again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SshKeySettings(secrets: SshSecrets) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sizes = VeloTheme.sizes
    val c = VeloTheme.surface
    // Each change of the keys reads them again.
    var reads by remember { mutableIntStateOf(0) }
    val list by produceState<KeyList>(KeyList.Loading, reads) {
        value = withContext(Dispatchers.IO) { readKeys(secrets::keys) }
    }
    // Back on screen: keys the Keystore could not open are tried again.
    LifecycleResumeEffect(Unit) {
        if (list == KeyList.Unavailable) reads++
        onPauseOrDispose {}
    }
    var busy by remember { mutableStateOf(false) }
    // Why the last change did not happen; said under its own button ([problemUnder]).
    var problem by remember { mutableStateOf<KeyPageProblem?>(null) }
    // Replace, waiting for its question's answer ([keyPageQuestion]).
    var confirming by remember { mutableStateOf<KeyPageAction?>(null) }
    // An imported key waiting for Remove's question ([removeKeyQuestion]).
    var removing by remember { mutableStateOf<KeyRow?>(null) }
    // A key file that waits for its passphrase, and why the last one did not open it.
    var locked by remember { mutableStateOf<String?>(null) }
    var passphraseError by remember { mutableStateOf<String?>(null) }

    fun import(pem: String, passphrase: String?) {
        busy = true
        problem = null
        scope.launch {
            // The bcrypt KDF of an OpenSSH key can take seconds, and holds the secrets' lock.
            val result = withContext(Dispatchers.IO) {
                try {
                    secrets.importKey(pem, passphrase)
                } catch (e: Exception) {
                    // The Keystore cannot open the phone key now, or cannot seal: nothing was kept.
                    Result.failure(e)
                } catch (e: LinkageError) {
                    Result.failure(e)
                }
            }
            busy = false
            when (val step = importStep(result)) {
                ImportStep.Done -> {
                    locked = null
                    reads++
                }
                // A passphrase dialog closed while its try ran stays closed.
                is ImportStep.AskPassphrase -> if (passphrase == null || locked != null) {
                    locked = pem
                    passphraseError = step.error
                }
                is ImportStep.Failed -> {
                    locked = null
                    problem = KeyPageProblem(step.message, KeyChange.Import)
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            problem = null
            scope.launch {
                val pem = withContext(Dispatchers.IO) { readKeyFile(context, uri) }
                if (pem == null) {
                    busy = false
                    problem = KeyPageProblem(SshSecrets.NOT_A_KEY, KeyChange.Import)
                } else import(pem, null)
            }
        }
    }

    /** Run [change] ([work], off the main thread), then read the keys again; a failure is said under its button. */
    fun changeKeys(change: KeyChange, work: () -> Unit) {
        busy = true
        problem = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                try {
                    work()
                    null
                } catch (e: Exception) {
                    // The file did not take it (nothing changed), or the Keystore failed.
                    e
                } catch (e: LinkageError) {
                    e
                }
            }
            busy = false
            if (failure != null) problem = keyChangeProblem(change, failure)
            reads++
        }
    }

    fun replaceKey() = changeKeys(KeyChange.Replace) { secrets.replaceKey() }

    // The pool reads the keys again (keysChanged), which opens them.
    fun removeKey(key: SshKey) = changeKeys(KeyChange.Remove) { secrets.removeKey(key.id) }

    /** [action] once its question, if it has one, is answered. */
    fun perform(action: KeyPageAction) {
        when (action) {
            KeyPageAction.Import -> try {
                picker.launch(arrayOf("*/*"))
            } catch (_: android.content.ActivityNotFoundException) {
                problem = KeyPageProblem("No document picker is available.", KeyChange.Import)
            }
            KeyPageAction.Replace -> replaceKey()
        }
    }

    fun act(action: KeyPageAction) {
        if (keyPageQuestion(action) != null) confirming = action else perform(action)
    }

    fun act(row: KeyRow, action: KeyAction) {
        when (action) {
            KeyAction.Copy, KeyAction.CopyInstallCommand -> keyActionText(action, row.key)?.let { copyPlainText(context, it) }
            KeyAction.Share -> keyActionText(action, row.key)?.let { shareText(context, it) }
            KeyAction.Remove -> removing = row
        }
    }

    val shown = list
    val rows = (shown as? KeyList.Ready)?.keys?.let(::keyRows).orEmpty()
    val pageActions = keyPageActions(shown)
    val column = Modifier.padding(vertical = sizes.overlayPadding)
    GroupHeader(PHONE_KEY_HEADER)
    GroupCard {
        Column(column, verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
            when (shown) {
                is KeyList.Ready -> rows.firstOrNull { it.key.phone }?.let { KeyBlock(it, titled = false, busy = busy, onAction = ::act) }
                // One node while the keys load and after they could not be read: it says so by a change.
                else -> LiveText(if (shown == KeyList.Unavailable) KEYS_UNAVAILABLE else "", c.secondary)
            }
            Text(AUTHORIZED_KEYS_TEXT, color = c.secondary)
            if (KeyPageAction.Replace in pageActions) VeloButton(KeyPageAction.Replace.label, { act(KeyPageAction.Replace) }, enabled = !busy)
            // Under Replace: its failure, and every failure while the keys are not listed.
            LiveText(problemUnder(KeyPageGroup.PhoneKey, problem, shown), c.danger)
        }
    }
    if (shown is KeyList.Ready) {
        GroupHeader(IMPORTED_KEYS_HEADER)
        GroupCard {
            Column(column, verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
                rows.filter { !it.key.phone }.forEachIndexed { index, row ->
                    if (index > 0) GroupDivider()
                    KeyBlock(row, titled = true, busy = busy, onAction = ::act)
                }
                keysNote(rows.size)?.let { Text(it, color = c.secondary) }
                if (KeyPageAction.Import in pageActions) VeloButton(KeyPageAction.Import.label, { act(KeyPageAction.Import) }, enabled = !busy)
                LiveText(problemUnder(KeyPageGroup.ImportedKeys, problem, shown), c.danger)
            }
        }
    }

    confirming?.let { action ->
        val q = keyPageQuestion(action) ?: return@let
        VeloDialog(q.title, onDismissRequest = { confirming = null }, confirmText = q.confirm, destructive = true, text = q.text,
            onConfirm = {
                confirming = null
                perform(action)
            })
    }

    removing?.let { row ->
        val q = removeKeyQuestion(row)
        VeloDialog(q.title, onDismissRequest = { removing = null }, confirmText = q.confirm, destructive = true, text = q.text,
            onConfirm = {
                removing = null
                removeKey(row.key)
            })
    }

    locked?.let { pem ->
        // Memory only, and gone with the dialog.
        var passphrase by remember(pem) { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val controls = passphraseControls(busy, passphrase)
        fun unlock() {
            if (controls.confirm) import(pem, passphrase)
        }
        // While its import runs the dialog stays: Cancel, Back and a tap beside it wait for the answer.
        VeloDialog("Enter Passphrase", onDismissRequest = { locked = null; passphraseError = null }, confirmText = "Import",
            onConfirm = ::unlock, text = SshSecrets.NEEDS_PASSPHRASE, confirmEnabled = controls.confirm, cancelEnabled = controls.cancel,
            closesOnBack = controls.cancel, closesOnTapOutside = controls.cancel, fieldFocus = focus) {
            VeloTextField(
                passphrase, { passphrase = it }, Modifier.fillMaxWidth().focusRequester(focus), placeholder = "Passphrase", error = passphraseError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { unlock() }),
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

/**
 * One key: its name ([titled], for an imported key; the phone key's is its group's heading), its
 * line, its fingerprint and its buttons. The buttons that change the keys wait while [busy].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyBlock(row: KeyRow, titled: Boolean, busy: Boolean, onAction: (KeyRow, KeyAction) -> Unit) {
    val sizes = VeloTheme.sizes
    val c = VeloTheme.surface
    if (titled) Text(row.name, Modifier.semantics { heading() }, style = VeloTheme.type.bodyStrong, color = c.text)
    CodeBlock(row.key.publicLine)
    Text(row.key.fingerprint, style = VeloTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.secondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(sizes.menuPadding), verticalArrangement = Arrangement.spacedBy(sizes.menuPadding)) {
        // Remove asks first ([removeKeyQuestion]), where the destructive button is.
        for (action in row.actions) VeloButton(action.label, { onAction(row, action) },
            Modifier.semantics { contentDescription = keyActionDescription(action, row) }, enabled = action != KeyAction.Remove || !busy)
    }
}

/**
 * A line a screen reader reads out when it changes: why the keys cannot be read, or why the last
 * change of the keys did not happen. A polite live region that is always there, a line high and
 * empty while [text] is "": a screen reader is told when what a node says changes, not when a
 * node appears. The words are its content description, whose change Compose reports with them
 * (CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION; a Text's change comes without), as the SSH details
 * panel's notice ([SshDetailsPanel]). An action empties it first, so the same words again are a
 * change again.
 */
@Composable
private fun LiveText(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().clearAndSetSemantics {
        liveRegion = LiveRegionMode.Polite
        if (text.isNotEmpty()) contentDescription = text
    }, color = color)
}

/** The SSH group's row in Settings → Remotes: it opens [SshKeySettings]. */
internal const val SSH_KEY_ROW = "SSH key"

/** The heading of the SSH group in Settings → Remotes. */
internal const val SSH_GROUP = "SSH"
