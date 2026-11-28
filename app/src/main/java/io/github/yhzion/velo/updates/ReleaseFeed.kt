package io.github.yhzion.velo.updates

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** GitHub refused the API request: its hourly limit for this network is used up until [resetAt] (epoch ms). */
class RateLimitedException(val resetAt: Long) : IOException("GitHub's request limit is reached.")

/**
 * What checks learn of GitHub's API limit for this network, told for each answer the API gives (#264, #274):
 * [blockedUntil] (epoch ms) when it refused a request or answered the last one it allows until then, else 0.
 */
fun interface ApiLimit {
    fun seen(blockedUntil: Long)
}

/** A page of the API's release list, and until when its headers say no request is left (0: some are). */
internal class ApiPage(val text: String, val usedUpUntil: Long = 0L)

/** Where the feed reads from; [ReleaseFeed]'s network by default, a fake in tests. */
internal interface FeedSource {
    /** Page [page] (from 1) of the API's release list. */
    fun page(page: Int): ApiPage

    /** The latest release's update manifest, and the tag the download redirect named as the latest. */
    fun manifest(): Pair<String, String>

    /** The text of release [tag]'s asset [name]. */
    fun asset(tag: String, name: String): String
}

enum class UpdateChannel { Stable, Preview, Edge }

data class AppRelease(
    val tag: String,
    val versionCode: Long,
    val channel: UpdateChannel,
    val apkUrl: String,
    val sha256: String,
    val notesUrl: String,
    /** What the release body's change-notes block says ([changeNotes]); empty without one. */
    val changes: List<ChangeSection> = emptyList(),
)

object ReleaseFeed {
    private const val REPO_PATH = "yhzion/velo-android"
    private const val REPOSITORY = "https://github.com/$REPO_PATH"
    private const val API = "https://api.github.com/repos/$REPO_PATH/releases"
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 32
    private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    private const val MANIFEST = "velo-update.json"
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_CHECKSUM_BYTES = 1024
    private const val MAX_REDIRECTS = 4
    private val RATE_LIMIT_WAIT = 60_000L..60 * 60_000L
    private const val MAX_CODE = 2_100_000_000L
    private const val LAST_LEGACY_CODE = 2_005_003L
    private val tagPattern = Regex(
        """v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})(?:-(preview|edge)\.[1-9][0-9]{0,8})?""",
    )
    private val marker = Regex("""<!-- android-version-code: ([1-9][0-9]{0,9}) -->""")
    private val digestPattern = Regex("""sha256:([0-9a-fA-F]{64})""")
    private val sha256Pattern = Regex("""[0-9a-fA-F]{64}""")
    private val checksumLine = Regex("""([0-9a-f]{64})  (\S+)\n?""")
    private val latestPath = Regex(Regex.escape("/$REPO_PATH/releases/download/") + "([^/]+)" + Regex.escape("/$MANIFEST"))

    /**
     * Blocking I/O. Eligible releases newer than [installedCode], newest first; the first of them is
     * the update. Every one when the API answered; on the stable channel, the manifest's release alone
     * when the API failed or saw no newer one ([check]). Empty means no eligible update, not a failed check.
     * [limit] is told what each of the API's answers says of its limit.
     */
    fun newer(channel: UpdateChannel, installedCode: Long, limit: ApiLimit): List<AppRelease> =
        check(channel, installedCode, everyRelease = true, apiBlockedUntil = 0L, source, limit = limit)

    /**
     * Blocking I/O. The update, if any, for an automatic check: a stable one from the update manifest
     * alone, which is outside the API's limit. While [apiBlockedUntil] is ahead the API is not asked.
     */
    fun update(channel: UpdateChannel, installedCode: Long, apiBlockedUntil: Long, limit: ApiLimit): AppRelease? =
        check(channel, installedCode, everyRelease = false, apiBlockedUntil, source, limit = limit).firstOrNull()

