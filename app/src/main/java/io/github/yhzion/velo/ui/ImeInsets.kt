package io.github.yhzion.velo.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Whether an input method's inset reaching [imeBottom] px up from the bottom of a window
 * [windowHeight] px high covers all of it (#325).
 *
 * Every editor of the app refuses a fullscreen input method: the terminal's input view asks for
 * `IME_FLAG_NO_FULLSCREEN` and `IME_FLAG_NO_EXTRACT_UI`, and Compose's text fields always add
 * `IME_FLAG_NO_FULLSCREEN`. So such an inset is no keyboard to keep clear of. The emulator's
 * LatinIME reports one with a hardware keyboard while it draws nothing, and the workspace, making
 * room for it, was left 41 px high. Should a keyboard cover all of a window anyway (a fullscreen
 * one, or a real keyboard over a small window), it leaves nothing above it to keep clear. A height
 * of 0 is a [WindowInfo] that knows no window yet (or a test's): nothing is taken for whole.
 */
internal fun wholeWindow(imeBottom: Int, windowHeight: Int): Boolean = windowHeight > 0 && imeBottom >= windowHeight

/** The room to make for an input method's inset: none when it covers the whole window ([wholeWindow]). */
internal fun imeRoom(imeBottom: Int, windowHeight: Int): Int = if (wholeWindow(imeBottom, windowHeight)) 0 else imeBottom

/** These insets, but with no room at the bottom when it covers the whole window ([wholeWindow]). */
@Composable
internal fun WindowInsets.unlessWholeWindow(): WindowInsets {
    val window = LocalWindowInfo.current
    return remember(this, window) { UnlessWholeWindow(this, window) }
}

/**
 * The input method's inset as the app's screens take it: none while it covers the whole window,
 * and none while it is shown or hidden with an animation from or to such an inset, whose values on
 * the way are below the window's height ([wholeWindow]).
 */
internal val WindowInsets.Companion.screenIme: WindowInsets
    @Composable get() {
        val ime = WindowInsets.ime
        val source = WindowInsets.imeAnimationSource
        val target = WindowInsets.imeAnimationTarget
        val window = LocalWindowInfo.current
        return remember(ime, source, target, window) { ScreenIme(ime, source, target, window) }
    }

/**
 * Where the app's screens draw: Compose's `safeDrawing` (system bars, display cutout, input
 * method), with the input method's part as [screenIme] takes it.
 */
internal val WindowInsets.Companion.screenSafeDrawing: WindowInsets
    @Composable get() {
        val bars = WindowInsets.systemBars
        val cutout = WindowInsets.displayCutout
        val ime = WindowInsets.screenIme
        return remember(bars, cutout, ime) { bars.union(cutout).union(ime) }
    }

@Stable
private class UnlessWholeWindow(private val insets: WindowInsets, private val window: WindowInfo) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = insets.getLeft(density, layoutDirection)
    override fun getTop(density: Density) = insets.getTop(density)
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = insets.getRight(density, layoutDirection)
    override fun getBottom(density: Density) = imeRoom(insets.getBottom(density), window.containerSize.height)

    override fun equals(other: Any?) = other is UnlessWholeWindow && other.insets == insets && other.window == window
    override fun hashCode() = insets.hashCode() * 31 + window.hashCode()
    override fun toString() = "($insets unless it covers the window)"
}

@Stable
private class ScreenIme(
    private val ime: WindowInsets,
    private val source: WindowInsets,
    private val target: WindowInsets,
    private val window: WindowInfo,
) : WindowInsets {
    private fun whole(density: Density): Boolean {
        val height = window.containerSize.height
        return wholeWindow(ime.getBottom(density), height) || wholeWindow(source.getBottom(density), height) ||
            wholeWindow(target.getBottom(density), height)
    }

    override fun getLeft(density: Density, layoutDirection: LayoutDirection) = ime.getLeft(density, layoutDirection)
    override fun getTop(density: Density) = ime.getTop(density)
    override fun getRight(density: Density, layoutDirection: LayoutDirection) = ime.getRight(density, layoutDirection)
    override fun getBottom(density: Density) = if (whole(density)) 0 else ime.getBottom(density)

    override fun equals(other: Any?) = other is ScreenIme && other.ime == ime && other.source == source &&
        other.target == target && other.window == window
    override fun hashCode() = ((ime.hashCode() * 31 + source.hashCode()) * 31 + target.hashCode()) * 31 + window.hashCode()
    override fun toString() = "(ime as the app's screens take it)"
}
