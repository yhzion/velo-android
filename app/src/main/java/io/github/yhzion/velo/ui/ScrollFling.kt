package io.github.yhzion.velo.ui

import kotlin.math.exp
import kotlin.math.hypot

/** How quickly a fling's speed dies, in seconds. derived: the mockup's flingScroll (v3, 2026-11-24). */
internal const val FlingTau = 0.4f

/** The fastest a fling leaves, in dp a second. derived: the mockup (v3). */
internal const val FlingMaxDp = 6000f

/** A fling this slow, in dp a second, is over. derived: the mockup (v3). */
internal const val FlingStopDp = 12f

// The speed is read from the finger's last places by WheelSample's rule (Task 1); the same windows,
// kept here because WindowWheel keeps its own private.
private const val ScrollSampleFreshMs = 90L
private const val ScrollSampleWindowMs = 100L
private const val ScrollSampleSpanMs = 260L

/** Where a scrolling finger had scrolled to, in px as the finger moved it, at [at] ms. */
internal data class ScrollSample(val at: Long, val place: Float)

/**
 * The finger's speed as it left the scroll at [releasedAt], in px a second: over the last [ScrollSampleWindowMs] of
 * [samples], or between its two last places up to [ScrollSampleSpanMs] apart when it moved once in that time. A finger
 * that stopped [ScrollSampleFreshMs] before it left, or that has no two places, has no speed.
 */
internal fun flingVelocity(samples: List<ScrollSample>, releasedAt: Long): Float {
    val last = samples.lastOrNull() ?: return 0f
    if (releasedAt - last.at > ScrollSampleFreshMs) return 0f
    val first = samples.firstOrNull { releasedAt - it.at <= ScrollSampleWindowMs }?.takeIf { it !== last }
        ?: samples.elementAtOrNull(samples.size - 2) ?: return 0f
    if (releasedAt - first.at > ScrollSampleSpanMs || last.at <= first.at) return 0f
    return (last.place - first.place) / (last.at - first.at) * 1000f
}

/**
 * What a released scroll does without a finger: it keeps the finger's speed, at most [FlingMaxDp] dp a second, with a
 * length of that speed decayed over [FlingTau] less its tail under [FlingStopDp]. Each [step] moves the speed of that
 * moment for the seconds passed, and the fling is over once the speed falls under [FlingStopDp] dp a second or the
 * length is gone. The direction is the finger's: a drag sends what the finger moved, and so does a coast.
 */
internal class ScrollFling(val vx: Float, val vy: Float, density: Float) {
    private val stop = FlingStopDp * density
    private val dirX: Float
    private val dirY: Float
    /** The speed left, in px a second; it dies with [FlingTau]. */
    private var speed: Float
    /** The px the fling still has to travel. */
    private var length: Float
    var moving: Boolean = false
        private set

    init {
        val v = hypot(vx, vy)
        if (v <= stop) {
            dirX = 0f
            dirY = 0f
            speed = 0f
            length = 0f
        } else {
            dirX = vx / v
            dirY = vy / v
            val s = v * (FlingMaxDp * density / v).coerceAtMost(1f)
            speed = s
            length = s * FlingTau * (1f - stop / s)
            moving = true
        }
    }

    /** [seconds] passed: the px to scroll in x and y; none once it has stopped. */
    fun step(seconds: Float): Pair<Float, Float> {
        if (!moving) return 0f to 0f
        val t = seconds.coerceAtLeast(0f)
        var px = speed * t
        speed *= exp(-t / FlingTau)
        if (px >= length) {
            px = length
            length = 0f
            moving = false
        } else {
            length -= px
        }
        if (speed < stop) moving = false
        return px * dirX to px * dirY
    }
}
