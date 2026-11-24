package io.github.yhzion.velo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yhzion.velo.DiscoveredServer
import io.github.yhzion.velo.ui.theme.VeloTheme
import io.github.yhzion.velo.ui.theme.UiSize

/** The states of the start card a preview can show, by the name given with `-e state`. */
enum class StartPreviewState { Login, Waiting, Starting, Failed, Looking, Found, NothingFound }

/**
 * Debug builds only. The start screen ([StartScreenBody]) in a state made up from the intent, with no node and no
 * connection, so that every state can be seen in every window size:
 * `-e state login|waiting|starting|failed|looking|found|nothingfound`,
 * `-e size small|medium|large`. Its buttons do nothing.
 */
class StartPreviewActivity : ComponentActivity() {
    private var state by mutableStateOf(StartPreviewState.Login)
    private var size by mutableStateOf(UiSize.Large)

    override fun onCreate(savedInstanceState: Bundle?) {
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        read(intent)
        setContent {
            VeloTheme(size = size) {
                StartScreenBody(previewModel(state), onAddByQr = {}, onSettings = {})
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        state = StartPreviewState.entries.firstOrNull { it.name.equals(intent.getStringExtra("state"), ignoreCase = true) } ?: StartPreviewState.Login
        size = UiSize.parse(intent.getStringExtra("size"))
    }
}

/** Two computers with long names and addresses, so that a narrow window shows what is cut. */
private val PreviewServers = listOf(
    DiscoveredServer("lab-laptop", "https://lab-laptop.tailnet.example"),
    DiscoveredServer("velo-build-workstation-with-a-long-name", "https://velo-build-workstation-with-a-long-name.tailnet.example"),
)

private fun previewModel(state: StartPreviewState): StartCardModel {
    val url = "https://login.example/preview"
    val step = when (state) {
        StartPreviewState.Login -> StartStep.LogIn(null)
        StartPreviewState.Waiting -> StartStep.LogIn(url)
        StartPreviewState.Starting -> StartStep.Starting
        StartPreviewState.Failed -> StartStep.Failed("AlreadyStarted")
        StartPreviewState.Looking, StartPreviewState.Found, StartPreviewState.NothingFound -> StartStep.Found
    }
    val found = if (state == StartPreviewState.Found) PreviewServers else emptyList()
    val note = when (state) {
        StartPreviewState.Looking -> FoundNote.Looking
        StartPreviewState.NothingFound -> FoundNote.NothingFound
        else -> FoundNote.None
    }
    return StartCardModel(
        step = step, waitingFor = if (state == StartPreviewState.Waiting) url else null, unopened = null,
        connectedAs = if (step == StartStep.Found) "Connected as velo.tailnet.example" else null,
        found = found, note = note, searching = false,
        logIn = {}, loginPageAgain = { url }, cancelWait = {}, tryAgain = {}, searchAgain = {}, connect = {}, getRex = { true },
    )
}
