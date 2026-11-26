package io.github.yhzion.velo.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * The names a `file:` URL may give the remote at [url]: `localhost`, the remote's host name and
 * its first label (`workstation` of `workstation.example.net`). The API does not tell the machine's own name.
 */
internal fun remoteNames(url: String): Set<String> {
    val host = runCatching { URI(url).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: return setOf("localhost")
    return setOf("localhost", host, host.substringBefore('.'))
}

/**
 * The path on the remote that a file link points at: a `file:` URL's path, decoded, or the
 * link's own text. A `file:` URL that names a machine other than the remote ([names]) is no
 * remote file: an ssh session inside the terminal prints such links for another machine's files.
 */
internal fun remoteFilePath(target: String, names: Set<String>): String? {
    if (target.isEmpty()) return null
    if (!target.startsWith("file:", ignoreCase = true)) return target
    val uri = runCatching { URI(target) }.getOrNull() ?: return null
    // The authority, not `host`: Java leaves `host` null for a name such as `build_box`.
    val host = uri.rawAuthority?.substringAfterLast('@')?.substringBefore(':')?.lowercase().orEmpty()
    if (host.isNotEmpty() && host !in names) return null
    return uri.path?.takeIf { it.isNotEmpty() }
}

/** The core's answer to a download request ([io.github.yhzion.velo.HostConnection.downloadBegin]). */
@Serializable
internal data class RemoteFileAnswer(
    val status: String,
    val ticket: String? = null,
    val path: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val version: String? = null,
    val code: String? = null,
    val message: String? = null,
) {
    /**
     * The link is no file on the remote (none there, or a folder): the tap was an ordinary tap.
     * The server says `not_found` for a missing file and `bad_request` for one that is not a
     * regular file or that it may not read; a server older than `download_begin` says
     * `method_not_found`, and no file opens there either.
     */
    val isNoFile: Boolean get() = status == "error" && code in setOf("not_found", "bad_request", "method_not_found")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String?): RemoteFileAnswer =
            text?.let { runCatching { json.decodeFromString<RemoteFileAnswer>(it) }.getOrNull() } ?: RemoteFileAnswer("error", code = "unreadable")
    }
}

/**
 * A path whose copy can be asked about again (`if_none_match`): it names the same file wherever
 * the terminal's shell is. A relative path names another file in another folder, and `~` the
 * home of whoever the program runs as.
 */
internal fun rememberable(path: String): Boolean = path.startsWith("/")

/** The cache folder of one remote file: the remote's address and the path, hashed. */
internal fun cacheKey(remote: String, path: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$remote\n$path".toByteArray())
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

/** Why a download failed, for the notice: the core's own codes in words, else what the server said. */
internal fun downloadFailure(answer: RemoteFileAnswer): String = when (answer.code) {
    "NotConnected" -> "the remote is not connected"
    "Timeout" -> "the remote did not answer"
    "UnknownBlock" -> "the pane is gone"
    "unreadable", "invalid" -> "the app could not read the server's answer"
    else -> answer.message?.takeIf { it.isNotBlank() } ?: "the server did not say why"
}

/**
 * The most a file of the remote may have: the core ends a larger download (`max_file_bytes` in
 * native/src/rex/Download.zig, which must say the same). It is the size of the app's cache of
 * opened files, so a larger one could not be kept anyway.
 */
internal const val MAX_FILE_BYTES = 256L * 1024 * 1024

/** What [io.github.yhzion.velo.core.VeloCore.downloadFetch] returns instead of a size. */
internal const val FETCH_STOPPED = -1L
internal const val FETCH_FAILED = -2L
internal const val FETCH_TOO_LARGE = -3L

/** Why a fetch that returned the negative [result] left no whole file, for the notice. */
internal fun fetchFailure(result: Long): String =
    if (result == FETCH_TOO_LARGE) "it is larger than Velo opens (${MAX_FILE_BYTES / (1024 * 1024)} MB)"
    else "the download ended before the whole file arrived"

/** The longest name a copy gets. */
private const val NAME_CHARS = 120

/** The server's name for a file, as a name this phone can give a file. A long one keeps its extension. */
internal fun safeFileName(name: String?): String {
    val cleaned = name.orEmpty().replace(Regex("[/\\\\\u0000]"), "_").trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return "file"
    if (cleaned.length <= NAME_CHARS) return cleaned
    val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length < 16 }
        ?: return cleaned.take(NAME_CHARS)
    return cleaned.take(NAME_CHARS - ext.length - 1) + "." + ext
}

/** Extensions of text that Android knows no type for. */
private val textExtensions = setOf(
    "kt", "kts", "java", "py", "rb", "go", "rs", "zig", "c", "h", "cc", "cpp", "hpp", "swift", "ts", "tsx",
    "jsx", "sh", "bash", "zsh", "fish", "toml", "yaml", "yml", "ini", "conf", "cfg", "env", "lock", "log",
    "gradle", "properties", "sql", "lua", "vim", "diff", "patch", "md", "markdown", "rst", "txt",
)

/**
 * The type a viewer gets for the file [name]: "text/plain" for a known text extension, which
 * Android may map to something else (`.ts` to a video), then [mapped] (Android's type for the
 * extension), then what its first bytes ([head]) read as.
 */
internal fun fileType(name: String, mapped: String?, head: () -> ByteArray): String {
    if (name.substringAfterLast('.', "").lowercase() in textExtensions) return "text/plain"
    return mapped ?: textTypeFor(name, head()) ?: "application/octet-stream"
}

/**
 * "text/plain" for a file that reads as text: a known text extension, or a head without NUL that
 * decodes as UTF-8. Null when it looks like anything else, whose type the phone must tell.
 */
internal fun textTypeFor(name: String, head: ByteArray): String? {
    if (name.substringAfterLast('.', "").lowercase() in textExtensions) return "text/plain"
    if (head.isEmpty() || head.any { it == 0.toByte() }) return null
    // The head may end inside a character: up to 3 bytes at its end may be cut off.
    val whole = decodes(head, head.size) || (head.size > 3 && (1..3).any { decodes(head, head.size - it) })
    return if (whole) "text/plain" else null
}

private fun decodes(bytes: ByteArray, length: Int): Boolean = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, length))
    true
} catch (_: CharacterCodingException) {
    false
}

/** An app package: opening it starts installing it, so the app asks first, as the desktop does for code. */
internal fun asksBeforeOpening(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("apk", "apks", "xapk", "apkm")
