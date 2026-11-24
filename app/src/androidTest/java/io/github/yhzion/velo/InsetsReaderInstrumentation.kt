package io.github.yhzion.velo

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import io.github.yhzion.velo.ui.StartPreviewActivity
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.foundation.layout.WindowInsets as ComposeInsets

/**
 * Compose keeps the window's insets when the screen changes while two insets animations run (#314).
 *
 * The order of #291 and #314, sent by hand to a Compose view laid out as the activity's: the
 * bars' hide animation runs; the input method's show animation starts and the window is given a
 * 126 px navigation bar (Android keeps the bar up for the input method's window); the screen
 * changes; the input method's animation ends; the bars' animation reports down to no bar and ends.
 * When the change of screen takes every reader of `WindowInsets` out of the composition, Compose
 * resets its listener as the next reader arrives (foundation-layout 1.13,
 * `WindowInsetsHolder.incrementAccessors`) and ends with 0. With [HoldWindowInsets] above the
 * screens, as `MainActivity` has it, it ends with the window's 126.
 *
 * The first run, without the reader at the root, must end with 0: it shows that the test still
 * reaches the order. Should a Compose version mend the order itself, that run fails and says so.
 *
 * The Compose view is put in [StandInWindow], which plays the window: whatever the borrowed
 * activity's window has, the view below gets only the insets and animations sent here. A debug
 * build's [StartPreviewActivity] lends its window. Run on an emulator:
 * `adb shell am instrument -w io.github.yhzion.velo.test/io.github.yhzion.velo.InsetsReaderInstrumentation`
 */
class InsetsReaderInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = StringBuilder()
        val result = runCatching {
            val activity = startActivitySync(Intent(targetContext, StartPreviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                val lost = changeScreenWhileAnimating(activity, holdAtRoot = false, report)
                check(lost == 0) {
                    "Without a reader at the root Compose ended with $lost, not 0: this order no longer loses the " +
                        "window's insets, and the reader at the root may not be needed. Unless a stray dispatch did it: " +
                        "without the reader \"own\" is 1, then 2 from the change of screen on; more is a stray one.\n$report"
                }
                val held = changeScreenWhileAnimating(activity, holdAtRoot = true, report)
                check(held == NAVIGATION_BAR) {
                    "With the reader at the root Compose ended with $held while the window has $NAVIGATION_BAR.\n$report"
                }
            } finally {
                runOnMainSync { activity.finish() }
            }
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "Insets reader regression passed\n$report")
        })
    }

    /** Sends the order of #314 to a new Compose view and returns the bottom inset the screen shown reads at the end. */
    private fun changeScreenWhileAnimating(activity: Activity, holdAtRoot: Boolean, report: StringBuilder): Int {
        val screen = mutableIntStateOf(0)
        val shown = AtomicInteger(-1)
        val bottom = AtomicInteger(-1)
        lateinit var window: StandInWindow
        report.append(if (holdAtRoot) "With the reader at the root:\n" else "Without a reader at the root:\n")
        fun step(name: String, action: StandInWindow.() -> Unit) {
            runOnMainSync { window.action() }
            frames(FRAMES)
            report.append(line(name, bottom.get(), window))
        }
        runOnMainSync {
            window = StandInWindow(activity, insets(NAVIGATION_BAR, imeShown = false))
            window.addView(ComposeView(activity).apply {
                setContent {
                    if (holdAtRoot) HoldWindowInsets()
                    key(screen.intValue) { Screen(screen.intValue, shown, bottom) }
                }
            })
            activity.setContentView(window)
        }
        awaitFrames("the first screen with the window's insets") { shown.get() == 0 && bottom.get() == NAVIGATION_BAR }
        report.append(line("first screen, bars shown", bottom.get(), window))

        val bars = WindowInsetsAnimation(WindowInsets.Type.systemBars(), LinearInterpolator(), 340)
        val ime = WindowInsetsAnimation(WindowInsets.Type.ime(), LinearInterpolator(), 285)
        step("bars' hide animation: prepare, insets (no bar), start") {
            prepare(bars)
            apply(insets(0, imeShown = false))
            start(bars)
        }
        step("bars' hide animation: progress 97") { progress(insets(97, imeShown = false), bars) }
        step("input method's animation: prepare, insets (bar 126), start") {
            prepare(ime)
            apply(insets(NAVIGATION_BAR, imeShown = true))
            start(ime)
        }
        step("progress 90 of both") { progress(insets(90, imeShown = true), bars, ime) }
        runOnMainSync { screen.intValue = 1 }
        awaitFrames("the second screen") { shown.get() == 1 }
        frames(FRAMES)
        report.append(line("the screen changes", bottom.get(), window))
        step("input method's animation ends") { end(ime) }
        step("bars' hide animation: progress 40") { progress(insets(40, imeShown = true), bars) }
        step("bars' hide animation: progress 0") { progress(insets(0, imeShown = true), bars) }
        step("bars' hide animation ends") { end(bars) }
        return bottom.get()
    }

    /** One step of the report; "own" counts the borrowed window's dispatches of insets so far, which carry the stand-in's. */
    private fun line(step: String, compose: Int, window: StandInWindow) =
        "  %-58s Compose %d, window %d, own %d\n".format(step, compose, window.navigationBar, window.ownDispatches)
}

/** One screen of the app: it reads the insets for itself, as each of `MainActivity`'s screens does. */
@Composable
private fun Screen(id: Int, shown: AtomicInteger, bottom: AtomicInteger) {
    val value = ComposeInsets.safeDrawing.getBottom(LocalDensity.current)
    SideEffect {
        shown.set(id)
        bottom.set(value)
    }
}
