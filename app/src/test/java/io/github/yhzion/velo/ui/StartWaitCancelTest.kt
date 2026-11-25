package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.UiSize
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Waiting for the browser…" has a way out: Cancel stops the wait. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class StartWaitCancelTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun cancelStopsWaitingForTheBrowser() {
        val page = "https://login.tailscale.com/a/sample"
        var cancelled = 0
        val model = StartCardModel(
            step = StartStep.LogIn(page), waitingFor = page, unopened = null, connectedAs = null,
            found = emptyList(), note = FoundNote.None, searching = false,
            logIn = {}, loginPageAgain = { null }, tryAgain = {}, searchAgain = {}, connect = {}, getRex = { true },
            cancelWait = { cancelled++ },
        )
        compose.setContent {
            VeloTheme(size = UiSize.Medium) { StartScreenBody(model, onAddByQr = {}, onSettings = {}) }
        }
        compose.onNodeWithTag("login-wait-cancel").performScrollTo().performClick()
        assertEquals(1, cancelled)
    }
}
