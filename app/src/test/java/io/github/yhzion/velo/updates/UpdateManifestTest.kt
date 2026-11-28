package io.github.yhzion.velo.updates

import java.io.IOException
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** #258: the stable check reads the update manifest before the API, and a reached API limit says so. */
class UpdateManifestTest {
    private val repo = "https://github.com/yhzion/velo-android"
    private val sha = "ABCDEF0123456789".repeat(4)
    private val notes = "<!-- android-version-code: 3000053 -->\n<!-- velo-changes -->\n### Fixed\n- Updates are found.\n<!-- /velo-changes -->\n"
    private val now = 1_800_000_000_000L

    private fun manifest(
        tag: String = "v2.16.1", code: String = "3000053", apk: String = "velo-$tag-arm64-v8a.apk",
        sha256: String = sha, body: String = notes,
    ) = """{"tag":"$tag","versionCode":$code,"apk":"$apk","sha256":"$sha256","notes":${quote(body)}}"""

    private fun quote(text: String) = "\"" + text.replace("\n", "\\n") + "\""

    private fun apiEntry(tag: String, code: Long, digest: String = sha): String {
        val name = "velo-$tag-arm64-v8a.apk"
        val url = "$repo/releases/download/$tag/$name"
        return """{"tag_name":"$tag","html_url":"$repo/releases/tag/$tag","draft":false,"prerelease":${'-' in tag},
            "body":"<!-- android-version-code: $code -->","assets":[
            {"name":"$name","browser_download_url":"$url","state":"uploaded","size":1,"digest":"sha256:$digest"},
            {"name":"$name.sha256","browser_download_url":"$url.sha256","state":"uploaded","size":91}] }"""
    }

    /** A fake GitHub: the manifest the latest-release redirect leads to, each release's checksum, the API. */
    private inner class Fake(
        val latest: String? = "v2.16.1",
        val text: String = manifest(),
        val checksum: (String) -> String = { tag -> "${sha.lowercase()}  velo-$tag-arm64-v8a.apk\n" },
        val api: (Int) -> String = { throw AssertionError("The API was asked") },
    ) : FeedSource {
        var apiCalls = 0
        override fun page(page: Int): String { apiCalls++; return api(page) }
        override fun manifest(): Pair<String, String> = (latest ?: throw IOException("404")) to text
        override fun asset(tag: String, name: String): String {
            assertEquals("velo-$tag-arm64-v8a.apk.sha256", name)
            return checksum(tag)
        }
    }

    private fun check(source: FeedSource, channel: UpdateChannel = UpdateChannel.Stable, installed: Long = 3000052,
        everyRelease: Boolean = false, blockedUntil: Long = 0) =
        ReleaseFeed.check(channel, installed, everyRelease, blockedUntil, source, now)

    @Test fun aStableCheckFindsTheUpdateInTheManifestWithoutTheApi() {
        val found = check(Fake()).single()
        assertEquals(AppRelease("v2.16.1", 3000053, UpdateChannel.Stable,
            "$repo/releases/download/v2.16.1/velo-v2.16.1-arm64-v8a.apk", sha.lowercase(), "$repo/releases/tag/v2.16.1",
            listOf(ChangeSection(ChangeKind.Fixed, listOf("Updates are found.")))), found)
    }

    /**
     * #265: the manifest .github/scripts/update-manifest.sh writes, as test-update-manifest.sh keeps
     * it, is one this parser takes. A key renamed on either side fails one of the two tests.
     */
    @Test fun theManifestTheReleaseScriptWritesIsTheOneThisReads() {
        val written = requireNotNull(javaClass.getResource("/velo-update.json")).readText()
        val found = check(Fake(text = written)).single()
        assertEquals(AppRelease("v2.16.1", 3000053, UpdateChannel.Stable,
            "$repo/releases/download/v2.16.1/velo-v2.16.1-arm64-v8a.apk", sha.lowercase(), "$repo/releases/tag/v2.16.1",
            listOf(ChangeSection(ChangeKind.Fixed, listOf("Updates are found.")))), found)
    }

    @Test fun aManifestThatIsNotNewerMeansNoUpdateWithoutTheApi() {
        assertEquals(emptyList<AppRelease>(), check(Fake(), installed = 3000053))
    }

    @Test fun anyDoubtAboutTheManifestLeavesTheDecisionToTheApi() {
        val api = { _: Int -> "[${apiEntry("v2.16.0", 3000052)}]" }
        val doubtful = listOf(
            Fake(latest = null, api = api), // no manifest yet: releases before it
            Fake(latest = "v2.16.0", api = api), // the redirect named another release
            Fake(text = manifest(tag = "v2.16.1-preview.1"), latest = "v2.16.1-preview.1", api = api),
            Fake(text = manifest(code = "3000054"), api = api), // the marker says 3000053
            Fake(text = manifest(code = "\"3000053\""), api = api),
            Fake(text = manifest(apk = "velo-v2.16.1-universal.apk"), api = api),
            Fake(text = manifest(sha256 = "12".repeat(32)), api = api), // not the .sha256 asset's digest
            Fake(text = manifest(sha256 = "xyz"), api = api),
            Fake(checksum = { "${sha.lowercase()}  velo-v2.16.0-arm64-v8a.apk\n" }, api = api),
            Fake(checksum = { throw IOException("offline") }, api = api),
            Fake(text = "[]", api = api),
            Fake(text = "not json", api = api),
        )
        for (fake in doubtful) {
            assertEquals(listOf("v2.16.0"), check(fake, installed = 3000051).map { it.tag })
            assertEquals(1, fake.apiCalls)
        }
    }

