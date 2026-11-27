package io.github.yhzion.velo.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A file that another app put on the clipboard (#107): which clips are files, their name, and their read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardFileTest {
    private val t = LocalDateTime.of(2026, 11, 27, 15, 30, 12)
    private lateinit var resolver: ContentResolver

    @Before fun setUp() {
        resolver = RuntimeEnvironment.getApplication().contentResolver
    }

    @Test fun textWinsOverUri() {
        assertTrue(clipItemIsFile(null, "content"))
        assertTrue(clipItemIsFile("", "content"))
        assertFalse(clipItemIsFile("https://x.example", "content"))
        assertFalse(clipItemIsFile(null, "file"))
        assertFalse(clipItemIsFile(null, null))
        assertFalse(clipItemIsFile("hi", null))
    }

    @Test fun providerNameIsKeptWhenUsable() {
        assertEquals("사진.png", clipboardFileName("사진.png", "jpg", t))
        assertEquals("clipboard-20261127-153012.png", clipboardFileName(null, "png", t))
        assertEquals("clipboard-20261127-153012.pdf", clipboardFileName("", "pdf", t))
        assertEquals("clipboard-20261127-153012.png", clipboardFileName("../x", "png", t))
        assertEquals("clipboard-20261127-153012", clipboardFileName(null, null, t))
    }

    @Test fun namelessProviderGetsGeneratedName() {
        // query() of an unregistered URI gives no cursor; registerInputStream gives the bytes.
        val uri = Uri.parse("content://test.clip/1")
        shadowOf(resolver).registerInputStream(uri, byteArrayOf(1, 2, 3).inputStream())
        val got = resolver.loadClipboardFile(ClipboardFile(uri, "image/png"), t)
        assertEquals("clipboard-20261127-153012.png", got.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), got.bytes)
    }

    @Test fun refusedReadBecomesIOException() {
        // A stream whose read() throws SecurityException stands for a provider that withdrew the grant.
        val refusingUri = Uri.parse("content://test.clip/refused")
        val refusing = object : InputStream() {
            override fun read(): Int = throw SecurityException("grant withdrawn")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw SecurityException("grant withdrawn")
        }
        shadowOf(resolver).registerInputStream(refusingUri, refusing)
        val e = assertThrows(IOException::class.java) { resolver.loadClipboardFile(ClipboardFile(refusingUri, null), t) }
        assertEquals("Velo may not read this clipboard file.", e.message)
    }
}
