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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No control in a registered scene ([FidelityScenes]) says the same words twice to a screen reader
 * (#147). A control whose own description sits over visible texts is read twice: Compose gives the
 * description as a child next to the texts (#120, #124, #127, #134). The test reads what an
 * accessibility service gets, the AccessibilityNodeInfo tree of the Compose view's node provider,
 * as DeviceHeaderSpeechTest does.
 *
 * Each focusable node says its own words and those of the descendants that are not focusable
 * themselves. The node says a thing twice when a part of it (split at ", ", " · " and ": ") comes
 * again, ignoring case, or when one of its words is said whole, in the same case, inside another.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi")
class DoubleSpeechTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    /** What one focusable node says: its own words first, then its descendants'. */
    private class Said(val node: String, val parts: List<String>) {
        override fun toString() = "$node: \"${parts.joinToString(" | ")}\""
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun noControlInAnySceneSaysAThingTwice() {
        val names = FidelityScenes.keys.toList()
        var current by mutableStateOf(names.first())
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            // Tags as resource ids name the node a failure points at; nothing else in the tree changes.
            Box(Modifier.semantics { testTagsAsResourceId = true }) { key(current) { FidelityScenes.getValue(current)() } }
        }
        val twiceSaid = mutableListOf<String>()
        for (name in names) {
            current = name
            compose.waitForIdle()
            for (said in compose.runOnIdle { utterances(view) }.filter { twice(it.parts) }) twiceSaid += "$name, $said"
        }
        assertTrue("Said twice to a screen reader (scene, node, words):\n" + twiceSaid.joinToString("\n"), twiceSaid.isEmpty())
    }

    @Test fun theCheckFindsADescriptionOverItsText() {
        assertTrue(twice(listOf("Sound, Attention", "Attention")))
        assertTrue(twice(listOf("Tab", "TAB")))
        assertTrue(twice(listOf("Attachment report.pdf", "report.pdf")))
        assertTrue(twice(listOf("Copy this phone's key", "Copy")))
        assertTrue(!twice(listOf("This Device, velo-phone, 100.101.12.7 · Connected")))
        assertTrue(!twice(listOf("Sound, Attention")))
        // A name said again in another case inside a longer word is not the same words: "NOTICE", "Full notice".
        assertTrue(!twice(listOf("NOTICE", "Full notice for this build", "Text")))
        assertTrue(!twice(listOf("Tailscale", "tailscale.com 1.104.1", "BSD-3-Clause")))
    }

    /** Each focusable node that says two things or more, with the words it says. */
    private fun utterances(view: View): List<Said> =
        Speech.focusableNodes(view).filter { it.second.size >= 2 }.map { (info, said) -> Said(nodeName(info), said) }

    private fun nodeName(info: AccessibilityNodeInfo): String =
        info.viewIdResourceName ?: Rect().also(info::getBoundsInScreen).let { "node at ${it.toShortString()}" }

    private fun twice(said: List<String>): Boolean {
        val parts = said.flatMap { it.split(", ", " · ", ": ") }.map { it.trim().lowercase() }.filter { it.length >= 2 }
        if (parts.size != parts.toSet().size) return true
        for ((a, x) in said.withIndex()) for ((b, y) in said.withIndex())
            if (a != b && y.length >= 2 && Regex("(^|\\W)" + Regex.escape(y) + "(\\W|$)").containsMatchIn(x)) return true
        return false
    }
}
