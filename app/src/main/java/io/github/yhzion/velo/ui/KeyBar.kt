package io.github.yhzion.velo.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.KeyCap
import io.github.yhzion.velo.ui.theme.KeyLegendPadding
import io.github.yhzion.velo.ui.theme.KeyStrip
import io.github.yhzion.velo.ui.theme.VeloTheme

internal val KEY_CODES = mapOf(
    "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "Enter" to KeyEvent.KEYCODE_ENTER,
    "Left" to KeyEvent.KEYCODE_DPAD_LEFT, "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "Up" to KeyEvent.KEYCODE_DPAD_UP, "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
    "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
    "Space" to KeyEvent.KEYCODE_SPACE, "Backspace" to KeyEvent.KEYCODE_DEL,
)

/** The key bar's modifiers: a tap arms one for the next key, another tap takes it back. */
val StickyKeys = setOf("Ctrl", "Alt", "Shift")

/** The text a key shows, in capitals for a named key; a screen reader says [spokenKeyName] instead. */
internal fun keyName(key: String): String = if (key in KEY_CODES || key in StickyKeys) key.uppercase() else key

/**
 * What a screen reader says for [key], the one name the key bar and the Keyboard settings use,
 * as the boards name them: a short label or a symbol in full words, a key named in a word as it is.
 */
fun spokenKeyName(key: String): String = when (key) {
    "Ctrl" -> "Control"
    "Esc" -> "Escape"
    "PgUp" -> "Page Up"
    "PgDn" -> "Page Down"
    "-" -> "Minus"
    "|" -> "Pipe"
    "/" -> "Slash"
    "~" -> "Tilde"
    else -> key
}

/** [armed] after a tap on the sticky key [key]: armed when it was not, else not armed. The other one stays as it is. */
fun toggleSticky(armed: Set<String>, key: String): Set<String> = if (key in armed) armed - key else armed + key

/** What a finger feels for a tap on [key] while [armed] are on: a modifier it turns on or off (#309), or a key. */
fun barKeyFeel(key: String, armed: Set<String>): Feel = when {
    key !in StickyKeys -> Feel.Key
    key in armed -> Feel.ToggleOff
    else -> Feel.ToggleOn
}

