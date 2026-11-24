package io.github.yhzion.velo.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.platform.findViewTreeCompositionContext
import java.util.IdentityHashMap

/**
 * Debug builds only: which composables recompose while something moves on screen, and what made them, for the
 * recomposition e2e (`dev/emulator/e2e_pane_effects.py`, `overview.recompose` and `workspace.pinch-recompose`, #335).
 * `adb shell am broadcast -a io.github.yhzion.velo.debug.RECOMPOSE -p io.github.yhzion.velo -e id N -e op OP`, where OP is
 * - `start`: forgets what was counted and counts from now on, in every composition of the activity in front (its
 *   dialogs and popups too); it logs `N started`, or `N started no-activity` / `N started no-recomposer`;
 * - `report`: stops counting and logs, under the tag `VeloRecomposeProbe`, each line starting with N:
 *   - `passes P`: how many times a composition was recomposed;
 *   - `scope RAN ENTERED CHECKED NAME`, per composable: how many passes ran its body (it read some state then), how
 *     many entered it (it was invalidated, or a parent that ran called it; it may have skipped), and how many only
 *     checked it: a derived state it reads was invalidated, and its value came out the same, so the body did not run
 *     and its reads were recorded again (`RecomposeScopeImpl.rereadTrackedInstances`). NAME is the function as
 *     `FileKt.Function`; a composable lambda is `{} in` the function that called it, or `{} read in` the function
 *     that read state in it when only the recomposer entered it;
 *   - `cause COUNT NAME :: STATE`, for the composables that ran most: a state whose change invalidated it, and how
 *     often;
 *   - `end`.
 * A scope counts once per pass. The activity in front is the one [LayoutProbe] follows. It changes nothing on screen;
 * until `report` (or the next `start`) it holds every scope it counted, those disposed meanwhile too.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
object RecomposeProbe {
    const val TAG = "VeloRecomposeProbe"
    const val ACTION = "io.github.yhzion.velo.debug.RECOMPOSE"

    private class Count(var ran: Int = 0, var entered: Int = 0, var checked: Int = 0)

    private var registration: CompositionObserverHandle? = null
    private val observed = mutableListOf<CompositionObserverHandle>()
    private val counts = IdentityHashMap<RecomposeScope, Count>()
    private val names = IdentityHashMap<RecomposeScope, String>()
    private val readNames = IdentityHashMap<RecomposeScope, String>()
    /** Per scope, the states whose changes invalidated it. Written on whichever thread applies a change. */
    private val causes = IdentityHashMap<RecomposeScope, MutableMap<String, Int>>()
    /** Per composition being composed now (one inside another for a subcomposition): the scopes entered and run in this pass. */
    private val passes = ArrayDeque<Pair<MutableSet<RecomposeScope>, MutableSet<RecomposeScope>>>()
    private var passCount = 0
    @Volatile private var counting = false

    private val scopes = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {
            passCount++
            passes.addLast(identitySet() to identitySet())
        }

        override fun onEndComposition(composition: ObservableComposition) {
            passes.removeLastOrNull()
        }

        override fun onScopeEnter(scope: RecomposeScope) {
            if (scope !in names) callerName()?.let { names[scope] = it }
            val pass = passes.lastOrNull() ?: return
            if (pass.first.add(scope)) counts.getOrPut(scope, ::Count).entered++
        }

        override fun onReadInScope(scope: RecomposeScope, value: Any) {
            val pass = passes.lastOrNull() ?: return
            if (!pass.second.add(scope)) return
            val trace = Thread.currentThread().stackTrace
            val count = counts.getOrPut(scope, ::Count)
            if (trace.any { it.methodName == "rereadTrackedInstances" }) count.checked++
            else {
                count.ran++
                if (scope !in names && scope !in readNames) readerName(trace)?.let { readNames[scope] = it }
            }
        }

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {
            if (!counting || value == null) return
            val state = "${value.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(value))} " +
                runCatching { value.toString().take(100) }.getOrDefault("?")
            synchronized(causes) { causes.getOrPut(scope) { HashMap() }.merge(state, 1, Int::plus) }
        }

        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}
    }

    private fun identitySet(): MutableSet<RecomposeScope> = java.util.Collections.newSetFromMap(IdentityHashMap())

    fun start(id: String) {
        stop()
        forget()
        val activity = LayoutProbe.front?.get()
        if (activity == null) {
            Log.i(TAG, "$id started no-activity")
            return
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        val recomposer = content?.findViewTreeCompositionContext() as? Recomposer
        if (recomposer == null) {
            Log.i(TAG, "$id started no-recomposer")
            return
        }
        counting = true
        registration = recomposer.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                observed += composition.setObserver(scopes)
            }

            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })
        Log.i(TAG, "$id started")
    }

    fun report(id: String) {
        stop()
        Log.i(TAG, "$id passes $passCount")
        fun nameOf(scope: RecomposeScope) = names[scope] ?: readNames[scope] ?: "?"
        val byName = counts.entries.groupBy({ nameOf(it.key) }, { it.value })
            .map { (name, all) -> name to Count(all.sumOf { it.ran }, all.sumOf { it.entered }, all.sumOf { it.checked }) }
            .sortedWith(compareByDescending<Pair<String, Count>> { it.second.ran }.thenByDescending { it.second.entered })
        byName.forEach { (name, c) -> Log.i(TAG, "$id scope ${c.ran} ${c.entered} ${c.checked} $name") }
        val most = byName.take(8).filter { it.second.ran > 1 }.map { it.first }.toSet()
        synchronized(causes) {
            causes.entries.filter { nameOf(it.key) in most }
                .flatMap { (scope, states) -> states.map { (state, n) -> Triple(nameOf(scope), state, n) } }
                .groupBy({ it.first to it.second }, { it.third }).mapValues { it.value.sum() }
                .entries.sortedByDescending { it.value }.take(24)
                .forEach { (key, n) -> Log.i(TAG, "$id cause $n ${key.first} :: ${key.second}") }
        }
        Log.i(TAG, "$id end")
        forget()
    }

    private fun stop() {
        counting = false
        registration?.dispose()
        registration = null
        observed.forEach { it.dispose() }
        observed.clear()
        passes.clear()
    }

    private fun forget() {
        counts.clear()
        names.clear()
        readNames.clear()
        synchronized(causes) { causes.clear() }
        passCount = 0
    }

    private fun isRuntime(frame: StackTraceElement) = frame.className.startsWith("androidx.compose.runtime.")

    /**
     * The composable whose restart group is starting: the first frame past the runtime's own that called
     * `startRestartGroup`. When the recomposer itself enters an invalidated scope that frame is its frame clock, and
     * the composable is not on the stack yet: null.
     */
    private fun callerName(): String? {
        val trace = Thread.currentThread().stackTrace
        var i = trace.indexOfFirst { it.methodName == "startRestartGroup" }
        if (i < 0) return null
        var lambda = false
        while (i < trace.size && isRuntime(trace[i])) {
            if ("ComposableLambda" in trace[i].className) lambda = true
            i++
        }
        val frame = trace.getOrNull(i) ?: return null
        if (frame.className.startsWith("androidx.compose.ui.platform.AndroidUiFrameClock") || frame.className.startsWith("kotlinx.coroutines.")) return null
        val name = frame.className.substringAfterLast('.') + "." + frame.methodName
        return if (lambda) "{} in $name" else name
    }

    /** The code that read a state in a scope only the recomposer entered: the last frame of the first run past the runtime's. */
    private fun readerName(trace: Array<StackTraceElement>): String? {
        var i = trace.indexOfFirst { isRuntime(it) }
        if (i < 0) return null
        while (i < trace.size && isRuntime(trace[i])) i++
        var last: StackTraceElement? = null
        while (i < trace.size && !isRuntime(trace[i])) last = trace[i++]
        return last?.let { "{} read in " + it.className.substringAfterLast('.') + "." + it.methodName }
    }
}

/** Starts and reports [RecomposeProbe] on the main thread, where the compositions run. */
class RecomposeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: "0"
        when (intent.getStringExtra("op")) {
            "start" -> RecomposeProbe.start(id)
            "report" -> RecomposeProbe.report(id)
            else -> Log.i(RecomposeProbe.TAG, "$id end unknown-op")
        }
    }
}
