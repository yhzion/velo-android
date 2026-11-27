package io.github.yhzion.velo.fidelity

import android.app.Application
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The checks DoubleSpeechTest and MenuSpeechTest scan by, on words given here, so they need no width
 * and run once (#175): [Speech.saysTwice] (#147, #167), that the scan hears a state, and the kind
 * check of an [Opened] menu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SpeechCheckTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theCheckFindsADescriptionOverItsText() {
        assertTrue(twice(listOf("Sound: Attention", "Attention")))
        assertTrue(twice(listOf("Tab", "TAB")))
        assertTrue(twice(listOf("Attachment report.pdf", "report.pdf")))
        assertTrue(twice(listOf("Copy this phone's key", "Copy")))
        assertTrue(!twice(listOf("This Device, velo-phone, 100.101.12.7 · Connected")))
        assertTrue(!twice(listOf("Sound: Attention")))
        // A name said again in another case inside a longer word is not the same words: "NOTICE", "Full notice".
        assertTrue(!twice(listOf("NOTICE", "Full notice for this build", "Text")))
        assertTrue(!twice(listOf("Tailscale", "tailscale.com 1.104.1", "BSD-3-Clause")))
    }

    // #167: a short and a full name of one thing, and one description that repeats a part of itself.

    @Test fun theCheckFindsAShortAndAFullNameOfOneThing() {
        assertTrue(twice(listOf("Escape", "Esc")))
        assertTrue(twice(listOf("Double tap: Escape", "Esc")))
        assertTrue(twice(listOf("Control", "Ctrl")))
        assertTrue(twice(listOf("Page Up", "PgUp")))
        assertTrue(twice(listOf("Neovim", "nvim")))
        assertTrue(twice(listOf("Neovim, 2 panes", "nvim")))
        assertTrue(twice(listOf("Claude Code", "claude")))
        assertTrue(twice(listOf("Docker", "docker-compose")))
        // The key bar draws a named key in capitals: ESC is Escape, in any case.
        assertTrue(twice(listOf("Escape", "ESC")))
        assertTrue(twice(listOf("Control", "CTRL")))
        assertTrue(twice(listOf("Page Up", "PGUP")))
        assertTrue(twice(listOf("Page Down", "PGDN")))
        assertTrue(twice(listOf("Slash", "/")))
        assertTrue(twice(listOf("Minus", "-")))
        // A short or full name said whole inside other words.
        assertTrue(twice(listOf("Send Escape", "Esc")))
        assertTrue(twice(listOf("Press ESC", "Escape")))
        assertTrue(twice(listOf("Scroll Page Up", "PgUp")))
        assertTrue(twice(listOf("Open Neovim", "nvim")))
        // ... and against a part of the other: a tile of two panes or more names them after the brand.
        assertTrue(twice(listOf("Neovim, 2 panes", "Open nvim")))
        assertTrue(!twice(listOf("Escape")))
        assertTrue(!twice(listOf("Send Escape", "Tab")))
        assertTrue(!twice(listOf("Neovim, 2 panes", "Edit the parser", "~/src/api")))
        assertTrue(!twice(listOf("zsh, 4 panes", "~/src/web")))
        // A folder is a fact of its own, not another name of the program it is named after.
        assertTrue(!twice(listOf("Neovim", "Working · 2s since report · ~/.config/nvim")))
    }

    @Test fun theCheckFindsOneDescriptionThatRepeatsAPart() {
        assertTrue(twice(listOf("zsh, zsh")))
        assertTrue(twice(listOf("Unread · Unread")))
        assertTrue(twice(listOf("Neovim, nvim")))
        assertTrue(!twice(listOf("zsh, 4 panes")))
    }

    /** A node whose state says its own words again is said twice: the scan reads states (#167). */
    @Test fun theScanHearsAState() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(40.dp).semantics { contentDescription = "Unread"; stateDescription = "Unread" }.clickable {})
        }
        compose.waitForIdle()
        val said = compose.runOnIdle { Speech.focusableNodes(view).map { it.second }.filter { it.isNotEmpty() } }
        assertTrue("$said", said.single() == listOf("Unread", "Unread") && twice(said.single()))
    }

    @Test fun theKindCheckMatchesATagFromItsStart() {
        val saved = Opened("settings-remotes", "saved-remote-menu-studio-mac", "saved-remote-menu-studio-mac (More or Actions button)", listOf(listOf("Edit")))
        val key = Opened("settings-keys", "imported key row", "imported key row", listOf(listOf("Copy")))
        assertTrue(!saved.isBy("remote-menu-"))
        assertTrue(saved.isBy("saved-remote-menu-"))
        assertTrue(key.isBy("imported key row"))
        assertTrue(!key.isBy("key row"))
    }

    private fun twice(said: List<String>) = Speech.saysTwice(said)
}
