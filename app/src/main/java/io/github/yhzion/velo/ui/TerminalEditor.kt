package io.github.yhzion.velo.ui

import android.os.Build
import android.os.Bundle
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import java.util.Collections
import java.util.WeakHashMap

/**
 * Android's keyboard and accessibility input method can hold separate live connections to the
 * terminal. They share this state, and write to the pane the view has bound at each call.
 */
internal class TerminalEditorState(
    private val selectionChanged: (Int, Int) -> Unit = { _, _ -> },
    /** What the pane shows of the composition at its cursor: the preedit, or "" for none. */
    private val preeditShown: (String) -> Unit = {},
) {
    private var shown = ""
    // A read-only suffix of this client's own plain input. Never copy screen or remote output.
    private var lastWriter: TerminalEditor? = null
    private val lastCharacter = SpannableStringBuilder()
    // The keyboard still thinks the cursor is after a character that is gone. Set when a composition
    // starts, because telling the keyboard then makes it drop that composition; told once it ends.
    private var staleCursor = false

    val preedit = SpannableStringBuilder().also { Selection.setSelection(it, 0) }

    /**
     * The front of [preedit] the remote already has, and all it has of it: the last character,
     * which the keyboard took back into its composition ([takeBack]), and the letters sent as they
     * were typed ([stream], #18). After each change of the preedit the remote follows it
     * ([releaseSent], then [stream]), so this stays its front. Only the rest is sent at the end
     * and shown on the pane. "" when the remote has nothing of the composition.
     */
    var sent = ""
        private set
    private val clients = Collections.newSetFromMap(WeakHashMap<TerminalEditor, Boolean>())
    private data class Pending(val source: TerminalEditor, val send: () -> Unit)
    private val pending = ArrayDeque<Pending>()
    var composing: TerminalEditor? = null
        private set

    fun contextFor(source: TerminalEditor) = lastCharacter.takeIf { lastWriter === source && preedit.isEmpty() }

    fun remember(source: TerminalEditor, text: String) {
        if (!source.active() || preedit.isNotEmpty() || text.isEmpty() ||
            text.any { Character.isISOControl(it) && it !in "\r\n\t" }) {
            forgetContext()
            return
        }
        val point = text.codePointBefore(text.length)
        if (Character.isISOControl(point) || point in 0xD800..0xDFFF) {
            forgetContext()
            return
        }
        lastWriter = source
        lastCharacter.replace(0, lastCharacter.length, String(Character.toChars(point)))
        Selection.setSelection(lastCharacter, lastCharacter.length)
        staleCursor = false
        selectionChanged(lastCharacter.length, lastCharacter.length)
    }

    /**
     * Drops the last character and tells the keyboard the cursor went back to the start. While a
     * composition starts ([composing]), the keyboard would drop that composition if told, so it is
     * told when the composition ends ([compositionEnded]).
     */
    fun forgetContext(composing: Boolean = false) {
        if (lastWriter == null) return
        lastWriter = null
        lastCharacter.clear()
        if (composing) staleCursor = true else reportCursor()
    }

    /** The composition was submitted or cancelled: a cursor left stale by its start is told now. */
    fun compositionEnded() {
        if (staleCursor && lastWriter == null && preedit.isEmpty()) reportCursor()
    }

    private fun reportCursor() {
        staleCursor = false
        selectionChanged(Selection.getSelectionStart(preedit).coerceAtLeast(0),
            Selection.getSelectionEnd(preedit).coerceAtLeast(0))
    }

    fun attach(source: TerminalEditor) { clients.add(source) }

    fun setComposing(source: TerminalEditor) { composing = source.takeIf { preedit.isNotEmpty() } }

    /**
     * Samsung Keyboard takes a digit or a letter before the cursor back into its composition
     * before the next jamo (#282). The last character of [source] becomes the
     * preedit, as [sent]: the keyboard's cursor stays after it, so nothing is told.
     */
    fun takeBack(source: TerminalEditor) {
        val character = contextFor(source)?.toString() ?: return
        lastWriter = null
        lastCharacter.clear()
        preedit.replace(0, preedit.length, character)
        Selection.setSelection(preedit, preedit.length)
        sent = character
    }

    /**
     * The keyboard changed or deleted what the remote has of its composition, or [always] when a
     * deletion reaches the remote before it: [sent] keeps only the front it shares with the
     * preedit ([commonFront]), none when [always], and this returns how many characters the remote
     * must erase for that, 0 while the preedit still starts with all of it.
     */
    fun releaseSent(always: Boolean = false): Int {
        val kept = if (always) 0 else commonFront(sent, preedit)
        val count = sent.codePointCount(kept, sent.length)
        sent = sent.substring(0, kept)
        return count
    }

    /**
     * Counts all of the preedit as [sent] when it goes to the remote as it is typed
     * ([streamsTerminalComposition]) and returns what the remote still needs of it; "" when it
     * has all of it or the composition is held. [sent] is the preedit's front ([releaseSent]).
     */
    fun stream(latinFinal: () -> Boolean): String {
        val text = preedit.toString()
        if (!streamsTerminalComposition(text, sent, latinFinal)) return ""
        val rest = text.substring(sent.length)
        sent = text
        return rest
    }

    fun clearPreedit() {
        composing = null
        sent = ""
        preedit.clear()
        showPreedit()
    }

    /**
     * True while a window over the workspace (a dictation bubble, a dialog) has the focus. The
     * composition stays for when the focus comes back, but the pane behind does not show it.
     */
    var preeditHidden = false
        set(value) {
            field = value
            showPreedit()
        }

    /**
     * Tells the pane what the keyboard composes now, when that changed. Called after every
     * input call: the keyboard edits the preedit through the editable as well as by the calls.
     */
    fun showPreedit() {
        // What the remote has of the composition is on the pane already, as the remote shows it.
        val now = if (preeditHidden) "" else remoteEdit(preedit.toString(), sent).second
        if (now == shown) return
        shown = now
        preeditShown(now)
    }

    fun defer(source: TerminalEditor, send: () -> Unit) { pending.addLast(Pending(source, send)) }

    fun flushDeferred() {
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next.source.active()) next.send()
        }
    }

    fun discardDeferred() {
        forgetContext()
        pending.clear()
        clearPreedit()
    }

    /** Whether the keyboard's input has to be restarted when this text is sent or dropped ([holdsTerminalComposition]). */
    fun holdsComposition() = holdsTerminalComposition(preedit, pending.size)

    fun release(source: TerminalEditor) {
        if (source === lastWriter) forgetContext()
        clients.remove(source)
        pending.removeAll { it.source === source }
        if (source === composing || clients.isEmpty()) clearPreedit()
    }

    fun close() {
        clients.toList().forEach { it.closeConnection() }
        discardDeferred()
    }
}

