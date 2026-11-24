package io.github.yhzion.velo.ui.theme

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

// The haptics the app plays itself (#309), and the Haptic feedback setting for the ones the library plays.

/**
 * What a finger is told: a step done, a threshold crossed one way or back, a stop passed, an end reached, a key
 * sent, a modifier turned on or off.
 */
enum class Feel { Confirm, ThresholdOn, ThresholdOff, Step, End, Key, ToggleOn, ToggleOff }

/**
 * The constant of [feel] on API [sdk]. Below 34 a gesture or segment constant is unknown, and an unknown one is
 * felt as nothing: the clock's tick stands in, as on the overview's cards and the rail's wheel. An end has no older
 * constant that says it, so it has none there.
 */
fun feelConstant(feel: Feel, sdk: Int): Int? = when (feel) {
    Feel.Confirm -> HapticFeedbackConstants.CONFIRM
    Feel.ThresholdOn -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    Feel.ThresholdOff -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CLOCK_TICK
    Feel.Step -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    Feel.End -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else null
    Feel.Key -> HapticFeedbackConstants.KEYBOARD_TAP
    // Below 34 a modifier's tap is the key bar's own, as before: on and off are not told apart there.
    Feel.ToggleOn -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.KEYBOARD_TAP
    Feel.ToggleOff -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.KEYBOARD_TAP
}

/** Plays [feel] on this view, if this API has a constant for it. The caller checks the setting. */
fun View.feel(feel: Feel) {
    feelConstant(feel, Build.VERSION.SDK_INT)?.let(::performHapticFeedback)
}

/** The Haptic feedback setting, for the controls that read it where they are drawn ([rememberFeel]). */
val LocalHapticsOn = compositionLocalOf { true }

/** The library's own haptics, such as a long press on a [ListRow], pass through only while [on]. */
class GatedHapticFeedback(private val platform: HapticFeedback, private val on: () -> Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (on()) platform.performHapticFeedback(hapticFeedbackType)
    }
}

/**
 * Gives [content] the Haptic feedback setting [on], and gates the library's own haptics with it. Each window
 * provides its own [LocalHapticFeedback], so a dialog's window calls this again inside it ([OverlayHost]).
 */
@Composable
fun ProvideHaptics(on: Boolean = LocalHapticsOn.current, content: @Composable () -> Unit) {
    val now by rememberUpdatedState(on)
    val platform = LocalHapticFeedback.current
    val gated = remember(platform) { GatedHapticFeedback(platform) { now } }
    CompositionLocalProvider(LocalHapticsOn provides on, LocalHapticFeedback provides gated, content = content)
}

/** Plays a [Feel] on the view this is drawn in while the Haptic feedback setting is on. */
@Composable
fun rememberFeel(): (Feel) -> Unit {
    val view = LocalView.current
    val on by rememberUpdatedState(LocalHapticsOn.current)
    return remember(view) { { feel -> if (on) view.feel(feel) } }
}
