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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.KeyCap
import io.github.yhzion.velo.ui.theme.KeyLegendPadding
import io.github.yhzion.velo.ui.theme.KeyStrip
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.keyCapRadius

internal val KEY_CODES = mapOf(
    "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "Enter" to KeyEvent.KEYCODE_ENTER,
    "Left" to KeyEvent.KEYCODE_DPAD_LEFT, "Right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    "Up" to KeyEvent.KEYCODE_DPAD_UP, "Down" to KeyEvent.KEYCODE_DPAD_DOWN,
    "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
    "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN,
    "Backspace" to KeyEvent.KEYCODE_DEL,
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

/**
 * How the key bar lays out its caps ([keyCaps]): [widths] for the caps of one row, in [rows] rows of
 * [columns] (the last row may hold one fewer), [gap] between two of them; a bar that [scrolls] is
 * wider than its room and moves sideways.
 */
internal data class KeyCaps(val widths: List<Int>, val gap: Int, val rows: Int = 1, val scrolls: Boolean = false) {
    /** The caps in a row: all of them in one row, half of them (the first row the larger half) in two. */
    val columns: Int get() = widths.size
}

/**
 * The caps of [count] keys on a bar with [available] room inside its padding (#218): a touch bar's
 * keys, every cap the same width and all of them across the bar, [gap] between two of them. A cap
 * needs the widest label in [need] less the padding in [give], and at least [floor]. When one row
 * leaves a cap less than that, a bar on an [upright] window puts the keys in two rows; one that is
 * not, or one too narrow even for two rows, keeps one row of caps at the widest need, and scrolls.
 * A pixel the share cannot split goes to the first caps.
 */
internal fun keyCaps(need: List<Int>, floor: Int, gap: Int, available: Int, give: Int = 0, upright: Boolean = false): KeyCaps {
    if (need.isEmpty()) return KeyCaps(emptyList(), gap)
    val least = maxOf(floor, need.max() - give)
    fun fits(columns: Int) = least * columns + gap * (columns - 1) <= available
    fun share(columns: Int, rows: Int): KeyCaps {
        val room = available - gap * (columns - 1)
        return KeyCaps(List(columns) { room / columns + if (it < room % columns) 1 else 0 }, gap, rows)
    }
    val twoRows = (need.size + 1) / 2
    return when {
        fits(need.size) -> share(need.size, 1)
        upright && need.size > 1 && fits(twoRows) -> share(twoRows, 2)
        else -> KeyCaps(List(need.size) { maxOf(floor, need.max()) }, gap, scrolls = true)
    }
}

/** The test tag of [key]'s cap, as the KeyBar board names it: `key-slash`, `key-ctrl`, `key-left`. */
internal fun keyTestTag(key: String): String = "key-" + when (key) {
    "/" -> "slash"
    else -> key.lowercase()
}

/**
 * The keys in the saved order (KeyBar board), as a touch bar's keys (#142, #218): caps of one width
 * across the bar, `sizes.keyGap` between them and around them ([keyCaps]). The strip rounds as the
 * pane card above it does ([radius]), and each cap one gap less, so the two curves run side by side.
 * Each key takes the taps up to halfway to the next, and the first and the last up to the strip's
 * ends. When the caps would be narrower than `sizes.key`, a bar on an [upright] window puts its keys
 * in two rows, and any other scrolls sideways, the edge with more keys beyond it fading out. A sticky
 * key in [armed] is lit until the next key uses it. While the bar is not [enabled] (the panes' input
 * is paused: [paneInputPaused]) every key is drawn disabled and takes no tap.
 */
@Composable
internal fun KeyBar(
    keys: List<String>, armed: Set<String>, modifier: Modifier, enabled: Boolean = true,
    radius: Dp = VeloTheme.radii.pane, upright: Boolean = LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp },
    onKey: (String, Boolean) -> Unit,
) {
    val sizes = VeloTheme.sizes
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // The gap is two halves of whole pixels, so that it is the same between every two caps, and the strip's padding is one gap.
    val gap = with(density) { (sizes.keyGap / 2).roundToPx() } * 2
    val capHeight = with(density) { sizes.keyCap.roundToPx() }
    // The room on each side of a label that the cap can give up before the bar takes two rows or scrolls.
    val legendPadding = with(density) { VeloTheme.medium(KeyLegendPadding).roundToPx() }
    val legendPaddingDp = VeloTheme.medium(KeyLegendPadding)
    val floor = with(density) { sizes.key.roundToPx() }
    val capRadius = keyCapRadius(radius)
    KeyStrip(modifier.testTag("key-bar"), radius) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewport = constraints.maxWidth
            SubcomposeLayout(Modifier.fillMaxWidth().horizontalScroll(scroll)) { _ ->
                // The labels alone first: how wide they are decides whether the keys take one row or two.
                val labels = subcompose(KeyBarSlot.Labels) {
                    for (key in keys) KeyLegend(key, Color.Unspecified, Modifier.padding(horizontal = legendPaddingDp))
                }
                val need = labels.map { maxOf(floor, it.maxIntrinsicWidth(capHeight)) }
                val caps = keyCaps(need, floor, gap, viewport - 2 * gap, give = legendPadding * 2, upright = upright)
                val columns = caps.columns
                // Between two caps, the first takes half the gap and the second the rest; at the strip's edge, a key takes the padding.
                fun start(i: Int) = if (i % columns == 0) gap else gap - gap / 2
                fun end(i: Int) = if (i % columns == columns - 1 || i == keys.lastIndex) gap else gap / 2
                fun top(i: Int) = if (i < columns) gap else gap - gap / 2
                fun bottom(i: Int) = if (i / columns == caps.rows - 1) gap else gap / 2
                val buttons = subcompose(KeyBarSlot.Keys) {
                    for ((i, key) in keys.withIndex()) {
                        key(key) {
                            // The cap stands inside the part of the bar it takes the taps in, half a gap from the next.
                            val insets = with(density) {
                                PaddingValues(start = start(i).toDp(), end = end(i).toDp(), top = top(i).toDp(), bottom = bottom(i).toDp())
                            }
                            KeyCap(key, { onKey(key, false) }, armed = key in armed,
                                contentDescription = spokenKeyName(key), enabled = enabled,
                                holdRepeat = key in REPEAT_KEYS, onRepeat = { onKey(key, true) },
                                capInsets = insets, capModifier = Modifier.testTag(keyTestTag(key)), radius = capRadius)
                        }
                    }
                }
                val children = buttons.mapIndexed { i, button ->
                    val width = caps.widths[i % columns] + start(i) + end(i)
                    val height = capHeight + top(i) + bottom(i)
                    button.measure(Constraints.fixed(width, height))
                }
                val rowHeight = { row: Int -> children.filterIndexed { i, _ -> i / columns == row }.maxOf { it.height } }
                val height = if (children.isEmpty()) capHeight + 2 * gap else (0 until caps.rows).sumOf(rowHeight)
                layout(maxOf(viewport, children.filterIndexed { i, _ -> i < columns }.sumOf { it.width }), height) {
                    var x = 0
                    var y = 0
                    for ((i, child) in children.withIndex()) {
                        if (i > 0 && i % columns == 0) { x = 0; y += rowHeight(i / columns - 1) }
                        child.placeRelative(x, y)
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
                Box(Modifier.matchParentSize()) {
                    if (scroll.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).width(sizes.icon).fillMaxHeight().background(if (rtl) right else left))
                    if (scroll.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).width(sizes.icon).fillMaxHeight().background(if (rtl) left else right))
                }
            }
        }
    }
}

private enum class KeyBarSlot { Labels, Keys }
