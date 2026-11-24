package io.github.yhzion.velo.ui

import android.view.KeyEvent

/**
 * Rules for the text the soft keyboard is still composing for the terminal (a Hangul syllable
 * that the next jamo may change), where a key meets it. [TerminalEditor] holds that text and
 * [TerminalInputView] sends it; these functions only decide.
 */

/** The jamo of the two-set Korean layout by the Latin letter on the same key, without Shift. */
private val TWO_SET_KEYS = mapOf(
    'ㅂ' to 'q', 'ㅈ' to 'w', 'ㄷ' to 'e', 'ㄱ' to 'r', 'ㅅ' to 't', 'ㅛ' to 'y', 'ㅕ' to 'u', 'ㅑ' to 'i', 'ㅐ' to 'o', 'ㅔ' to 'p',
    'ㅁ' to 'a', 'ㄴ' to 's', 'ㅇ' to 'd', 'ㄹ' to 'f', 'ㅎ' to 'g', 'ㅗ' to 'h', 'ㅓ' to 'j', 'ㅏ' to 'k', 'ㅣ' to 'l',
    'ㅋ' to 'z', 'ㅌ' to 'x', 'ㅊ' to 'c', 'ㅍ' to 'v', 'ㅠ' to 'b', 'ㅜ' to 'n', 'ㅡ' to 'm',
)

/**
 * The Latin letter on the key of [text] on the two-set Korean layout, when [text] is exactly one
 * Hangul compatibility jamo that the layout types without Shift; null for anything else.
 */
fun twoSetLatinKey(text: String): Char? = if (text.length == 1) TWO_SET_KEYS[text[0]] else null

/**
 * Whether the modifiers armed on the key bar ([stickyMeta]) take the key of a text the keyboard
 * composes instead of the text: Ctrl or Alt does, so the letter or the jamo becomes a key
 * chord. Shift alone does not: it is also the key bar's Shift for Tab and the arrows, and a
 * jamo typed after it stays the start of a syllable.
 */
fun takesComposedKey(stickyMeta: Int): Boolean = stickyMeta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON) != 0

/**
 * The key of [text] when it is one ASCII letter the keyboard composed as its own key, lower-case
 * as a key of the layout is; null for anything else. The armed modifier takes the key, not the
 * letter, so its case is gone with it.
 */
private fun asciiLetter(text: String): Char? =
    if (text.length == 1 && (text[0] in 'a'..'z' || text[0] in 'A'..'Z')) text[0].lowercaseChar() else null

/**
 * The Latin key to send with the armed modifiers ([stickyMeta]) in place of the composing
 * [text]: the key of its place when [text] is one jamo of the two-set Korean layout
 * ([twoSetLatinKey]) or the letter itself when it is one ASCII letter ([asciiLetter]), while
 * the modifiers take keys ([takesComposedKey]). Ctrl with the Korean C key is Ctrl+C, and so is the c a
 * predicting keyboard composes. Null when [text] is composed as usual: a letter with no armed
 * Ctrl or Alt, every text with Shift alone, and a word of many letters, which is text.
 */
fun armedLatinKey(text: String, stickyMeta: Int): Char? =
    if (takesComposedKey(stickyMeta)) twoSetLatinKey(text) ?: asciiLetter(text) else null

/**
 * How the remote follows a composition that ended as [text] when it already has [taken]: the
 * front of that composition, the character before the cursor that the keyboard took back into
 * it (Samsung Keyboard does so with a digit or a letter before the next jamo, #282). The pair is
 * the number of characters to erase first and the text to send after. A [text] that still starts
 * with [taken] sends only the rest; any other [text] erases [taken] and sends all of itself.
 */
fun remoteEdit(text: String, taken: String): Pair<Int, String> =
    if (text.startsWith(taken)) 0 to text.substring(taken.length)
    else taken.codePointCount(0, taken.length) to text

/**
 * Whether the press of a hardware key that is about to be forwarded sends the composition first:
 * every key but a [modifier]. Shift alone types nothing, and with a hardware keyboard it is held
 * between two jamo of one syllable (to type a doubled final consonant): ending the composition there
 * would split the syllable.
 */
fun keyFlushesComposition(modifier: Boolean): Boolean = !modifier

/**
 * Whether the editors of the terminal hold a composition: [preedit] the keyboard still composes
 * (a character it took back counts, #282), or writes that wait behind a window over the app
 * ([deferredWrites]). When the bound pane changes or gets text from elsewhere, the terminal
 * drops that text and restarts the keyboard's input only if this holds: the restart makes the
 * keyboard forget the composition, so that it does not send it a second time or into another
 * pane. A restart without a composition to retire loses input: an accessibility input method
 * (Typeless) treats a restarted connection as lost and drops its next transcript (#295).
 */
fun holdsTerminalComposition(preedit: CharSequence, deferredWrites: Int): Boolean =
    preedit.isNotEmpty() || deferredWrites > 0
