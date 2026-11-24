package io.github.yhzion.velo.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.yhzion.velo.ConnectionFailure
import io.github.yhzion.velo.SshQuestion
import io.github.yhzion.velo.SshQuestions
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTextField
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.rounded

/*
 * What the SSH pool asks the user (SshQuestions.pending): the host key dialog and the dialog
 * for a password or a keyboard-interactive prompt, and what the failure notice says of an SSH
 * login. The words are the comparison page's and the desktop's; the models are pure, so the JVM
 * tests read them.
 */

/** A remote row's note while its SSH login waits for the user ([remoteRowNote]). */
internal const val NEEDS_ANSWER_NOTE = "needs your answer"

/** The label of the control that copies a failure's or a question's one-line command (convenience C). */
internal const val COPY_COMMAND = "Copy command"

/** What the failure notice of [remote] says while its SSH login waits for the user. */
internal fun needsAnswerText(remote: String) = "$remote needs your answer to connect."

/** The login [target] (`ssh://user@host[:port]`) as the dialogs name it: `user@host[:port]`. */
internal fun sshLogin(target: String): String = target.substringAfter("://")

/** The user and the host of the login [target] as the pool's sentences name them: an IPv6 host without brackets. */
private fun loginParts(target: String): Pair<String, String> {
    val login = sshLogin(target)
    val authority = login.substringAfterLast('@')
    val host = if (authority.startsWith('[')) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
    return login.substringBeforeLast('@') to host
}

/** How OpenSSH names a host key type in "… key fingerprint is": `ssh-ed25519` is ED25519. */
internal fun keyTypeName(keyType: String): String = when {
    keyType == "ssh-ed25519" -> "ED25519"
    keyType.startsWith("ecdsa-") -> "ECDSA"
    keyType == "ssh-rsa" -> "RSA"
    else -> keyType
}

/**
 * The command that prints the host's own fingerprint of a key of [keyType], run on the host to
 * compare. It reads the file the pool's `host_key_changed` fix reads (`errHostKeyChanged`).
 */
internal fun hostKeyCommand(keyType: String): String {
    val file = when {
        keyType.startsWith("ecdsa-") -> "ecdsa"
        keyType == "ssh-rsa" -> "rsa"
        else -> "ed25519"
    }
    return "ssh-keygen -lf /etc/ssh/ssh_host_${file}_key.pub"
}

/** The words of the host key dialog. */
internal data class HostKeyText(
    val title: String, val intro: String, val fingerprint: String, val compare: String, val command: String,
    val copy: String, val cancel: String, val confirm: String,
)

/** What the host key dialog says about [q], a `hostkey` question: the login, the key and how to check it on the host. */
internal fun hostKeyText(q: SshQuestion) = HostKeyText(
    title = "Check the host key",
    intro = "${sshLogin(q.target)} is new to this phone. Its ${keyTypeName(q.keyType)} key fingerprint is",
    fingerprint = q.fingerprint,
    compare = "Compare it on the host:",
    command = hostKeyCommand(q.keyType),
    copy = COPY_COMMAND,
    cancel = "Cancel",
    confirm = "Trust and connect",
)

/**
 * The words of the dialog that asks for a password or a prompt's answer: what is asked
 * ([sentence]), why it is asked again ([refused]), what Velo keeps ([note]), the phone key's
 * option of convenience A ([keyOption]) and whether the answer is hidden ([masked]).
 */
internal data class AnswerText(
    val title: String, val sentence: String, val refused: String?, val note: String?, val keyOption: String?, val masked: Boolean,
    val cancel: String = "Cancel", val confirm: String = "Log in",
)

/**
 * What the dialog for [q] says. A password: the pool's prompt, a notice if the previous
 * password was rejected, what Velo keeps and the key option. A prompt (keyboard-interactive)
 * is the host's own text under the login's name; its answer is never kept, and is hidden unless
 * the host lets it show ([SshQuestion.echo]).
 */
internal fun answerText(q: SshQuestion): AnswerText {
    if (q.kind == "password") {
        val (user, host) = loginParts(q.target)
        return AnswerText(
            title = "Password", sentence = q.text, refused = if (q.retry) "Password rejected for $user on $host." else null,
            note = "Velo saves it on this phone, encrypted.", keyOption = "Use this phone's key from now on", masked = true,
        )
    }
    return AnswerText(title = sshLogin(q.target), sentence = q.text, refused = if (q.retry) "The host is requesting another answer." else null,
        note = null, keyOption = null, masked = !q.echo)
}

/** Whether "Log in" sends [text] for [q]: a password must have something in it; a prompt may be answered with nothing. */
internal fun canLogIn(q: SshQuestion, text: String): Boolean = q.kind != "password" || text.isNotEmpty()

/** One answer for [io.github.yhzion.velo.SshQuestions.answer], field for field. */
internal data class SshAnswer(val id: Long, val text: String, val ok: Boolean, val savePassword: Boolean, val installKey: Boolean) {
    // It may hold a password: never in a log or a test's message.
    override fun toString() = "SshAnswer(id=$id, ok=$ok, savePassword=$savePassword, installKey=$installKey)"
}

