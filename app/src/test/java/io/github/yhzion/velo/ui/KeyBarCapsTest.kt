package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.unit.DpRect
import io.github.yhzion.velo.ui.theme.keyCapRadius
import io.github.yhzion.velo.ui.theme.veloSizes
import org.junit.Assert.assertNotNull
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.InputMode
import io.github.yhzion.velo.ui.theme.VeloPalettes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The key bar's caps (#142, #218) drawn: one width across the bar, in two rows on the upright bar phone and scrolling
 * when it lies on its side, the bar as wide as the pane column, TAB, ENTER, SHIFT and SPACE drawn as icons, taps and
 * holds sent as before, the armed modifier selected and a paused bar taking no tap.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: the labels measure as on a phone, which decides whether the caps fit.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w821dp-h1088dp-420dpi")
class KeyBarCapsTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val keys = Settings.DEFAULT_KEY_BAR.split(',')
    private val sent = mutableListOf<Pair<String, Boolean>>()
    private var enabled by mutableStateOf(true)
    private lateinit var inputMode: InputModeManager

    private fun show(width: Dp, armed: Set<String> = emptySet(), upright: Boolean = true, keys: List<String> = this.keys) {
        compose.setContent {
            inputMode = LocalInputModeManager.current
            VeloTheme(UiSize.Medium) {
                KeyBar(keys, armed, Modifier.width(width), enabled = enabled, upright = upright) { key, repeat -> sent += key to repeat }
            }
        }
        compose.waitForIdle()
    }

    private fun width(b: DpRect) = (b.right - b.left).value
    private fun height(b: DpRect) = (b.bottom - b.top).value

    // The tag is on the cap, inside the key that takes the taps.
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
    private fun key(name: String) = compose.onNodeWithTag(keyTestTag(name), useUnmergedTree = true).onParent()

    @Test fun theFoldedFold8BarHoldsEveryKeyAtOneWidth() {
        // WsClosedTerminal: the key bar is 390.5 wide.
        show(390.5.dp)
        val bar = bounds("key-bar")
        val caps = keys.map { bounds(keyTestTag(it)) }
        assertTrue("${caps.last().right} > ${bar.right}", caps.last().right <= bar.right)
        val widths = caps.map { it.right - it.left }
        for (w in widths) assertEquals(widths.first().value, w.value, 0.5f)
    }

    @Test fun oneRowIsAStripOf56WithTheCapsSixFromItsEdges() {
        show(390.5.dp)
        val bar = bounds("key-bar")
        val caps = keys.map { bounds(keyTestTag(it)) }
        // 6 + 44 + 6 (#218), to the pixel at 420 dpi.
        assertEquals(56f, height(bar), 1f)
        for (cap in caps) assertEquals(44f, height(cap), 0.5f)
        assertEquals(6f, caps.first().top.value - bar.top.value, 0.5f)
        assertEquals(6f, caps.first().left.value - bar.left.value, 0.5f)
        assertEquals(6f, bar.right.value - caps.last().right.value, 0.5f)
        // 6 between two caps.
        assertEquals(6f, caps[1].left.value - caps[0].right.value, 0.5f)
        // The first and the last key take the taps up to the strip's ends.
        assertEquals(bar.left.value, key(keys.first()).getBoundsInRoot().left.value, 0.5f)
        assertEquals(bar.right.value, key(keys.last()).getBoundsInRoot().right.value, 0.5f)
    }

    @Test fun theUprightBarPhoneBarTakesTwoRowsOfOneWidth() {
        // The bar phone upright (393 wide): its pane column is 325, under nine caps of 36 (#218).
        show(325.dp)
        val bar = bounds("key-bar")
        val caps = keys.map { bounds(keyTestTag(it)) }
        // 6 + 44 + 6 + 44 + 6.
        assertEquals(106f, height(bar), 1f)
        for (cap in caps) assertEquals(width(caps.first()), width(cap), 0.5f)
        // Five in the first row, four under them, each under the cap above it.
        for (cap in caps.take(5)) assertEquals(caps.first().top.value, cap.top.value, 0.5f)
        for ((i, cap) in caps.drop(5).withIndex()) {
            assertEquals(caps.first().bottom.value + 6f, cap.top.value, 0.5f)
            assertEquals(caps[i].left.value, cap.left.value, 0.5f)
        }
        assertEquals(6f, bar.right.value - caps[4].right.value, 0.5f)
        assertEquals(6f, bar.bottom.value - caps.last().bottom.value, 0.5f)
        // Nothing to scroll: the strip ends where the column does.
        assertEquals(325f, width(bar), 0.5f)
    }

    @Test fun theLastKeyOfAShortSecondRowTakesTheTapsToTheStripsEnd() {
        // Eleven keys on the upright bar phone: six over five, the sixth place of the second row empty.
        val eleven = keys + listOf("Enter", "Shift")
        show(325.dp, keys = eleven)
        val bar = bounds("key-bar")
        val caps = eleven.map { bounds(keyTestTag(it)) }
        assertEquals(106f, height(bar), 1f)
        for (cap in caps) assertEquals(width(caps.first()), width(cap), 0.5f)
        // The last cap stands under the fifth, not stretched over the empty place.
        assertEquals(caps[4].right.value, caps.last().right.value, 0.5f)
        // Its key takes the taps over the empty place, up to the strip's end, as the last key of the first row does.
        assertEquals(bar.right.value, key(eleven[5]).getBoundsInRoot().right.value, 0.5f)
        assertEquals(bar.right.value, key(eleven.last()).getBoundsInRoot().right.value, 0.5f)
        assertEquals(bar.bottom.value, key(eleven.last()).getBoundsInRoot().bottom.value, 0.5f)
    }

    @Test fun inCompactStyleTheBarKeepsAGapToTheKeyboardUnderIt() {
        // The workspace's outer margin in Compact style is 4 pixels; under the key bar it is made up to gapCompact, 3.4 at Medium.
        val outer = (4 / compose.density.density).dp
        val gapCompact = veloSizes(UiSize.Medium).gapCompact
        assertEquals(3.4f, gapCompact.value, 0.01f)
        val clearance = keyBarClearance(compact = true, outer = outer, gapCompact = gapCompact)
        compose.setContent {
            VeloTheme(UiSize.Medium) {
                Column(Modifier.width(325.dp).testTag("column")) {
                    KeyBar(keys, emptySet(), keyBarSlot(4.dp, clearance), upright = true) { _, _ -> }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(gapCompact.value, (bounds("column").bottom - bounds("key-bar").bottom).value + outer.value, 0.5f)
        // The other style's margin is a whole gap already, and nothing is added.
        assertEquals(0.dp, keyBarClearance(compact = false, outer = 6.8.dp, gapCompact = gapCompact))
    }

    @Test fun theBarPhoneBarOnItsSideScrollsInOneRow() {
        show(325.dp, upright = false)
        val bar = bounds("key-bar")
        val caps = keys.map { bounds(keyTestTag(it)) }
        assertEquals(56f, height(bar), 1f)
        // Every cap at the widest need, wider together than the bar. The bounds are cut at the bar's end:
        // the caps that show whole are of one width, and the last lies beyond the end, out of sight.
        val whole = caps.filter { it.right > it.left && it.right < bar.right }
        assertTrue("$whole", whole.size in 2 until keys.size)
        for (cap in whole) assertEquals(caps.first().top.value, cap.top.value, 0.5f)
        for (cap in whole) assertEquals(width(caps.first()), width(cap), 0.5f)
        assertTrue("${width(caps.first())}", width(caps.first()) >= 36f - 0.5f)
        assertEquals(0f, width(caps.last()), 0.01f)
    }

    @Test fun theBarIsAsWideAsThePaneColumn() {
        // The unfolded Fold8 upright: a pane column of 753, wider than the old 560 the bar stopped at.
        compose.setContent {
            VeloTheme(UiSize.Medium) {
                Column(Modifier.width(753.dp).testTag("column")) {
                    KeyBar(keys, emptySet(), keyBarSlot(8.dp), upright = true) { _, _ -> }
                }
            }
        }
        compose.waitForIdle()
        val column = bounds("column")
        val bar = bounds("key-bar")
        assertEquals(column.left.value, bar.left.value, 0.5f)
        assertEquals(column.right.value, bar.right.value, 0.5f)
        val caps = keys.map { bounds(keyTestTag(it)) }
        for (cap in caps) assertEquals(width(caps.first()), width(cap), 0.5f)
        assertEquals(6f, bar.right.value - caps.last().right.value, 0.5f)
    }

    @Test fun tabEnterShiftAndSpaceAreDrawnAsIconsAndSaidInWords() {
        val drawn = listOf("Tab", "Enter", "Shift", "Space")
        for (key in drawn) assertNotNull(key, keyIcon(key))
        // Drawn at 20 at Medium, as the canvas draws them on a key. ALT stays a word: its ink is wider than an icon's box.
        val icon = veloSizes(UiSize.Medium).keyIcon.value
        assertEquals(20f, icon, 0.01f)
        show(560.dp, keys = drawn + "Alt")
        for (key in drawn) key(key).assertContentDescriptionEquals(key)
        fun ink(key: String): Float {
            val pixels = compose.onNodeWithTag(keyTestTag(key), useUnmergedTree = true).captureToImage().toPixelMap()
            val glass = VeloPalettes.dark.chrome.glass
            // The middle of the cap, clear of its rounded corners.
            val xs = (pixels.height / 4 until pixels.height * 3 / 4).flatMap { y -> (0 until pixels.width).filter { pixels[it, y] != glass } }
            return if (xs.isEmpty()) 0f else (xs.max() - xs.min() + 1) / compose.density.density
        }
        for (key in drawn) assertTrue("$key inks ${ink(key)} of $icon", ink(key) in 1f..icon + 0.5f)
        assertTrue("ALT inks ${ink("Alt")}", ink("Alt") > icon + 0.5f)
    }

    @Test fun shiftTabDeletePasteAndThePagesAreDrawnAsIconsAndSaidInWords() {
        val drawn = listOf("BackTab", "Delete", "PgUp", "PgDn", "Paste")
        show(560.dp, keys = drawn + "Esc")
        for ((key, said) in drawn.zip(listOf("Shift Tab", "Delete", "Page Up", "Page Down", "Paste"))) {
            key(key).assertContentDescriptionEquals(said)
            compose.onNode(hasTestTag(KeyIconTag) and hasAnyAncestor(hasTestTag(keyTestTag(key))), useUnmergedTree = true).assertExists()
        }
        // ESC stays a word.
        compose.onNode(hasTestTag(KeyIconTag) and hasAnyAncestor(hasTestTag(keyTestTag("Esc"))), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aHeldDeleteRepeatsAndAHeldPasteDoesNot() {
        show(560.dp, keys = listOf("Delete", "Paste"))
        compose.mainClock.autoAdvance = false
        for (key in listOf("Delete", "Paste")) {
            key(key).performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(1_000)
            key(key).performTouchInput { up() }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Delete" to false, sent.first())
        assertTrue("$sent", sent.count { it == ("Delete" to true) } > 1)
        assertEquals(listOf("Paste" to false), sent.filter { it.first == "Paste" })
    }

    @Test fun aCapRoundsOneGapLessThanTheStrip() {
        val radii = mutableListOf<Dp>()
        compose.setContent {
            VeloTheme(UiSize.Medium) {
                radii += keyCapRadius(VeloTheme.radii.pane)
                radii += keyCapRadius(VeloTheme.radii.paneCompact)
                radii += VeloTheme.radii.pane
                radii += VeloTheme.sizes.keyGap
            }
        }
        compose.waitForIdle()
        val (cap, compactCap, pane, gap) = radii
        assertEquals(16f, pane.value, 0.01f)
        assertEquals(6f, gap.value, 0.01f)
        assertEquals(10f, cap.value, 0.01f)
        // The compact card's 12 at Large is 10.2 at Medium: its caps keep the same 6 to the strip.
        assertEquals(10.2f - 6f, compactCap.value, 0.01f)
    }

    @Test fun aTapSendsTheKeyAndAHoldRepeatsIt() {
        show(560.dp)
        key("Esc").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Esc" to false), sent)
        sent.clear()
        compose.mainClock.autoAdvance = false
        key("Left").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        key("Left").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Left" to false, sent.first())
        assertTrue("$sent", sent.size > 2 && sent.drop(1).all { it == ("Left" to true) })
    }

    @Test fun anArmedModifierIsSelectedAndAPausedBarTakesNoTap() {
        show(560.dp, armed = setOf("Ctrl"))
        key("Ctrl").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        enabled = false
        compose.waitForIdle()
        key("Esc").performClick()
        compose.waitForIdle()
        assertTrue("$sent", sent.isEmpty())
    }

    @Test fun theKeyWithTheKeyboardsFocusIsAStepLighter() {
        show(560.dp)
        val cap = compose.onNodeWithTag(keyTestTag("Esc"), useUnmergedTree = true)
        fun fill() = cap.captureToImage().toPixelMap().let { it[it.width / 2, 4] }
        assertEquals(VeloPalettes.dark.chrome.glass, fill())
        // A hardware keyboard's Tab moves the focus: the keys take it only out of touch mode.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        key("Esc").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertEquals(VeloPalettes.dark.chrome.glassSelected, fill())
    }
}
