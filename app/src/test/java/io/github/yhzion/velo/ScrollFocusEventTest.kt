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
import androidx.compose.ui.AndroidComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yhzion.velo.fidelity.HostActivityRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When a list scrolls and no touch has come since a key, TalkBack keeps its focus where it was (#199). Compose UI
 * 1.13.0 sends an accessibility-focused event for the node with input focus after each scroll then, and TalkBack
 * leaves the item it reads for that node. [VeloApplication] turns that off; these tests run under it.
 *
 * The scene is a scrolling column with a focusable control at its top that holds input focus, as the Notifications
 * settings' volume slider did. A screen reader's scroll ([SemanticsActions.ScrollBy]) moves the column.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VeloApplication::class)
class ScrollFocusEventTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    private val manager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)

    /** The flag as the app left it: a test that changes it puts it back, so the next test sees the app's value. */
    private var appValue = true

    @Before fun keep() {
        appValue = AndroidComposeUiFlags.isScrollAccessibilityFocusEventEnabled
    }

    @After fun restore() {
        AndroidComposeUiFlags.isScrollAccessibilityFocusEventEnabled = appValue
    }

    @Test fun `the app turns off the focus event Compose sends on a scroll`() {
        assertFalse(AndroidComposeUiFlags.isScrollAccessibilityFocusEventEnabled)
    }

    @Test fun `a scroll sends no focus event for the control with input focus`() {
        val events = scrollWithFocusUp()
        assertTrue("the scroll reached the screen reader", events.any { it == AccessibilityEvent.TYPE_VIEW_SCROLLED })
        assertEquals(0, events.count { it == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED })
    }

    /** With Compose's default, the same scroll sends it: the scene shows the jump, so the test above can fail. */
    @Test fun `with Compose's default the scroll sends one`() {
        AndroidComposeUiFlags.isScrollAccessibilityFocusEventEnabled = true
        val events = scrollWithFocusUp()
        assertTrue(events.any { it == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED })
    }

    /** Puts input focus on the control, leaves touch mode, scrolls the column; returns the events' types after it. */
    private fun scrollWithFocusUp(): List<Int> {
        val shadow = shadowOf(manager)
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)
        shadow.setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        val focus = FocusRequester()
        compose.setContent {
            Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()).testTag("list")) {
                Box(Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = "Volume" }
                    .focusRequester(focus).focusable())
                repeat(20) { i ->
                    Box(Modifier.fillMaxWidth().height(60.dp).semantics { contentDescription = "Row $i" }.focusable())
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.runOnIdle { focus.requestFocus() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        val before = shadow.sentAccessibilityEvents.size
        compose.onNodeWithTag("list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 400f) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return shadow.sentAccessibilityEvents.drop(before).map { it.eventType }
    }
}
