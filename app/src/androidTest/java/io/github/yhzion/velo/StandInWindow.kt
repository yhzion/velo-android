package io.github.yhzion.velo

import android.app.Instrumentation
import android.content.Context
import android.graphics.Insets
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Frames waited after each step: Compose recomposes in the next one, and applies the insets it asks for in the one after. */
internal const val FRAMES = 4

/** The navigation bar Android keeps up for the input method's window on the unfolded Fold8 emulator. */
internal const val NAVIGATION_BAR = 126

/** Waits for [count] frames of the main thread: Compose recomposes in them. */
internal fun Instrumentation.frames(count: Int) {
    val done = CountDownLatch(1)
    runOnMainSync {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            var left = count
            override fun doFrame(frameTimeNanos: Long) {
                if (--left == 0) done.countDown() else Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }
    check(done.await(5, TimeUnit.SECONDS)) { "The main thread drew no frame in 5 s" }
}

internal fun Instrumentation.awaitFrames(what: String, condition: () -> Boolean) {
    val end = SystemClock.uptimeMillis() + 5_000
    while (!condition()) {
        check(SystemClock.uptimeMillis() < end) { "Waited 5 s for $what" }
        frames(1)
    }
}

/**
 * A window with a navigation bar of [navigationBar] px at the bottom (none when 0), and the input
 * method's window shown or not, taking [ime] px at the bottom (0: it draws no keyboard).
 */
internal fun insets(navigationBar: Int, imeShown: Boolean, ime: Int = 0): WindowInsets = WindowInsets.Builder()
    .setInsets(WindowInsets.Type.statusBars(), Insets.NONE)
    .setVisible(WindowInsets.Type.statusBars(), false)
    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
    .setVisible(WindowInsets.Type.navigationBars(), navigationBar > 0)
    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, ime))
    .setVisible(WindowInsets.Type.ime(), imeShown)
    .build()

/**
 * Plays the window for the views inside it. Each dispatch of insets, the window's own included
 * (Compose asks for them again when it sets its listener), hands them [insets]; insets animations
 * reach them only when sent with [prepare], [start], [progress] and [end].
 */
internal class StandInWindow(context: Context, private var insets: WindowInsets) : FrameLayout(context) {
    private var sending = false
    private var applying = false
    val navigationBar get() = insets.getInsets(WindowInsets.Type.navigationBars()).bottom

    /** Dispatches of insets that did not come from [apply]: the window's own, asked for by Compose or by the system. */
    var ownDispatches = 0
        private set

    fun apply(next: WindowInsets) {
        insets = next
        applying = true
        try {
            dispatchApplyWindowInsets(next)
        } finally {
            applying = false
        }
    }

    override fun dispatchApplyWindowInsets(given: WindowInsets): WindowInsets {
        if (!applying) ownDispatches++
        return super.dispatchApplyWindowInsets(insets)
    }

    fun prepare(animation: WindowInsetsAnimation) = sent { dispatchWindowInsetsAnimationPrepare(animation) }
    fun start(animation: WindowInsetsAnimation) = sent {
        dispatchWindowInsetsAnimationStart(animation, WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, NAVIGATION_BAR)))
    }
    fun progress(state: WindowInsets, vararg running: WindowInsetsAnimation) = sent {
        dispatchWindowInsetsAnimationProgress(state, running.toMutableList())
    }
    fun end(animation: WindowInsetsAnimation) = sent { dispatchWindowInsetsAnimationEnd(animation) }

    private fun sent(action: () -> Unit) {
        sending = true
        try {
            action()
        } finally {
            sending = false
        }
    }

    override fun dispatchWindowInsetsAnimationPrepare(animation: WindowInsetsAnimation) {
        if (sending) super.dispatchWindowInsetsAnimationPrepare(animation)
    }

    override fun dispatchWindowInsetsAnimationStart(
        animation: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds,
    ): WindowInsetsAnimation.Bounds = if (sending) super.dispatchWindowInsetsAnimationStart(animation, bounds) else bounds

    override fun dispatchWindowInsetsAnimationProgress(
        state: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>,
    ): WindowInsets = if (sending) super.dispatchWindowInsetsAnimationProgress(state, runningAnimations) else state

    override fun dispatchWindowInsetsAnimationEnd(animation: WindowInsetsAnimation) {
        if (sending) super.dispatchWindowInsetsAnimationEnd(animation)
    }
}
