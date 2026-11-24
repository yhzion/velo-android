package io.github.yhzion.velo.updates

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class UpdateChannel { Stable, Preview, Edge }

data class AppRelease(
    val tag: String,
    val versionCode: Long,
    val channel: UpdateChannel,
    val apkUrl: String,
    val sha256: String,
    val notesUrl: String,
)

object ReleaseFeed {
    private const val REPO_PATH = "yhzion/velo-android"
    private const val REPOSITORY = "https://github.com/$REPO_PATH"
    private const val API = "https://api.github.com/repos/$REPO_PATH/releases"
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 32
    private const val MAX_PAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_CODE = 2_100_000_000L
    private const val LAST_LEGACY_CODE = 2_005_003L
    private val tagPattern = Regex(
        """v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})(?:-(preview|edge)\.[1-9][0-9]{0,8})?""",
    )
    private val marker = Regex("""<!-- android-version-code: ([1-9][0-9]{0,9}) -->""")
    private val digestPattern = Regex("""sha256:([0-9a-fA-F]{64})""")

    /** Blocking I/O. Null means a complete feed contained no eligible update, not a failed check. */
    fun latest(channel: UpdateChannel, installedCode: Long): AppRelease? = latest(channel, installedCode, ::readPage)

    internal fun latest(channel: UpdateChannel, installedCode: Long, readPage: (Int) -> String): AppRelease? {
        var best: AppRelease? = null
        for (page in 1..MAX_PAGES) {
            val text = readPage(page)
            val entries = try {
                Json.parseToJsonElement(text) as? JsonArray ?: throw IOException("Expected a release list.")
            } catch (e: SerializationException) {
                throw IOException("Invalid release list.", e)
            }
            if (entries.size > PER_PAGE) throw IOException("Too many releases on one page.")
            for (entry in entries) {
                val release = parse(entry as? JsonObject ?: continue, channel, best?.versionCode ?: installedCode)
                if (release != null) best = release
            }
            if (entries.size < PER_PAGE) return best
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
            val line = body.lineSequence().filter { "<!-- android-version-code" in it }.singleOrNull() ?: return null
            marker.matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 3_000_001..MAX_CODE } ?: return null
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
        return AppRelease(tag, versionCode, channel, apkUrl, digest.groupValues[1].lowercase(), notesUrl)
    }

    private fun JsonArray.uploaded(name: String, url: String): JsonObject? {
        val asset = singleOrNull { (it as? JsonObject)?.text("name") == name } as? JsonObject ?: return null
        val size = asset["size"] as? JsonPrimitive ?: return null
        return asset.takeIf { !size.isString && (size.longOrNull ?: 0) > 0 &&
            it.text("state") == "uploaded" && it.text("browser_download_url") == url }
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun readPage(page: Int): String {
        val connection = URL("$API?per_page=$PER_PAGE&page=$page").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Velo-Update-Check")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Could not fetch the release list.")
            if (connection.contentLengthLong > MAX_PAGE_BYTES) throw IOException("Release page exceeds the size limit.")
            return connection.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > MAX_PAGE_BYTES - bytes.size()) throw IOException("Release page exceeds the size limit.")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }
}
