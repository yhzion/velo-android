package io.github.yhzion.velo.ui

import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

// The rail's window wheel (#304). A long press on the rail's free strip turns the strip into a wheel of the
// session's windows: each window the wheel turns past ticks once, and the window at its front when the finger lifts is
// selected. A plain drag on the strip scrolls the focused pane instead. The numbers come from the mockup the owner chose
// (v2, 2026-11-23).

/** The fastest the wheel turns, in windows per second: one tick every 25 ms at most, so ticks never blur into a buzz. */
internal const val WheelMaxSpeed = 40f

/** How far past the line between two windows the wheel goes before the next one is in front: a finger resting on the line ticks once. */
internal const val WheelHysteresis = 0.1f

/** How far past an end, in windows, the finger pulls before the end is felt. */
internal const val WheelEndPull = 0.35f

/** Back within this much of the ends, a pull past an end may be felt again. */
private const val WheelEndRearm = 0.1f

/** The farthest the finger's pull counts past an end, in windows. */
private const val WheelMaxPull = 1.6f

/** How quickly a let-go wheel comes to rest on its window, in seconds. derived: the mockup (v2). */
private const val WheelSettleSeconds = 0.07f

/** The finger's travel that turns a slow wheel by one window. derived: the mockup (v2), 28 dp. */
internal const val WheelDetentDp = 28f

/** The angle between two titles on the rail's drum, in degrees. derived: the mockup (v2). */
internal const val WheelTitleAngle = 30f

/** The least time between two ticks out, in ms: ticks a dropped frame bunched up leave one after another. */
internal const val WheelTickGapMs = 22L

/** A press shows no charge ring for this long: most scrolls begin within it. derived: the mockup (v2). */
internal const val ChargeDelayMs = 110L

/** The finger's speed, dp per ms, below which the wheel turns one window per [WheelDetentDp]. */
private const val GainFrom = 0.35f

/** The finger's speed, dp per ms, from which the wheel turns [MaxGain] windows per [WheelDetentDp]. */
private const val GainTo = 1.6f

/** How much farther a fast finger turns the wheel. derived: the mockup (v2). */
private const val MaxGain = 2.6f

private fun smoothStep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/** How many windows one [WheelDetentDp] of travel turns at a finger's speed of [dpPerMs]: a quick flick goes farther. */
internal fun wheelGain(dpPerMs: Float): Float = 1f + (MaxGain - 1f) * smoothStep(GainFrom, GainTo, dpPerMs)

/** Where the wheel shows a pull of [over] windows past an end: it gives less and less, never three quarters of a window. */
internal fun wheelRubber(over: Float): Float = (1f - 1f / (over.coerceAtLeast(0f) * 0.6f + 1f)) * 0.75f

/** How full the ring around a press on the strip is after [heldMs]: empty while a scroll may still begin, full at the long press. */
internal fun pressCharge(heldMs: Long, longPressMs: Long): Float =
    if (longPressMs <= ChargeDelayMs) 1f else ((heldMs - ChargeDelayMs).toFloat() / (longPressMs - ChargeDelayMs)).coerceIn(0f, 1f)

/** What the strip makes of a finger that has not yet held long enough to turn the wheel. */
internal enum class StripFinger {
    /** Still: it may become the wheel's. */
    Holding,
    /** It moved past the touch slop: it scrolls the focused pane. */
    Scrolls,
    /** Lifted, or joined by another finger: neither a scroll nor the wheel. */
    Gone,
}

/** A finger that went down on the strip and has moved by ([dx], [dy]). */
internal fun stripFinger(dx: Float, dy: Float, slop: Float, pressed: Boolean, fingers: Int): StripFinger = when {
    !pressed || fingers > 1 -> StripFinger.Gone
    abs(dx) > slop || abs(dy) > slop -> StripFinger.Scrolls
    else -> StripFinger.Holding
}

/** The haptic of a window the wheel turns past: the frequent tick of a dial where the system has it (API 34). */
internal fun wheelTickFeedback(sdk: Int): Int =
    if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK

/** The haptic of a pull past an end; none below API 34, where no constant says it. */
internal fun wheelEndFeedback(sdk: Int): Int? =
    if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else null