/**
 * The answer a dialog gives [q]: [ok] false (Cancel, Back, a tap beside it) declines it with
 * nothing. Trust answers a host key. A password is saved once its login connects, and with
 * [useKey] the phone key is installed then (convenience A). A prompt's answer is never kept.
 */
internal fun sshAnswer(q: SshQuestion, text: String, ok: Boolean, useKey: Boolean): SshAnswer = when {
    !ok -> SshAnswer(q.id, "", ok = false, savePassword = false, installKey = false)
    q.kind == "hostkey" -> SshAnswer(q.id, "", ok = true, savePassword = false, installKey = false)
    q.kind == "password" -> SshAnswer(q.id, text, ok = true, savePassword = true, installKey = useKey)
    else -> SshAnswer(q.id, text, ok = true, savePassword = false, installKey = false)
}

/**
 * Whether a question dialog's controls may take key focus: once the user [touched] it (keys
 * typed for the terminal before that must not answer it), or at once while an accessibility
 * service is on ([accessibilityOn]): TalkBack, Switch Access and Voice Access click without a
 * touch, and the focus their click asks for would otherwise be refused.
 */
internal fun questionTakesKeys(touched: Boolean, accessibilityOn: Boolean): Boolean = touched || accessibilityOn

/** How a question dialog can be left without its confirming button. */
internal enum class QuestionExit { Cancel, Back, TapOutside }

/**
 * What leaving the dialog of [q] by [exit] answers (ruling P10): only Cancel declines, and the
 * login stops until Reconnect. Back and a tap beside the dialog answer nothing and do nothing:
 * the question stays on screen until it is answered, canceled, or the pool drops it (120 s).
 */
internal fun exitAnswer(q: SshQuestion, exit: QuestionExit): SshAnswer? =
    if (exit == QuestionExit.Cancel) sshAnswer(q, "", ok = false, useKey = false) else null

/**
 * Convenience B: the command that adds the public key [line] to `~/.ssh/authorized_keys`, typed
 * in a terminal on the host. A quote in the line (an imported key's comment) is escaped, so it
 * cannot end the quoted line early.
 */
internal fun sshInstallCommand(line: String): String =
    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && echo '${line.replace("'", "'\\''")}' >> ~/.ssh/authorized_keys"

/** What the failure notice says ([text]) and the one-line command "Copy command" copies ([fix]; null: no such control). */
internal data class FailureNotice(val text: String, val fix: String?)

/**
 * The failure notice of a remote that is down (the empty pane area, the rail's popover):
 * its [failure] and that failure's fix (convenience C), copied exactly as the pool gave it. A
 * login that [needsAnswer] says so instead, with no fix: the pool reports a question that went
 * unanswered for 120 s as "declined", although the user canceled nothing, and Reconnect asks again.
 */
internal fun failureNotice(failure: ConnectionFailure?, needsAnswer: Boolean, remote: String): FailureNotice? = when {
    needsAnswer -> FailureNotice(needsAnswerText(remote), null)
    failure == null -> null
    else -> FailureNotice(failure.text, failure.fix.ifEmpty { null })
}

/**
 * What the passing notice says for [error]: the "declined" sentence of a login that only went
 * unanswered ([needsAnswer]) says that it needs an answer, as [failureNotice] does. Every other
 * notice, a command's among them, stays as it is.
 */
internal fun errorNotice(error: String, failure: ConnectionFailure?, needsAnswer: Boolean, remote: String): String =
    if (needsAnswer && failure?.code == "declined" && error == failure.text) needsAnswerText(remote) else error

/** Put [text] on the clipboard: a command or a public key, nothing secret. */
internal fun copyPlainText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Velo", text))
}

/** Offer [text] to another app through the share sheet. */
internal fun shareText(context: Context, text: String) {
    val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No app takes text: Copy is beside it.
    }
}

/** A command, a fingerprint or a key, in a monospaced block that can be selected. */
@Composable
internal fun CodeBlock(text: String, modifier: Modifier = Modifier) {
    val s = VeloTheme.sizes
    SelectionContainer(modifier.fillMaxWidth().clip(rounded(VeloTheme.radii.tile)).background(VeloTheme.surface.glass)
        .padding(horizontal = s.rowPadding, vertical = s.menuPadding)) {
        Text(text, style = VeloTheme.type.caption.copy(fontFamily = FontFamily.Monospace), color = VeloTheme.surface.text)
    }
}

/**
 * The SSH pool's question the user answers now ([SshQuestions.pending]), as a dialog over
 * whatever screen is up. Only Cancel declines it ([exitAnswer]); Back and a tap beside it do
 * nothing. Each question gets a dialog of its own, so nothing typed for one reaches another.
 * The question comes up uninvited, maybe while the user types into the terminal on a hardware
 * keyboard: [QuestionDialog] takes no key until the user touches it.
 */
