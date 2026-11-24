package io.github.yhzion.velo

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsetsAnimation
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import io.github.yhzion.velo.ui.StartPreviewActivity
import io.github.yhzion.velo.ui.screenIme
import io.github.yhzion.velo.ui.screenSafeDrawing
import io.github.yhzion.velo.ui.unlessWholeWindow
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.foundation.layout.WindowInsets as ComposeInsets

/**
 * The app's screens make no room for an input method's inset as tall as the window (#325).
 *
 * With a hardware keyboard the emulator's LatinIME can report an ime inset that covers the whole
 * window while it draws no keyboard, and the workspace, making room for it, was left 41 px high.
 * Every editor of the app refuses a fullscreen input method, so an inset that tall is no room a
 * keyboard needs. A Compose view padded as the app's screens are ([screenSafeDrawing]) is sent, by
 * hand: a 126 px navigation bar alone; an 800 px keyboard, shown and hidden with an animation; an
 * input method's inset as tall as the window, as it is and while it is shown and hidden with an
 * animation (the values on the way are below the window's height). Beside it, a view padded with
 * Compose's own `safeDrawing` must be left no room in the whole-window case: that shows the test
 * reaches the state. The test sends the window's height Compose reports as the inset; that the
 * platform's inset matches it is the issue's measurement, not this test's.
 *
 * The Compose view is put in [StandInWindow], as in [InsetsReaderInstrumentation]. Run on an emulator:
 * `adb shell am instrument -w io.github.yhzion.velo.test/io.github.yhzion.velo.ImeRoomInstrumentation`
 */
class ImeRoomInstrumentation : Instrumentation() {
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
                measure(activity, report)
            } finally {
                runOnMainSync { activity.finish() }
            }
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", result.exceptionOrNull()?.stackTraceToString() ?: "Ime room regression passed\n$report")
        })
    }

    private fun measure(activity: Activity, report: StringBuilder) {
        val windowHeight = AtomicInteger(-1)
        val screen = AtomicInteger(-1)
        val compose = AtomicInteger(-1)
        val ime = AtomicInteger(-1)
        val target = AtomicInteger(-1)
        lateinit var window: StandInWindow
        runOnMainSync {
            window = StandInWindow(activity, insets(NAVIGATION_BAR, imeShown = false))
            window.addView(ComposeView(activity).apply {
                setContent {
                    val density = LocalDensity.current
                    val height = LocalWindowInfo.current.containerSize.height
                    val imeBottom = ComposeInsets.screenIme.getBottom(density)
                    val targetBottom = ComposeInsets.imeAnimationTarget.unlessWholeWindow().getBottom(density)
                    SideEffect {
                        windowHeight.set(height)
                        ime.set(imeBottom)
                        target.set(targetBottom)
                    }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(ComposeInsets.screenSafeDrawing)) {
                            Box(Modifier.fillMaxSize().onSizeChanged { screen.set(it.height) })
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(ComposeInsets.safeDrawing)) {
                            Box(Modifier.fillMaxSize().onSizeChanged { compose.set(it.height) })
                        }
                    }
                }
            })
            activity.setContentView(window)
        }
        awaitFrames("the window's height") { windowHeight.get() > 0 && screen.get() >= 0 }
        val whole = windowHeight.get()
        val viewHeight = window.height
        report.append("Window %d px high (Compose), the view %d px\n".format(whole, viewHeight))

        fun case(name: String, state: android.view.WindowInsets, room: Int, imeRoom: Int, composeRoom: Int) {
            runOnMainSync { window.apply(state) }
            frames(FRAMES)
            report.append("  %-44s screen %d, safeDrawing %d, ime %d, target %d\n".format(
                name, screen.get(), compose.get(), ime.get(), target.get()))
            check(compose.get() == maxOf(0, viewHeight - composeRoom)) {
                "Compose's safeDrawing left ${compose.get()} px, expected ${maxOf(0, viewHeight - composeRoom)}: " +
                    "the test no longer sends what it means to.\n$report"
            }
            check(screen.get() == viewHeight - room) {
                "$name: the screen was left ${screen.get()} px, expected ${viewHeight - room}.\n$report"
            }
            check(ime.get() == imeRoom && target.get() == imeRoom) {
                "$name: the input method's inset read ${ime.get()} (target ${target.get()}), expected $imeRoom.\n$report"
            }
        }
        /** Shows or hides the input method with an animation to [end], checking the room made halfway, at [middle]. */
        fun animated(name: String, end: android.view.WindowInsets, middle: Int, room: Int, imeRoom: Int, targetRoom: Int) {
            val animation = WindowInsetsAnimation(android.view.WindowInsets.Type.ime(), LinearInterpolator(), 285)
            runOnMainSync {
                window.prepare(animation)
                window.apply(end)
                window.start(animation)
                window.progress(insets(NAVIGATION_BAR, imeShown = true, ime = middle), animation)
            }
            frames(FRAMES)
            report.append("  %-44s screen %d, safeDrawing %d, ime %d, target %d\n".format(
                "$name, halfway", screen.get(), compose.get(), ime.get(), target.get()))
            check(screen.get() == viewHeight - room) {
                "$name, halfway: the screen was left ${screen.get()} px, expected ${viewHeight - room}.\n$report"
            }
            check(ime.get() == imeRoom && target.get() == targetRoom) {
                "$name, halfway: the input method's inset read ${ime.get()} (target ${target.get()}), " +
                    "expected $imeRoom (target $targetRoom).\n$report"
            }
            runOnMainSync { window.end(animation) }
            frames(FRAMES)
        }
        case("navigation bar, no input method", insets(NAVIGATION_BAR, imeShown = false),
            room = NAVIGATION_BAR, imeRoom = 0, composeRoom = NAVIGATION_BAR)
        animated("an 800 px keyboard shown", insets(NAVIGATION_BAR, imeShown = true, ime = KEYBOARD), middle = KEYBOARD / 2,
            room = KEYBOARD / 2, imeRoom = KEYBOARD / 2, targetRoom = KEYBOARD)
        case("navigation bar and an 800 px keyboard", insets(NAVIGATION_BAR, imeShown = true, ime = KEYBOARD),
            room = KEYBOARD, imeRoom = KEYBOARD, composeRoom = KEYBOARD)
        animated("the 800 px keyboard hidden", insets(NAVIGATION_BAR, imeShown = false), middle = KEYBOARD / 2,
            room = KEYBOARD / 2, imeRoom = KEYBOARD / 2, targetRoom = 0)
        animated("an input method as tall as the window shown", insets(NAVIGATION_BAR, imeShown = true, ime = whole),
            middle = whole / 2, room = NAVIGATION_BAR, imeRoom = 0, targetRoom = 0)
        case("navigation bar and an input method as tall as the window", insets(NAVIGATION_BAR, imeShown = true, ime = whole),
            room = NAVIGATION_BAR, imeRoom = 0, composeRoom = whole)
        animated("the input method as tall as the window hidden", insets(NAVIGATION_BAR, imeShown = false),
            middle = whole / 2, room = NAVIGATION_BAR, imeRoom = 0, targetRoom = 0)
        case("the input method's window with no keyboard", insets(NAVIGATION_BAR, imeShown = true),
            room = NAVIGATION_BAR, imeRoom = 0, composeRoom = NAVIGATION_BAR)
    }

    private companion object {
        const val KEYBOARD = 800
    }
}