/** The meta state the next key is sent with while [armed] waits. */
fun stickyMeta(armed: Set<String>): Int =
    (if ("Ctrl" in armed) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
        (if ("Alt" in armed) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
        (if ("Shift" in armed) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

/** Categories in the settings picker and in the original fixed-order bar. */
enum class KeyGroup(val caption: String) { Modifiers("Modifiers"), Editing("Editing"), Navigation("Navigation") }

private val NAVIGATION_KEYS = setOf("Left", "Down", "Up", "Right", "Home", "End", "PgUp", "PgDn")

/** Only movement by one step and deletion repeat; a held Enter never submits twice. */
internal val REPEAT_KEYS = setOf("Backspace", "Left", "Down", "Up", "Right")

/** The group [key] belongs to. A key that is neither a modifier nor moves the cursor is typed: Editing, also one the app does not know. */
fun keyGroup(key: String): KeyGroup = when (key) {
    in StickyKeys -> KeyGroup.Modifiers
    in NAVIGATION_KEYS -> KeyGroup.Navigation
    else -> KeyGroup.Editing
}

/** Older releases always drew modifiers, editing keys and navigation keys in that order. */
internal fun readKeyOrder(saved: String, ordered: Boolean): List<String> {
    val keys = saved.split(',').filter { it.isNotBlank() }.distinct()
    return if (ordered) keys else keys.sortedBy { keyGroup(it).ordinal }
}

internal fun toggleKey(saved: List<String>, key: String): List<String> = when {
    key in saved -> saved - key
    key == "/" -> listOf(key) + saved
    saved.lastOrNull() == "Backspace" -> saved.toMutableList().apply { add(lastIndex, key) }
    else -> saved + key
}

internal fun moveKey(saved: List<String>, key: String, delta: Int): List<String> {
    val from = saved.indexOf(key)
    val to = from + delta
    if (from < 0 || to !in saved.indices) return saved
    return saved.toMutableList().apply { add(to, removeAt(from)) }
}

/** The caps' [widths] on the key bar and the [gap] between two of them ([keyCaps]). */
internal data class KeyCaps(val widths: List<Int>, val gap: Int)

/**
 * The caps on a bar with [available] room inside it (#142): a touch bar's keys, every cap the same
 * width and all of them across the bar. A cap needs the room in [need] (its label and padding) and
 * at least [floor], with [gap] between two caps. When the bar is too narrow, it gives up room in
 * this order: the labels' padding, up to [give]; the gap, down to [leastGap]; the floor, down to
 * the widest label without its padding. Only when even that does not fit does every cap take the
 * widest need, at [gap], and the bar scroll. A pixel the share cannot split goes to the first caps.
 */
internal fun keyCaps(need: List<Int>, floor: Int, gap: Int, available: Int, give: Int = 0, leastGap: Int = gap): KeyCaps {
    if (need.isEmpty()) return KeyCaps(emptyList(), gap)
    val gaps = need.lastIndex
    val label = need.max() - give
    val least = maxOf(floor, label)
    fun fits(cap: Int, gap: Int) = cap * need.size + gap * gaps <= available
    val chosen = when {
        fits(least, gap) -> gap
        // The widest gap at which the caps keep the floor, or failing that the least gap.
        gaps > 0 && fits(least, leastGap) -> (available - least * need.size) / gaps
        fits(label, leastGap) -> leastGap
        else -> return KeyCaps(List(need.size) { maxOf(floor, need.max()) }, gap)
    }
    val room = available - chosen * gaps
    val share = room / need.size
    val left = room % need.size
    return KeyCaps(List(need.size) { share + if (it < left) 1 else 0 }, chosen)
}

/** The test tag of [key]'s cap, as the KeyBar board names it: `key-slash`, `key-ctrl`, `key-left`. */
internal fun keyTestTag(key: String): String = "key-" + when (key) {
    "/" -> "slash"
    else -> key.lowercase()
}

/**
 * The keys in the saved order (KeyBar board), as a touch bar's keys (#142): caps of one width across
 * the bar, `sizes.keyGap` between them ([keyCaps]). Each key takes the taps up to halfway to the
 * next, and the first and the last up to the bar's ends. A bar too narrow for its keys narrows the
 * gap to `sizes.keyGapLeast` and the caps to their labels; when they need more even then, the bar
 * scrolls sideways and the edge with more keys beyond it fades out. A sticky key in [armed] is lit
 * until the next key uses it. While the bar is not [enabled] (the panes' input is paused:
 * [paneInputPaused]) every key is drawn disabled and takes no tap.
 */
@Composable
internal fun KeyBar(keys: List<String>, armed: Set<String>, modifier: Modifier, enabled: Boolean = true, onKey: (String, Boolean) -> Unit) {
    val sizes = VeloTheme.sizes
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // The gap is two halves of whole pixels, so that a bar with room to spare draws the same gap everywhere.
    val gap = with(density) { (sizes.keyGap / 2).roundToPx() } * 2
    val leastGap = with(density) { sizes.keyGapLeast.roundToPx() }
    // The room on each side of a label that the cap can give up before the bar scrolls.
    val legendPadding = with(density) { VeloTheme.medium(KeyLegendPadding).roundToPx() }
    val legendPaddingDp = VeloTheme.medium(KeyLegendPadding)
    val floor = with(density) { sizes.key.roundToPx() }
    KeyStrip(modifier.testTag("key-bar")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = constraints.maxWidth
            SubcomposeLayout(Modifier.fillMaxSize().horizontalScroll(scroll)) { limits ->
                // The labels alone first: how wide they are decides the gap the keys stand at.
                val labels = subcompose(KeyBarSlot.Labels) {
                    for (key in keys) KeyLegend(key, Color.Unspecified, Modifier.padding(horizontal = legendPaddingDp))
                }
                val need = labels.map { maxOf(floor, it.maxIntrinsicWidth(limits.maxHeight)) }
                val caps = keyCaps(need, floor, gap, viewport, give = legendPadding * 2, leastGap = leastGap)
                // Between two caps, the first takes half the gap and the second the rest.
                fun start(i: Int) = if (i == 0) 0 else caps.gap - caps.gap / 2
                fun end(i: Int) = if (i == keys.lastIndex) 0 else caps.gap / 2
                val buttons = subcompose(KeyBarSlot.Keys) {
                    for ((i, key) in keys.withIndex()) {
                        key(key) {
                            // The cap stands inside the part of the bar it takes the taps in, half a gap from the next.
                            val insets = with(density) { PaddingValues(start = start(i).toDp(), end = end(i).toDp()) }
                            KeyCap(key, { onKey(key, false) }, armed = key in armed,
                                contentDescription = spokenKeyName(key), enabled = enabled,
                                holdRepeat = key in REPEAT_KEYS, onRepeat = { onKey(key, true) },
                                capInsets = insets, capModifier = Modifier.testTag(keyTestTag(key)))
                        }
                    }
                }
                val children = buttons.mapIndexed { i, button ->
                    val cell = caps.widths[i] + start(i) + end(i)
                    button.measure(Constraints(minWidth = cell, maxWidth = cell, maxHeight = limits.maxHeight))
                }
                layout(maxOf(viewport, children.sumOf { it.width }), limits.maxHeight) {
                    var x = 0
                    for (child in children) {
                        child.placeRelative(x, (limits.maxHeight - child.height) / 2)
                        x += child.width
                    }
                }
            }
            if (scroll.canScrollBackward || scroll.canScrollForward) {
                val surface = VeloTheme.surface.surface
                val clear = surface.copy(alpha = 0f)
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val left = remember(surface) { Brush.horizontalGradient(listOf(surface, clear)) }
                val right = remember(surface) { Brush.horizontalGradient(listOf(clear, surface)) }
                if (scroll.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).width(sizes.icon).fillMaxHeight().background(if (rtl) right else left))
                if (scroll.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).width(sizes.icon).fillMaxHeight().background(if (rtl) left else right))
            }
        }
    }
}

private enum class KeyBarSlot { Labels, Keys }