@Composable
internal fun SshQuestionDialog(questions: SshQuestions) {
    val pending by questions.pending.collectAsState()
    val q = pending ?: return
    val send = { a: SshAnswer -> questions.answer(a.id, a.text, a.ok, a.savePassword, a.installKey) }
    key(q.id) {
        if (q.kind == "hostkey") HostKeyDialog(q, send) else AnswerDialog(q, send)
    }
}

/**
 * The ways out of a question dialog: Cancel calls [onDismissRequest] with what it answers;
 * Back and a tap beside the dialog are switched off when they answer nothing ([exitAnswer]).
 *
 * Nothing in it is focused when it opens, and nothing can take key focus until the user
 * touches it ([content] gets the rule for its own controls): keys typed for the terminal
 * neither fill its field nor press Cancel, and Enter does nothing. A field gets the keys once
 * tapped, and its Done (Enter) then logs in. While an accessibility service is on, its
 * controls take focus at once ([questionTakesKeys]): TalkBack's double tap is a click, not a
 * touch, and the field it focuses must accept that focus.
 */
@Composable
private fun QuestionDialog(
    q: SshQuestion, send: (SshAnswer) -> Unit, title: String, text: String, cancelText: String, confirmText: String, onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.(canFocus: () -> Boolean) -> Unit,
) {
    var touched by remember { mutableStateOf(false) }
    val accessibility = accessibilityServiceOn()
    val canFocus = { questionTakesKeys(touched, accessibility.value) }
    // Seen before the controls see it (the initial pass), so a tap on the field focuses it.
    val touch = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touched = true
        }
    }
    VeloDialog(title, onDismissRequest = { exitAnswer(q, QuestionExit.Cancel)?.let(send) }, confirmText = confirmText,
        onConfirm = onConfirm, modifier = touch, text = text, cancelText = cancelText, confirmEnabled = confirmEnabled,
        closesOnBack = exitAnswer(q, QuestionExit.Back) != null, closesOnTapOutside = exitAnswer(q, QuestionExit.TapOutside) != null,
        keyFocus = canFocus, content = { content(canFocus) })
}

/** Whether an accessibility service (TalkBack, Switch Access, Voice Access…) is on, kept up to date. */
@Composable
private fun accessibilityServiceOn(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val on = remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on.value = it }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return on
}

/** A control of a question dialog takes key focus only once the dialog was touched, or with an accessibility service on ([QuestionDialog]). */
private fun Modifier.keyFocusAfterTouch(canFocus: () -> Boolean): Modifier = focusProperties { this.canFocus = canFocus() }

/** "Check the host key": the key the host showed, the command to compare it on the host, and Trust and connect. Nothing is focused when it opens. */
@Composable
private fun HostKeyDialog(q: SshQuestion, send: (SshAnswer) -> Unit) {
    val text = hostKeyText(q)
    val context = LocalContext.current
    val s = VeloTheme.sizes
    QuestionDialog(q, send, text.title, text.intro, text.cancel, text.confirm, onConfirm = { send(sshAnswer(q, "", ok = true, useKey = false)) }) { canFocus ->
        CodeBlock(text.fingerprint)
        // The words sit where the dialog's own text does, inside the corner's curve.
        Text(text.compare, Modifier.padding(horizontal = s.menuPadding), style = VeloTheme.type.body, color = VeloTheme.surface.secondary)
        CodeBlock(text.command)
        VeloButton(text.copy, { copyPlainText(context, text.command) }, Modifier.keyFocusAfterTouch(canFocus))
    }
}

/**
 * A password ("Password", with what Velo keeps and "Use this phone's key from now on", on by
 * default) or a prompt's answer. The field takes the keys once tapped, and Done logs in.
 */
@Composable
private fun AnswerDialog(q: SshQuestion, send: (SshAnswer) -> Unit) {
    val text = answerText(q)
    val s = VeloTheme.sizes
    // Memory only: an answer is never saved with the screen's state.
    var answer by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(true) }
    val usable = canLogIn(q, answer)
    fun logIn() {
        if (usable) send(sshAnswer(q, answer, ok = true, useKey = useKey))
    }
    QuestionDialog(q, send, text.title, text.sentence, text.cancel, text.confirm, onConfirm = ::logIn, confirmEnabled = usable) { canFocus ->
        VeloTextField(
            answer, { answer = it }, Modifier.fillMaxWidth().keyFocusAfterTouch(canFocus),
            placeholder = if (q.kind == "password") "Password" else "Answer", error = text.refused,
            keyboardOptions = KeyboardOptions(keyboardType = if (text.masked) KeyboardType.Password else KeyboardType.Text,
                autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { logIn() }),
            visualTransformation = if (text.masked) PasswordVisualTransformation() else VisualTransformation.None,
        )
        text.note?.let { Text(it, Modifier.padding(horizontal = s.menuPadding), style = VeloTheme.type.caption, color = VeloTheme.surface.secondary) }
        text.keyOption?.let { Box(Modifier.keyFocusAfterTouch(canFocus)) { Toggle(it, useKey) { on -> useKey = on } } }
    }
}
