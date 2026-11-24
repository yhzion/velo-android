package io.github.yhzion.velo.updates

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val url = "https://github.com/yhzion/velo-android/releases/download/v2.6.0-preview.2/velo-v2.6.0-preview.2-arm64-v8a.apk"
    private val release = AppRelease("v2.6.0-preview.2", 3_000_100, UpdateChannel.Preview, url, abc, "")

    @Test fun rejectsTheSameAssetPathUnderAnotherRepositoryOrOwner() {
        for (other in listOf(url.replace("/velo-android/", "/other-android/"), url.replace("/yhzion/", "/other/"))) {
            assertThrows(IllegalArgumentException::class.java) {
                updateTarget(temp.root, release.copy(apkUrl = other))
            }
        }
    }

    @Test fun onlyCurrentExactSignerSetAndRequestedUpgradeAreAccepted() {
        val installed = UpdateIdentity("io.github.yhzion.velo", "2.5.3", 2_005_003, setOf("A", "B"))
        val archive = UpdateIdentity("io.github.yhzion.velo", "2.6.0-preview.2", 3_000_100, setOf("B", "A"))
        requireUpdateIdentity(installed, archive, release)
        for (bad in listOf(archive.copy(name = "io.github.yhzion.velo.other"), archive.copy(version = "2.6.0"),
            archive.copy(code = 3_000_101), archive.copy(signers = setOf("A")),
            archive.copy(signers = setOf("A", "B", "C")), archive.copy(signers = setOf("C", "D")))) {
            assertThrows(IllegalArgumentException::class.java) { requireUpdateIdentity(installed, bad, release) }
        }
        for (installedCode in listOf(3_000_100L, 3_000_101L)) {
            assertThrows(IllegalArgumentException::class.java) {
                requireUpdateIdentity(installed.copy(code = installedCode), archive, release)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireUpdateIdentity(installed.copy(signers = emptySet()), archive.copy(signers = emptySet()), release)
        }
    }

    @Test fun onlyHttpsReleaseAssetOriginsMayReceiveRedirects() {
        val origin = URI(url)
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/id?sig=foo"
        assertEquals(URI(cdn), assetRedirect(origin, cdn))
        assertEquals(URI("https://objects.githubusercontent.com/asset"), assetRedirect(URI(cdn), "//objects.githubusercontent.com/asset"))
        for (bad in listOf("http://objects.githubusercontent.com/a", "https://objects.githubusercontent.com.evil.test/a",
            "https://evil.test@objects.githubusercontent.com/a", "https://release-assets.githubusercontent.com:8080/a",
            "https://github.com/another/repo", "file:///tmp/file", "https://raw.githubusercontent.com/a")) {
            assertThrows(bad, IllegalArgumentException::class.java) { assetRedirect(origin, bad) }
        }
    }

    @Test fun launchablePathBindsDigestAndRejectsSymlinksAndOutsideFiles() {
        val cache = temp.newFolder("cache")
        val target = updateTarget(cache, release)
        target.parentFile!!.mkdir()
        target.writeText("abc")
        requireUpdatePath(cache, target, release)
        val elsewhere = temp.newFile("external.apk")
        for (file in listOf(elsewhere, File(target.parentFile, "staging.partial.apk"), File(cache, "updates/../updates/${target.name}"))) {
            assertThrows(IllegalArgumentException::class.java) { requireUpdatePath(cache, file, release) }
        }
        assertThrows(IllegalArgumentException::class.java) { requireUpdatePath(cache, target, release.copy(sha256 = "c".repeat(64))) }
        for (bad in listOf(release.copy(sha256 = "../other"), release.copy(apkUrl = "https://github.com/other/repo"))) {
            assertThrows(IllegalArgumentException::class.java) { updateTarget(cache, bad) }
        }
        Files.delete(target.toPath())
        Files.createSymbolicLink(target.toPath(), elsewhere.toPath())
        assertThrows(IllegalArgumentException::class.java) { requireUpdatePath(cache, target, release) }
        val cache2 = temp.newFolder("second-cache")
        Files.createSymbolicLink(File(cache2, "updates").toPath(), temp.root.toPath())
        assertThrows(IllegalArgumentException::class.java) { updateTarget(cache2, release) }
    }

    @Test fun streamRequiresExactNonemptyPayloadWithinLimitEvenWithoutLengthHeader() {
        fun copy(text: String, length: Long, limit: Long, digest: String = abc): String {
            val sink = ByteArrayOutputStream()
            verifyStream(text.byteInputStream(), sink, length, limit, digest) {}
            return sink.toString("US-ASCII")
        }
        assertEquals("abc", copy("abc", -1, 3))
        assertEquals("abc", copy("abc", 3, 3))
        assertThrows(IllegalArgumentException::class.java) { copy("abc", -1, 2) }
        assertThrows(IllegalArgumentException::class.java) { copy("abc", 4, 8) }
        assertThrows(IllegalArgumentException::class.java) { copy("abc", 2, 8) }
        assertThrows(IllegalArgumentException::class.java) { copy("abd", 3, 3) }
        assertThrows(IllegalArgumentException::class.java) {
            copy("", -1, 8, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        }
    }

    @Test fun cancellationAfterPartialWriteLeavesNeitherPartialNorPublishedFile() {
        val dir = temp.newFolder("updates")
        val target = File(dir, "verified.apk")
        assertThrows(CancellationException::class.java) {
            stageUpdate(target) { part ->
                part.outputStream().use { out ->
                    verifyStream(ByteArray(256 * 1024).inputStream(), out, -1, 256 * 1024L, abc) {
                        if (part.length() > 0) throw CancellationException()
                    }
                }
            }
        }
        assertArrayEquals(emptyArray<String>(), dir.list())
        stageUpdate(target) { it.writeText("complete") }
        assertEquals("complete", target.readText())
        assertArrayEquals(arrayOf("verified.apk"), dir.list())
    }

    @Test fun releaseNeedsBothArm64LibrariesRegardlessOfOtherZipEntries() {
        fun apk(vararg entries: String): File = temp.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for (entry in entries) {
                    zip.putNextEntry(ZipEntry(entry)); zip.write(1); zip.closeEntry()
                }
            }
        }
        val core = "lib/arm64-v8a/libvelocore.so"
        val tailscale = "lib/arm64-v8a/libtailscale.so"
        requireArm64Payload(apk(core, tailscale))
        for (bad in listOf(apk(core), apk(tailscale), apk(core, tailscale.replace("arm64-v8a", "x86_64")))) {
            assertThrows(IllegalArgumentException::class.java) { requireArm64Payload(bad) }
        }
    }
}
