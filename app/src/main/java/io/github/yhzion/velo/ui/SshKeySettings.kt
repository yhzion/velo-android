package io.github.yhzion.velo.ui

import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import kotlin.coroutines.CoroutineContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import io.github.yhzion.velo.ui.theme.textAt
import io.github.yhzion.velo.ui.theme.rounded
import io.github.yhzion.velo.ui.theme.paragraph
import io.github.yhzion.velo.ui.theme.monoAt
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.VeloMenu
import io.github.yhzion.velo.ui.theme.RaisedCard
import io.github.yhzion.velo.ui.theme.MenuItem
import io.github.yhzion.velo.ui.theme.ButtonKind
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * cannot be read now are read again when the page is shown again. The keys are read and changed
 * on [io].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SshKeySettings(secrets: SshSecrets, io: CoroutineContext = Dispatchers.IO) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sizes = VeloTheme.sizes
    val c = VeloTheme.surface
    // Each change of the keys reads them again.
    var reads by remember { mutableIntStateOf(0) }
    val list by produceState<KeyList>(KeyList.Loading, reads) {
        value = withContext(io) { readKeys(secrets::keys) }
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
            val result = withContext(io) {
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
                val pem = withContext(io) { readKeyFile(context, uri) }
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
            val failure = withContext(io) {
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
    val space = VeloTheme.space
    Column(Modifier.fillMaxWidth().testTag("ssh-content").padding(bottom = space.s24)) {
        GroupHeader(PHONE_KEY_HEADER, Modifier.testTag("ssh-phone-key-header"), first = true)
        val phone = rows.firstOrNull { it.key.phone }
        if (phone != null) PhoneKeyCard(phone, ::act)
        // One node while the keys load and after they could not be read: it says so by a change.
        else LiveText(if (shown == KeyList.Unavailable) KEYS_UNAVAILABLE else "", c.secondary)
        Text(AUTHORIZED_KEYS_TEXT, Modifier.fillMaxWidth().testTag("ssh-install-hint").padding(start = VeloTheme.medium(6.dp), end = VeloTheme.medium(6.dp), top = VeloTheme.medium(10.dp)),
            style = VeloTheme.type.caption.paragraph(1.5.em), color = c.secondary)
        if (shown is KeyList.Ready) {
            GroupHeader(IMPORTED_KEYS_HEADER, Modifier.testTag("ssh-imported-header"))
            GroupCard(Modifier.testTag("ssh-imported-group")) {
                rows.forEachIndexed { index, row ->
                    // Named by its place among the keys logins offer, the phone key being the first.
                    // Keyed by the key: an open menu stays with its key when a removal reloads the list.
                    if (!row.key.phone) key(row.key.id) {
                        ImportedKeyRow(row, (index + 1).toString(), busy, ::act)
                        GroupDivider(full = true)
                    }
                }
                keysNote(rows.size)?.let { Text(it, Modifier.padding(vertical = space.s12), style = VeloTheme.type.caption, color = c.secondary) }
                // Import and Remove say why they failed on the Import row, which a screen reader is told about.
                if (KeyPageAction.Import in pageActions) ImportKeyRow(enabled = !busy, problemUnder(KeyPageGroup.ImportedKeys, problem, shown)) { act(KeyPageAction.Import) }
            }
        }
        if (KeyPageAction.Replace in pageActions) Column(Modifier.padding(top = space.s24), verticalArrangement = Arrangement.spacedBy(space.s8)) {
            VeloButton(KeyPageAction.Replace.label, { act(KeyPageAction.Replace) }, Modifier.fillMaxWidth(), enabled = !busy, tag = "ssh-replace-key-button")
            // Under Replace: what it costs, or its failure and every failure while the keys are not listed.
            val failure = problemUnder(KeyPageGroup.PhoneKey, problem, shown)
            LiveText(failure.ifEmpty { keyPageQuestion(KeyPageAction.Replace)?.text.orEmpty() }, if (failure.isEmpty()) c.secondary else c.danger,
                Modifier.testTag("ssh-replace-key-caption").padding(horizontal = space.s8), centered = true)
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
 * This phone's key in its card: its type and fingerprint, its line, then Copy install command
 * (convenience B), and Copy and Share. Every button names the key for a screen reader.
 */
@Composable
private fun PhoneKeyCard(row: KeyRow, onAction: (KeyRow, KeyAction) -> Unit) {
    val c = VeloTheme.surface
    val space = VeloTheme.space
    fun Modifier.said(action: KeyAction) = semantics { contentDescription = keyActionDescription(action, row) }
    RaisedCard(Modifier.testTag("ssh-phone-key-card"), padding = VeloTheme.medium(18.dp) + VeloTheme.sizes.edge) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s12)) {
            Box(Modifier.size(VeloTheme.medium(44.dp)).clip(rounded(space.s12)).background(c.glass), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Key, null, Modifier.size(VeloTheme.medium(22.dp)), tint = c.text)
            }
            // As wide as its words, at most the rest of the row, as the board sets them.
            Column(Modifier.weight(1f, fill = false).width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp))) {
                Text(keyType(row.key), Modifier.fillMaxWidth().testTag("ssh-phone-key-type"), style = textAt(18f, FontWeight.SemiBold), color = c.text)
                Text(cardFingerprint(row.key), Modifier.fillMaxWidth().testTag("ssh-phone-key-fingerprint"), style = monoAt(12.5f), color = c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Broken anywhere, as the board breaks a key; a screen reader reads the line itself, and Copy copies it.
        Text(
            breakAnywhere(row.key.publicLine),
            Modifier.fillMaxWidth().testTag("ssh-public-key").clip(rounded(VeloTheme.medium(10.dp))).background(VeloTheme.colors.backdrop.surface)
                .border(VeloTheme.sizes.edge, c.divider, rounded(VeloTheme.medium(10.dp))).padding(space.s12)
                .clearAndSetSemantics { text = AnnotatedString(row.key.publicLine) },
            style = monoAt(12f).paragraph(1.5.em), color = VeloTheme.colors.textOnRaised,
        )
        VeloButton(KeyAction.CopyInstallCommand.label, { onAction(row, KeyAction.CopyInstallCommand) }, Modifier.fillMaxWidth().said(KeyAction.CopyInstallCommand),
            kind = ButtonKind.Primary, tag = "ssh-copy-install-command")
        Row(horizontalArrangement = Arrangement.spacedBy(space.s8)) {
            VeloButton(KeyAction.Copy.label, { onAction(row, KeyAction.Copy) }, Modifier.weight(1f).said(KeyAction.Copy), row = true, tag = "ssh-copy-key")
            VeloButton(KeyAction.Share.label, { onAction(row, KeyAction.Share) }, Modifier.weight(1f).said(KeyAction.Share), row = true, tag = "ssh-share-key")
        }
    }
}

/**
 * An imported key: its name over its type and fingerprint, and Remove, which asks first. A tap on
 * the key opens what the phone key shows as buttons: copy its line, or copy or share the command
 * that installs it. Remove waits while [busy]. [place] names the row's parts for tests.
 */
@Composable
private fun ImportedKeyRow(row: KeyRow, place: String, busy: Boolean, onAction: (KeyRow, KeyAction) -> Unit) {
    val c = VeloTheme.surface
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.groupBleed().testTag("ssh-imported-key-row-$place").fillMaxWidth().heightIn(min = VeloTheme.medium(64.dp))
            .padding(start = VeloTheme.space.s16, end = VeloTheme.space.s8, top = VeloTheme.space.s8, bottom = VeloTheme.space.s8),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.space.s12),
    ) {
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Copy or share ${row.spoken}", role = Role.Button) { menu = true },
                verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(2.dp)),
            ) {
                Text(row.name, Modifier.fillMaxWidth().testTag("ssh-imported-key-name-$place").semantics { heading() }, style = VeloTheme.type.body, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(keySummary(row.key), Modifier.fillMaxWidth().testTag("ssh-imported-key-fingerprint-$place"), style = monoAt(12.5f), color = c.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            VeloMenu(menu, { menu = false }) {
                for (action in row.actions - KeyAction.Remove) MenuItem(action.label, {
                    menu = false
                    onAction(row, action)
                }, Modifier.semantics { contentDescription = keyActionDescription(action, row) })
            }
        }
        if (KeyAction.Remove in row.actions) VeloButton(
            KeyAction.Remove.label, { onAction(row, KeyAction.Remove) },
            Modifier.semantics { contentDescription = keyActionDescription(KeyAction.Remove, row) },
            kind = ButtonKind.Text, row = true, enabled = !busy, danger = true, tag = "ssh-imported-key-remove-$place",
        )
    }
}

