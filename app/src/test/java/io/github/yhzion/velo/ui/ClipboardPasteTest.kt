package io.github.yhzion.velo.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The key bar's Paste (#219) reads the clipboard as the pane menu's Paste does: text goes through
 * the paste question, a file another app copied through the upload, and an empty clipboard does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardPasteTest {
    private val app = RuntimeEnvironment.getApplication()
    private val clipboard = app.getSystemService(ClipboardManager::class.java)
    private val done = mutableListOf<String>()
    private var waiting = false

    /** A Paste of the clipboard as it is now; [done] lists what it did, in order. */
    private fun paste() = pasteClipboard(clipboardFile(app), { clipboardText(app) },
        beforePaste = { done += "composition"; waiting },
        sendFile = { done += "file ${it.uri}" }, sendText = { done += "text $it" })

    @Test fun textGoesToTheQuestionAfterTheComposition() {
        clipboard.setPrimaryClip(ClipData.newPlainText("Velo", "make\nmake test"))
        paste()
        assertEquals(listOf("composition", "text make\nmake test"), done)
    }

    @Test fun aFileGoesAsTheUploadAfterTheComposition() {
        val uri = Uri.parse("content://test.clip/1")
        clipboard.setPrimaryClip(ClipData(ClipDescription("l", arrayOf("image/png")), ClipData.Item(uri)))
        paste()
        assertEquals(listOf("composition", "file $uri"), done)
    }

    @Test fun anEmptyClipboardDoesNothing() {
        paste()
        assertTrue("$done", done.isEmpty())
        clipboard.setPrimaryClip(ClipData.newPlainText("Velo", ""))
        paste()
        assertTrue("$done", done.isEmpty())
    }

    @Test fun nothingGoesWhileAPasteWaitsForItsQuestion() {
        waiting = true
        clipboard.setPrimaryClip(ClipData.newPlainText("Velo", "ls"))
        paste()
        clipboard.setPrimaryClip(ClipData(ClipDescription("l", arrayOf("image/png")), ClipData.Item(Uri.parse("content://test.clip/2"))))
        paste()
        assertEquals(listOf("composition", "composition"), done)
    }
}
