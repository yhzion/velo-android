package io.github.yhzion.velo.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import io.github.yhzion.velo.CoreEnv
import io.github.yhzion.velo.GlyphRasterizer
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.HostStore
import io.github.yhzion.velo.Settings

/** Exercises the platform Editable and BaseInputConnection on an Android runtime. */
class TerminalEditorInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = runCatching {
            var failure: Throwable? = null
            runOnMainSync {
                failure = runCatching {
                    exerciseEditor()
                    exerciseNotificationReturn()
                    exerciseKeptEditors()
                    exerciseDictation()
                    exerciseRecomposedContext()
                    exerciseSendContext()
                    exerciseArmedComposition()
                    exerciseSharedEditorConnections()
                    exerciseInterruptedDictation()
                }.exceptionOrNull()
            }
            failure?.let { throw it }
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "TerminalEditor regression passed")
        })
    }

    private fun exerciseDictation() {
        val info = EditorInfo()
        TerminalInputView(targetContext).onCreateInputConnection(info)
        val sent = mutableListOf<String>()
        var accepts = true
        val editor = TerminalEditor(View(targetContext), { accepts }, sent::add, sent::add,
            { sent.add("key $it") }, {}, paste = { sent.add("paste $it") })
        if (Build.VERSION.SDK_INT >= 33) {
            editor.commitText("음성", 1, null)
            check(sent == listOf("음성"))
            sent.clear()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            editor.setComposingText("입력중", 1)
            check(editor.replaceText(2, 3, "함", 1, null))
            check(sent == listOf("입력함")) { "Range replacement did not reach the terminal immediately: $sent" }
            editor.finishComposingText()
            check(sent == listOf("입력함"))
            sent.clear()
            accepts = false
            check(!editor.replaceText(0, 0, "다른 창", 1, null))
            accepts = true
        }
        editor.commitText("첫 줄\n다음 줄", 1)
        editor.commitText("\n", 1)
        check(!editor.performEditorAction(EditorInfo.IME_ACTION_NONE))
        check(!editor.performEditorAction(EditorInfo.IME_ACTION_NEXT))
        check(editor.performEditorAction(EditorInfo.IME_ACTION_SEND))
        // A text with a line break is a paste; one line break alone is the keyboard's Enter.
        check(sent == listOf("paste 첫 줄\n다음 줄", "\n", "key ${KeyEvent.KEYCODE_ENTER}")) { "$sent" }
        check(info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT)
        check(info.inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_NORMAL)
        check(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        check(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        check(info.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND)
        check(info.initialSelStart == 0 && info.initialSelEnd == 0)

        // A composition of two lines ended by Enter: the paste asks, and the editor that asked takes no key.
        sent.clear()
        var asking = false
        val asked = TerminalEditor(View(targetContext), { !asking }, sent::add, sent::add,
            { sent.add("key $it") }, { sent.add("forward ${it.keyCode}") }, paste = { sent.add("paste $it"); asking = true })
        asked.setComposingText("ls\npwd", 1)
        check(asked.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
        asked.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        check(sent == listOf("paste ls\npwd")) { "The Enter that ended a pasted composition was sent: $sent" }
    }

    /**
     * The letter a predicting keyboard composes, with Ctrl of the key bar armed, is sent as
     * that key ([TerminalInputView.sendArmedKey] through the [TerminalEditor.armedKey] the
     * view's input connection passes): nothing of it is composed, so it cannot arrive as plain
     * text when the composition ends. The jamo of the Korean layout took that road first
     * (Ctrl with the Korean C key is Ctrl+C); the equivalent Latin letters are C, X and Z.
     */
    private fun exerciseArmedComposition() {
        val sent = mutableListOf<String>()
        var sticky = 0
        val editor = TerminalEditor(View(targetContext), { true },
            { sent.add("commit $it") }, { sent.add("finish $it") }, { sent.add("key $it") }, {},
            armedKey = { text -> armedLatinKey(text, sticky)?.let { sent.add("armed $it"); true } ?: false })
        sticky = KeyEvent.META_CTRL_ON
        check(editor.setComposingText("c", 1) && editor.setComposingText("x", 1) && editor.setComposingText("z", 1)) {
            "An armed Ctrl refused the letter a keyboard composes"
        }
        check(sent == listOf("armed c", "armed x", "armed z")) { "Composed letters went somewhere else: $sent" }
        // The jamo of the two-set layout keeps its place on the same road.
        check(editor.setComposingText("ㅊ", 1))
        check(sent.last() == "armed c") { "The jamo lost its key: $sent" }
        // None of the taken letters was composed: ending the composition sends nothing.
        check(editor.finishComposingText())
        check(sent == listOf("armed c", "armed x", "armed z", "armed c")) { "$sent" }
        // Without Ctrl the letter is the composition, and the key bar's Shift alone leaves it
        // one: it ends as the plain letter, which only then is text.
        sticky = 0
        check(editor.setComposingText("c", 1))
        sticky = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        check(editor.setComposingText("c", 1))
        check(editor.finishComposingText())
        check(sent.last() == "finish c") { "A letter without Ctrl or with Shift alone became a key: $sent" }
        // Samsung Keyboard takes the 1 before the cursor back into the composition first (#282):
        // the jamo typed after it is still the key, and the 1 is not sent again.
        sent.clear()
        sticky = 0
        editor.commitText("1", 1)
        sticky = KeyEvent.META_CTRL_ON
        editor.setComposingRegion(0, 1)
        check(editor.setComposingText("1ㅊ", 1))
        check(sent == listOf("commit 1", "armed c")) { "The jamo after a taken-back digit lost its key: $sent" }
        sticky = 0
        check(editor.finishComposingText())
        check(sent == listOf("commit 1", "armed c")) { "The digit went again: $sent" }
    }

    /**
     * Samsung Keyboard reads the character before the cursor and, when it is a letter or a digit,
     * takes it back into its composition with setComposingRegion before it adds the next jamo:
     * a digit followed by a jamo composes both (#282). The digit is already on the remote, so only what the
     * composition adds is sent and drawn. The calls are the ones the keyboard made on the Fold8.
     */
    private fun exerciseRecomposedContext() {
        val sent = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val state = TerminalEditorState(preeditShown = shown::add)
        val keyboard = TerminalEditor(View(targetContext), { true }, sent::add, sent::add,
            { sent.add("key $it") }, {}, state = state)
        fun expect(case: String, vararg expected: String) {
            check(sent == expected.toList()) { "$case sent $sent" }
            check(shown.lastOrNull().isNullOrEmpty()) { "$case left a composition on the pane: $shown" }
            sent.clear()
            shown.clear()
        }
        // A number-row digit or a letter: committed alone, then taken back for the jamo after it.
        // The keyboard does not see what the call returns: it composes over the word either way.
        fun takeBack(word: String) {
            check(keyboard.getTextBeforeCursor(64, 0).toString() == word) { "Context before $word" }
            keyboard.setComposingRegion(0, word.length)
        }
        keyboard.commitText("1", 1)
        keyboard.finishComposingText()
        keyboard.finishComposingText()
        takeBack("1")
        keyboard.setComposingText("1ㅎ", 1)
        check(shown.last() == "ㅎ") { "The pane drew the digit it already has: $shown" }
        keyboard.setComposingText("1하", 1)
        check(shown.last() == "하") { "$shown" }
        keyboard.commitText("1하", 1)
        keyboard.commitText(" ", 1)
        expect("1 하", "1", "하", " ")

        // Velo exposes one character: after 12 the keyboard takes back the 2 alone.
        keyboard.commitText("1", 1)
        keyboard.commitText("2", 1)
        takeBack("2")
        keyboard.setComposingText("2ㅎ", 1)
        keyboard.setComposingText("2하", 1)
        keyboard.commitText("2하", 1)
        keyboard.commitText(" ", 1)
        expect("12 하", "1", "2", "하", " ")

        // An English letter the keyboard composed, ended by the switch to Korean.
        keyboard.setComposingText("a", 1)
        keyboard.finishComposingText()
        takeBack("a")
        keyboard.setComposingText("aㅎ", 1)
        keyboard.setComposingText("a하", 1)
        keyboard.finishComposingText()
        keyboard.commitText(" ", 1)
        expect("a 하", "a", "하", " ")

        // Cases that sent each character once already stay that way.
        keyboard.setComposingText("ㅎ", 1)
        keyboard.setComposingText("하", 1)
        keyboard.setComposingText("하1", 1)
        keyboard.commitText("하1", 1)
        keyboard.commitText(" ", 1)
        expect("하 1", "하1", " ")
        for (symbol in listOf("#", "/")) {
            keyboard.commitText(symbol, 1)
            keyboard.setComposingText("ㅎ", 1)
            keyboard.setComposingText("하", 1)
            keyboard.commitText("하", 1)
            keyboard.commitText(" ", 1)
            expect("$symbol 하", symbol, "하", " ")
        }
        // Gboard composes the jamo alone after a digit.
        keyboard.commitText("1", 1)
        keyboard.setComposingText("ㅎ", 1)
        keyboard.setComposingText("하", 1)
        keyboard.commitText("하", 1)
        expect("Gboard digit and syllable", "1", "하")

        // Backspace through the composition removes the taken-back digit from the remote as well.
        keyboard.commitText(" ", 1)
        keyboard.commitText("1", 1)
        takeBack("1")
        keyboard.setComposingText("1ㅎ", 1)
        keyboard.setComposingText("1", 1)
        check(sent == listOf(" ", "1")) { "The digit went while the keyboard still composed it: $sent" }
        keyboard.setComposingText("", 1)
        keyboard.finishComposingText()
        expect("Digit, jamo, two backspaces", " ", "1", "key ${KeyEvent.KEYCODE_DEL}")
        keyboard.commitText("1", 1)
        takeBack("1")
        keyboard.deleteSurroundingText(1, 0)
        keyboard.finishComposingText()
        expect("1 taken back, deleted", "1", "key ${KeyEvent.KEYCODE_DEL}")
        // A commit that replaces it, or cancels the composition, erases it first.
        keyboard.commitText("1", 1)
        takeBack("1")
        keyboard.setComposingText("1ㅎ", 1)
        keyboard.commitText("2하", 1)
        expect("1 replaced", "1", "key ${KeyEvent.KEYCODE_DEL}", "2하")
        keyboard.commitText("1", 1)
        takeBack("1")
        keyboard.commitText("", 1)
        expect("1 cancelled", "1", "key ${KeyEvent.KEYCODE_DEL}")
        // With the cursor moved before it, a Backspace reaches past the digit on the remote: the
        // digit goes first, so the b goes, and the commit resends the digit with the syllable.
        keyboard.commitText("ab", 1)
        keyboard.commitText("1", 1)
        takeBack("1")
        keyboard.setComposingText("1ㅎ", 1)
        check(keyboard.setSelection(0, 0))
        keyboard.deleteSurroundingText(1, 0)
        keyboard.commitText("1하", 1)
        expect("cursor before 1, ⌫", "ab", "1", "key ${KeyEvent.KEYCODE_DEL}", "key ${KeyEvent.KEYCODE_DEL}", "1하")
        state.close()
    }

    private fun exerciseSendContext() {
        val sent = mutableListOf<String>()
        var focused = true
        var plain = true
        val changes = mutableListOf<Pair<Int, Int>>()
        val state = TerminalEditorState(selectionChanged = { start, end -> changes.add(start to end) })
        fun client() = TerminalEditor(View(targetContext), { focused }, sent::add, sent::add,
            { sent.add("key $it") }, {}, state = state, plainInput = { plain })
        val keyboard = client()
        val other = client()
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        keyboard.commitText("A🙂", 1)
        check(changes.last() == (2 to 2))
        check(keyboard.getTextBeforeCursor(1000, 0).toString() == "🙂")
        check(keyboard.getTextBeforeCursor(1, 0).isNullOrEmpty())
        check(other.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        check(keyboard.getSelectedText(0).isNullOrEmpty() && keyboard.getTextAfterCursor(1000, 0).isNullOrEmpty())
        if (Build.VERSION.SDK_INT >= 31) check(keyboard.getSurroundingText(1000, 1000, 0) == null)
        check(!keyboard.setSelection(0, 2) && !keyboard.setComposingRegion(1, 2))
        if (Build.VERSION.SDK_INT >= 34) check(!keyboard.replaceText(0, 2, "재전송", 1, null))
        // The whole character may go back into the keyboard's composition (#282); it is not sent again.
        check(keyboard.setComposingRegion(0, 2))
        keyboard.finishComposingText()
        keyboard.deleteSurroundingText(2, 0)
        check(sent == listOf("A🙂", "key ${KeyEvent.KEYCODE_DEL}"))
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty() && changes.last() == (0 to 0))
        keyboard.commitText("보내기", 1)
        keyboard.performEditorAction(EditorInfo.IME_ACTION_SEND)
        check(sent.takeLast(2) == listOf("보내기", "key ${KeyEvent.KEYCODE_ENTER}"))
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        // A paste is not what this client typed, and its last character is no context.
        keyboard.commitText("a\nb", 1)
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        keyboard.commitText("b", 1)
        check(keyboard.getTextBeforeCursor(1000, 0).toString() == "b")
        keyboard.commitText("a\n", 1)
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        keyboard.commitText("\u001b[A", 1)
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        plain = false
        keyboard.commitText("c", 1)
        check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        plain = true
        // Gboard drops its Korean composition when told the cursor moved: starting the next
        // syllable after a commit tells it nothing.
        keyboard.commitText("가", 1)
        val reported = changes.size
        keyboard.setComposingText("ㄱ", 1)
        keyboard.setComposingText("가", 1)
        check(changes.size == reported) { "The start of a composition moved the keyboard's cursor: ${changes.drop(reported)}" }
        keyboard.commitText("가", 1)
        check(sent.takeLast(2) == listOf("가", "가")) { "$sent" }
        check(changes.size == reported + 1 && changes.last() == (1 to 1))
        // A composition that ends without a character to show tells the keyboard once it ends.
        for (end in listOf("", "\n")) {
            keyboard.commitText("가", 1)
            val before = changes.size
            keyboard.setComposingText("ㄴ", 1)
            check(changes.size == before)
            keyboard.commitText(end, 1)
            check(keyboard.getTextBeforeCursor(1000, 0).isNullOrEmpty())
            check(changes.size == before + 1 && changes.last() == (0 to 0)) { "'$end' left the cursor at ${changes.drop(before)}" }
        }
        // The pane shows what the keyboard composes, from its first jamo until the commit.
        val shown = mutableListOf<String>()
        val drawn = TerminalEditorState(preeditShown = shown::add)
        val typing = TerminalEditor(View(targetContext), { true }, sent::add, sent::add,
            { sent.add("key $it") }, {}, state = drawn)
        typing.setComposingText("ㅇ", 1)
        typing.setComposingText("안", 1)
        typing.setComposingText("안", 1)
        typing.setComposingText("안ㄴ", 1)
        typing.deleteSurroundingText(1, 0)
        typing.commitText("안", 1)
        check(shown == listOf("ㅇ", "안", "안ㄴ", "안", "")) { "Shown compositions: $shown" }
        typing.setComposingText("녕", 1)
        // A window over the app hides it, keeps it, and shows the current one when it goes.
        drawn.preeditHidden = true
        typing.setComposingText("녕ㅎ", 1)
        check(shown.takeLast(2) == listOf("녕", "")) { "A covered pane showed the composition: $shown" }
        drawn.preeditHidden = false
        check(shown.last() == "녕ㅎ") { "The composition did not come back: $shown" }
        drawn.close()
        check(shown.last() == "") { "A closed editor left its composition on the pane: $shown" }
        other.commitText("다", 1)
        focused = false
        check(other.getTextBeforeCursor(1000, 0).isNullOrEmpty())
        focused = true
        state.close()
        check(other.getTextBeforeCursor(1000, 0).isNullOrEmpty())
    }

    private fun exerciseSharedEditorConnections() {
        val sent = mutableListOf<String>()
        val state = TerminalEditorState()
        var focused = true
        fun client() = TerminalEditor(View(targetContext), { focused }, sent::add, sent::add,
            { sent.add("key $it") }, {}, { !focused }, state)
        val keyboard = client()
        val service = client()
        keyboard.setComposingText("미확정", 1)
        check(service.getTextBeforeCursor(4, 0).toString() == "미확정")
        service.commitText("확정", 1)
        keyboard.finishComposingText()
        check(sent == listOf("확정"))

        focused = false
        keyboard.commitText("첫 ", 1)
        service.commitText("폐기", 1)
        service.performEditorAction(EditorInfo.IME_ACTION_SEND)
        keyboard.commitText("끝", 1)
        service.closeConnection()
        service.closeConnection()
        check(!service.commitText("닫힘", 1))
        focused = true
        state.flushDeferred()
        check(sent == listOf("확정", "첫 ", "끝"))

        val probe = client()
        keyboard.setComposingText("남김", 1)
        probe.closeConnection()
        check(probe.getTextBeforeCursor(8, 0).isNullOrEmpty())
        check(!probe.setSelection(0, 0))
        check(!probe.setComposingRegion(0, 1))
        check(keyboard.getTextBeforeCursor(8, 0).toString() == "남김")
        keyboard.finishComposingText()
        check(sent.last() == "남김")
        state.close()
        check(!keyboard.commitText("다른 대상", 1))
    }

    private fun exerciseInterruptedDictation() {
        var focused = false
        var interrupted = true
        val sent = mutableListOf<String>()
        val state = TerminalEditorState()
        val editor = TerminalEditor(View(targetContext), { focused }, sent::add, sent::add,
            { sent.add("key $it") }, {}, { interrupted }, state)
        check(editor.commitText("첫 ", 1))
        check(editor.setComposingText("끝", 1))
        check(editor.performEditorAction(EditorInfo.IME_ACTION_SEND))
        check(!editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
        check(sent.isEmpty())

        interrupted = false
        focused = true
        state.flushDeferred()
        state.flushDeferred()
        check(sent == listOf("첫 ", "끝", "key ${KeyEvent.KEYCODE_ENTER}"))
        sent.clear()

        focused = false
        interrupted = true
        check(editor.commitText("다른 창", 1))
        state.discardDeferred()
        interrupted = false
        check(!editor.commitText("이전 연결", 1))
        focused = true
        state.flushDeferred()
        check(sent.isEmpty())

        focused = false
        interrupted = true
        check(editor.commitText("닫힌 창", 1))
        editor.closeConnection()
        focused = true
        state.flushDeferred()
        check(sent.isEmpty())
    }

    /** A connection that never connects: the view binds a pane of it. */
    private fun parkedHost(): HostConnection {
        val prefs = targetContext.getSharedPreferences("editor-regression", Context.MODE_PRIVATE)
        val env = CoreEnv(emptyArray(), 1f, 1f, GlyphRasterizer(targetContext.assets), Settings(prefs), HostStore(prefs))
        return HostConnection("ws://127.0.0.1:1", env, initiallyParked = true, waitForNode = true, onConnectivityChanged = {})
    }

    /**
     * The keyboard's input is restarted only to retire a composition (#295): an accessibility
     * input method stops writing through a restarted connection. Without preedit or deferred
     * writes the live editors stay open and follow the bound pane. With them a change of pane
     * closes the editors, and what they held for the old pane goes unsent.
     */
    private fun exerciseKeptEditors() {
        val view = TerminalInputView(targetContext)
        val host = parkedHost()
        fun bind(pane: String) {
            view.connection = host
            view.target = { pane }
            view.workspaceAcceptsInput = true
            view.refreshEditorTarget()
        }
        fun editor() = view.onCreateInputConnection(EditorInfo()) as TerminalEditor
        try {
            // Android can ask for a connection before the workspace binds a pane: the pane then uses it.
            val kept = editor()
            bind("pane-a")
            check(kept.editable != null) { "Binding a pane closed an editor that held no composition" }
            check(editor().editable === kept.editable) { "A new connection did not join the live editors" }
            bind("pane-b")
            check(kept.editable != null) { "A change of pane closed an editor that held no composition" }
            check(editor().editable === kept.editable) { "A connection for the new pane replaced the live editors" }
            view.disconnectInput()
            check(kept.editable != null) { "Disconnecting closed an editor that held no composition" }
            bind("pane-a")

            // A pause keeps the editors and discards what they compose. A resume keeps them as well.
            view.onActivityResumed()
            check(kept.editable != null) { "A resume closed the editors" }
            checkNotNull(kept.editable).append("중")
            view.onActivityPaused()
            check(kept.editable?.isEmpty() == true) { "A pause closed the editors or kept their composition: ${kept.editable}" }
            view.onActivityResumed()
            check(kept.editable != null) { "A resume after a pause closed the editors" }
            bind("pane-b")
            check(kept.editable != null) { "A composition that a pause discarded still closed the editors" }

            // A composition for the old pane is dropped with its editors: the keyboard has to forget it.
            checkNotNull(kept.editable).append("전")
            bind("pane-a")
            check(kept.editable == null) { "A change of pane kept the composition for the old pane" }

            // Writes that wait behind a window over the app are retired the same way.
            check(view.requestFocus()) { "The input view took no focus" }
            val waiting = editor()
            view.onWindowFocusChanged(false)
            check(waiting.commitText("대기", 1)) { "A write behind a window over the app was refused" }
            bind("pane-b")
            check(waiting.editable == null) { "A change of pane kept the writes that waited for the old pane" }
            // The pane changes behind a window that holds no writes yet: what that window confirms
            // afterwards still waits for the focus, for the pane bound then.
            val behind = editor()
            view.onWindowFocusChanged(false)
            view.disconnectInput()
            check(!behind.commitText("무대상", 1)) { "An editor took input while no pane was bound" }
            bind("pane-a")
            check(behind.commitText("대기", 1)) { "A change of pane behind a window over the app refused its next write" }
            // A pause discards the waiting writes and refuses more. Nothing is left to retire, so the editors stay.
            view.onActivityPaused()
            check(!behind.commitText("정지", 1)) { "A paused editor took input" }
            view.onActivityResumed()
            bind("pane-b")
            check(behind.editable != null) { "Writes that a pause discarded still closed the editors" }
            view.disconnectInput()
        } finally {
            host.destroy()
        }
    }

    private fun exerciseNotificationReturn() {
        val view = TerminalInputView(targetContext)
        val host = parkedHost()
        fun bindSamePane() {
            view.connection = host
            view.target = { "pane-a" }
            view.workspaceAcceptsInput = true
            view.refreshEditorTarget()
        }
        fun editor() = view.onCreateInputConnection(EditorInfo()) as TerminalEditor
        try {
            bindSamePane()
            // Seed preedit directly: this view has no window or IME focus in instrumentation.
            val before = editor()
            checkNotNull(before.editable).append("전")
            view.disconnectInput()
            check(before.editable.isNullOrEmpty())
            check(!view.workspaceAcceptsInput && view.connection == null && view.target() == null)
            // Android can request a connection while the notification gate is still up.
            val blocked = editor()
            checkNotNull(blocked.editable).append("중")
            bindSamePane()
            check(blocked.editable.isNullOrEmpty())
            check(!blocked.commitText("중", 1))
            check(!before.finishComposingText())
            val fresh = editor()
            checkNotNull(fresh.editable).append("새")
            bindSamePane()
            check(fresh.editable.toString() == "새")
            view.disconnectInput()
        } finally {
            host.destroy()
        }
    }

    private fun exerciseEditor() {
        val events = mutableListOf<String>()
        val view = View(targetContext)
        var current: TerminalEditor? = null
        fun newEditor() = TerminalEditor(view, { it === current },
            { events.add(it) }, { events.add(it) }, { events.add("key $it") },
            { events.add("event ${it.keyCode} ${it.action}") }).also { current = it }
        fun expect(vararg expected: String) {
            check(events == expected.toList()) { "Expected ${expected.toList()}, got $events" }
            events.clear()
        }

        val editor = newEditor()
        editor.commitText("한", 1)
        editor.setComposingText("ㄱ", 1)
        editor.setComposingText("글", 1)
        check(editor.getTextBeforeCursor(4, 0).toString() == "글")
        expect("한")
        editor.finishComposingText()
        editor.finishComposingText()
        expect("글")

        editor.setComposingText("가", 1)
        editor.commitText("각", 1)
        editor.finishComposingText()
        editor.commitText("각", 1)
        expect("각", "각")

        editor.setComposingText("글", 1)
        check(editor.finishBeforeExternalKey())
        val restarted = newEditor()
        check(!editor.commitText("글", 1))
        expect("글")

        restarted.setComposingText("한글", 1)
        restarted.deleteSurroundingText(1, 0)
        restarted.performEditorAction(EditorInfo.IME_ACTION_DONE)
        expect("한", "key ${KeyEvent.KEYCODE_ENTER}")
        restarted.setComposingText("🙂", 1)
        restarted.deleteSurroundingTextInCodePoints(2, 1)
        restarted.finishComposingText()
        expect("key ${KeyEvent.KEYCODE_DEL}", "key ${KeyEvent.KEYCODE_FORWARD_DEL}")

        restarted.setComposingText("끝", 1)
        restarted.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        restarted.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        expect("끝", "event ${KeyEvent.KEYCODE_ENTER} 0", "event ${KeyEvent.KEYCODE_ENTER} 1")

        restarted.setComposingText("전", 1)
        val switched = newEditor()
        check(!restarted.finishComposingText())
        check(!restarted.commitText("전", 1))
        restarted.closeConnection()
        switched.commitText("새", 1)
        expect("새")
    }
}
