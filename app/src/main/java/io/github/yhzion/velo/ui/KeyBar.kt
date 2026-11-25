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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import io.github.yhzion.velo.ui.theme.Feel
import io.github.yhzion.velo.ui.theme.KeyCap
import io.github.yhzion.velo.ui.theme.KeyStrip
import io.github.yhzion.velo.ui.theme.VeloTheme

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

/** The name remains available to accessibility even when the button displays an arrow. */
internal fun keyName(key: String): String = if (key in KEY_CODES || key in StickyKeys) key.uppercase() else key

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
 * The keys' widths on a bar with [available] room inside it (KeyBar board): each key at its own
 * width ([natural]: its label and padding, at least [floor]) while they all fit. When they do not,
 * the keys wider than [floor] give up the room that is missing in proportion to their widths, none
 * below [floor], as the board's CTRL does on the bar phone. Null when the keys do not fit even at
 * [floor]: the bar scrolls.
 */
internal fun keyCapWidths(natural: List<Int>, floor: Int, available: Int): List<Int>? {
    if (natural.sum() <= available) return natural
    val widths = natural.map { it.toFloat() }.toMutableList()
    val frozen = BooleanArray(natural.size) { natural[it] <= floor }
    var missing = (natural.sum() - available).toFloat()
    while (missing > 0.001f) {
        val open = natural.indices.filter { !frozen[it] }
        if (open.isEmpty()) return null
        val basis = open.sumOf { natural[it] }.toFloat()
        var given = 0f
        for (i in open) {
            val share = missing * natural[i] / basis
            if (widths[i] - share <= floor) {
                given += widths[i] - floor
                widths[i] = floor.toFloat()
                frozen[i] = true
            } else {
                widths[i] -= share
                given += share
            }
        }
        missing -= given
    }
    return roundToTotal(widths, available)
}

/** [values] rounded to whole pixels that add up to [total], the largest fractions rounded up. */
private fun roundToTotal(values: List<Float>, total: Int): List<Int> {
    val down = values.map { kotlin.math.floor(it).toInt() }.toMutableList()
    var left = total - down.sum()
    for (i in values.indices.sortedByDescending { values[it] - down[it] }) {
        if (left <= 0) break
        down[i]++
        left--
    }
    return down
}

/**
 * The part of the bar each key takes the taps in, for keys [caps] wide on a bar with [available]
 * room. The room the keys leave goes between them in equal gaps (the board's space-between): the
 * first key starts at the bar's start, the last ends at its end. Each key takes the taps up to half
 * of the gap on each side, and the first and the last up to the bar's ends, so a tap anywhere on the
 * bar reaches the nearest key. Without room left (or while the bar scrolls) the cells are the keys.
 */
internal fun keyCells(caps: List<Int>, available: Int): List<Int> {
    if (caps.isEmpty()) return emptyList()
    if (caps.size == 1) return listOf(maxOf(caps[0], available))
    val spare = available - caps.sum()
    if (spare <= 0) return caps
    val gap = spare.toFloat() / caps.lastIndex
    val edges = FloatArray(caps.size + 1)
    var start = 0f
    for (i in 1 until caps.size) {
        start += caps[i - 1]
        edges[i] = start + gap * i - gap / 2
    }
    edges[caps.size] = available.toFloat()
    return caps.indices.map { Math.round(edges[it + 1]) - Math.round(edges[it]) }
}

/** The test tag of [key]'s cap, as the KeyBar board names it: `key-slash`, `key-ctrl`, `key-left`. */
internal fun keyTestTag(key: String): String = "key-" + when (key) {
    "/" -> "slash"
    else -> key.lowercase()
}

/**
 * The keys in the saved order (KeyBar board). Each key keeps its own width ([keyCapWidths]); the room
 * left goes between them, and each key takes the taps up to halfway to the next ([keyCells]). When
 * their labels need more than the bar has, the bar scrolls sideways and the edge with more keys
 * beyond it fades out. A sticky key in [armed] is lit until the next key uses it. While the bar is
 * not [enabled] (the panes' input is paused: [paneInputPaused]) every key is drawn disabled and takes no tap.
 */
@Composable
internal fun KeyBar(keys: List<String>, armed: Set<String>, modifier: Modifier, enabled: Boolean = true, onKey: (String, Boolean) -> Unit) {
    val sizes = VeloTheme.sizes
    val scroll = rememberScrollState()
    KeyStrip(modifier.testTag("key-bar")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = constraints.maxWidth
            Layout(
                modifier = Modifier.fillMaxSize().horizontalScroll(scroll),
                content = {
                    for ((i, key) in keys.withIndex()) {
                        key(key) {
                            // The cap stands where the board draws it inside the part of the bar it takes the taps in.
                            val place = when {
                                keys.size == 1 -> Alignment.Center
                                i == 0 -> Alignment.CenterStart
                                i == keys.lastIndex -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }
                            KeyCap(key, { onKey(key, false) }, armed = key in armed, latching = key in StickyKeys,
                                contentDescription = if (key == "Ctrl") "Control" else key, enabled = enabled,
                                holdRepeat = key in REPEAT_KEYS, onRepeat = { onKey(key, true) },
                                capAlignment = place, capModifier = Modifier.testTag(keyTestTag(key)))
                        }
                    }
                },
            ) { buttons, limits ->
                val natural = buttons.map { it.maxIntrinsicWidth(limits.maxHeight) }
                val caps = keyCapWidths(natural, sizes.key.roundToPx(), viewport) ?: natural
                val cells = keyCells(caps, viewport)
                val children = buttons.mapIndexed { i, button ->
                    button.measure(Constraints(minWidth = cells[i], maxWidth = cells[i], maxHeight = limits.maxHeight))
                }
                layout(maxOf(viewport, cells.sum()), limits.maxHeight) {
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
