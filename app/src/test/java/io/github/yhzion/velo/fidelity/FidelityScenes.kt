package io.github.yhzion.velo.fidelity

import androidx.compose.runtime.Composable

/**
 * The app side of each redesign screen, keyed by the screen id the canvas boards map to
 * (SetBarUpdates, SetClosedUpdates and SetOpenUpdates are "settings-updates"; see
 * tools/fidelity/metrics.py screen_of). A scene draws the screen as the app does, filling the
 * window; FidelityCaptureTest draws it at each board's size.
 *
 * A screen is measured once it has a scene here. Elements are matched by testTag: give each
 * composable the data-el name its canvas element has.
 */
val FidelityScenes: Map<String, @Composable () -> Unit> = linkedMapOf(
    *StartSplashScenes.toList().toTypedArray(),
    *WorkspaceScenes.toList().toTypedArray(),
    *SettingsScenes.toList().toTypedArray(),
)
