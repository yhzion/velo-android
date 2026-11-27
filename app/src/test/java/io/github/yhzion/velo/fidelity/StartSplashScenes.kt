package io.github.yhzion.velo.fidelity

import androidx.compose.runtime.Composable
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.ui.FoundNote
import io.github.yhzion.velo.ui.SplashFrame
import io.github.yhzion.velo.ui.SplashScreen
import io.github.yhzion.velo.ui.StartCardModel
import io.github.yhzion.velo.ui.StartScreenBody
import io.github.yhzion.velo.ui.StartStep
import io.github.yhzion.velo.ui.splashFrame
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme

private const val LoginPage = "https://login.tailscale.com/a/sample"

/** The StartKit board's computers; the address line carries its "macOS · 100.101.4.20". */
private val BoardComputers = listOf(
    DiscoveredServer("studio-mac", "macOS · 100.101.4.20"),
    DiscoveredServer("build-server", "Linux · 100.101.9.3"),
)

/**
 * The model of each start screen (boards Start<Device><Step>), by screen id, with the StartKit board's sample content:
 * the scenes draw them, and the start screen's own tests use them so that both see the same content. Declared before
 * [StartSplashScenes]: a file's top-level values are set in order, and the scenes read these models at once.
 */
internal val StartSceneModels: Map<String, StartCardModel> = linkedMapOf(
    "start-1" to model(StartStep.LogIn(null)),
    "start-1-wait" to model(StartStep.LogIn(LoginPage), waitingFor = LoginPage),
    "start-2" to model(StartStep.Found, found = BoardComputers, searching = true),
    "start-2-empty" to model(StartStep.Found, note = FoundNote.NothingFound),
    "start-3" to model(StartStep.Found, opening = "studio-mac"),
)

/**
 * The start screens (boards Start<Device><Step>, page "2 시작 화면") and the splash's frames (board Main, paused at
 * each time; see tools/fidelity/fidelity.py PREVIEWS), with the boards' sample content. Registered in [FidelityScenes].
 */
val StartSplashScenes: Map<String, @Composable () -> Unit> =
    StartSceneModels.mapValues<String, StartCardModel, @Composable () -> Unit> { (_, model) -> { Start(model) } } +
        linkedMapOf<String, @Composable () -> Unit>(
    "splash-0ms" to { Splash(splashFrame(0)) },
    "splash-240ms" to { Splash(splashFrame(240)) },
    "splash-480ms" to { Splash(splashFrame(480)) },
    "splash-660ms" to { Splash(splashFrame(660)) },
    "splash-1000ms" to { Splash(splashFrame(1000)) },
    "splash" to { Splash(SplashFrame.End) },
    // Animations off: the last frame from the start.
    "splash-reduced" to { Splash(SplashFrame.End) },
)

private fun model(
    step: StartStep, waitingFor: String? = null, found: List<DiscoveredServer> = emptyList(), note: FoundNote = FoundNote.None,
    searching: Boolean = false, opening: String? = null,
) = StartCardModel(
    step = step, waitingFor = waitingFor, unopened = null,
    connectedAs = if (step == StartStep.Found) "Connected as velo-phone" else null,
    found = found, note = note, searching = searching,
    logIn = {}, loginPageAgain = { null }, tryAgain = {}, searchAgain = {}, connect = {}, getRex = { true },
    opening = opening, openingProgress = 0.62f,
)

/** The boards draw the update dot on Settings. */
@Composable
private fun Start(model: StartCardModel) {
    VeloTheme(size = UiSize.Medium) { StartScreenBody(model, onAddByQr = {}, onSettings = {}, settingsBadge = true) }
}

@Composable
private fun Splash(frame: SplashFrame) {
    VeloTheme(size = UiSize.Medium) { SplashScreen(frame) }
}
