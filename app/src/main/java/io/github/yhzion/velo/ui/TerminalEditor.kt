package io.github.yhzion.velo.ui

import android.os.Build
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
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
     * The front of [preedit] the remote already has: the last character, which the keyboard took
     * back into its composition ([takeBack]). Only what the composition makes of it is sent and
     * shown. "" when nothing was taken back.
     */
    var takenBack = ""
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
     * preedit, as [takenBack]: the keyboard's cursor stays after it, so nothing is told.
     */
    fun takeBack(source: TerminalEditor) {
        val character = contextFor(source)?.toString() ?: return
        lastWriter = null
        lastCharacter.clear()
        preedit.replace(0, preedit.length, character)
        Selection.setSelection(preedit, preedit.length)
        takenBack = character
    }

    /**
     * The keyboard changed or deleted the character it took back, or [always] when a deletion
     * reaches the remote before it: forgets it and returns how many characters the remote must
     * erase, 0 while the preedit still starts with it. The preedit then sends all of itself.
     */
    fun releaseTakenBack(always: Boolean = false): Int {
        if (takenBack.isEmpty() || !always && preedit.startsWith(takenBack)) return 0
        val count = takenBack.codePointCount(0, takenBack.length)
        takenBack = ""
        return count
    }

    fun clearPreedit() {
        composing = null
        takenBack = ""
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
        // A character taken back is on the pane already, as the remote shows it.
        val now = if (preeditHidden) "" else remoteEdit(preedit.toString(), takenBack).second
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
        // composition over a character taken back, the key is what was typed after it (the jamo, not the digit).
        if (active() && armedKey(remoteEdit(text.toString(), state.takenBack).second)) return true
        if (!super.setComposingText(text, newCursorPosition)) return false
        eraseReleased()
        state.setComposing(this)
        state.showPreedit()
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) = submit(text, commit, newCursorPosition == 1)

    override fun finishComposingText() = submit(null, finish)

    /** The keyboard changed or deleted the character it took back: the remote loses it as well. */
    private fun eraseReleased() {
        val count = state.releaseTakenBack()
        if (count > 0) write { repeat(count) { key(KeyEvent.KEYCODE_DEL) } }
    }

    private fun submit(text: CharSequence?, send: (String) -> Unit, atEnd: Boolean = true): Boolean {
        if (!canEdit()) return false
        if (text != null && text.isEmpty()) state.forgetContext()
        submitTerminalComposition(state.preedit, text, state::clearPreedit, state.takenBack) { erase, value, whole ->
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
        // The remote's cursor is after a character taken back, not at the front of the preedit:
        // a deletion past that front erases the character first.
        repeat(state.releaseTakenBack(always = remoteBefore > takeLeft)) { key(KeyEvent.KEYCODE_DEL) }
        state.showPreedit()
        sendSurroundingDeletes(remoteBefore - takeLeft, after - takeRight, key)
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

    override fun closeConnection() {
        if (closed) return
        closed = true
        state.release(this)
        super.closeConnection()
    }
}

/**
 * A commit replaces preedit, including an explicit empty commit which cancels it. [taken] is the
 * front of the preedit the remote already has ([TerminalEditorState.takenBack]): [send] gets the
 * characters to erase first ([remoteEdit]), the text to send, and the whole text the composition
 * ended as. Nothing is sent for an empty composition that took nothing back.
 */
internal fun submitTerminalComposition(
    preedit: CharSequence,
    committed: CharSequence?,
    clear: () -> Unit,
    taken: String = "",
    send: (erase: Int, text: String, whole: String) -> Unit,
) {
    val whole = (committed ?: preedit).toString()
    clear()
    val (erase, text) = remoteEdit(whole, taken)
    if (erase > 0 || whole.isNotEmpty()) send(erase, text, whole)
}