    /**
     * Stable checks read the latest release's update manifest first: a release download, so not under
     * the API's 60-an-hour limit that every device behind one router shares. Any doubt about it, or
     * another channel, asks the API, unless a limit it reported ([apiBlockedUntil]) has not passed.
     * [everyRelease] also asks the API for every newer release's notes when the manifest found one,
     * and keeps the manifest's release if the API fails or disagrees. [limit] is told of each answer
     * of the API as it comes (#264, #274): a page ends a limit stored before, perhaps on another network,
     * unless its headers say it was the last request allowed; a refusal stores one, also when the
     * manifest's release is kept despite it. A refusal stored while the API was not asked is rethrown
     * with that same [apiBlockedUntil], and [limit] is not told of it.
     */
    internal fun check(
        channel: UpdateChannel, installedCode: Long, everyRelease: Boolean, apiBlockedUntil: Long, source: FeedSource,
        now: Long = System.currentTimeMillis(), limit: ApiLimit = ApiLimit {},
    ): List<AppRelease> {
        val blocked = apiBlockedUntil > now && apiBlockedUntil - now <= RATE_LIMIT_WAIT.last
        val readPage = { page: Int ->
            val answer = try {
                source.page(page)
            } catch (refused: RateLimitedException) {
                limit.seen(refused.resetAt)
                throw refused
            }
            limit.seen(answer.usedUpUntil)
            answer.text
        }
        if (channel == UpdateChannel.Stable) {
            val found = try {
                listOfNotNull(fromManifest(installedCode, source))
            } catch (e: IOException) {
                null // No manifest yet (releases before it), or one that does not hold up: the API decides.
            }
            if (found != null) {
                if (found.isEmpty() || !everyRelease || blocked) return found
                val all = try { fromApi(channel, installedCode, readPage) } catch (e: IOException) { null }
                // The API's list, checked in full, when it has the manifest's release with the same digest or a newer one.
                val first = all?.firstOrNull() ?: return found
                val agrees = first.versionCode > found[0].versionCode ||
                    first.versionCode == found[0].versionCode && first.sha256 == found[0].sha256
                return if (agrees) all else found
            }
        }
        if (blocked) throw RateLimitedException(apiBlockedUntil)
        return fromApi(channel, installedCode, readPage)
    }

    internal fun latest(channel: UpdateChannel, installedCode: Long, readPage: (Int) -> String): AppRelease? =
        fromApi(channel, installedCode, readPage).firstOrNull()

    internal fun fromApi(channel: UpdateChannel, installedCode: Long, readPage: (Int) -> String): List<AppRelease> {
        val found = mutableListOf<AppRelease>()
        for (page in 1..MAX_PAGES) {
            val text = readPage(page)
            val entries = try {
                Json.parseToJsonElement(text) as? JsonArray ?: throw IOException("Expected a release list.")
            } catch (e: SerializationException) {
                throw IOException("Invalid release list.", e)
            }
            if (entries.size > PER_PAGE) throw IOException("Too many releases on one page.")
            for (entry in entries) {
                found += parse(entry as? JsonObject ?: continue, channel, installedCode) ?: continue
            }
            // A stable sort: of releases with one version code, the feed's first stays, as it always won.
            if (entries.size < PER_PAGE) return found.sortedByDescending { it.versionCode }.distinctBy { it.versionCode }
        }
        throw IOException("Release list exceeds the page limit.")
    }

    /** The canonical download URL of a valid release tag in this repository. */
    internal fun apkUrl(tag: String): String? =
        if (tagPattern.matches(tag)) downloadUrl(tag, apkName(tag)) else null

    private fun downloadUrl(tag: String, name: String) = "$REPOSITORY/releases/download/$tag/$name"

    private fun apkName(tag: String) = "velo-$tag-arm64-v8a.apk"

    private fun parse(entry: JsonObject, selected: UpdateChannel, minimumCode: Long): AppRelease? {
        if (entry["draft"] != JsonPrimitive(false)) return null
        val tag = entry.text("tag_name") ?: return null
        val (major, minor, patch, suffix) = tagPattern.matchEntire(tag)?.destructured ?: return null
        val channel = when (suffix) {
            "preview" -> UpdateChannel.Preview
            "edge" -> UpdateChannel.Edge
            else -> UpdateChannel.Stable
        }
        if (channel > selected || entry["prerelease"] != JsonPrimitive(channel != UpdateChannel.Stable)) return null
        val baseCode = major.toLong() * 1_000_000 + minor.toInt() * 1_000 + patch.toInt()
        if (baseCode !in 1..MAX_CODE) return null
        val body = entry.text("body") ?: return null
        val versionCode = if (baseCode <= LAST_LEGACY_CODE) {
            // Only these six published stable releases predate the CI marker. A tag alone isn't a release.
            val published = baseCode in 2_004_002..2_004_003 || baseCode in 2_005_000..LAST_LEGACY_CODE
            if (!published || channel != UpdateChannel.Stable || "<!-- android-version-code" in body) return null
            baseCode
        } else {
            modernCode(body) ?: return null
        }
        if (versionCode <= minimumCode) return null

        val notesUrl = "$REPOSITORY/releases/tag/$tag"
        if (entry.text("html_url") != notesUrl) return null
        val assets = entry["assets"] as? JsonArray ?: return null
        val name = apkName(tag)
        val apkUrl = downloadUrl(tag, name)
        val apk = assets.uploaded(name, apkUrl) ?: return null
        if (assets.uploaded("$name.sha256", "$apkUrl.sha256") == null) return null
        val digest = digestPattern.matchEntire(apk.text("digest") ?: return null) ?: return null
        return AppRelease(tag, versionCode, channel, apkUrl, digest.groupValues[1].lowercase(), notesUrl, changeNotes(body))
    }