/**
 * Where a screen reader's step of the slider goes from the window at [at] of [count], asked for [value] (1 to [count]):
 * the window nearest [value], and at least the next one in the way it moved. A slider of two windows has no steps, and
 * its reader moves it a twentieth at a time: rounded, that would never leave the window.
 */
internal fun wheelStepTo(at: Int, value: Float, count: Int): Int {
    val asked = Math.round(value) - 1
    val target = when {
        value > at + 1 -> maxOf(asked, at + 1)
        value < at + 1 -> minOf(asked, at - 1)
        else -> at
    }
    return target.coerceIn(0, count - 1)
}

/** What a screen reader says of the wheel: the window in front and where it stands. */
internal fun wheelValueText(name: String, index: Int, count: Int): String = "$name, ${index + 1} of $count"

/** A title on the rail's drum: the window at [index], [angle] degrees from the front (later windows below). */
internal data class DrumTitle(val index: Int, val angle: Float)

/**
 * The titles the rail's drum shows at [position] among [count] windows, [open] of the way out of the strip: three on
 * each side at most, those turned less than a right angle from the front. Opening, they unroll from the front.
 */
internal fun drumTitles(position: Float, count: Int, open: Float): List<DrumTitle> {
    if (count <= 0 || open <= 0f) return emptyList()
    val middle = position.roundToInt()
    val spread = 0.3f + 0.7f * open.coerceIn(0f, 1f)
    return ((middle - 3)..(middle + 3)).filter { it in 0 until count }
        .map { DrumTitle(it, (it - position) * WheelTitleAngle * spread) }
        .filter { abs(it.angle) < 88f }
}

/** What the wheel felt in one step of the finger. */
internal enum class WheelTick { End }

/**
 * The window wheel of [count] windows, opened on the window at [start]. [position] is where it is drawn, in windows;
 * [index] the window in front, the last one it ticked past. The finger [drag]s it; time [advance]s it toward the
 * finger, no faster than [WheelMaxSpeed]; let go ([release]), it comes to rest on [index].
 */
internal class WindowWheel(val count: Int, start: Int) {
    init {
        require(count > 0) { "a wheel of no windows" }
    }

    var index = start.coerceIn(0, count - 1)
        private set
    var position = index.toFloat()
        private set
    /** Where the finger has pulled the wheel to, ends not held back. */
    private var pull = position
    private var held = true
    private var pastEnd = false

    private val last get() = (count - 1).toFloat()

    /** Where the finger's pull shows: past an end, held back by [wheelRubber]. */
    val target: Float get() = when {
        pull < 0f -> -wheelRubber(-pull)
        pull > last -> last + wheelRubber(pull - last)
        else -> pull
    }

    /** At rest on its window after the finger let go. */
    val resting: Boolean get() = !held && position == index.toFloat()

    /**
     * The finger turned the wheel by [windows], toward later windows when positive. [WheelTick.End] the first time
     * its pull passes an end; it may be felt again once the pull has come back.
     */
    fun drag(windows: Float): WheelTick? {
        if (!held) return null
        pull = (pull + windows).coerceIn(-WheelMaxPull, last + WheelMaxPull)
        if (pull > -WheelEndRearm && pull < last + WheelEndRearm) pastEnd = false
        val past = pull < -WheelEndPull || pull > last + WheelEndPull
        if (past && !pastEnd) {
            pastEnd = true
            return WheelTick.End
        }
        return null
    }

    /** The finger let go: the wheel comes to rest on [index]. */
    fun release() {
        held = false
        pull = index.toFloat()
    }

    /**
     * [seconds] passed: the wheel turns toward the finger (held) or its window (let go), no faster than
     * [WheelMaxSpeed]. Returns how many windows it turned past, each one a tick.
     */
    fun advance(seconds: Float): Int {
        val goal = if (held) target else index.toFloat()
        val most = WheelMaxSpeed * seconds.coerceAtLeast(0f)
        var step = if (held) goal - position else (goal - position) * (1f - exp(-seconds / WheelSettleSeconds))
        if (abs(step) > most) step = most * sign(step)
        position += step
        if (!held && abs(goal - position) < 0.002f) position = goal
        var ticks = 0
        while (position > index + 0.5f + WheelHysteresis && index < count - 1) {
            index++
            ticks++
        }
        while (position < index - 0.5f - WheelHysteresis && index > 0) {
            index--
            ticks++
        }
        return ticks
    }
}
