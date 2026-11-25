package io.github.yhzion.velo.updates

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseFeedTest {
    private val repo = "https://github.com/yhzion/velo-android"
    private val digest = "ABCDEF0123456789".repeat(4)
    private val marker = "<!-- android-version-code: 3000100 -->"

    private fun entry(
        tag: String = "v2.6.0", body: String = marker,
        prerelease: Boolean = '-' in tag,
    ): String {
        val name = "velo-$tag-arm64-v8a.apk"
        val url = "$repo/releases/download/$tag/$name"
        return """{"tag_name":"$tag","html_url":"$repo/releases/tag/$tag","draft":false,"prerelease":$prerelease,
            "body":"$body","assets":[
            {"name":"$name","browser_download_url":"$url","state":"uploaded","size":123456,"digest":"sha256:$digest"},
            {"name":"$name.sha256","browser_download_url":"$url.sha256","state":"uploaded","size":91}] }"""
    }

    private fun select(vararg entries: String, channel: UpdateChannel = UpdateChannel.Edge, installed: Long = 0) =
        ReleaseFeed.latest(channel, installed) { page ->
            check(page == 1)
            entries.joinToString(prefix = "[", postfix = "]")
        }

    @Test fun readsVerifiedAssetMetadataAndNormalizesApkDigest() {
        val tag = "v2.6.0-preview.12"
        assertEquals(AppRelease(tag, 3000100L, UpdateChannel.Preview,
            "$repo/releases/download/$tag/velo-v2.6.0-preview.12-arm64-v8a.apk",
            "abcdef0123456789".repeat(4), "$repo/releases/tag/$tag"),
            select(entry(tag, body = "Release notes\\r\\n$marker\\r\\nFix android-version-code parsing")))
    }

    private fun newer(vararg entries: String, channel: UpdateChannel = UpdateChannel.Edge, installed: Long = 0) =
        ReleaseFeed.newer(channel, installed) { entries.joinToString(prefix = "[", postfix = "]") }

    private fun code(code: Long) = marker.replace("3000100", "$code")

    @Test fun everyEligibleNewerReleaseCarriesItsChangeNotesNewestFirst() {
        val notes = "\\n<!-- velo-changes -->\\n### Added\\n- A dot on [Settings](https://example.com).\\n" +
            "### Fixed\\n- Hangul no longer looks flat.\\n<!-- /velo-changes -->\\n\\n## What's Changed\\n- x"
        val releases = newer(
            entry("v2.9.19", code(3000037)),
            entry("v2.9.21", code(3000039) + notes),
            entry("v2.9.20-edge.1", code(3000038) + notes.replace("Added", "Removed")),
            entry("v2.9.18", code(3000036)),
            channel = UpdateChannel.Preview, installed = 3000036,
        )
        assertEquals(listOf("v2.9.21", "v2.9.19"), releases.map { it.tag })
        assertEquals(listOf(
            ChangeSection(ChangeKind.Added, listOf("A dot on Settings.")),
            ChangeSection(ChangeKind.Fixed, listOf("Hangul no longer looks flat.")),
        ), releases[0].changes)
        assertEquals(emptyList<ChangeSection>(), releases[1].changes)
        assertEquals("v2.9.21", ReleaseFeed.latest(UpdateChannel.Preview, 3000036) { "[${entry("v2.9.21", code(3000039))}]" }?.tag)
    }

    @Test fun newerKeepsTheFeedsFirstReleaseOfAVersionCodeAndValidatesEveryEntry() {
        val first = entry("v2.9.21", code(3000039))
        val duplicate = entry("v2.9.22", code(3000039))
        val broken = entry("v2.9.23", code(3000040)).replace("\"uploaded\"", "\"starter\"")
        assertEquals(listOf("v2.9.21"), newer(first, duplicate, broken).map { it.tag })
        assertEquals("v2.9.21", select(first, duplicate)?.tag)
        assertEquals(emptyList<AppRelease>(), newer(first, installed = 3000039))
    }

    @Test fun rejectsAnOtherwiseValidReleaseFromAnotherRepository() {
        val other = "https://github.com/yhzion/other-android"
        val good = entry()
        assertNull(select(good.replace(repo, other)))
        assertNull(select(good.replace("$repo/releases/tag/", "$other/releases/tag/")))
        assertNull(select(good.replace("$repo/releases/download/", "$other/releases/download/")))
    }

    @Test fun channelsAreCumulativeAndOnlyStrictlyNewerCodesWinRegardlessOfOrderOrTag() {
        val stable = entry("v2.7.0")
        val preview = entry("v2.6.0-preview.8", marker.replace("100", "110"))
        val edge = entry("v2.6.1-edge.1", marker.replace("100", "120"))
        assertEquals("v2.7.0", select(edge, preview, stable, channel = UpdateChannel.Stable)?.tag)
        assertEquals("v2.6.0-preview.8", select(stable, edge, preview, channel = UpdateChannel.Preview)?.tag)
        assertEquals("v2.6.1-edge.1", select(preview, edge, stable)?.tag)
        assertEquals("v2.7.0", select(stable, channel = UpdateChannel.Preview)?.tag)
        assertEquals("v2.6.0-preview.8", select(preview)?.tag)
        assertNull(select(edge, preview, stable, installed = 3000120))
        assertNull(select(stable, installed = Long.MAX_VALUE))
    }

    @Test fun fullPagesContinueEvenWhenEntriesAreRejected() {
        val best = ReleaseFeed.latest(UpdateChannel.Stable, 0) { page -> when (page) {
            1 -> List(100) { "null" }.joinToString(prefix = "[", postfix = "]")
            2 -> (List(99) { "{}" } + entry("v2.7.0")).joinToString(prefix = "[", postfix = "]")
            3 -> "[${entry("v2.6.0", marker.replace("100", "101"))}]"
            else -> error("Read beyond the last page")
        } }
        assertEquals(3000101L, best?.versionCode)
    }

    @Test fun laterNetworkFailureIsNotAnEmptyOrPartialResult() {
        assertThrows(IOException::class.java) {
            ReleaseFeed.latest(UpdateChannel.Stable, 0) { page ->
                if (page > 1) throw IOException("offline")
                (List(99) { "{}" } + entry()).joinToString(prefix = "[", postfix = "]")
            }
        }
        assertThrows(IOException::class.java) { ReleaseFeed.latest(UpdateChannel.Stable, 0) { "{}" } }
    }

    @Test fun fallbackIsLimitedToPublishedLegacyStableReleases() {
        assertEquals(2004002L, select(entry("v2.4.2", body = "notes"))?.versionCode)
        assertEquals(2005003L, select(entry("v2.5.3", body = "notes"))?.versionCode)
        for (tag in listOf("v2.4.1", "v1.9.0", "v2.5.3-preview.1", "v2.5.3-edge.1", "v2.5.4")) {
            assertNull(tag, select(entry(tag, body = "notes")))
        }
        assertNull(select(entry("v2.5.3", body = "<!-- android-version-code: broken -->")))
    }

    @Test fun rejectsMalformedOrAmbiguousModernCodes() {
        for (body in listOf("", "$marker\\n$marker", "quoted: $marker", marker.replace("3000100", "03000100"),
            marker.replace("3000100", "0"), marker.replace("3000100", "2100000001"),
            marker.replace("3000100", "3000000"),
            marker.replace("3000100", "99999999999999999999999999"))) {
            assertNull(body, select(entry(body = body)))
        }
    }

    @Test fun rejectsWrongTagOrPublicationFlags() {
        for (tag in listOf("2.6.0", "v2.6", "v02.6.0", "v2.1000.0", "v2.6.1000", "v2.6.0-beta.1",
            "v2.6.0-preview.01", "v2.6.0-edge.0", "v2.6.0-preview.1000000000", "v2.6.0-edge.1/other")) {
            assertNull(tag, select(entry(tag)))
        }
        assertNull(select(entry(prerelease = true)))
        assertNull(select(entry("v2.6.0-edge.1", prerelease = false)))
        for (field in listOf("draft", "prerelease")) {
            for (value in listOf("true", "null", "\"false\"")) {
                assertNull(select(entry().replace("\"$field\":false", "\"$field\":$value")))
            }
        }
    }

    @Test fun rejectsIncompleteUploadsWrongArtifactsAndNonCanonicalUrls() {
        val good = entry()
        val url = "$repo/releases/download/v2.6.0/velo-v2.6.0-arm64-v8a.apk"
        for ((original, replacement) in listOf(
            "velo-v2.6.0-arm64-v8a.apk\"" to "debug.apk\"",
            "apk.sha256\"" to "apk.sig\"", "\"uploaded\"" to "\"starter\"", "\"size\":91" to "\"size\":0",
            "sha256:$digest" to "md5:$digest", digest to digest.dropLast(1), digest to "g".repeat(64),
            url to url.replace("https:", "http:"), url to url.replace("github.com", "github.com.example"),
            url to url.replace("/yhzion/", "/other/"), url to url.replace("/velo-android/", "/other/"),
            url to url.replace("download/v2.6.0", "download/v2.5.3"),
            "$url.sha256" to "$url.sha256?alternate=1", "$url\"" to "$url#x\"",
            "/releases/tag/v2.6.0" to "/releases/latest",
        )) {
            check(original in good)
            assertNull(replacement, select(good.replace(original, replacement)))
        }
    }
}
