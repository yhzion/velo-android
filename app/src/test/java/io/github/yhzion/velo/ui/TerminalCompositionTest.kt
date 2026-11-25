package io.github.yhzion.velo.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCompositionTest {
    @Test fun commitReplacesPreeditAndFinishDrainsItOnce() {
        val pending = StringBuilder("하")
        val sent = mutableListOf<String>()
        fun submit(commit: String?) =
            submitTerminalComposition(pending, commit, { pending.clear() }) { erase, text, _ ->
                assertEquals(0, erase)
                sent.add(text)
            }

        submit("한")
        submit(null)
        pending.append("글")
        submit(null)
        submit(null)
        pending.append("취소")
        submit("")
        submit(null)
        submit("한")
        assertEquals(listOf("한", "글", "한"), sent)
    }

    /** Samsung Keyboard took the 1 before the cursor back into its composition (#282). */
    @Test fun aTakenBackCharacterIsNotSentAgain() {
        val pending = StringBuilder()
        val sent = mutableListOf<Triple<Int, String, String>>()
        fun submit(commit: String?, taken: String) {
            submitTerminalComposition(pending, commit, { pending.clear() }, taken) { erase, text, whole ->
                sent.add(Triple(erase, text, whole))
            }
        }

        pending.append("1하")
        submit(null, "1")
        submit("1하", "1")
        // Unchanged: nothing to send, and the composition still ended as the 1 before the cursor.
        submit("1", "1")
        // Replaced or cancelled: one Backspace for the character, then the text.
        submit("2하", "1")
        submit("", "1")
        submit("🙂", "🙂")
        submit("", "🙂")
        assertEquals(listOf(
            Triple(0, "하", "1하"), Triple(0, "하", "1하"), Triple(0, "", "1"),
            Triple(1, "2하", "2하"), Triple(1, "", ""), Triple(0, "", "🙂"), Triple(1, "", ""),
        ), sent)
    }

    @Test fun theRemoteFollowsTheTakenBackFront() {
        assertEquals(0 to "하", remoteEdit("1하", "1"))
        assertEquals(0 to "1하", remoteEdit("1하", ""))
        assertEquals(1 to "ㅎ", remoteEdit("ㅎ", "1"))
        assertEquals(1 to "", remoteEdit("", "🙂"))
        assertEquals(0 to "", remoteEdit("", ""))
    }

    /** A streamed word (#18) is erased only back to where it and the new text part. */
    @Test fun theRemoteErasesOnlyBackToTheCommonFront() {
        // Autocorrect at the space: two Backspaces, then the rest.
        assertEquals(2 to "he ", remoteEdit("the ", "teh"))
        // The commit at a space sends only the space.
        assertEquals(0 to " ", remoteEdit("hello ", "hello"))
        // A Backspace in the composition.
        assertEquals(1 to "", remoteEdit("he", "hel"))
        // Two emoji that share their high surrogate are two different characters: one Backspace.
        assertEquals(1 to "😁", remoteEdit("a😁", "a😀"))
        assertEquals(0 to "", remoteEdit("a😀", "a😀"))
    }

    @Test fun theCommonFrontEndsBetweenCodePoints() {
        assertEquals(3, commonFront("the", "the "))
        assertEquals(1, commonFront("the ", "teh"))
        assertEquals(0, commonFront("", "abc"))
        assertEquals(1, commonFront("a😀", "a😁"))
        assertEquals(3, commonFront("a😀b", "a😀c"))
    }

    /** Latin typed on a predicting keyboard reaches the remote letter by letter (#18). */
    @Test fun latinThatGrowsByOneCharacterStreams() {
        val latin = { true }
        assertTrue(streamsTerminalComposition("h", "", latin))
        assertTrue(streamsTerminalComposition("he", "h", latin))
        assertTrue(streamsTerminalComposition("Hello,", "Hello", latin))
        assertTrue(streamsTerminalComposition("1a", "1", latin))
        // A Vietnamese Telex keyboard recomposes its letter: the remote erased the a already.
        assertTrue(streamsTerminalComposition("â", "", latin))
        // One emoji is one character, in two UTF-16 units.
        assertTrue(streamsTerminalComposition("🙂", "", latin))
        // Nothing left to send.
        assertTrue(streamsTerminalComposition("he", "he", latin))
        assertTrue(streamsTerminalComposition("", "", latin))
    }

    @Test fun aJumpOfManyCharactersIsHeld() {
        val latin = { true }
        // A suggestion, or a voice transcript that grows.
        assertFalse(streamsTerminalComposition("hello", "hel", latin))
        assertFalse(streamsTerminalComposition("hello world", "", latin))
        // A skin tone makes an emoji of two characters.
        assertFalse(streamsTerminalComposition("👍🏽", "", latin))
    }

    @Test fun aCompositionWithHangulKanaOrIdeographsIsHeld() {
        val latin = { true }
        for (composed in listOf(
            "ㅎ", "하", // compatibility jamo, syllables
            "ᄀ", "ꥠ", "ힰ", "ﾡ", // jamo, Extended-A, Extended-B, halfwidth
            "か", "カ", "ｶ", // hiragana, katakana, halfwidth katakana
            "中", "𠀀", "ㄅ", // ideographs, Extension B, bopomofo
        )) {
            assertFalse(composed, streamsTerminalComposition(composed, "", latin))
            // Also when the remote already has the Latin front of the composition (#282).
            assertFalse(composed, streamsTerminalComposition("a$composed", "a", latin))
        }
    }

    /** A line break is held: a composition that ends with one is still a paste ([keyboardTextIsPaste]). */
    @Test fun aCompositionWithAControlCharacterIsHeld() {
        assertFalse(streamsTerminalComposition("ls\n", "ls", { true }))
        assertFalse(streamsTerminalComposition("\t", "", { true }))
    }

    @Test fun aKeyboardThatComposesLatinIntoOtherCharactersHoldsIt() {
        assertFalse(streamsTerminalComposition("k", "", { false }))
        // The keyboard is asked only for a composition that would stream otherwise.
        val unasked = { throw AssertionError("asked about the keyboard for a held composition") }
        assertFalse(streamsTerminalComposition("하", "", unasked))
        assertFalse(streamsTerminalComposition("hello", "", unasked))
        assertTrue(streamsTerminalComposition("he", "he", unasked))
    }

    @Test fun latinIsFinalUnlessTheKeyboardIsChineseOrJapanese() {
        for (tag in listOf(null, "", "en", "en-US", "en_US", "ko", "ko-KR", "vi", "jv", "zu")) {
            assertTrue("$tag", keyboardLatinIsFinal(tag))
        }
        for (tag in listOf("zh", "zh-CN", "zh_TW", "zh-Hant-TW", "ja", "ja-JP", "JA_jp", "yue-Hant-HK")) {
            assertFalse(tag, keyboardLatinIsFinal(tag))
        }
    }

    /** Only a composition makes the terminal restart the keyboard's input (#295). */
    @Test fun onlyPreeditOrDeferredWritesAreACompositionToRetire() {
        assertFalse(holdsTerminalComposition("", 0))
        assertTrue(holdsTerminalComposition("하", 0))
        assertTrue(holdsTerminalComposition("", 1))
        assertTrue(holdsTerminalComposition("하", 2))
        // A character the keyboard took back is its composition as well (#282).
        assertTrue(holdsTerminalComposition("1", 0))
    }

    @Test
    fun everyJamoOfTheTwoSetLayoutHasItsLatinKey() {
        val keys = listOf(
            "ㅂ" to 'q', "ㅈ" to 'w', "ㄷ" to 'e', "ㄱ" to 'r', "ㅅ" to 't', "ㅛ" to 'y', "ㅕ" to 'u', "ㅑ" to 'i', "ㅐ" to 'o', "ㅔ" to 'p',
            "ㅁ" to 'a', "ㄴ" to 's', "ㅇ" to 'd', "ㄹ" to 'f', "ㅎ" to 'g', "ㅗ" to 'h', "ㅓ" to 'j', "ㅏ" to 'k', "ㅣ" to 'l',
            "ㅋ" to 'z', "ㅌ" to 'x', "ㅊ" to 'c', "ㅍ" to 'v', "ㅠ" to 'b', "ㅜ" to 'n', "ㅡ" to 'm',
        )
        assertEquals(26, keys.size)
        assertEquals(('a'..'z').toSet(), keys.map { it.second }.toSet())
        for ((jamo, latin) in keys) {
            // The keyboard composes compatibility jamo (U+3131 to U+3163), not the conjoining ones.
            assertTrue(jamo, jamo.single().code in 0x3131..0x3163)
            assertEquals(jamo, latin, twoSetLatinKey(jamo))
        }
    }

    @Test
    fun whatIsNotOneJamoOfTheLayoutHasNoLatinKey() {
        assertNull(twoSetLatinKey(""))
        assertNull(twoSetLatinKey("a"))
        assertNull(twoSetLatinKey("가"))
        assertNull(twoSetLatinKey("ㅂㅂ"))
        // Typed with Shift on the layout: no key of its own.
        assertNull(twoSetLatinKey("ㅃ"))
        // Conjoining jamo U+1100 is not what a keyboard composes.
        assertNull(twoSetLatinKey("ᄀ"))
    }

    @Test
    fun aJamoAfterAnArmedCtrlOrAltIsTheLatinKeyOfItsPlace() {
        // Ctrl with the Korean C key is Ctrl+C.
        assertEquals('c', armedLatinKey("ㅊ", KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertEquals('c', armedLatinKey("ㅊ", KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertEquals('c', armedLatinKey("ㅊ", KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
        // A syllable, two jamo, and a double consonant have no key of their own in the table.
        for (text in listOf("차", "ㅊㅊ", "ㅆ")) assertNull(text, armedLatinKey(text, KeyEvent.META_CTRL_ON))
    }

    @Test
    fun aComposedLatinLetterWithCtrlOrAltIsItsOwnKey() {
        // The letter a predicting keyboard composes, with Ctrl of the key bar armed, is the key:
        // Ctrl and a composed c is Ctrl+C, and x and z give Ctrl+X and Ctrl+Z the same way.
        assertEquals('c', armedLatinKey("c", KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertEquals('x', armedLatinKey("x", KeyEvent.META_CTRL_ON))
        assertEquals('z', armedLatinKey("z", KeyEvent.META_CTRL_ON))
        assertEquals('c', armedLatinKey("C", KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        // Without Ctrl or Alt the letter is composed as the text it is.
        assertNull(armedLatinKey("c", 0))
        // The key bar's Shift alone leaves a letter to the text it starts, as it leaves a jamo.
        assertNull(armedLatinKey("c", KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        // A word the keyboard composes at once is text, not one key.
        assertNull(armedLatinKey("cat", KeyEvent.META_CTRL_ON))
        assertNull(armedLatinKey("c1", KeyEvent.META_CTRL_ON))
    }

    @Test
    fun withoutCtrlOrAltAJamoIsComposed() {
        assertNull(armedLatinKey("ㅊ", 0))
        // The key bar's Shift alone leaves the jamo to the syllable it starts.
        assertNull(armedLatinKey("ㅊ", KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        assertFalse(takesComposedKey(0))
        assertFalse(takesComposedKey(KeyEvent.META_SHIFT_ON))
        assertTrue(takesComposedKey(KeyEvent.META_CTRL_ON))
        assertTrue(takesComposedKey(KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun onlyThePressOfAKeyThatTypesSomethingFlushesTheComposition() {
        assertTrue(keyFlushesComposition(modifier = false))
        // Shift held to type a doubled final consonant must not end the syllable.
        assertFalse(keyFlushesComposition(modifier = true))
    }
}
