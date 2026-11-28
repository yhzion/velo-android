package io.github.yhzion.velo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yhzion.velo.fidelity.HostActivityRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Compose UI 1.13.0 sends no TYPE_VIEW_FOCUSED when a key moves input focus onto a node wholly off the screen, which a
 * list then scrolls to show (#220), so TalkBack says nothing for that move. A node partly on the screen gets it. Compose keeps, for its change events, only the nodes on the screen,
 * and compares each with the one it kept at the last check: a node that was off the screen has none, so its focus is
 * never compared (AndroidComposeViewAccessibilityDelegateCompat.sendSemanticsPropertyChangeEvents, "previousSemanticsNodes[id]
 * ?: return@forEachKey"; still so in 1.14.0-alpha01). The app has no public way to send the event itself: it would need
 * the node's virtual view id.
 *
 * This test pins that: when a Compose update makes it fail, Compose sends the event, and #220 is to be checked again on
 * a device. The scene is a column of ten focusable rows, five of them on the screen; Next moves through all and wraps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VeloApplication::class)
class OffscreenFocusEventTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val manager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)

    @Test fun `a move onto a node off the screen sends no focus event, as Compose 1_13 does`() {
        val shadow = shadowOf(manager)
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)
        shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        val first = FocusRequester()
        lateinit var focus: FocusManager
        compose.setContent {
            focus = LocalFocusManager.current
            Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState())) {
                repeat(10) { i ->
                    Box(Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = "Row $i" }
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier).focusable())
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.runOnIdle { first.requestFocus() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        // Each move: whether the list scrolled, and how many focus events came.
        val moves = (1..12).map {
            val before = shadow.sentAccessibilityEvents.size
            compose.runOnIdle { focus.moveFocus(FocusDirection.Next) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            val events = shadow.sentAccessibilityEvents.drop(before).map { it.eventType }
            (AccessibilityEvent.TYPE_VIEW_SCROLLED in events) to events.count { it == AccessibilityEvent.TYPE_VIEW_FOCUSED }
        }
        // Rows 1 to 4 are on the screen; rows 5 to 9, then row 0 (the wrap), need a scroll; then rows 1 and 2 again.
        val expected = List(4) { false to 1 } + List(6) { true to 0 } + List(2) { false to 1 }
        assertEquals("(scrolled, focus events) for each of 12 moves", expected, moves)
    }
}
