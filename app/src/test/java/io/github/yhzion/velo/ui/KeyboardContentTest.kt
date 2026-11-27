package io.github.yhzion.velo.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An image the keyboard inserts into a pane (#160): the declared types, the decision, the editor's hook, and the read's end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeyboardContentTest {
    private lateinit var resolver: ContentResolver
    private val main = Handler(Looper.getMainLooper())

    @Before fun setUp() {
        resolver = RuntimeEnvironment.getApplication().contentResolver
    }

    @Test fun editorDeclaresImageTypes() {
        val info = EditorInfo()
        declareKeyboardContent(info)
        assertArrayEquals(arrayOf("image/*", "image/png", "image/gif", "image/jpeg", "image/webp"), info.contentMimeTypes)
    }

    @Test fun onlyAContentUriForABoundPaneIsAccepted() {
        assertTrue(keyboardContentAccepted("content", bound = true))
        assertFalse(keyboardContentAccepted("content", bound = false))
        assertFalse(keyboardContentAccepted("file", bound = true))
        assertFalse(keyboardContentAccepted("https", bound = true))
        assertFalse(keyboardContentAccepted(null, bound = true))
    }

    @Test fun fileTakesTheDescriptionsFirstNamedType() {
        val uri = Uri.parse("content://test.keyboard/1")
        val png = keyboardContentFile(uri, ClipDescription("image", arrayOf("image/png", "image/*")))
        assertSame(uri, png.uri)
        assertEquals("image/png", png.mime)
        // A wildcard names no extension: the read asks the provider instead, as for a clip.
        assertNull(keyboardContentFile(uri, ClipDescription("image", arrayOf("image/*"))).mime)
        assertNull(keyboardContentFile(uri, ClipDescription("image", arrayOf())).mime)
    }

    @Test fun editorHandsContentToItsHook() {
        val got = mutableListOf<Pair<InputContentInfo, Int>>()
        var answer = true
        val editor = editor(accepts = { true }) { info, flags -> got += info to flags; answer }
        val info = InputContentInfo(Uri.parse("content://test.keyboard/1"), ClipDescription("image", arrayOf("image/png")))
        val flags = InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        assertTrue(editor.commitContent(info, flags, null))
        answer = false
        assertFalse(editor.commitContent(info, 0, null))
        assertEquals(listOf(info to flags, info to 0), got)
    }

    @Test fun editorThatTakesNoInputRefusesContent() {
        var calls = 0
        val info = InputContentInfo(Uri.parse("content://test.keyboard/1"), ClipDescription("image", arrayOf("image/png")))
        assertFalse(editor(accepts = { false }) { _, _ -> calls++; true }.commitContent(info, 0, null))
        val closed = editor(accepts = { true }) { _, _ -> calls++; true }
        closed.closeConnection()
        assertFalse(closed.commitContent(info, 0, null))
        assertEquals(0, calls)
    }

    @Test fun readEndsOnceOnSuccess() {
        val uri = Uri.parse("content://test.keyboard/ok")
        shadowOf(resolver).registerInputStream(uri, byteArrayOf(4, 5, 6).inputStream())
        val got = read(uri)
        assertEquals(1, got.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), got[0].getOrThrow().bytes)
    }

    @Test fun readEndsOnceOnFailure() {
        val uri = Uri.parse("content://test.keyboard/refused")
        shadowOf(resolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = throw SecurityException("grant withdrawn")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SecurityException("grant withdrawn")
        })
        val got = read(uri)
        assertEquals(1, got.size)
        assertEquals("Velo may not read this clipboard file.", (got[0].exceptionOrNull() as IOException).message)
    }

    @Test fun readThatStallsEndsOnceAtTheLimit() {
        val uri = Uri.parse("content://test.keyboard/stalled")
        val release = CountDownLatch(1)
        shadowOf(resolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = read(ByteArray(1), 0, 1)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                release.await(10, TimeUnit.SECONDS)
                return -1
            }
        })
        val got = mutableListOf<Result<ComposerAttachment>>()
        readClipboardFileOnWorker(resolver, ClipboardFile(uri, "image/png"), main) { got += it }
        shadowOf(Looper.getMainLooper()).idleFor(READ_LIMIT_MS, TimeUnit.MILLISECONDS)
        assertEquals(1, got.size)
        assertEquals(UNREADABLE, got[0].exceptionOrNull()?.message)
        // The worker's late result comes to main and does nothing.
        release.countDown()
        awaitMain()
        assertEquals(1, got.size)
    }

    private fun read(uri: Uri): List<Result<ComposerAttachment>> {
        val got = mutableListOf<Result<ComposerAttachment>>()
        readClipboardFileOnWorker(resolver, ClipboardFile(uri, "image/png"), main) { got += it }
        awaitMain()
        // Past the limit too: the timeout was taken away with the result.
        shadowOf(Looper.getMainLooper()).idleFor(READ_LIMIT_MS, TimeUnit.MILLISECONDS)
        return got
    }

    /** Waits for the worker to post its result to main, then runs it. */
    private fun awaitMain() {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (looper.isIdle && System.nanoTime() < end) Thread.sleep(5)
        looper.idle()
    }

    private fun editor(accepts: () -> Boolean, content: (InputContentInfo, Int) -> Boolean) =
        TerminalEditor(View(RuntimeEnvironment.getApplication()), { accepts() },
            commit = {}, finish = {}, key = {}, forward = {}, content = content)
}
