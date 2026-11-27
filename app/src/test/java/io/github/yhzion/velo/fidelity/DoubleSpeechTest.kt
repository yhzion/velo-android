package io.github.yhzion.velo.fidelity

import android.app.Application
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No control in a registered scene ([FidelityScenes]) says the same words twice to a screen reader
 * (#147). A control whose own description sits over visible texts is read twice: Compose gives the
 * description as a child next to the texts (#120, #124, #127, #134). The test reads what an
 * accessibility service gets, the AccessibilityNodeInfo tree of the Compose view's node provider,
 * as DeviceHeaderSpeechTest does.
 *
 * Each focusable node says its own words and states and those of the descendants that are not
 * focusable themselves. The node says a thing twice by [Speech.saysTwice]: a part comes again, as it
 * is or by a short or full name of one thing (#167), or one of its words is said inside another.
 * MenuSpeechTest opens the scenes' menus and scans their options the same way.
 *
 * The scan runs at each [ScanWidth], the phone's and the unfolded Fold8's (#175), and its name says which.
 * Unfolded, a scene draws its wide layout, and settings-remotes draws studio-mac's menu open. The
 * checks that need no width are in [SpeechCheckTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class DoubleSpeechTest(private val width: ScanWidth) {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Before fun drawAtTheWidth() = width.apply()

    /** What one focusable node says: its own words first, then its descendants'. */
    private class Said(val node: String, val parts: List<String>) {
        override fun toString() = "$node: \"${parts.joinToString(" | ")}\""
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun noControlInAnySceneSaysAThingTwice() {
        val names = FidelityScenes.keys.toList()
        var current by mutableStateOf(names.first())
        lateinit var view: View
        var drawn = 0 to 0
        var window = 0 to 0
        var density = 1f
        compose.setContent {
            view = LocalView.current
            drawn = LocalConfiguration.current.let { it.screenWidthDp to it.screenHeightDp }
            // Some layouts measure the window, not the configuration, so the window (whole px, rounded down) must match too.
            window = LocalWindowInfo.current.containerSize.let { it.width to it.height }
            density = LocalDensity.current.density
            // Tags as resource ids name the node a failure points at; nothing else in the tree changes.
            Box(Modifier.semantics { testTagsAsResourceId = true }) { key(current) { FidelityScenes.getValue(current)() } }
        }
        compose.waitForIdle()
        assertEquals("The scenes are drawn at $width", width.w to width.h, drawn)
        assertEquals("The window is $width", (width.w * density).toInt() to (width.h * density).toInt(), window)
        val twiceSaid = mutableListOf<String>()
        for (name in names) {
            current = name
            compose.waitForIdle()
            for (said in compose.runOnIdle { utterances(view) }.filter { Speech.saysTwice(it.parts) }) twiceSaid += "$name, $said"
        }
        assertTrue("Said twice to a screen reader at $width (scene, node, words):\n" + twiceSaid.joinToString("\n"), twiceSaid.isEmpty())
    }

    /** Each focusable node that says something, with the words it says. */
    private fun utterances(view: View): List<Said> =
        Speech.focusableNodes(view).filter { it.second.isNotEmpty() }.map { (info, said) -> Said(nodeName(info), said) }

    private fun nodeName(info: AccessibilityNodeInfo): String =
        info.viewIdResourceName ?: Rect().also(info::getBoundsInScreen).let { "node at ${it.toShortString()}" }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun widths() = ScanWidth.parameters()
    }
}
