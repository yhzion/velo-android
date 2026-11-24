package io.github.yhzion.velo.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.NotificationAccess
import io.github.yhzion.velo.PaneAddress
import io.github.yhzion.velo.PaneAlertKind
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.ui.theme.OverlayHost
import io.github.yhzion.velo.ui.theme.PopoverSurface
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloTheme

@Composable
internal fun PaneNotificationSettings(app: AppController, remote: Remote, target: PaneAddress, onDismiss: () -> Unit) {
    val conn = app.connectionOf(remote)
    val state = conn?.state
    if (state == null || remote !in app.saved || !target.isPresent(state)) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val window = state.windows.first { it.id == target.window }
    val pane = "Pane ${tabBlocks(window).indexOf(target.block) + 1}"
    val sessionLabel = state.sessions.firstOrNull { it.id == target.session }?.label.orEmpty()
    val label = listOf(remote.label, sessionLabel, tabTitle(state, window), pane).filter { it.isNotBlank() }.joinToString(" · ")
    val sub = app.paneAlerts.find(remote.id, target)
    val system = app.notifications
    val context = LocalContext.current
    var unavailable by remember { mutableStateOf(emptyList<PaneAlertKind>()) }
    fun refresh() {
        unavailable = PaneAlertKind.entries.filter { system.access(it) != NotificationAccess.Allowed }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    LifecycleResumeEffect(Unit) {
        system.createChannels()
        refresh()
        onPauseOrDispose { }
    }

    OverlayHost(onDismiss) {
        PopoverSurface(Modifier.align(Alignment.Center).padding(VeloTheme.sizes.overlayMargin)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(VeloTheme.sizes.menuPadding), verticalArrangement = Arrangement.spacedBy(VeloTheme.sizes.menuPadding)) {
                Text("Pane notifications", style = VeloTheme.type.overlayTitle, color = VeloTheme.surface.text)
                Text(label, style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
                Toggle("Use app settings", sub == null) { inherit ->
                    app.inheritPaneAlerts(remote, target, label, inherit)
                }
                for (kind in PaneAlertKind.entries) {
                    Toggle(kind.label, sub?.enabled(kind) ?: app.settings.posts(kind), enabled = sub != null) { on ->
                        app.setPaneAlert(remote, target, label, kind, on)
                    }
                }
                val blocked = unavailable.firstOrNull { sub?.enabled(it) ?: app.settings.posts(it) }
                if (blocked != null) {
                    if (Build.VERSION.SDK_INT >= 33 && system.access(blocked) == NotificationAccess.RequestPermission) {
                        VeloButton("Allow notifications", { permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
                    }
                    VeloButton("Allow in Android settings", { context.startActivity(system.settingsIntent(blocked)) })
                }
                VeloButton("Done", onDismiss)
            }
        }
    }
}
