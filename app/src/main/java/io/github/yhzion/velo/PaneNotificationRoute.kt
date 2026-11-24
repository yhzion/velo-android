package io.github.yhzion.velo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.ui.screenSafeDrawing
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class OpenPaneStep { Wait, SelectSession, Restore, SelectWindow, Unzoom, Focus, Ready, Gone }

internal fun PaneAddress.openStep(state: UiState): OpenPaneStep {
    if (state.status != "connected") return OpenPaneStep.Wait
    if (state.sessions.none { it.id == session }) return OpenPaneStep.Gone
    if (state.session != session) return OpenPaneStep.SelectSession
    // The selected session is published before its attach response supplies the view.
    if (state.windows.isEmpty()) return OpenPaneStep.Wait
    val tab = state.windows.firstOrNull { it.id == window } ?: return OpenPaneStep.Gone
    if (block in tab.minimizedBlocks) return OpenPaneStep.Restore
    if (!isPresent(state)) return OpenPaneStep.Gone
    if (state.window != window) return OpenPaneStep.SelectWindow
    val zoom = tab.zoomed
    if (zoom != null && zoom != block) return OpenPaneStep.Unzoom
    return if (state.focused == block) OpenPaneStep.Ready else OpenPaneStep.Focus
}

/** Keep the current workspace (including its document picker) until the composer closes. */
internal class PaneNotificationLaunches {
    var token by mutableStateOf<String?>(null)
    var generation by mutableIntStateOf(0)
        private set
    var pending by mutableStateOf<String?>(null)
        private set
    private var composing = false

    fun open(token: String) {
        if (composing) {
            pending = token
        } else {
            this.token = token
            generation++
        }
    }

    fun draftOpened() { composing = true }

    fun draftClosed() {
        composing = false
        pending?.let(::open)
        pending = null
    }
}

internal class PaneNotificationGate(
    val opened: Boolean,
    val opening: Boolean,
    private val targetIsOpen: () -> Boolean,
    val leave: () -> Unit,
) {
    fun canUsePane() = opened && targetIsOpen()
}

@Composable
internal fun paneNotificationGate(app: AppController, token: String, leave: () -> Unit): PaneNotificationGate {
    val sub = remember(token) { app.paneAlerts.byToken(token) }
    val remote = app.saved.firstOrNull { it.id == sub?.remote }
    val conn = remote?.let(app::connectionOf)
    var opened by remember(token) { mutableStateOf(false) }
    var failed by remember(token) { mutableStateOf(false) }
    LaunchedEffect(token, conn) {
        if (conn == null || sub == null) return@LaunchedEffect
        app.activate(remote.url)
        val target = sub.target
        val result = withTimeoutOrNull(20_000) {
            snapshotFlow { target.openStep(conn.state) }.first { step ->
                when (step) {
                    OpenPaneStep.Ready, OpenPaneStep.Gone -> return@first true
                    OpenPaneStep.SelectSession -> conn.selectSession(target.session)
                    OpenPaneStep.Restore -> conn.op("restorePane", "session" to target.session,
                        "window" to target.window, "block" to target.block)
                    OpenPaneStep.SelectWindow -> conn.op("selectWindow", "session" to target.session, "window" to target.window)
                    OpenPaneStep.Unzoom -> conn.state.windows.firstOrNull { it.id == target.window }?.zoomed?.let {
                        conn.op("unzoom", "block" to it)
                    }
                    OpenPaneStep.Focus -> conn.op("focus", "block" to target.block)
                    OpenPaneStep.Wait -> Unit
                }
                false
            }
        }
        opened = result == OpenPaneStep.Ready && target.isOpen(conn.state)
        failed = !opened
    }
    return PaneNotificationGate(opened, !failed && !opened && conn != null && sub != null,
        targetIsOpen = { conn != null && sub != null && app.active === conn && sub.target.isOpen(conn.state) },
        leave = leave)
}

@Composable
internal fun PaneNotificationNotice(gate: PaneNotificationGate) {
    BackHandler(onBack = gate.leave)
    // Spaced as a dialog's words and buttons are.
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.screenSafeDrawing).padding(VeloTheme.sizes.overlayPadding),
        verticalArrangement = Arrangement.spacedBy(VeloTheme.sizes.overlayPadding, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (gate.opening) "Opening the original pane…" else "The original pane is unavailable.", color = VeloTheme.surface.text)
        VeloButton(if (gate.opening) "Cancel" else "Return to workspace", gate.leave)
    }
}