/**
 * Import a key…: the imported keys' last row, in the accent, as Add Remote… is the remotes'. Under
 * its words it says why the last import or removal failed ([problem], "" for none). The row is a
 * polite live region that is always there, so a screen reader is told when its words change, as
 * [LiveText] is; with nothing to say the row is as the board draws it.
 */
@Composable
private fun ImportKeyRow(enabled: Boolean, problem: String, onClick: () -> Unit) {
    val c = VeloTheme.surface
    val ink = if (enabled) c.accent else c.disabled
    val label = KeyPageAction.Import.label
    Row(
        Modifier.groupBleed().testTag("ssh-import-key-button").fillMaxWidth().heightIn(min = VeloTheme.medium(56.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
                contentDescription = if (problem.isEmpty()) label else "$label $problem"
                if (!enabled) disabled()
                onClick { onClick(); true }
            }
            .groupInset().padding(end = VeloTheme.space.s4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VeloTheme.medium(14.dp)),
    ) {
        Icon(Icons.Default.Add, null, Modifier.size(VeloTheme.medium(20.dp)), tint = ink)
        Column(Modifier.padding(vertical = if (problem.isEmpty()) 0.dp else VeloTheme.space.s8)) {
            Text(label, style = VeloTheme.type.body, fontWeight = FontWeight.Medium, color = ink)
            if (problem.isNotEmpty()) Text(problem, style = VeloTheme.type.caption.paragraph(1.45.em), color = c.danger)
        }
    }
}

/** [line] for the eye only, with a break allowed after every character (the board's word-break: break-all). */
private fun breakAnywhere(line: String): String = line.toList().joinToString("\u200B")

/** The type of [key] as its card says it: the first word of its line without `ssh-` or a curve ("ed25519", "rsa", "ecdsa"). */
internal fun keyType(key: SshKey): String = keySummary(key).substringBefore(" · ")

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
private fun LiveText(text: String, color: Color, modifier: Modifier = Modifier, centered: Boolean = false) {
    Text(text, Modifier.fillMaxWidth().then(modifier).clearAndSetSemantics {
        liveRegion = LiveRegionMode.Polite
        if (text.isNotEmpty()) contentDescription = text
    }, style = VeloTheme.type.caption.paragraph(1.45.em), color = color, textAlign = if (centered) TextAlign.Center else null)
}

/** The SSH group's row in Settings → Remotes: it opens [SshKeySettings]. */
internal const val SSH_KEY_ROW = "SSH key"

/** The heading of the SSH group in Settings → Remotes. */
internal const val SSH_GROUP = "SSH"
