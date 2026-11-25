package io.github.yhzion.velo.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Replays the calls on-screen keyboards make into [TerminalEditor] and checks, after each call,
 * what the remote program's input line holds, what the pane draws over it, and what that call
 * sent (#18). Latin letters reach the remote as they are typed, as on a hardware keyboard; a
 * Hangul syllable is drawn while it is composed and sent once it is finished.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyboardReplayTest {
    /**
     * The remote program's input line, as a shell keeps it: a text appends, Backspace erases the
     * last code point, Enter runs the line. [events] is what reached it during one call, with
     * adjacent texts joined: how a fix splits a text into writes is not the remote's concern.
     */
    private class Remote {
        val line = StringBuilder()
        val entered = mutableListOf<String>()
        val events = mutableListOf<String>()
        private var lastWasText = false

        /** Starts the record of one call. */
        fun newCall() {
            events.clear()
            lastWasText = false
        }

        fun text(text: String) {
            if (text in setOf("\n", "\r", "\r\n")) {
                enter()
                return
            }
            line.append(text)
            if (lastWasText) events[events.lastIndex] += text else events.add(text)
            lastWasText = true
        }

        fun key(code: Int) {
            lastWasText = false
            when (code) {
                KeyEvent.KEYCODE_DEL -> {
                    if (line.isNotEmpty()) line.setLength(line.offsetByCodePoints(line.length, -1))
                    events.add(DEL)
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> events.add(FORWARD_DEL)
                KeyEvent.KEYCODE_ENTER -> enter()
                else -> events.add("key $code")
            }
        }

        /** A key the keyboard sent as an event: the press types, the release does nothing. */
        fun forward(event: KeyEvent) {
            if (event.action == KeyEvent.ACTION_DOWN) key(event.keyCode)
        }

        fun paste(text: String) {
            lastWasText = false
            events.add("paste:$text")
        }

        fun armed(key: Char) {
            lastWasText = false
            events.add("armed:$key")
        }

        private fun enter() {
            lastWasText = false
            entered.add(line.toString())
            line.clear()
            events.add(ENTER)
        }
    }

    /** One keyboard on one pane, with the editor state the input view shares between connections. */
    private class Replay(armedKey: (Remote, String) -> Boolean = { _, _ -> false }) {
        val remote = Remote()
        var overlay = ""
            private set
        val state = TerminalEditorState(preeditShown = { overlay = it })
        val ime = TerminalEditor(
            View(RuntimeEnvironment.getApplication()),
            accepts = { true },
            commit = remote::text,
            finish = remote::text,
            key = remote::key,
            forward = remote::forward,
            state = state,
            armedKey = { armedKey(remote, it) },
            paste = remote::paste,
        )

        /**
         * Runs one keyboard [call] and checks what it [sends], the remote [line] after it, and
         * the [overlay] the pane draws at the cursor after it ("" for none).
         */
        fun step(call: String, sends: List<String>, line: String, overlay: String = "", action: TerminalEditor.() -> Unit) {
            remote.newCall()
            ime.action()
            assertEquals("$call sent", sends, remote.events.toList())
            assertEquals("$call left the remote line", line, remote.line.toString())
            assertEquals("$call left the overlay", overlay, this.overlay)
        }

        /** A Latin word composed letter by letter, each letter streamed as it is typed. */
        fun typeComposing(word: String, before: String) {
            for (end in 1..word.length) {
                val text = word.substring(0, end)
                step("setComposingText($text)", listOf(word.substring(end - 1, end)), before + text) {
                    setComposingText(text, 1)
                }
            }
        }

        fun keyDown(code: Int) = KeyEvent(KeyEvent.ACTION_DOWN, code)
        fun keyUp(code: Int) = KeyEvent(KeyEvent.ACTION_UP, code)
    }

    private fun replay(armedKey: (Remote, String) -> Boolean = { _, _ -> false }, body: Replay.() -> Unit) {
        val replay = Replay(armedKey)
        try {
            replay.body()
        } finally {
            replay.state.close()
        }
    }

    @Test fun samsungEnglishReachesTheRemoteLetterByLetterAndTheSpaceSendsOnlyItself() = replay {
        step("setComposingText(h)", listOf("h"), "h") { setComposingText("h", 1) }
        step("setComposingText(he)", listOf("e"), "he") { setComposingText("he", 1) }
        step("setComposingText(hel)", listOf("l"), "hel") { setComposingText("hel", 1) }
        step("setComposingText(hell)", listOf("l"), "hell") { setComposingText("hell", 1) }
        step("setComposingText(hello)", listOf("o"), "hello") { setComposingText("hello", 1) }
        // The keyboard's view of its composition is what it was: it still predicts from the whole word.
        assertEquals("hello", ime.getTextBeforeCursor(16, 0).toString())
        val editable = checkNotNull(ime.editable)
        assertEquals(0, BaseInputConnection.getComposingSpanStart(editable))
        assertEquals(5, BaseInputConnection.getComposingSpanEnd(editable))
        step("commitText(hello )", listOf(" "), "hello ") { commitText("hello ", 1) }
        // The next word streams the same way.
        typeComposing("ls", before = "hello ")
        step("finishComposingText()", emptyList(), "hello ls") { finishComposingText() }
    }

    @Test fun aBackspaceInsideAnEnglishCompositionErasesOnTheRemoteAtOnce() = replay {
        step("commitText(ab)", listOf("ab"), "ab") { commitText("ab", 1) }
        typeComposing("hel", before = "ab")
        // Samsung Keyboard shortens its composition.
        step("setComposingText(he)", listOf(DEL), "abhe") { setComposingText("he", 1) }
        step("setComposingText(hey)", listOf("y"), "abhey") { setComposingText("hey", 1) }
        // A keyboard that deletes through the editable instead.
        step("deleteSurroundingText(1, 0)", listOf(DEL), "abhe") { deleteSurroundingText(1, 0) }
        step("deleteSurroundingTextInCodePoints(1, 0)", listOf(DEL), "abh") { deleteSurroundingTextInCodePoints(1, 0) }
        // Past the front of the composition, the Backspace reaches what was there before it.
        step("deleteSurroundingText(2, 0)", listOf(DEL, DEL), "a") { deleteSurroundingText(2, 0) }
        step("finishComposingText()", emptyList(), "a") { finishComposingText() }
        typeComposing("go", before = "a")
        step("setComposingText(g)", listOf(DEL), "ag") { setComposingText("g", 1) }
        step("setComposingText()", listOf(DEL), "a") { setComposingText("", 1) }
        step("finishComposingText()", emptyList(), "a") { finishComposingText() }
    }

    @Test fun autocorrectAtCommitErasesAndRetypesOnlyTheDifference() = replay {
        typeComposing("teh", before = "")
        step("commitText(the )", listOf(DEL, DEL, "he "), "the ") { commitText("the ", 1) }
        // A suggestion that completes the word sends only the letters it adds.
        typeComposing("hel", before = "the ")
        step("commitText(hello )", listOf("lo "), "the hello ") { commitText("hello ", 1) }
        // A transposition erases back to the first letter that differs.
        typeComposing("wrod", before = "the hello ")
        step("commitText(word )", listOf(DEL, DEL, DEL, "ord "), "the hello word ") { commitText("word ", 1) }
        // A suggestion that changes the first letter retypes the whole word.
        typeComposing("yhe", before = "the hello word ")
        step("commitText(the )", listOf(DEL, DEL, DEL, "the "), "the hello word the ") { commitText("the ", 1) }
    }

    @Test fun aKeyboardThatCommitsEachLetterStillSendsEachAtOnce() = replay {
        step("commitText(h)", listOf("h"), "h") { commitText("h", 1) }
        step("commitText(i)", listOf("i"), "hi") { commitText("i", 1) }
        step("commitText( )", listOf(" "), "hi ") { commitText(" ", 1) }
        step("commitText(x)", listOf("x"), "hi x") { commitText("x", 1) }
        step("deleteSurroundingText(1, 0)", listOf(DEL), "hi ") { deleteSurroundingText(1, 0) }
        step("sendKeyEvent(DEL down)", listOf(DEL), "hi") { sendKeyEvent(keyDown(KeyEvent.KEYCODE_DEL)) }
        step("sendKeyEvent(DEL up)", emptyList(), "hi") { sendKeyEvent(keyUp(KeyEvent.KEYCODE_DEL)) }
        step("commitText(\\n)", listOf(ENTER), "") { commitText("\n", 1) }
        assertEquals(listOf("hi"), remote.entered)
    }

    @Test fun koreanTypingSendsOnlyFinishedSyllablesAsItDoesToday() = replay {
        step("setComposingText(ㄱ)", emptyList(), "", overlay = "ㄱ") { setComposingText("ㄱ", 1) }
        step("setComposingText(가)", emptyList(), "", overlay = "가") { setComposingText("가", 1) }
        step("setComposingText(각)", emptyList(), "", overlay = "각") { setComposingText("각", 1) }
        // ㅗ moves the final ㄱ into the next syllable: the keyboard commits 가 and composes 고.
        step("commitText(가)", listOf("가"), "가") { commitText("가", 1) }
        step("setComposingText(고)", emptyList(), "가", overlay = "고") { setComposingText("고", 1) }
        step("finishComposingText()", listOf("고"), "가고") { finishComposingText() }
        step("commitText( )", listOf(" "), "가고 ") { commitText(" ", 1) }
        // The same split with finishComposingText, as some keyboards end the syllable.
        step("setComposingText(ㅎ)", emptyList(), "가고 ", overlay = "ㅎ") { setComposingText("ㅎ", 1) }
        step("setComposingText(하)", emptyList(), "가고 ", overlay = "하") { setComposingText("하", 1) }
        step("setComposingText(한)", emptyList(), "가고 ", overlay = "한") { setComposingText("한", 1) }
        step("setComposingText(하)", emptyList(), "가고 ", overlay = "하") { setComposingText("하", 1) }
        step("finishComposingText()", listOf("하"), "가고 하") { finishComposingText() }
        step("setComposingText(느)", emptyList(), "가고 하", overlay = "느") { setComposingText("느", 1) }
        step("setComposingText(늘)", emptyList(), "가고 하", overlay = "늘") { setComposingText("늘", 1) }
        step("commitText(늘)", listOf("늘"), "가고 하늘") { commitText("늘", 1) }
        // Backspace inside a syllable changes only the composition.
        step("setComposingText(ㅇ)", emptyList(), "가고 하늘", overlay = "ㅇ") { setComposingText("ㅇ", 1) }
        step("setComposingText(안)", emptyList(), "가고 하늘", overlay = "안") { setComposingText("안", 1) }
        step("setComposingText(아)", emptyList(), "가고 하늘", overlay = "아") { setComposingText("아", 1) }
        step("deleteSurroundingText(1, 0)", emptyList(), "가고 하늘") { deleteSurroundingText(1, 0) }
        step("finishComposingText()", emptyList(), "가고 하늘") { finishComposingText() }
    }

    @Test fun englishThenHangulStreamsTheLettersAndHoldsTheSyllable() = replay {
        typeComposing("hi", before = "")
        step("commitText(hi )", listOf(" "), "hi ") { commitText("hi ", 1) }
        step("setComposingText(ㅎ)", emptyList(), "hi ", overlay = "ㅎ") { setComposingText("ㅎ", 1) }
        step("setComposingText(하)", emptyList(), "hi ", overlay = "하") { setComposingText("하", 1) }
        step("commitText(하)", listOf("하"), "hi 하") { commitText("하", 1) }
        // Samsung Keyboard ends an English letter at the switch to Korean, then takes it back (#282).
        typeComposing("a", before = "hi 하")
        step("finishComposingText()", emptyList(), "hi 하a") { finishComposingText() }
        assertEquals("a", ime.getTextBeforeCursor(64, 0).toString())
        step("setComposingRegion(0, 1)", emptyList(), "hi 하a") { setComposingRegion(0, 1) }
        step("setComposingText(aㅎ)", emptyList(), "hi 하a", overlay = "ㅎ") { setComposingText("aㅎ", 1) }
        step("setComposingText(a하)", emptyList(), "hi 하a", overlay = "하") { setComposingText("a하", 1) }
        step("finishComposingText()", listOf("하"), "hi 하a하") { finishComposingText() }
        // A Hangul syllable added to a composition the remote already has the Latin part of.
        typeComposing("x", before = "hi 하a하")
        step("setComposingText(xㅎ)", emptyList(), "hi 하a하x", overlay = "ㅎ") { setComposingText("xㅎ", 1) }
        step("setComposingText(x하)", emptyList(), "hi 하a하x", overlay = "하") { setComposingText("x하", 1) }
        step("commitText(x하)", listOf("하"), "hi 하a하x하") { commitText("x하", 1) }
    }

    /** The calls Samsung Keyboard made on the Fold8 for a digit and the jamo after it (#282). */
    @Test fun aDigitTakenBackIntoTheCompositionIsNotSentAgain() = replay {
        step("commitText(1)", listOf("1"), "1") { commitText("1", 1) }
        step("finishComposingText()", emptyList(), "1") { finishComposingText() }
        step("finishComposingText()", emptyList(), "1") { finishComposingText() }
        assertEquals("1", ime.getTextBeforeCursor(64, 0).toString())
        step("setComposingRegion(0, 1)", emptyList(), "1") { setComposingRegion(0, 1) }
        step("setComposingText(1ㅎ)", emptyList(), "1", overlay = "ㅎ") { setComposingText("1ㅎ", 1) }
        step("setComposingText(1하)", emptyList(), "1", overlay = "하") { setComposingText("1하", 1) }
        step("commitText(1하)", listOf("하"), "1하") { commitText("1하", 1) }
        step("commitText( )", listOf(" "), "1하 ") { commitText(" ", 1) }
        // A Latin letter after a digit taken back streams like any letter: only the letter is new.
        step("commitText(2)", listOf("2"), "1하 2") { commitText("2", 1) }
        step("setComposingRegion(0, 1)", emptyList(), "1하 2") { setComposingRegion(0, 1) }
        step("setComposingText(2a)", listOf("a"), "1하 2a") { setComposingText("2a", 1) }
        step("commitText(2a )", listOf(" "), "1하 2a ") { commitText("2a ", 1) }
        // The digit deleted from the composition goes from the remote as well.
        step("commitText(3)", listOf("3"), "1하 2a 3") { commitText("3", 1) }
        step("setComposingRegion(0, 1)", emptyList(), "1하 2a 3") { setComposingRegion(0, 1) }
        step("deleteSurroundingText(1, 0)", listOf(DEL), "1하 2a ") { deleteSurroundingText(1, 0) }
        step("finishComposingText()", emptyList(), "1하 2a ") { finishComposingText() }
    }

    @Test fun aLatinCompositionThatJumpsSeveralCharactersIsHeldUntilCommit() = replay {
        // A voice transcript arrives in pieces of words.
        step("setComposingText(hello)", emptyList(), "", overlay = "hello") { setComposingText("hello", 1) }
        step("setComposingText(hello world)", emptyList(), "", overlay = "hello world") { setComposingText("hello world", 1) }
        step("commitText(hello world)", listOf("hello world"), "hello world") { commitText("hello world", 1) }
        // After letters already streamed, only the part past them waits.
        typeComposing("ok", before = "hello world")
        step("setComposingText(ok then)", emptyList(), "hello worldok", overlay = " then") { setComposingText("ok then", 1) }
        step("finishComposingText()", listOf(" then"), "hello worldok then") { finishComposingText() }
    }

    @Test fun enterInsideAnEnglishCompositionSendsNothingTwice() = replay {
        typeComposing("hi", before = "")
        step("sendKeyEvent(ENTER down)", listOf(ENTER), "") { sendKeyEvent(keyDown(KeyEvent.KEYCODE_ENTER)) }
        step("sendKeyEvent(ENTER up)", emptyList(), "") { sendKeyEvent(keyUp(KeyEvent.KEYCODE_ENTER)) }
        typeComposing("ls", before = "")
        step("performEditorAction(SEND)", listOf(ENTER), "") { performEditorAction(EditorInfo.IME_ACTION_SEND) }
        typeComposing("cd", before = "")
        step("sendKeyEvent(DEL down)", listOf(DEL), "c") { sendKeyEvent(keyDown(KeyEvent.KEYCODE_DEL)) }
        step("sendKeyEvent(DEL up)", emptyList(), "c") { sendKeyEvent(keyUp(KeyEvent.KEYCODE_DEL)) }
        assertEquals(listOf("hi", "ls"), remote.entered)
    }

    @Test fun kanaAndCjkCompositionsAreHeldLikeHangul() = replay {
        step("setComposingText(か)", emptyList(), "", overlay = "か") { setComposingText("か", 1) }
        step("setComposingText(かな)", emptyList(), "", overlay = "かな") { setComposingText("かな", 1) }
        step("commitText(かな)", listOf("かな"), "かな") { commitText("かな", 1) }
        step("setComposingText(你)", emptyList(), "かな", overlay = "你") { setComposingText("你", 1) }
        step("finishComposingText()", listOf("你"), "かな你") { finishComposingText() }
    }

    @Test fun aLetterWithAnArmedCtrlIsStillTheKeyAlone() {
        var ctrl = false
        replay(armedKey = { remote, text ->
            val key = if (ctrl) armedLatinKey(text, KeyEvent.META_CTRL_ON) else null
            key?.let { remote.armed(it); true } ?: false
        }) {
            ctrl = true
            step("setComposingText(c) with Ctrl", listOf("armed:c"), "") { setComposingText("c", 1) }
            step("finishComposingText()", emptyList(), "") { finishComposingText() }
            ctrl = false
            typeComposing("c", before = "")
            step("finishComposingText()", emptyList(), "c") { finishComposingText() }
        }
    }

    @Test fun aTextWithALineBreakIsStillAPaste() = replay {
        step("commitText(ls\\npwd)", listOf("paste:ls\npwd"), "") { commitText("ls\npwd", 1) }
        step("setComposingText(a\\nb)", emptyList(), "", overlay = "a\nb") { setComposingText("a\nb", 1) }
        step("finishComposingText()", listOf("paste:a\nb"), "") { finishComposingText() }
    }

    private companion object {
        const val DEL = "⌫"
        const val FORWARD_DEL = "⌦"
        const val ENTER = "⏎"
    }
}