    @Test fun previewAndEdgeAskTheApi() {
        for (channel in listOf(UpdateChannel.Preview, UpdateChannel.Edge)) {
            val fake = Fake(api = { "[${apiEntry("v2.16.1-preview.1", 3000054)}]" })
            assertEquals(listOf("v2.16.1-preview.1"), check(fake, channel).map { it.tag })
            assertEquals(1, fake.apiCalls)
        }
    }

    @Test fun theUpdatesPageTakesEveryNewerReleaseFromTheApiOnlyWhenItAgrees() {
        val both = "[${apiEntry("v2.16.1", 3000053)},${apiEntry("v2.16.0", 3000052)}]"
        assertEquals(listOf("v2.16.1", "v2.16.0"), check(Fake(api = { both }), installed = 3000051, everyRelease = true).map { it.tag })
        val behind = "[${apiEntry("v2.16.0", 3000052)}]" // a list that does not have the manifest's release yet
        assertEquals(listOf("v2.16.1"), check(Fake(api = { behind }), installed = 3000051, everyRelease = true).map { it.tag })
        // The API, checked in full, may know a newer release than the one marked latest.
        val newer = "[${apiEntry("v2.17.0", 3000060)},${apiEntry("v2.16.1", 3000053)}]"
        assertEquals(listOf("v2.17.0", "v2.16.1"), check(Fake(api = { newer }), installed = 3000051, everyRelease = true).map { it.tag })
        // The same release with another digest: the manifest's, which matches the release's checksum.
        val otherDigest = "[${apiEntry("v2.16.1", 3000053, "12".repeat(32))}]"
        assertEquals(sha.lowercase(), check(Fake(api = { otherDigest }), installed = 3000051, everyRelease = true).single().sha256)
        val limited = Fake(api = { throw RateLimitedException(now + 60_000) })
        assertEquals(listOf("v2.16.1"), check(limited, installed = 3000051, everyRelease = true).map { it.tag })
        // Nothing newer: the API is not asked for notes.
        assertEquals(emptyList<AppRelease>(), check(Fake(), installed = 3000053, everyRelease = true))
    }

    @Test fun whileTheLimitHoldsTheApiIsNotAsked() {
        val until = now + 20 * 60_000
        assertEquals(listOf("v2.16.1"), check(Fake(), blockedUntil = until, everyRelease = true).map { it.tag })
        val noManifest = Fake(latest = null)
        assertEquals(until, assertThrows(RateLimitedException::class.java) { check(noManifest, blockedUntil = until) }.resetAt)
        val preview = Fake()
        assertThrows(RateLimitedException::class.java) { check(preview, UpdateChannel.Preview, blockedUntil = until) }
        assertEquals(0, noManifest.apiCalls + preview.apiCalls)
    }

    /** #264: a page from the API, on any channel, ends a stored limit; a refusal or a skipped API does not. */
    @Test fun theApiAnsweringOnceEndsAStoredLimit() {
        fun answers(source: FeedSource, channel: UpdateChannel = UpdateChannel.Stable, installed: Long = 3000051,
            everyRelease: Boolean = true, blockedUntil: Long = 0): Int {
            var count = 0
            try {
                ReleaseFeed.check(channel, installed, everyRelease, blockedUntil, source, now) { count++ }
            } catch (_: IOException) {}
            return count
        }
        val page = { _: Int -> "[${apiEntry("v2.16.1", 3000053)}]" }
        assertEquals(1, answers(Fake(api = page)))
        assertEquals(1, answers(Fake(latest = null, api = page), everyRelease = false))
        assertEquals(1, answers(Fake(api = { "[${apiEntry("v2.16.1-edge.1", 3000054)}]" }), UpdateChannel.Edge, everyRelease = false))
        // Once for a list of several pages.
        val full = (1..100).joinToString(",", "[", "]") { apiEntry("v2.15.$it", 3000000L + it) }
        assertEquals(1, answers(Fake(api = { if (it == 1) full else "[]" }), installed = 2_000_000))
        // A list that does not parse still came back with 200: the limit is not used up.
        assertEquals(1, answers(Fake(latest = null, api = { "not json" }), everyRelease = false))
        assertEquals(0, answers(Fake(api = { throw RateLimitedException(now + 60_000) })))
        assertEquals(0, answers(Fake(latest = null, api = { throw IOException("offline") }), everyRelease = false))
        assertEquals(0, answers(Fake(), installed = 3000053)) // nothing newer: the API is not asked
        assertEquals(0, answers(Fake(), everyRelease = false)) // an automatic stable check: the manifest alone
        assertEquals(0, answers(Fake(), UpdateChannel.Preview, blockedUntil = now + 60_000))
    }