/**
 * The editable holds preedit. Once submitted, the terminal owns the text.
 * [armedKey] is asked about each preedit text: true when the view sent it as a key with an armed
 * modifier of the key bar, so that nothing of it is composed. A submitted text that is a paste
 * ([keyboardTextIsPaste]) goes to [paste] instead of [commit] or [finish].
 * A composition of Latin letters goes to the remote as it is typed, through [finish] as the
 * composition's own text ([TerminalEditorState.sent], #18); [latinFinal] is false while the
 * keyboard turns Latin letters into other characters ([keyboardLatinIsFinal]).
 * An image the keyboard inserts goes to [content] with its flags (#160): true when it was taken.
 */
internal class TerminalEditor(
    view: View,
    private val accepts: (TerminalEditor) -> Boolean,
    private val commit: (String) -> Unit,
    private val finish: (String) -> Unit,
    private val key: (Int) -> Unit,
    private val forward: (KeyEvent) -> Unit,
    private val interrupted: (TerminalEditor) -> Boolean = { false },
    private val state: TerminalEditorState = TerminalEditorState(),
    private val armedKey: (String) -> Boolean = { false },
    private val plainInput: () -> Boolean = { true },
    private val paste: (String) -> Unit = commit,
    private val latinFinal: () -> Boolean = { true },
    private val content: (InputContentInfo, Int) -> Boolean = { _, _ -> false },
) : BaseInputConnection(view, true) {
    private var closed = false
    init { state.attach(this) }

    private fun readContext() = if (active()) state.contextFor(this) else null

    override fun getEditable() = if (closed) null else readContext() ?: state.preedit

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
        val context = readContext() ?: return super.getTextBeforeCursor(length, flags)
        require(length >= 0)
        // Keep a supplementary code point intact even when the IME requests one UTF-16 unit.
        return if (length >= context.length) context.toString() else ""
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        // The remote program owns the document and its cursor. No document snapshot is available.
        if (readContext() != null) return null
        return super.getSurroundingText(beforeLength, afterLength, flags)
    }

    internal fun active() = !closed && accepts(this)

    private fun canEdit() = !closed && (accepts(this) || interrupted(this))

    /** The voice keyboard can confirm a transcript while its own window has focus. */
    private fun write(action: () -> Unit) {
        if (active()) {
            state.flushDeferred()
            action()
        } else if (!closed && interrupted(this)) {
            state.defer(this, action)
        }
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val context = readContext()
        if (context != null) return start == context.length && end == context.length
        return canEdit() && super.setSelection(start, end)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val context = readContext()
        if (context != null) {
            if (start == context.length && end == context.length) return true
            // The keyboard takes the whole character back into its composition. It does not see
            // a refusal and would compose over the character anyway: the remote would get it twice.
            if (start != 0 || end != context.length) return false
            state.takeBack(this)
        }
        if (!canEdit() || !super.setComposingRegion(start, end)) return false
        follow()
        state.setComposing(this)
        state.showPreedit()
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (!canEdit()) return false
        // The keyboard starts or changes its composition here. Telling it the cursor moved now makes
        // Gboard drop that composition: the syllable restarts from its last jamo (only the final vowel is sent).
        state.forgetContext(composing = true)
        // Sent as a key: the preedit before it went when the modifier was armed, and none follows.
        // Only while this editor writes at once: an interrupted one defers what it gets. Of a
        // composition the remote has the front of, the key is what was typed after it: the jamo,
        // not the digit taken back (#282), and the letter, not the word streamed before it (#18).
        if (active() && armedKey(remoteEdit(text.toString(), state.sent).second)) return true
        if (!super.setComposingText(text, newCursorPosition)) return false
        follow()
        state.setComposing(this)
        state.showPreedit()
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) = submit(text, commit, newCursorPosition == 1)

    override fun finishComposingText() = submit(null, finish)

    /**
     * The remote follows the preedit the keyboard has just changed: it loses what it has past
     * their common front (a Backspace in an English word, the digit taken back and then changed),
     * and gets the rest at once while the composition streams (#18). An interrupted editor holds
     * that rest for the commit: only the erasure waits for the focus, with the writes before it.
     */
    private fun follow() {
        val erase = state.releaseSent()
        val rest = if (active()) state.stream(latinFinal) else ""
        if (erase == 0 && rest.isEmpty()) return
        write {
            repeat(erase) { key(KeyEvent.KEYCODE_DEL) }
            if (rest.isNotEmpty()) finish(rest)
        }
    }

    private fun submit(text: CharSequence?, send: (String) -> Unit, atEnd: Boolean = true): Boolean {
        if (!canEdit()) return false
        if (text != null && text.isEmpty()) state.forgetContext()
        submitTerminalComposition(state.preedit, text, state::clearPreedit, state.sent) { erase, value, whole ->
            write {
                repeat(erase) { key(KeyEvent.KEYCODE_DEL) }
                if (keyboardTextIsPaste(value)) {
                    state.forgetContext()
                    paste(value)
                } else {
                    val plain = atEnd && plainInput()
                    if (value.isNotEmpty()) send(value)
                    // The character before the cursor is the last of the whole text, sent now or before.
                    if (plain && whole.isNotEmpty()) state.remember(this, whole) else state.forgetContext()
                }
            }
        }
        // A cancelled composition, a line break or a key sends nothing to remember: the keyboard
        // learns now that no character is before the cursor.
        state.compositionEnded()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        if (!canEdit()) return false
        val context = readContext()
        if (context != null) {
            // Only append is meaningful here. The remote program owns previously sent text.
            if (start != context.length || end != context.length) return false
            return commitText(text, newCursorPosition)
        }
        if (!super.replaceText(start, end, text, newCursorPosition, textAttribute)) return false
        // BaseInputConnection applied the range to local preedit. The terminal owns the result.
        return commitText(state.preedit, newCursorPosition)
    }

    fun finishBeforeExternalKey(): Boolean =
        active() && state.preedit.isNotEmpty() && finishComposingText()

    override fun deleteSurroundingText(before: Int, after: Int) = delete(before, after, false)

    override fun deleteSurroundingTextInCodePoints(before: Int, after: Int) = delete(before, after, true)

    private fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
        if (!active() || before < 0 || after < 0) return false
        // A supplementary character occupies two UTF-16 units but takes one terminal Backspace.
        val knownUnits = if (codePoints) 0 else readContext()?.length ?: 0
        val remoteBefore = if (knownUnits == 2 && before >= 2) before - 1 else before
        if (before != 0 || after != 0) state.forgetContext()
        val buffer = state.preedit
        val cursor = Selection.getSelectionEnd(buffer).coerceIn(0, buffer.length)
        val left = if (codePoints) Character.codePointCount(buffer, 0, cursor) else cursor
        val right = if (codePoints) Character.codePointCount(buffer, cursor, buffer.length) else buffer.length - cursor
        val takeLeft = minOf(before, left)
        val takeRight = minOf(after, right)
        val start = if (codePoints) Character.offsetByCodePoints(buffer, cursor, -takeLeft) else cursor - takeLeft
        val end = if (codePoints) Character.offsetByCodePoints(buffer, cursor, takeRight) else cursor + takeRight
        buffer.delete(start, end)
        // The remote's cursor is after what it has of the preedit ([TerminalEditorState.sent]), not
        // at the front of the preedit: a deletion past that front erases all of it first.
        repeat(state.releaseSent(always = remoteBefore > takeLeft)) { key(KeyEvent.KEYCODE_DEL) }
        sendSurroundingDeletes(remoteBefore - takeLeft, after - takeRight, key)
        // What is left of an English word stays on the remote, or goes back to it after such a deletion.
        state.stream(latinFinal).takeIf { it.isNotEmpty() }?.let(finish)
        state.showPreedit()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (!active() || event.isSystem) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            finishComposingText()
            state.forgetContext()
            // The composition was a paste that now waits for its question: the key that ended it waits with it, unsent.
            if (!active()) return true
        }
        forward(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        if (actionCode != EditorInfo.IME_ACTION_SEND && actionCode != EditorInfo.IME_ACTION_DONE) return false
        if (!finishComposingText()) return false
        write {
            state.forgetContext()
            key(KeyEvent.KEYCODE_ENTER)
        }
        return true
    }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        active() && content(inputContentInfo, flags)

    override fun closeConnection() {
        if (closed) return
        closed = true
        state.release(this)
        super.closeConnection()
    }
}

/**
 * A commit replaces preedit, including an explicit empty commit which cancels it. [sent] is the
 * front of the preedit the remote already has ([TerminalEditorState.sent]): [send] gets the
 * characters to erase first ([remoteEdit]), the text to send, and the whole text the composition
 * ended as. Nothing is sent for an empty composition the remote has nothing of.
 */
internal fun submitTerminalComposition(
    preedit: CharSequence,
    committed: CharSequence?,
    clear: () -> Unit,
    sent: String = "",
    send: (erase: Int, text: String, whole: String) -> Unit,
) {
    val whole = (committed ?: preedit).toString()
    clear()
    val (erase, text) = remoteEdit(whole, sent)
    if (erase > 0 || whole.isNotEmpty()) send(erase, text, whole)
}
