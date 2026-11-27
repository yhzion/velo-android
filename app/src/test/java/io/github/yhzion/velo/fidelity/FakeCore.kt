package io.github.yhzion.velo.fidelity

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.yhzion.velo.CoreEnv
import io.github.yhzion.velo.GlyphRasterizer
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.HostStore
import io.github.yhzion.velo.Settings
import io.github.yhzion.velo.core.CoreListener
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.VeloCore
import kotlinx.serialization.json.Json
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * The native core, absent from a unit test: no library is loaded, and each call does nothing (the
 * core starts as handle 1, a pane takes no bracketed paste). A test that draws a [HostConnection]
 * names this shadow and instruments the core's package (Robolectric's `instrumentedPackages`), so
 * the real connection runs over it and its state is the one [fakeConnection] hands it.
 */
@Implements(VeloCore::class)
class ShadowVeloCore {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() {}

        @JvmStatic @Implementation fun start(listener: CoreListener, caDirs: Array<String>, fontScaleDp: Float): Long = 1L

        @JvmStatic @Implementation fun paneBracketedPaste(core: Long, block: String): Boolean = false
    }
}

/** The package [ShadowVeloCore] needs instrumented: VeloCore's. */
const val CorePackage = "io.github.yhzion.velo.core"

private val stateJson = Json { encodeDefaults = true }

/**
 * A real [HostConnection] to [url] over [ShadowVeloCore], showing [state] as if the core had
 * published it: the state goes through [HostConnection.onState], as the core's does, and is applied
 * on the main thread's next turn.
 */
fun fakeConnection(context: Context, state: UiState, url: String = "https://${state.server}.example.ts.net"): HostConnection {
    val prefs = context.getSharedPreferences("fake-core", Context.MODE_PRIVATE)
    val env = CoreEnv(emptyArray(), 1f, context.resources.displayMetrics.density, GlyphRasterizer(context.assets), Settings(prefs), HostStore(prefs))
    return HostConnection(url, env, initiallyParked = false, onConnectivityChanged = {}).also {
        it.onState(1, true, stateJson.encodeToString(UiState.serializer(), state))
    }
}

/** A [fakeConnection] for this composition, destroyed when it leaves. */
@Composable
fun rememberFakeConnection(state: UiState): HostConnection {
    val context = LocalContext.current
    val conn = remember(state) { fakeConnection(context, state) }
    DisposableEffect(conn) { onDispose { conn.destroy() } }
    return conn
}
