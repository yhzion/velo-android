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

/** Share spare pixels equally, reserving extra space only for keys whose labels need it. */
internal fun keyWidths(minimum: List<Int>, available: Int): List<Int> {
    if (minimum.sum() >= available) return minimum
    val widths = minimum.toMutableList()
    var remaining = available
    for ((position, index) in minimum.indices.sortedByDescending { minimum[it] }.withIndex()) {
        val width = maxOf(minimum[index], remaining / (minimum.size - position))
        widths[index] = width
        remaining -= width
    }
    return widths
}

/**
 * The keys in the saved order. They share the width ([keyWidths]); when their labels need more
 * than the bar has, the bar scrolls sideways and the edge with more keys beyond it fades out.
 * A sticky key in [armed] is lit until the next key uses it. While the bar is not [enabled]
 * (the panes' input is paused: [paneInputPaused]) every key is drawn disabled and takes no tap.
 */
@Composable
internal fun KeyBar(keys: List<String>, armed: Set<String>, modifier: Modifier, enabled: Boolean = true, onKey: (String, Boolean) -> Unit) {
    val gap = VeloTheme.sizes.gapCompact
    val sizes = VeloTheme.sizes
    val scroll = rememberScrollState()
    KeyStrip(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = constraints.maxWidth
            Layout(
                modifier = Modifier.fillMaxSize().horizontalScroll(scroll),
                content = {
                    for (key in keys) {
                        key(key) {
                            KeyCap(key, { onKey(key, false) }, armed = key in armed,
                                contentDescription = if (key == "Ctrl") "Control" else key, enabled = enabled,
                                holdRepeat = key in REPEAT_KEYS, onRepeat = { onKey(key, true) })
                        }
                    }
                },
            ) { buttons, limits ->
                val gapPx = gap.roundToPx()
                val totalGap = (buttons.size - 1).coerceAtLeast(0) * gapPx
                val minimum = buttons.map { it.maxIntrinsicWidth(limits.maxHeight) }
                val widths = keyWidths(minimum, viewport - totalGap)
                val children = buttons.mapIndexed { i, button ->
                    button.measure(Constraints(minWidth = widths[i], maxWidth = widths[i], maxHeight = limits.maxHeight))
                }
                layout(maxOf(viewport, widths.sum() + totalGap), limits.maxHeight) {
                    var x = 0
                    for (child in children) {
                        child.placeRelative(x, (limits.maxHeight - child.height) / 2)
                        x += child.width + gapPx
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
