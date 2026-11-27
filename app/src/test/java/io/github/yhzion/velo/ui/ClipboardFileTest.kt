package io.github.yhzion.velo.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A provider that only answers getType, and counts the calls: a call on main is what fix 2 of the #106 review removed. */
class TypedProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String {
        types++
        return "image/jpeg"
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
    companion object { var types = 0 }
}

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

    private fun clip(c: ClipData) {
        val cm = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(c)
    }

    private fun failingRead(uri: Uri, e: Throwable) = shadowOf(resolver).registerInputStream(uri, object : InputStream() {
        override fun read(): Int = throw e
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw e
    })

    @Test fun emptyClipboardIsNoFile() {
        assertNull(clipboardFile(RuntimeEnvironment.getApplication()))
    }

    @Test fun textClipsAreNoFile() {
        val app = RuntimeEnvironment.getApplication()
        clip(ClipData.newPlainText("l", "hello"))
        assertNull(clipboardFile(app))
        clip(ClipData.newHtmlText("l", "hello", "<b>hello</b>"))
        assertNull(clipboardFile(app))
        // A browser's copied link: text and a URI, and the text wins.
        clip(ClipData(ClipDescription("l", arrayOf("text/plain")), ClipData.Item("x", null, null, Uri.parse("content://test.typed/1"))))
        assertNull(clipboardFile(app))
    }

    @Test fun mimeComesFromTheClipNotTheProvider() {
        Robolectric.setupContentProvider(TypedProvider::class.java, "test.typed")
        TypedProvider.types = 0
        val uri = Uri.parse("content://test.typed/1")
        clip(ClipData(ClipDescription("l", arrayOf("image/png")), ClipData.Item(uri)))
        val got = clipboardFile(RuntimeEnvironment.getApplication())!!
        assertEquals(uri, got.uri)
        assertEquals("image/png", got.mime)
        assertEquals(0, TypedProvider.types)
    }

    @Test fun uriListOrWildcardIsNoMime() {
        // ClipData.newUri writes text/uri-list when the provider gives no type; neither it nor */* names an extension.
        val uri = Uri.parse("content://test.typed/1")
        clip(ClipData(ClipDescription("l", arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST)), ClipData.Item(uri)))
        assertNull(clipboardFile(RuntimeEnvironment.getApplication())!!.mime)
        clip(ClipData(ClipDescription("l", arrayOf("*/*")), ClipData.Item(uri)))
        assertNull(clipboardFile(RuntimeEnvironment.getApplication())!!.mime)
    }

    @Test fun readAsksTheProviderForAMissingMime() {
        Robolectric.setupContentProvider(TypedProvider::class.java, "test.typed")
        val uri = Uri.parse("content://test.typed/2")
        shadowOf(resolver).registerInputStream(uri, byteArrayOf(7).inputStream())
        assertEquals("clipboard-20261127-153012.jpg", resolver.loadClipboardFile(ClipboardFile(uri, null), t).name)
    }

    @Test fun goneFileSaysSo() {
        val uri = Uri.parse("content://test.clip/gone")
        failingRead(uri, FileNotFoundException("open failed: ENOENT"))
        val e = assertThrows(IOException::class.java) { resolver.loadClipboardFile(ClipboardFile(uri, null), t) }
        assertEquals("The clipboard file is no longer available.", e.message)
    }

    @Test fun providerTextIsNotShown() {
        val uri = Uri.parse("content://test.clip/eio")
        failingRead(uri, IOException("read failed: EIO (I/O error)"))
        val e = assertThrows(IOException::class.java) { resolver.loadClipboardFile(ClipboardFile(uri, null), t) }
        assertEquals("Could not read the clipboard file.", e.message)
    }

    @Test fun providerRuntimeExceptionBecomesIOException() {
        val uri = Uri.parse("content://test.clip/iae")
        failingRead(uri, IllegalArgumentException("bad column"))
        val e = assertThrows(IOException::class.java) { resolver.loadClipboardFile(ClipboardFile(uri, null), t) }
        assertEquals("Could not read the clipboard file.", e.message)
    }

    @Test fun tooLargeKeepsItsText() {
        val uri = Uri.parse("content://test.clip/big")
        shadowOf(resolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        })
        val e = assertThrows(IOException::class.java) { resolver.loadClipboardFile(ClipboardFile(uri, null), t) }
        assertEquals("Choose a file of 8 MiB or less.", e.message)
    }
}