    /** The one version-code marker line of a release body, for a release after the legacy ones. */
    private fun modernCode(body: String): Long? {
        val line = body.lineSequence().filter { "<!-- android-version-code" in it }.singleOrNull() ?: return null
        return marker.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 3_000_001..MAX_CODE }
    }

    /**
     * The latest release by its update manifest (made by release.yml): null when it is not newer than
     * [installedCode]. Throws when the manifest is missing or does not hold up: it must name the
     * release the redirect named, a stable tag, the canonical APK, and the digest in the release's own
     * `.sha256` asset. The installer still checks the downloaded APK against that digest.
     */
    private fun fromManifest(installedCode: Long, source: FeedSource): AppRelease? {
        val (latestTag, text) = source.manifest()
        val manifest = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw IOException("Invalid update manifest.")
        fun invalid(): Nothing = throw IOException("Invalid update manifest.")
        val tag = manifest.text("tag")?.takeIf { it == latestTag } ?: invalid()
        // The latest release is never a prerelease.
        val (_, _, _, suffix) = tagPattern.matchEntire(tag)?.destructured ?: invalid()
        if (suffix.isNotEmpty()) invalid()
        val code = (manifest["versionCode"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: invalid()
        val notes = manifest.text("notes") ?: invalid()
        if (modernCode(notes) != code) invalid()
        if (code <= installedCode) return null
        val name = apkName(tag)
        if (manifest.text("apk") != name) invalid()
        val sha256 = manifest.text("sha256")?.takeIf { sha256Pattern.matches(it) }?.lowercase() ?: invalid()
        val listed = checksumLine.matchEntire(source.asset(tag, "$name.sha256"))?.destructured
        if (listed == null || listed.component1() != sha256 || listed.component2() != name) {
            throw IOException("The update manifest's digest differs from the release's checksum.")
        }
        return AppRelease(tag, code, UpdateChannel.Stable, downloadUrl(tag, name), sha256,
            "$REPOSITORY/releases/tag/$tag", changeNotes(notes))
    }

    /** The tag of the release a `releases/latest/download/` redirect to [location] stands for, or null. */
    internal fun latestTag(location: String): String? {
        val uri = try { URI(location) } catch (e: java.net.URISyntaxException) { return null }
        if (uri.scheme != "https" || uri.host != "github.com" || uri.rawUserInfo != null || uri.port != -1) return null
        return uri.rawPath?.let(latestPath::matchEntire)?.groupValues?.get(1)
    }

    /**
     * Where a download redirect from [from] to [location] leads: the repository's own release pages on
     * github.com, or GitHub's asset storage under the installer's rules ([assetRedirect]).
     */
    internal fun downloadRedirect(from: URI, location: String): URI = try {
        val next = from.resolve(location)
        if (next.host == "github.com" && next.scheme == "https" && next.rawUserInfo == null && next.port == -1 &&
            next.rawFragment == null && next.rawPath.orEmpty().startsWith("/$REPO_PATH/releases/download/")) next
        else assetRedirect(from, location)
    } catch (e: IllegalArgumentException) {
        throw IOException("Unexpected download redirect.", e)
    }

    /**
     * The limit a 403 or 429 reports, or null for another refusal. The wait is the reset time less
     * the server's own clock, so a phone clock that is off does not stretch or cut it; at least a
     * minute, at most an hour.
     */
    internal fun rateLimit(status: Int, remaining: String?, reset: String?, serverDate: Long, now: Long): RateLimitedException? {
        if (status != 403 && status != 429) return null
        return usedUpUntil(remaining, reset, serverDate, now).takeIf { it > 0 }?.let(::RateLimitedException)
    }

    /**
     * Until when the API allows no more requests, by an answer's headers, or 0 when [remaining] is not 0: a 200
     * can carry the hour's last request (#274), and is then a limit as a refusal is ([rateLimit]).
     */
    internal fun usedUpUntil(remaining: String?, reset: String?, serverDate: Long, now: Long): Long {
        if (remaining?.trim() != "0") return 0L
        val resetAt = reset?.trim()?.toLongOrNull()?.times(1000)
        val wait = if (resetAt == null) RATE_LIMIT_WAIT.last else resetAt - (if (serverDate > 0) serverDate else now)
        return now + wait.coerceIn(RATE_LIMIT_WAIT)
    }

    private fun JsonArray.uploaded(name: String, url: String): JsonObject? {
        val asset = singleOrNull { (it as? JsonObject)?.text("name") == name } as? JsonObject ?: return null
        val size = asset["size"] as? JsonPrimitive ?: return null
        return asset.takeIf { !size.isString && (size.longOrNull ?: 0) > 0 &&
            it.text("state") == "uploaded" && it.text("browser_download_url") == url }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Where [newer] and [update] read from: GitHub, or a fake in the tests of their callers (#289). */
    @Volatile internal var source: FeedSource = network()

    /**
     * GitHub, read through the connections [open] makes (a fake in tests: #289). A limit an answer's
     * headers report counts from [clock]'s time.
     */
    internal fun network(
        open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
        clock: () -> Long = System::currentTimeMillis,
    ): FeedSource = Network(open, clock)

    private class Network(private val open: (URI) -> HttpURLConnection, private val clock: () -> Long) : FeedSource {
        override fun page(page: Int) = fetch("$API?per_page=$PER_PAGE&page=$page", MAX_PAGE_BYTES, api = true)
            .let { ApiPage(it.text, it.usedUpUntil) }

        override fun manifest(): Pair<String, String> {
            val fetched = fetch("$REPOSITORY/releases/latest/download/$MANIFEST", MAX_MANIFEST_BYTES, api = false)
            // The first redirect names the release "latest" stands for.
            val tag = fetched.via.firstOrNull()?.let(::latestTag) ?: throw IOException("The latest release was not named.")
            return tag to fetched.text
        }

        override fun asset(tag: String, name: String) = fetch(downloadUrl(tag, name), MAX_CHECKSUM_BYTES, api = false).text

        /** What [fetch] read: the text, the redirects followed to it, and for the API until when no request is left (0: some are). */
        private class Fetched(val text: String, val via: List<String>, val usedUpUntil: Long)

        /**
         * The text at [address] and the redirects followed to it. The API is read without redirects; a
         * download follows a few, as [downloadRedirect] allows.
         */
        private fun fetch(address: String, maxBytes: Int, api: Boolean): Fetched {
            var uri = URI(address)
            val via = mutableListOf<String>()
            for (hop in 0..MAX_REDIRECTS) {
                val connection = open(uri)
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "Velo-Update-Check")
                    if (api) {
                        connection.setRequestProperty("Accept", "application/vnd.github+json")
                        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    }
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_OK) {
                        val usedUp = if (!api) 0L else usedUpUntil(connection.getHeaderField("X-RateLimit-Remaining"),
                            connection.getHeaderField("X-RateLimit-Reset"), connection.date, clock())
                        return Fetched(read(connection, maxBytes), via, usedUp)
                    }
                    if (!api && status in setOf(301, 302, 303, 307, 308)) {
                        val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without a location.")
                        uri = downloadRedirect(uri, location)
                        via += uri.toString()
                        continue
                    }
                    if (api) {
                        rateLimit(status, connection.getHeaderField("X-RateLimit-Remaining"),
                            connection.getHeaderField("X-RateLimit-Reset"), connection.date, clock())
                            ?.let { throw it }
                        throw IOException("Could not fetch the release list.")
                    }
                    throw IOException("Could not fetch the update manifest.")
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Too many download redirects.")
        }

        private fun read(connection: HttpURLConnection, maxBytes: Int): String {
            if (connection.contentLengthLong > maxBytes) throw IOException("Response exceeds the size limit.")
            return connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > maxBytes - bytes.size()) throw IOException("Response exceeds the size limit.")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
        }
    }
}
