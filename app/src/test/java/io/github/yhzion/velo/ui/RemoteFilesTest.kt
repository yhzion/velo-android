package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFilesTest {
    @Test
    fun aFileUrlGivesItsDecodedPathAndAPathStaysAsItIs() {
        val names = remoteNames("https://workstation.tailnet.example")
        assertEquals("/home/me/a b.png", remoteFilePath("file:///home/me/a%20b.png", names))
        assertEquals("/tmp/한글.txt", remoteFilePath("file:///tmp/%ED%95%9C%EA%B8%80.txt", names))
        assertEquals("src/a.kt", remoteFilePath("src/a.kt", names))
        assertEquals("~/shot.png", remoteFilePath("~/shot.png", names))
        assertNull(remoteFilePath("file://", names))
        assertNull(remoteFilePath("", names))
    }

    @Test
    fun aFileUrlOfAnotherMachineIsNoRemoteFile() {
        val names = remoteNames("https://workstation.tailnet.example")
        assertEquals("/x.kt", remoteFilePath("file://localhost/x.kt", names))
        assertEquals("/x.kt", remoteFilePath("file://workstation/x.kt", names))
        assertEquals("/x.kt", remoteFilePath("file://WORKSTATION.tailnet.example/x.kt", names))
        // Another machine's file, such as one an ssh session inside the terminal printed.
        assertNull(remoteFilePath("file://build-box/x.kt", names))
        assertNull(remoteFilePath("file://build_box/x.kt", names))
        assertEquals(setOf("localhost", "workstation.tailnet.example", "workstation"), remoteNames("https://workstation.tailnet.example:8443/x"))
        assertEquals(setOf("localhost"), remoteNames("not a url"))
    }

    @Test
    fun theCoresAnswerIsRead() {
        assertEquals(
            RemoteFileAnswer("ok", ticket = "T", path = "/h/a.kt", name = "a.kt", size = 12, version = "v1"),
            RemoteFileAnswer.parse("""{"status":"ok","ticket":"T","path":"/h/a.kt","name":"a.kt","size":12,"version":"v1"}"""),
        )
        assertEquals(RemoteFileAnswer("error", code = "not_found", message = "no"), RemoteFileAnswer.parse("""{"status":"error","code":"not_found","message":"no"}"""))
        assertEquals(RemoteFileAnswer("error", code = "unreadable"), RemoteFileAnswer.parse("{"))
        assertEquals(RemoteFileAnswer("error", code = "unreadable"), RemoteFileAnswer.parse(null))
    }

    @Test
    fun aPathThatIsNoFileThereIsAnOrdinaryTap() {
        assertTrue(RemoteFileAnswer("error", code = "not_found").isNoFile)
        assertTrue(RemoteFileAnswer("error", code = "bad_request").isNoFile)
        // A server older than download_begin: the path may be a file, but none opens there.
        assertTrue(RemoteFileAnswer("error", code = "method_not_found").isNoFile)
        assertFalse(RemoteFileAnswer("error", code = "conflict").isNoFile)
        assertFalse(RemoteFileAnswer("ok", ticket = "T").isNoFile)
    }

    @Test
    fun onlyPathsThatNameOneFileWhereverTheShellIsAreRemembered() {
        assertTrue(rememberable("/home/me/a.png"))
        assertFalse(rememberable("~/a.png"))
        assertFalse(rememberable("src/a.kt"))
        assertFalse(rememberable("./a.kt"))
    }

    @Test
    fun theCacheKeyNamesTheRemoteAndThePath() {
        val key = cacheKey("https://workstation.example.net", "/home/me/a.png")
        assertEquals(key, cacheKey("https://workstation.example.net", "/home/me/a.png"))
        assertNotEquals(key, cacheKey("https://other.example.net", "/home/me/a.png"))
        assertNotEquals(key, cacheKey("https://workstation.example.net", "/home/me/b.png"))
        assertTrue(key.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun aNameFromTheServerIsMadeSafeForAFile() {
        assertEquals("a.png", safeFileName("a.png"))
        assertEquals("a_b", safeFileName("a/b"))
        assertEquals("file", safeFileName(""))
        assertEquals("file", safeFileName(null))
        assertEquals("file", safeFileName(".."))
        assertEquals(120, safeFileName("x".repeat(300)).length)
    }

    @Test
    fun textIsToldByItsExtensionOrItsBytes() {
        assertEquals("text/plain", textTypeFor("App.kt", byteArrayOf(0)))
        assertEquals("text/plain", textTypeFor("notes", "hello\n".toByteArray()))
        assertEquals("text/plain", textTypeFor("README", "한글".toByteArray()))
        assertNull(textTypeFor("blob.bin", byteArrayOf(0x50, 0x4b, 0, 3)))
        assertNull(textTypeFor("bad", byteArrayOf(0xff.toByte(), 0xfe.toByte())))
    }

    @Test
    fun aFailureSaysWhatWentWrongInWords() {
        assertEquals("the remote is not connected", downloadFailure(RemoteFileAnswer("error", code = "NotConnected")))
        assertEquals("the remote did not answer", downloadFailure(RemoteFileAnswer("error", code = "Timeout")))
        assertEquals("too many downloads wait", downloadFailure(RemoteFileAnswer("error", code = "conflict", message = "too many downloads wait")))
        assertEquals("the server did not say why", downloadFailure(RemoteFileAnswer("error", code = "conflict", message = " ")))
    }

    @Test
    fun aFetchThatEndedWithoutTheFileSaysWhy() {
        assertEquals("it is larger than Velo opens (256 MB)", fetchFailure(FETCH_TOO_LARGE))
        assertEquals("the download ended before the whole file arrived", fetchFailure(FETCH_FAILED))
        // The ceiling the notice names is the one the core applies.
        assertEquals(256L * 1024 * 1024, MAX_FILE_BYTES)
        assertEquals(-1L, FETCH_STOPPED)
    }

    @Test
    fun aKnownTextExtensionWinsOverWhatAndroidMapsItTo() {
        val binary = { byteArrayOf(0x50, 0x4b, 0, 3) }
        assertEquals("text/plain", fileType("app.ts", "video/mp2ts", binary))
        assertEquals("text/plain", fileType("main.rs", "application/rls-services+xml", binary))
        assertEquals("image/png", fileType("a.png", "image/png", binary))
        assertEquals("text/plain", fileType("LICENSE", null) { "MIT\n".toByteArray() })
        assertEquals("application/octet-stream", fileType("blob", null, binary))
    }

    @Test
    fun aLongNameKeepsItsExtension() {
        val name = safeFileName("x".repeat(300) + ".png")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun anAppPackageAsksBeforeItOpens() {
        assertTrue(asksBeforeOpening("tool.apk"))
        assertTrue(asksBeforeOpening("TOOL.APKS"))
        assertFalse(asksBeforeOpening("shot.png"))
    }
}
