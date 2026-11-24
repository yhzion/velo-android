package io.github.yhzion.velo.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yhzion.velo.AppController
import io.github.yhzion.velo.ui.theme.VeloButton
import io.github.yhzion.velo.ui.theme.VeloDialog
import io.github.yhzion.velo.ui.theme.VeloTheme

/** Android's battery request for Velo, or the battery optimization list where the request is missing. */
private fun ManagedActivityResultLauncher<Intent, ActivityResult>.askUnrestricted(app: AppController) {
    try {
        launch(app.background.requestUnrestricted())
    } catch (_: ActivityNotFoundException) {
        launch(app.background.unrestrictedSettings())
    }
}

/** Explanations for the first-connection permission prompt. */
internal const val BackgroundExplanation =
    "Velo stays connected while it is off screen, so you are told when a program finishes, fails or waits for your answer. " +
        "Android shows a quiet \"Velo is running\" notification meanwhile."

internal const val UnrestrictedExplanation =
    "Allow Velo to run without battery restrictions, or Android may cut its connection after a while."

/** Settings → Program activity: the switch and, while it is on, the battery request. */
@Composable
internal fun BackgroundSettings(app: AppController) {
    val settings = app.settings
    var unrestricted by remember { mutableStateOf(app.background.unrestricted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { unrestricted = app.background.unrestricted() }
    LifecycleResumeEffect(Unit) { unrestricted = app.background.unrestricted(); onPauseOrDispose { } }
    Toggle("Stay connected in the background", settings.stayConnected) { settings.saveStayConnected(it) }
    if (settings.stayConnected) {
        if (unrestricted) {
            Text("Battery: unrestricted", style = VeloTheme.type.caption, color = VeloTheme.surface.secondary)
        } else {
            VeloButton("Allow background running", { ask.askUnrestricted(app) })
        }
    }
}

/**
 * Asked once, the first time a remote is connected: whether Velo may stay connected off screen.
 * Allow turns the setting on and opens Android's battery request when it is still needed; Not now
 * turns it off. Settings → Program activity changes the answer later.
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
            if (!app.background.unrestricted()) ask.askUnrestricted(app)
        },
        text = "$BackgroundExplanation $UnrestrictedExplanation You can change this in Settings → Program activity.",
        cancelText = "Not now",
    )
}