    @Test fun aPassedOrImplausibleLimitDoesNotHoldTheApi() {
        val api = { _: Int -> "[${apiEntry("v2.16.1-edge.1", 3000054)}]" }
        assertEquals(1, check(Fake(api = api), UpdateChannel.Edge, blockedUntil = now).size)
        // Over an hour ahead: the clock was set back since, so the stored time says nothing.
        assertEquals(1, check(Fake(api = api), UpdateChannel.Edge, blockedUntil = now + 2 * 3_600_000).size)
    }

    @Test fun aRefusalWithNoRequestsLeftIsTheLimitUntilItsResetByTheServersClock() {
        val server = now - 5 * 60_000 // the phone's clock is five minutes ahead of GitHub's
        val reset = ((server + 20 * 60_000) / 1000).toString()
        for (status in listOf(403, 429)) {
            assertEquals(now + 20 * 60_000, ReleaseFeed.rateLimit(status, "0", reset, server, now)?.resetAt)
        }
        // Without the server's date, the phone's clock.
        assertEquals(now + 20 * 60_000, ReleaseFeed.rateLimit(403, "0", ((now + 20 * 60_000) / 1000).toString(), 0, now)?.resetAt)
    }

    @Test fun theWaitIsAtLeastAMinuteAndAtMostAnHour() {
        assertEquals(now + 60_000, ReleaseFeed.rateLimit(403, "0", (now / 1000 - 10).toString(), now, now)?.resetAt)
        assertEquals(now + 3_600_000, ReleaseFeed.rateLimit(403, "0", (now / 1000 + 86_400).toString(), now, now)?.resetAt)
        assertEquals(now + 3_600_000, ReleaseFeed.rateLimit(429, "0", null, now, now)?.resetAt)
        assertEquals(now + 3_600_000, ReleaseFeed.rateLimit(429, "0", "soon", now, now)?.resetAt)
    }

    @Test fun otherRefusalsAreNotTheLimit() {
        assertNull(ReleaseFeed.rateLimit(403, "12", "1800000000", now, now))
        assertNull(ReleaseFeed.rateLimit(403, null, "1800000000", now, now))
        assertNull(ReleaseFeed.rateLimit(404, "0", "1800000000", now, now))
        assertNull(ReleaseFeed.rateLimit(500, "0", "1800000000", now, now))
        assertTrue(RateLimitedException(now) is IOException)
    }

    @Test fun theFirstRedirectNamesTheLatestRelease() {
        val download = "$repo/releases/download/v2.16.1/velo-update.json"
        assertEquals("v2.16.1", ReleaseFeed.latestTag(download))
        assertEquals("v2.16.1", ReleaseFeed.latestTag("$download?x=1")) // a query does not change the release
        for (location in listOf(
            "https://github.com/yhzion/other-android/releases/download/v2.16.1/velo-update.json",
            "$repo/releases/download/v2.16.1/velo-v2.16.1-arm64-v8a.apk",
            "$repo/releases/download/v2.16.1/x/velo-update.json",
            "http://github.com/yhzion/velo-android/releases/download/v2.16.1/velo-update.json",
            "https://github.com:8443/yhzion/velo-android/releases/download/v2.16.1/velo-update.json",
            "https://u@github.com/yhzion/velo-android/releases/download/v2.16.1/velo-update.json",
            "https://gist.github.com/yhzion/velo-android/releases/download/v2.16.1/velo-update.json",
            "/yhzion/velo-android/releases/download/v2.16.1/velo-update.json",
            "not a uri ^",
        )) assertNull(location, ReleaseFeed.latestTag(location))
    }

    @Test fun downloadsFollowRedirectsToTheReleasePagesAndGitHubsAssetStorageOnly() {
        val start = URI("$repo/releases/latest/download/velo-update.json")
        val release = "$repo/releases/download/v2.16.1/velo-update.json"
        assertEquals(URI(release), ReleaseFeed.downloadRedirect(start, release))
        assertEquals(URI(release), ReleaseFeed.downloadRedirect(start, "/yhzion/velo-android/releases/download/v2.16.1/velo-update.json"))
        val asset = "https://release-assets.githubusercontent.com/github-production-release-asset/1470994129/a?sp=r&sig=x"
        assertEquals(URI(asset), ReleaseFeed.downloadRedirect(URI(release), asset))
        for (location in listOf(
            "http://release-assets.githubusercontent.com/a",
            "https://evil.example/a",
            "https://github.com/yhzion/other-android/releases/download/v2.16.1/velo-update.json",
            "https://github.com:8443/yhzion/velo-android/releases/download/v2.16.1/velo-update.json",
            "https://u@objects.githubusercontent.com/a",
            "https://objects.githubusercontent.com/a#x",
            "https://release-assets.githubusercontent.com:8443/a",
            "not a uri ^",
        )) assertThrows(location, IOException::class.java) { ReleaseFeed.downloadRedirect(URI(release), location) }
    }
}
