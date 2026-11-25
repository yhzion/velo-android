package io.github.yhzion.velo.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import io.github.yhzion.velo.ui.theme.groupInset
import io.github.yhzion.velo.ui.theme.groupBleed
import io.github.yhzion.velo.ui.theme.SwitchRow
import io.github.yhzion.velo.ui.theme.GroupDivider
import io.github.yhzion.velo.ui.theme.ActionRow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import android.content.ActivityNotFoundException
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.BackgroundConnection
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTheme

/** Android's battery request for Velo, or the battery optimization list where the request is missing. */
private fun ManagedActivityResultLauncher<Intent, ActivityResult>.askUnrestricted(background: BackgroundConnection) {
    try {
        launch(background.requestUnrestricted())
    } catch (_: ActivityNotFoundException) {
        launch(background.unrestrictedSettings())
    }
}

/** Explanations for the first-connection permission prompt. */
internal const val BackgroundExplanation =
    "Velo stays connected while it is off screen, so you are told when a program finishes, fails or waits for your answer. " +
        "Android shows a quiet \"Velo is running\" notification meanwhile."

internal const val UnrestrictedExplanation =
    "Allow Velo to run without battery restrictions, or Android may cut its connection after a while."

/**
 * Notifications → Background (canvas SetNotifications block): staying connected, which Android
 * marks with a quiet notification, and while it is on the battery: Unrestricted with a check, or a
 * row that asks Android to let Velo run without its restrictions.
 */
@Composable
internal fun BackgroundSettings(host: SettingsHost) {
    val settings = host.settings
    val background = host.background
    val space = VeloTheme.space
    var unrestricted by remember { mutableStateOf(background.unrestricted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { unrestricted = background.unrestricted() }
    LifecycleResumeEffect(Unit) { unrestricted = background.unrestricted(); onPauseOrDispose { } }
    SwitchRow("Stay connected in the background", settings.stayConnected, { settings.saveStayConnected(it) },
        subtitle = "Android shows a quiet notification while Velo stays connected.", tag = "notifications-stay-connected", paragraph = true)
    if (settings.stayConnected) {
        GroupDivider()
        if (unrestricted) Row(
            Modifier.groupBleed().testTag("notifications-battery-row").fillMaxWidth().heightIn(min = VeloTheme.sizes.row).groupInset().padding(end = space.s4),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s12),
        ) {
            Text("Battery", Modifier.weight(1f).testTag("notifications-battery-label"), style = VeloTheme.type.body, color = VeloTheme.surface.text)
            Icon(Icons.Default.Check, null, Modifier.size(VeloTheme.medium(18.dp)), tint = VeloTheme.surface.ok)
            Text("Unrestricted", Modifier.testTag("notifications-battery-status"), style = VeloTheme.type.value, color = VeloTheme.colors.textOnRaised)
        } else ActionRow("Allow background running", Icons.Default.BatteryAlert, { ask.askUnrestricted(background) }, tag = "notifications-battery-row")
    }
}

/**
 * Asked once, the first time a remote is connected: whether Velo may stay connected off screen.
 * Allow turns the setting on and opens Android's battery request when it is still needed; Not now
 * turns it off. Settings → Notifications → Background changes the answer later.
 */
@Composable
internal fun BackgroundPrompt(app: AppController) {
    val settings = app.settings
    if (settings.backgroundAsked) return
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    VeloDialog(
        "Get notified while Velo is closed?",
        onDismissRequest = { settings.saveStayConnected(false); settings.saveBackgroundAsked() },
        confirmText = "Allow",
        onConfirm = {
            settings.saveStayConnected(true)
            settings.saveBackgroundAsked()
            if (!app.background.unrestricted()) ask.askUnrestricted(app.background)
        },
        text = "$BackgroundExplanation $UnrestrictedExplanation You can change this in Settings → Notifications → Background.",
        cancelText = "Not now",
    )
}
