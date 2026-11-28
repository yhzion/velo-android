package io.github.yhzion.velo.fidelity

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No node in a registered scene ([FidelityScenes]) says something without a place in a screen reader's order (#235).
 * Compose gives a screen reader the order of the nodes it stops on; a node with a description whose parts carry test
 * tags is neither a leaf nor merging, so it is not one of them, and TalkBack reaches it last, out of its place (#205's
 * caption tile, CaptionOrderTest). So a node that says something must be a stop ([Speech.focusable]) or be said by one
 * above it. The scan runs with a screen reader on, as Compose works out the order only then, at each [ScanWidth].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class LeftOutOfOrderTest(private val width: ScanWidth) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    /** A screen reader is on before anything is composed: only then does Compose work out the order. */
    @Before fun screenReaderOn() {
        width.apply()
        val manager = ApplicationProvider.getApplicationContext<Application>().getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun everyNodeThatSaysSomethingHasItsPlace() {
        val names = FidelityScenes.keys.toList()
        var current by mutableStateOf(names.first())
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            // Tags as resource ids name the node a failure points at.
            Box(Modifier.semantics { testTagsAsResourceId = true }) { key(current) { FidelityScenes.getValue(current)() } }
        }
        val leftOut = mutableListOf<String>()
        for (name in names) {
            current = name
            compose.waitForIdle()
            leftOut += compose.runOnIdle { leftOut(view) }.map { "$name, $it" }
        }
        assertTrue("Said, but out of a screen reader's order at $width (scene, node, words):\n" + leftOut.joinToString("\n"), leftOut.isEmpty())
    }

    /** Each node in [view] that says something, is no stop and is under none, with what it says. */
    private fun leftOut(view: View): List<String> {
        val provider = view.accessibilityNodeProvider ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(id: Int, underStop: Boolean) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            val stop = Speech.focusable(info)
            val words = Speech.words(info)
            if (!stop && !underStop && words.isNotEmpty()) found += "${info.viewIdResourceName ?: "#$id"}: \"${words.joinToString(" | ")}\""
            for (c in 0 until info.childCount) childId(info, c)?.let { walk(it, underStop || stop) }
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID, false)
        return found
    }

    /** The virtual view id of [info]'s child at [index]; null for a view of its own (Speech's scans skip them too). */
    private fun childId(info: AccessibilityNodeInfo, index: Int): Int? {
        val id = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, index) as Long
        return (id ushr 32).toInt().takeIf { it != AccessibilityNodeProvider.HOST_VIEW_ID }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun widths() = ScanWidth.parameters()
    }
}
