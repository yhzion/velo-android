package io.github.yhzion.velo

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Calls run one at a time on [executor], in the order given: an unregistration given while its registration still
 * waits runs after it, so a caller that is gone before its registration ran leaves nothing registered. A call that
 * throws is handed to [failed] and the next one still runs.
 */
internal class OneByOne(private val executor: Executor, private val failed: (RuntimeException) -> Unit) {
    fun submit(call: () -> Unit) = executor.execute {
        try {
            call()
        } catch (e: RuntimeException) {
            failed(e)
        }
    }
}

/**
 * One thread for the binder calls to system_server that register a callback or a receiver, or unregister it. On the
 * main thread the rail's registrations held the first frame for 45-146 ms (#336). The callbacks still arrive where
 * they were registered to. A call that throws is logged: let out on that thread, it would end the app.
 */
internal val SystemCalls = OneByOne(Executors.newSingleThreadExecutor { Thread(it, "velo-system-calls").apply { isDaemon = true } }) { e ->
    Log.w("velo.system", "system call failed: ${e.javaClass.simpleName}: ${e.message}")
}
