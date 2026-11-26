package io.github.yhzion.velo.ui

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A real [TerminalEditor] on a plain view, driven with the calls keyboards make (#18): Latin that
 * composes nothing reaches the remote as it is typed, and everything else as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TerminalEditorStreamTest {
    /** What reaches the remote: each text as it is sent, ⌫ for a Backspace, and the line they make. */
    private class Remote {
        val events = mutableListOf<String>()
        /** The texts that went through the editor's commit; the rest went through finish. */
        val commits = mutableListOf<String>()
        val line = StringBuilder()

        fun text(value: String) {
            events += value
            line.append(value)
        }

        fun key(code: Int) {
            if (code != KeyEvent.KEYCODE_DEL) {
                events += "key $code"
                return
            }
            events += "⌫"
            if (line.isNotEmpty()) line.delete(line.offsetByCodePoints(line.length, -1), line.length)
        }

        fun take(): List<String> = events.toList().also { events.clear() }
    }

    private val remote = Remote()
    private val shown = mutableListOf<String>()
    private val cursor = mutableListOf<Pair<Int, Int>>()
    private val armed = mutableListOf<String>()
    private var armedTakes: (String) -> Boolean = { false }
    private var latinFinal = true
    private var accepts = true
    private var interrupted = false
    private val state = TerminalEditorState({ start, end -> cursor += start to end }, preeditShown = shown::add)
    private val keyboard = editor()

    private fun editor() = TerminalEditor(View(RuntimeEnvironment.getApplication()), { accepts },
        commit = { remote.commits += it; remote.text(it) }, finish = remote::text, key = remote::key, forward = {},
        interrupted = { interrupted }, state = state, armedKey = { armed += it; armedTakes(it) },
        paste = { remote.text("paste $it") }, latinFinal = { latinFinal })

    private fun compose(vararg steps: String) = steps.forEach { assertTrue(it, keyboard.setComposingText(it, 1)) }

    /** Samsung Keyboard takes the character before its cursor back into its composition (#282). */
    private fun takeBack(character: String) {
        assertEquals(character, keyboard.getTextBeforeCursor(64, 0).toString())
        keyboard.setComposingRegion(0, character.length)
    }

    private fun nothingShown() = assertTrue("The pane drew a composition: $shown", shown.all { it.isEmpty() })

    @Test fun samsungEnglishSendsEachLetterAsItIsTypedAndTheSpaceAlone() {
        // The ways a predicting keyboard ends a word at the space.
        val endings = listOf<(String) -> Unit>(
            { word -> keyboard.commitText("$word ", 1) },
            { word -> keyboard.commitText(word, 1); keyboard.commitText(" ", 1) },
            { _ -> keyboard.finishComposingText(); keyboard.commitText(" ", 1) },
        )
        for (end in endings) {
            compose("h", "he", "hel", "hell", "hello")
            assertEquals(listOf("h", "e", "l", "l", "o"), remote.take())
            end("hello")
            assertEquals(listOf(" "), remote.take())
        }
        assertEquals("hello hello hello ", remote.line.toString())
        nothingShown()
        // The letters went as the text of a composition (finish), not as commits: of these only the spaces did.
        assertEquals(listOf(" ", " ", " "), remote.commits)
    }

    @Test fun backspaceInAnEnglishCompositionErasesOnTheRemoteAtOnce() {
        compose("h", "he", "hel")
        remote.take()
        // Samsung Keyboard composes the shorter word.
        compose("he")
        assertEquals(listOf("⌫"), remote.take())
        // Another keyboard deletes from its composition.
        assertTrue(keyboard.deleteSurroundingText(1, 0))
        assertEquals(listOf("⌫"), remote.take())
        compose("")
        assertEquals(listOf("⌫"), remote.take())
        keyboard.finishComposingText()
        assertEquals(emptyList<String>(), remote.take())
        assertEquals("", remote.line.toString())
        nothingShown()
    }

    @Test fun autocorrectAtTheCommitErasesAndRetypesOnlyTheDifference() {
        compose("t", "te", "teh")
        keyboard.commitText("the ", 1)
        assertEquals(listOf("t", "e", "h", "⌫", "⌫", "he "), remote.take())
        // A suggestion picked before the word is finished.
        compose("w", "wo", "wor")
        keyboard.commitText("world ", 1)
        assertEquals(listOf("w", "o", "r", "ld "), remote.take())
        assertEquals("the world ", remote.line.toString())
        nothingShown()
    }

    @Test fun aJumpOfManyCharactersIsHeldUntilTheCommit() {
        compose("h", "he", "hel")
        remote.take()
        // A suggestion replaces the composition: the remote keeps what it has, the pane draws the rest.
        compose("hello")
        assertEquals(emptyList<String>(), remote.take())
        assertEquals("lo", shown.last())
        compose("hello!")
        assertEquals(emptyList<String>(), remote.take())
        assertEquals("lo!", shown.last())
        keyboard.commitText("hello! ", 1)
        assertEquals(listOf("lo! "), remote.take())
        assertEquals("", shown.last())

        // A voice transcript that grows is held whole.
        compose("hello wor", "hello world")
        assertEquals(emptyList<String>(), remote.take())
        assertEquals("hello world", shown.last())
        keyboard.commitText("hello world", 1)
        assertEquals(listOf("hello world"), remote.take())
        assertEquals("hello! hello world", remote.line.toString())
        assertEquals("", shown.last())
    }

    @Test fun aBackspaceIntoAHeldJumpSendsWhatIsLeftOfIt() {
        compose("h", "he", "hel", "hello")
        remote.take()
        compose("hell")
        assertEquals(listOf("l"), remote.take())
        assertEquals("", shown.last())
    }

    /** The calls Samsung Keyboard and Gboard made for Korean before #18: the same reach the remote. */
    @Test fun koreanSendsWhatItSentBefore() {
        compose("ㅇ", "안", "안", "안ㄴ")
        keyboard.deleteSurroundingText(1, 0)
        keyboard.commitText("안", 1)
        assertEquals(listOf("안"), remote.take())
        assertEquals(listOf("ㅇ", "안", "안ㄴ", "안", ""), shown)

        // Gboard ends a syllable when the next jamo cannot join it.
        compose("ㄱ", "가", "각")
        keyboard.commitText("각", 1)
        compose("ㅎ", "하")
        keyboard.commitText("하", 1)
        keyboard.commitText(" ", 1)
        assertEquals(listOf("각", "하", " "), remote.take())

        // A Korean composition that ends with a digit stays one text.
        compose("ㅎ", "하", "하1")
        keyboard.commitText("하1", 1)
        assertEquals(listOf("하1"), remote.take())
        assertEquals("안각하 하1", remote.line.toString())
    }

    /** Samsung Keyboard takes a digit or a letter back into its composition (#282). */
    @Test fun aTakenBackCharacterIsNotSentAgain() {
        keyboard.commitText("1", 1)
        takeBack("1")
        compose("1ㅎ")
        assertEquals("ㅎ", shown.last())
        compose("1하")
        keyboard.commitText("1하", 1)
        assertEquals(listOf("1", "하"), remote.take())

        // A Latin letter after it streams like any other.
        keyboard.commitText("2", 1)
        takeBack("2")
        compose("2a", "2ab")
        assertEquals(listOf("2", "a", "b"), remote.take())
        keyboard.commitText("2ab ", 1)
        assertEquals(listOf(" "), remote.take())

        // Replaced: one Backspace for the character, then the text.
        keyboard.commitText("1", 1)
        takeBack("1")
        compose("1ㅎ")
        keyboard.commitText("2하", 1)
        assertEquals(listOf("1", "⌫", "2하"), remote.take())
        assertEquals("1하2ab 2하", remote.line.toString())
    }

    @Test fun anEnglishLetterThenKoreanSendsEachOnce() {
        // The switch to Korean ends the English composition; Samsung Keyboard then takes its letter back.
        compose("a")
        keyboard.finishComposingText()
        takeBack("a")
        compose("aㅎ", "a하")
        keyboard.finishComposingText()
        keyboard.commitText(" ", 1)
        assertEquals(listOf("a", "하", " "), remote.take())
        assertEquals(listOf("ㅎ", "하", ""), shown)
        // And Korean then English: the syllable waits for its end, the letters do not.
        compose("ㅎ", "하")
        keyboard.finishComposingText()
        compose("h", "hi")
        assertEquals(listOf("하", "h", "i"), remote.take())
        assertEquals("a하 하hi", remote.line.toString())
    }

    @Test fun aKeyboardThatComposesLatinIntoOtherCharactersIsHeld() {
        latinFinal = false
        // Japanese romaji.
        compose("k")
        assertEquals("k", shown.last())
        compose("か")
        keyboard.commitText("か", 1)
        // Chinese pinyin.
        compose("n", "ni")
        assertEquals("ni", shown.last())
        keyboard.commitText("你", 1)
        assertEquals(listOf("か", "你"), remote.take())
        assertEquals("", shown.last())
    }

    @Test fun anArmedModifierTakesTheLetterTypedAfterTheSentOnes() {
        armedTakes = { it == "c" }
        compose("a", "ab")
        assertEquals(listOf("a", "b"), remote.take())
        compose("abc")
        // The c is the armed key; the remote keeps the letters it has.
        assertEquals(listOf("a", "b", "c"), armed)
        assertEquals(emptyList<String>(), remote.take())
        keyboard.finishComposingText()
        assertEquals(emptyList<String>(), remote.take())
        // A Korean composition hands the whole syllable over, as before.
        armed.clear()
        compose("ㅊ")
        assertEquals(listOf("ㅊ"), armed)
    }

    @Test fun aPasteIsStillAPaste() {
        compose("ls\npwd")
        keyboard.finishComposingText()
        keyboard.commitText("첫 줄\n다음 줄", 1)
        assertEquals(listOf("paste ls\npwd", "paste 첫 줄\n다음 줄"), remote.take())
    }

    @Test fun aDeletionPastTheFrontErasesTheSentLettersFirst() {
        keyboard.commitText("ab", 1)
        compose("c")
        assertEquals(listOf("ab", "c"), remote.take())
        // The keyboard's cursor goes before its composition and deletes the b.
        assertTrue(keyboard.setSelection(0, 0))
        assertTrue(keyboard.deleteSurroundingText(1, 0))
        assertEquals(listOf("⌫", "⌫", "c"), remote.take())
        keyboard.commitText("c", 1)
        assertEquals(emptyList<String>(), remote.take())
        assertEquals("ac", remote.line.toString())
    }

    @Test fun anEmojiIsOneCharacterToSendAndToErase() {
        compose("🙂")
        assertEquals(listOf("🙂"), remote.take())
        assertTrue(keyboard.deleteSurroundingTextInCodePoints(1, 0))
        assertEquals(listOf("⌫"), remote.take())
        compose("🙂")
        assertTrue(keyboard.deleteSurroundingText(2, 0))
        compose("😀")
        compose("😁")
        assertEquals(listOf("🙂", "⌫", "😀", "⌫", "😁"), remote.take())
        assertEquals("😁", remote.line.toString())
    }

    /** A keyboard that deletes one UTF-16 unit of an emoji leaves half of it: no half goes to the remote. */
    @Test fun halfAnEmojiIsNotStreamed() {
        compose("🙂")
        assertEquals(listOf("🙂"), remote.take())
        assertTrue(keyboard.deleteSurroundingText(1, 0))
        assertEquals(listOf("⌫"), remote.take())
        assertTrue(keyboard.deleteSurroundingText(1, 0))
        keyboard.finishComposingText()
        assertEquals(emptyList<String>(), remote.take())
        assertEquals("", remote.line.toString())
    }

    @Test fun aKeyOrAnActionSendsOnlyWhatIsLeft() {
        compose("l", "ls")
        assertTrue(keyboard.performEditorAction(EditorInfo.IME_ACTION_SEND))
        assertEquals(listOf("l", "s", "key ${KeyEvent.KEYCODE_ENTER}"), remote.take())
        // A key of the bar ends the composition first ([TerminalInputView.finishBarComposition]).
        compose("c", "cd")
        assertTrue(keyboard.finishBeforeExternalKey())
        assertEquals(listOf("c", "d"), remote.take())
        // A held jump goes with it.
        compose("g", "git")
        assertTrue(keyboard.finishBeforeExternalKey())
        assertEquals(listOf("g", "it"), remote.take())
    }

    @Test fun aCompositionWithLettersOnTheRemoteIsStillOneToRetire() {
        compose("h")
        // The keyboard still holds it: a change of pane restarts its input (#295).
        assertTrue(state.holdsComposition())
        keyboard.commitText("h ", 1)
        assertFalse(state.holdsComposition())
    }

    @Test fun theKeyboardsCursorIsToldAsBefore() {
        keyboard.commitText("x", 1)
        assertEquals(listOf(1 to 1), cursor)
        // Gboard drops a composition when told the cursor moved: streaming tells it nothing.
        compose("h", "he")
        assertEquals(listOf(1 to 1), cursor)
        keyboard.commitText("he ", 1)
        assertEquals(listOf(1 to 1, 1 to 1), cursor)
        assertEquals(" ", keyboard.getTextBeforeCursor(64, 0).toString())
    }

    /** A voice keyboard can write while its own window has the focus: what it writes waits for the focus. */
    @Test fun anInterruptedEditorHoldsTheLettersAndDefersTheirErasure() {
        compose("h", "he")
        assertEquals(listOf("h", "e"), remote.take())
        accepts = false
        interrupted = true
        compose("h", "hi", "hit")
        keyboard.commitText("hit ", 1)
        assertEquals(emptyList<String>(), remote.take())
        accepts = true
        interrupted = false
        state.flushDeferred()
        assertEquals(listOf("⌫", "it "), remote.take())
        assertEquals("hit ", remote.line.toString())
    }

    @Test fun replacingARangeSendsOnlyTheDifference() {
        compose("h", "he", "hel")
        remote.take()
        assertTrue(keyboard.replaceText(0, 3, "help", 1, null))
        assertEquals(listOf("p"), remote.take())
        assertEquals("help", remote.line.toString())
    }
}
