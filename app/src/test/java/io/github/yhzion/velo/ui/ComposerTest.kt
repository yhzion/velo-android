package io.github.yhzion.velo.ui

import io.github.yhzion.velo.ComposerFailure
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ComposerTest {
    @Test fun controlsCannotReachTheTerminal() {
        assertNull(draftProblem("résumé\n\t한글"))
        for (control in (0..31) + 127) {
            if (control != 9 && control != 10) assertNotNull("control $control", draftProblem("a${control.toChar()}z"))
        }
    }

    @Test fun attachmentsMustHaveUsableBasenames() {
        assertTrue(usableAttachmentName("사진.png"))
        for (name in listOf(null, "", ".", "..", "../log", "folder\\file", "image\r.jpg", "한".repeat(86))) {
            assertFalse(name, usableAttachmentName(name))
        }
    }

    @Test fun unknownSizeIsBoundedByBytesReadNotMetadata() {
        val limit = 8 * 1024 * 1024
        val bytes = ByteArray(limit) { it.toByte() }
        assertArrayEquals(bytes, readComposerBytes(bytes.inputStream(), null))
        assertThrows(IOException::class.java) { readComposerBytes(ByteArray(limit + 1).inputStream(), 12) }
        assertArrayEquals(byteArrayOf(1, 2), readComposerBytes(byteArrayOf(1, 2).inputStream(), 0))
        assertThrows(IOException::class.java) { readComposerBytes(byteArrayOf().inputStream(), limit + 1L) }
    }

    @Test fun onlyTheOriginalShownPaneCanReceiveTheDraft() {
        val window = WindowInfo("w", layout = LayoutNode(block = "b"))
        val state = UiState(status = "connected", session = "s", window = "w", focused = "b", windows = listOf(window))
        assertTrue(state.showsComposerPane("s", "b"))
        assertFalse(state.copy(windows = listOf(window.copy(layout = LayoutNode(block = "other")))).showsComposerPane("s", "b"))
        for (changed in listOf(state.copy(session = "other"), state.copy(status = "reconnecting"),
            state.copy(focused = "other"), state.copy(window = "other"), state.copy(windows = emptyList()))) {
            assertFalse(changed.showsComposerPane("s", "b"))
        }
    }

    @Test fun dispatchIsExplicitAndSingleUseEvenAfterAnUnconfirmedSend() {
        val draft = ComposerDraft()
        assertFalse(draft.beginSend(true))
        draft.text = "hello"
        assertFalse(draft.beginSend(false))
        assertFalse(draft.beginSend(true)) // coming back to the same IDs does not revive it
        val invalid = ComposerDraft().apply { text = "before\u001bafter" }
        assertFalse(invalid.beginSend(true))
        val pending = ComposerDraft().apply { text = "hello" }
        assertTrue(pending.beginSend(true))
        assertFalse(pending.beginSend(true))
        pending.cancel()
        assertTrue(pending.sending)
        pending.finish(ComposerFailure("disconnected", wroteNothing = false))
        assertFalse(pending.beginSend(true))
        assertEquals("hello", pending.text)
        assertNotNull(pending.failure)
    }

    @Test fun loadingCancelAndConfirmationCannotDispatchAgain() {
        val draft = ComposerDraft().apply { text = "message"; loading = true }
        assertFalse(draft.beginSend(true))
        draft.cancel()
        draft.loading = false
        assertFalse(draft.beginSend(true))
        val fileOnly = ComposerDraft().apply { attachment = ComposerAttachment("empty.txt", byteArrayOf()) }
        assertTrue(fileOnly.beginSend(true))
        fileOnly.finish(null)
        assertNull(fileOnly.attachment)
        assertFalse(fileOnly.beginSend(true))
    }

    @Test fun failedAttachmentDoesNotSilentlyTurnSendIntoTextOnly() {
        val draft = ComposerDraft().apply { text = "α\n\tβ"; loadError = "too large" }
        assertFalse(draft.beginSend(true))
        draft.loadError = null // explicitly remove the failed attachment
        assertTrue(draft.beginSend(true))
        draft.finish(null)
        assertEquals("", draft.text)
    }

    @Test fun aConfirmedSendShowsSentAndCanNeitherBeSentNorEditedAgain() {
        val draft = ComposerDraft().apply { text = "hello"; attachment = ComposerAttachment("a.txt", byteArrayOf(1)) }
        assertFalse(draft.sent)
        assertTrue(draft.beginSend(true))
        // Not before the remote has confirmed: the draft is still whole and nothing says Sent.
        assertFalse(draft.sent)
        assertEquals("hello", draft.text)
        assertNotNull(draft.attachment)
        assertFalse(draft.beginSend(true))
        draft.finish(null)
        assertTrue(draft.sent)
        assertFalse(draft.sending)
        assertFalse(draft.editable)
        assertEquals("", draft.text)
        assertNull(draft.attachment)
        // Text that still reaches the draft while Sent is shown is not a second message.
        draft.text = "again"
        assertFalse(draft.ready)
        assertFalse(draft.beginSend(true))
        // The pane changing afterwards does not turn the confirmation into a warning, nor does a late result.
        draft.invalidate()
        assertFalse(draft.stale)
        draft.finish(ComposerFailure("late", wroteNothing = false))
        assertTrue(draft.sent)
        assertNull(draft.failure)
    }

    @Test fun aFailedSendNeverReachesSent() {
        val draft = ComposerDraft().apply { text = "hello" }
        assertTrue(draft.beginSend(true))
        draft.finish(ComposerFailure("Confirmation timed out.", wroteNothing = false))
        assertFalse(draft.sent)
        assertEquals("hello", draft.text)
        // A second result for the same send cannot confirm what was reported as failed.
        draft.finish(null)
        assertFalse(draft.sent)
        assertNotNull(draft.failure)
        assertFalse(draft.beginSend(true))
        // Without a send nothing is confirmed either.
        val idle = ComposerDraft().apply { text = "hello" }
        idle.finish(null)
        assertFalse(idle.sent)
        assertEquals("hello", idle.text)
    }

    @Test fun theAttachMenuRemovesAFileOrAFailedLoadButNotWhatIsBeingSent() {
        val draft = ComposerDraft().apply { text = "note" }
        assertEquals(listOf(AttachAction.Image, AttachAction.File), attachMenu(draft.removable).map { it.action })
        // A failed load blocks Send: the menu must offer the way out.
        draft.loadError = "Choose a file of 8 MiB or less."
        assertFalse(draft.beginSend(true))
        assertEquals(listOf(AttachAction.Image, AttachAction.File, AttachAction.Remove), attachMenu(draft.removable).map { it.action })
        draft.removeAttachment()
        assertFalse(draft.removable)
        draft.attachment = ComposerAttachment("a.png", byteArrayOf(1))
        assertTrue(draft.removable)
        assertTrue(draft.beginSend(true))
        // The bytes on their way stay the ones the dialog names.
        draft.removeAttachment()
        assertEquals("a.png", draft.attachment?.name)
    }

    @Test fun closingAnEmptyDraftDoesNotAsk() {
        val draft = ComposerDraft()
        assertTrue(draft.requestClose())
        assertFalse(draft.discarding)
    }

    @Test fun closingADraftWithTextOrOnlyAnAttachmentAsksFirst() {
        val withText = ComposerDraft().apply { text = "hello" }
        val fileOnly = ComposerDraft().apply { attachment = ComposerAttachment("a.png", byteArrayOf(1)) }
        for (draft in listOf(withText, fileOnly)) {
            // The scrim, Back and Cancel all make this request: none of them closes the draft by itself.
            assertFalse(draft.requestClose())
            assertTrue(draft.discarding)
        }
        assertEquals("hello", withText.text)
        assertEquals("a.png", fileOnly.attachment?.name)
    }

    @Test fun keepReturnsToTheDraftAsItWas() {
        val draft = ComposerDraft().apply { text = "hello"; attachment = ComposerAttachment("a.png", byteArrayOf(1)) }
        assertFalse(draft.requestClose())
        draft.keep()
        assertFalse(draft.discarding)
        assertEquals("hello", draft.text)
        assertEquals("a.png", draft.attachment?.name)
        assertTrue(draft.ready)
        // A second tap on the scrim, or Back, while the question is shown answers Keep.
        assertFalse(draft.requestClose())
        assertFalse(draft.requestClose())
        assertFalse(draft.discarding)
        assertEquals("hello", draft.text)
        assertTrue(draft.beginSend(true))
    }

    @Test fun discardEndsTheDraft() {
        val draft = ComposerDraft().apply { text = "hello"; attachment = ComposerAttachment("a.png", byteArrayOf(1)) }
        assertFalse(draft.requestClose())
        assertTrue(draft.discard())
        assertFalse(draft.discarding)
        assertEquals("", draft.text)
        assertNull(draft.attachment)
        assertFalse(draft.beginSend(true))
    }

    @Test fun nothingClosesWhileASendRunsAndAConfirmedSendClosesAtOnce() {
        val draft = ComposerDraft().apply { text = "hello" }
        assertTrue(draft.beginSend(true))
        assertFalse(draft.requestClose())
        assertFalse(draft.discarding)
        assertFalse(draft.discard())
        assertEquals("hello", draft.text)
        draft.finish(null)
        assertTrue(draft.requestClose())
    }

    @Test fun aSendThatWroteNothingCanBeEditedAndSentAgain() {
        val draft = ComposerDraft().apply { text = "hello" }
        assertTrue(draft.beginSend(true))
        draft.finish(ComposerFailure("The terminal is still receiving other input.", wroteNothing = true))
        assertFalse(draft.sent)
        assertFalse(draft.mayHaveWritten)
        assertTrue(draft.editable)
        assertTrue(draft.ready)
        // The error alone: nothing reached the pane, so there is nothing there to check.
        assertEquals("The terminal is still receiving other input.", draft.problem(current = true))
        draft.text = "hello again"
        assertTrue(draft.beginSend(true))
        // The second send's outcome is its own: the first one's error is gone.
        assertNull(draft.problem(current = true))
        draft.finish(null)
        assertTrue(draft.sent)
        assertEquals("", draft.text)
    }

    @Test fun aSendThatMayHaveWrittenIsReadOnlyAndSaysSo() {
        val draft = ComposerDraft().apply { text = "hello" }
        assertTrue(draft.beginSend(true))
        draft.finish(ComposerFailure("Confirmation timed out. Check the original pane before sending again.", wroteNothing = false))
        assertTrue(draft.mayHaveWritten)
        assertFalse(draft.editable)
        assertFalse(draft.beginSend(true))
        assertEquals(
            "Confirmation timed out. Check the original pane before sending again.\n" +
                "Some content may already have reached the pane. Check it before sending again.",
            draft.problem(current = true),
        )
        // Its text is still there, so closing asks first.
        assertFalse(draft.requestClose())
        assertTrue(draft.discarding)
    }

    @Test fun aStaleDraftCannotBeSentButItsTextCanBeCopied() {
        val draft = ComposerDraft().apply { text = "hello" }
        assertFalse(draft.copyable)
        assertFalse(draft.beginSend(false))
        assertTrue(draft.stale)
        assertTrue(draft.copyable)
        assertFalse(draft.ready)
        val stale = "The pane changed or disconnected. Copy the draft, then reopen Compose in the intended pane."
        assertEquals(stale, draft.problem(current = true))
        assertEquals(stale, ComposerDraft().apply { text = "hello" }.problem(current = false))
        // A send that finds its target gone leaves the draft stale too, even when it wrote nothing.
        val refused = ComposerDraft().apply { text = "hello" }
        assertTrue(refused.beginSend(true))
        refused.finish(ComposerFailure("The original pane or connection changed.", wroteNothing = true, targetGone = true))
        assertTrue(refused.stale)
        assertTrue(refused.copyable)
        assertFalse(refused.beginSend(true))
        assertEquals(stale, refused.problem(current = true))
        // Without text there is nothing to copy, and the message does not ask for it.
        val fileOnly = ComposerDraft().apply { attachment = ComposerAttachment("a.png", byteArrayOf(1)); invalidate() }
        assertFalse(fileOnly.copyable)
        val noText = "The pane changed or disconnected. Reopen Compose in the intended pane."
        assertEquals(noText, fileOnly.problem(current = true))
        assertEquals(noText, ComposerDraft().problem(current = false))
    }

    @Test fun onlyRefusalsBeforeTheFirstWriteCountAsNothingWritten() {
        assertNull(HostConnection.composerFailure("OK"))
        // Core.sendComposer and protocol.composerInput return these before the first request is handed to the writer.
        for (code in listOf("InputBusy", "PasteModeRequired", "TextTooLarge", "ControlCharacter", "FileTooLarge", "BadFileName", "EmptyDraft")) {
            val failure = HostConnection.composerFailure(code)!!
            assertTrue(code, failure.wroteNothing)
            assertFalse(code, failure.targetGone)
        }
        // These can also come after a write, or do: the pane may already have part of the draft.
        for (code in listOf("TargetChanged", "Canceled", "ServerRefused", "Timeout", "Unconfirmed", "OutOfMemory", null)) {
            assertFalse("$code", HostConnection.composerFailure(code)!!.wroteNothing)
        }
        // A target whose generation moved on can never be sent to again.
        assertTrue(HostConnection.composerFailure("TargetChanged")!!.targetGone)
        assertTrue(HostConnection.composerFailure("Canceled")!!.targetGone)
        assertFalse(HostConnection.composerFailure("Timeout")!!.targetGone)
    }

    @Test fun aTargetChangeFoundBeforeTheWriteSaysNothingWasSent() {
        val before = HostConnection.composerFailure("TargetChangedBeforeWrite")!!
        assertTrue(before.wroteNothing)
        assertTrue(before.targetGone)
        // The draft then reads as stale only: no warning that part of it reached the pane.
        val draft = ComposerDraft()
        draft.text = "echo hi"
        assertTrue(draft.beginSend(current = true))
        draft.finish(before)
        assertEquals("The pane changed or disconnected. Copy the draft, then reopen Compose in the intended pane.", draft.problem(current = true))
        // The same change found while a write waited still warns.
        val during = ComposerDraft()
        during.text = "echo hi"
        assertTrue(during.beginSend(current = true))
        during.finish(HostConnection.composerFailure("TargetChanged"))
        assertTrue(during.problem(current = true)!!.endsWith("Some content may already have reached the pane. Check it before sending again."))
    }

    @Test fun theTargetIsNamedByTheRemoteAndTheTab() {
        val tab = WindowInfo("w", label = "build", layout = LayoutNode(block = "b"))
        val state = UiState(status = "connected", server = "studio.tailnet.example", account = "me", session = "s", window = "w", focused = "b", windows = listOf(tab))
        assertEquals("Studio · build", composerTargetLabel("Studio", state))
        // A remote saved without a label is named as the server names itself.
        assertEquals("me@studio · build", composerTargetLabel("", state))
        assertEquals("me@studio · build", composerTargetLabel(null, state))
    }
}
