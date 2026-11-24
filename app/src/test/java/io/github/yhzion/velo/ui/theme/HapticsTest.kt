package io.github.yhzion.velo.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticsTest {
    @Test
    fun belowApi34EachFeelIsOneTheSystemKnows() {
        // A constant the system does not know yet is felt as nothing at all.
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, feelConstant(Feel.ThresholdOn, 31))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, feelConstant(Feel.ThresholdOff, 33))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, feelConstant(Feel.Step, 33))
        assertEquals(HapticFeedbackConstants.CONFIRM, feelConstant(Feel.Confirm, 30))
        // An end has no older constant that says it: none rather than a tick that means something else.
        assertNull(feelConstant(Feel.End, 33))
    }

    @Test
    fun fromApi34EachFeelIsItsOwn() {
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, feelConstant(Feel.ThresholdOn, 34))
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, feelConstant(Feel.ThresholdOff, 34))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, feelConstant(Feel.Step, 37))
        assertEquals(HapticFeedbackConstants.CONFIRM, feelConstant(Feel.Confirm, 37))
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, feelConstant(Feel.End, 34))
    }

    @Test
    fun aModifierTurnedOnOrOffIsAToggleWhereTheSystemHasOne() {
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, feelConstant(Feel.ToggleOn, 34))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, feelConstant(Feel.ToggleOff, 37))
        // Below 34 both are the key bar's own tap, as before: never nothing.
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, feelConstant(Feel.ToggleOn, 33))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, feelConstant(Feel.ToggleOff, 30))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, feelConstant(Feel.Key, 37))
    }

    @Test
    fun belowApi34NoFeelUsesAConstantAddedIn34() {
        // 21 to 27 (TOGGLE_ON to SEGMENT_FREQUENT_TICK) came with API 34; REJECT, 17, is the last one before.
        for (sdk in 30..33) for (feel in Feel.entries) {
            val constant = feelConstant(feel, sdk)
            assertTrue("$feel on API $sdk is $constant", constant == null || constant <= HapticFeedbackConstants.REJECT)
        }
    }

    @Test
    fun theLibrarysOwnFeedbackFollowsTheSetting() {
        val felt = mutableListOf<HapticFeedbackType>()
        val platform = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                felt += hapticFeedbackType
            }
        }
        var on = false
        val gate = GatedHapticFeedback(platform) { on }
        gate.performHapticFeedback(HapticFeedbackType.LongPress)
        assertEquals(emptyList<HapticFeedbackType>(), felt)
        // Turned on in Settings while the screen stays: the next long press is felt.
        on = true
        gate.performHapticFeedback(HapticFeedbackType.LongPress)
        assertEquals(listOf(HapticFeedbackType.LongPress), felt)
    }
}
