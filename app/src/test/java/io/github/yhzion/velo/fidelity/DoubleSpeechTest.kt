package io.github.yhzion.velo.fidelity

import android.app.Application
import android.graphics.Rect
import android.view.View
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
import org.junit.Assert.assertEquals
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

    /** A place known to say a thing twice: the scene, the start of what its node says, and the issue that fixes it. */
    private data class Known(val scene: String, val saysFirst: String, val issue: Int)

    /** Remove an entry when its issue is fixed; the test fails while an entry no longer says a thing twice. */
    private val known = listOf(
        Known("workspace-sidebar", "Claude Code", 150),
        Known("workspace-sidebar", "Codex, 2 panes", 150),
        Known("workspace-sidebar", "zsh, 4 panes", 150),
        Known("workspace-docked", "Claude Code", 150),
        Known("workspace-docked", "Codex, 2 panes", 150),
        Known("workspace-docked", "zsh, 4 panes", 150),
        Known("settings-appearance", "Minimized windows: Bottom Left", 148),
        Known("settings-keyboard", "Double tap: Tab", 148),
        Known("settings-key-order", "Tab", 149),
        Known("settings-notif", "Background notification, Needs your response", 149),
        Known("settings-notif", "Activity sound, Needs your response", 149),
        Known("settings-notif", "Sound, Attention", 148),
        Known("settings-notif", "Background notification, Work completed", 149),
        Known("settings-notif", "Activity sound, Work completed", 149),
        Known("settings-notif", "Sound, Success", 148),
        Known("settings-ssh", "Copy install command for this phone's key", 149),
        Known("settings-ssh", "Copy this phone's key", 149),
        Known("settings-ssh", "Share install command for this phone's key", 149),
        Known("settings-ssh", "Remove imported key", 149),
    )

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
        val unknown = mutableListOf<String>()
        val stale = known.toMutableList()
        for (name in names) {
            current = name
            compose.waitForIdle()
            for (said in compose.runOnIdle { utterances(view) }.filter { twice(it.parts) }) {
                val entry = known.firstOrNull { it.scene == name && said.parts.first().startsWith(it.saysFirst) }
                if (entry == null) unknown += "$name, $said" else stale -= entry
            }
        }
        assertTrue("Said twice to a screen reader (scene, node, words):\n" + unknown.joinToString("\n"), unknown.isEmpty())
        assertEquals("Known places that no longer say a thing twice: remove them from the list", emptyList<Known>(), stale)
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

    private fun focusable(i: AccessibilityNodeInfo) =
        i.isImportantForAccessibility && (i.isScreenReaderFocusable || i.isFocusable || i.isClickable || i.isCheckable || i.isLongClickable)

    private fun words(i: AccessibilityNodeInfo): String? =
        if (!i.isImportantForAccessibility) null else (i.contentDescription ?: i.text)?.toString()?.takeIf { it.isNotBlank() }

    /** Each focusable node that says two things or more, with the words it says. */
    private fun utterances(view: View): List<Said> {
        val provider = view.accessibilityNodeProvider ?: return emptyList()
        val result = mutableListOf<Said>()
        fun gather(info: AccessibilityNodeInfo, into: MutableList<String>) {
            for (c in 0 until info.childCount) {
                val child = provider.createAccessibilityNodeInfo(childVirtualId(info, c)) ?: continue
                if (focusable(child)) continue
                words(child)?.let(into::add)
                gather(child, into)
            }
        }
        fun walk(id: Int) {
            val info = provider.createAccessibilityNodeInfo(id) ?: return
            if (focusable(info)) {
                val said = mutableListOf<String>()
                words(info)?.let(said::add)
                gather(info, said)
                if (said.size >= 2) result += Said(nodeName(info), said)
            }
            for (c in 0 until info.childCount) walk(childVirtualId(info, c))
        }
        walk(AccessibilityNodeProvider.HOST_VIEW_ID)
        return result
    }

    private fun nodeName(info: AccessibilityNodeInfo): String =
        info.viewIdResourceName ?: Rect().also(info::getBoundsInScreen).let { "node at ${it.toShortString()}" }

    private fun twice(said: List<String>): Boolean {
        val parts = said.flatMap { it.split(", ", " · ", ": ") }.map { it.trim().lowercase() }.filter { it.length >= 2 }
        if (parts.size != parts.toSet().size) return true
        for ((a, x) in said.withIndex()) for ((b, y) in said.withIndex())
            if (a != b && y.length >= 2 && Regex("(^|\\W)" + Regex.escape(y) + "(\\W|$)").containsMatchIn(x)) return true
        return false
    }

    // AccessibilityNodeInfo keeps each child as a long whose high half is the virtual view id;
    // getChildId is hidden, so it is read by reflection, as Robolectric allows.
    private fun childVirtualId(info: AccessibilityNodeInfo, index: Int): Int {
        val id = AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, index) as Long
        return (id ushr 32).toInt()
    }
}
