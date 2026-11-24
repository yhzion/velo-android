package io.github.yhzion.velo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * The overview's motion ([OverviewState.progress] and [OverviewState.scroll]) and what composition decides from it (#347).
 * The two numbers change on every frame of a motion, but what composition decides from them changes rarely: whether the
 * overview shows at all ([showing]), and which places keep surfaces ([keptAt]). A composition that read the numbers, even
 * through a derived state that does not change, had a recompose pass scheduled on every frame, only to find nothing to
 * do. So these are worked out as the numbers are written, and what composition reads changes only when they do.
 * Drawing, placing and laying out read the numbers themselves, as before.
 *
 * A finger that moves a card or a pane, and another session's row that drops in, move on each frame as well (#373): what
 * composition decides from them ([decided]) is worked out as they are written too, by those who write them ([moved]).
 *
 * Written and read on the main thread, the numbers in the global snapshot (animations, gestures, effects). Each decision is
 * read by one composable: [decided] keeps what the last call worked out in plain fields, and a move compares with that.
 */
internal class MotionValues {
    private val progressState = mutableFloatStateOf(0f)
    private val scrollState = mutableFloatStateOf(0f)

    var progress: Float
        get() = progressState.floatValue
        set(value) {
            progressState.floatValue = value
            moved()
        }

    var scroll: Float
        get() = scrollState.floatValue
        set(value) {
            scrollState.floatValue = value
            moved()
        }

    /** The overview shows: [progress] is above 0. Set as [progress] is written, it changes only when it flips. */
    var showing by mutableStateOf(false)
        private set

    /**
     * One thing composition decides: the question the last [decided] call asked, and its answer as that call or a move
     * ([moved]) last found it. Plain: only those two use them.
     */
    private class Decision {
        var decide: () -> Any? = { null }
        var answer: Any? = null

        /** Counts the changes of [answer] that a move made: [decided] subscribes to it, and to nothing that moves on each frame. */
        val changes = mutableIntStateOf(0)
    }

    private val decisions = LinkedHashMap<Any, Decision>()

    /**
     * What [decide] answers now, read in composition: it brings a recomposition when a move changes the answer, not when
     * it moves within it. [decide] reads what it needs when it is called, without a subscription to any of it; a move asks
     * the question of the last call under [key] again. One composable reads each [key]. [T] is taken from where the answer
     * goes: called where nothing is returned, it is Unit, an answer that never changes.
     */
    fun <T> decided(key: Any, decide: () -> T): T {
        val decision = decisions.getOrPut(key, ::Decision)
        // The subscription; what the answer is worked out from is read without one.
        decision.changes.intValue
        val now = Snapshot.withoutReadObservation(decide)
        decision.decide = decide
        decision.answer = now
        return now
    }

    /** The composable that read [key] has gone: a move no longer works it out. */
    fun forget(key: Any) {
        decisions.remove(key)
    }

    /**
     * [keptPlaces] for the stage's [count] places, built around [anchor] on [stage], at the motion's numbers now. Read in
     * composition, it brings a recomposition when a motion changes these places, not when it moves within them. One stage
     * reads it: a motion compares with the places of the last call.
     */
    fun keptAt(count: Int, anchor: Int, stage: Stage): Set<Int> = decided(Kept) { keptPlaces(count, anchor, progress, scroll, stage) }

    private object Kept

    /**
     * The numbers changed, or something else a decision is worked out from that moves on each frame (a finger, the drop):
     * what composition reads follows when it changes. Not a read of anything, wherever it is called.
     */
    fun moved() = Snapshot.withoutReadObservation {
        showing = progress > 0f
        for (decision in decisions.values) {
            val now = decision.decide()
            if (now != decision.answer) {
                decision.answer = now
                decision.changes.intValue++
            }
        }
    }
}
