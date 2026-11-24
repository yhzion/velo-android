package io.github.yhzion.velo.core

import kotlinx.serialization.Serializable

/**
 * The remotes saved on the phone, as sidebar rows, and the locator rules they follow. Pure Kotlin,
 * no Android, so the JVM tests run it directly.
 */

/** A Rex server saved on the phone: [url] is its canonical locator ([usableUrl]): `https://NAME`, `http://NAME:PORT`, `ssh://USER@NAME:PORT` or a socket path. */
@Serializable
data class Remote(val id: String, val label: String, val url: String)

/**
 * An endpoint as a grouping key: lower-case, `https://` added to a bare host, the scheme's
 * default port (`:443`, `:80`) and a trailing `/` removed. A socket path is case-sensitive and
 * stays as it is. Two endpoints of one machine normalize alike whatever spelling the server or
 * the user used.
 */
fun normalizeEndpoint(raw: String): String {
    val trimmed = raw.trim { it in whitespace }
    if (trimmed.startsWith('/')) return trimmed
    val value = trimmed.lowercase()
    if (value.isEmpty()) return ""
    val withScheme = if (value.contains("://")) value else "https://$value"
    val path = withScheme.indexOfAny(charArrayOf('/', '?', '#'), withScheme.indexOf("://") + 3)
    val authority = if (path < 0) withScheme else withScheme.substring(0, path)
    val defaultPort = if (authority.startsWith("http://")) ":80" else ":443"
    val host = if (authority.endsWith(defaultPort)) authority.dropLast(defaultPort.length) else authority
    val tail = if (path < 0) "" else withScheme.substring(path).removeSuffix("/")
    return host + tail
}

private const val maxNameLength = 253
private const val maxIpv6Length = 45
private const val whitespace = " \t\r\n"
/**
 * Besides letters and digits, what an ssh user may hold: the SSH pool's rule. Nothing a shell
 * reads as more than a word, since the login goes into commands the user copies into one.
 */
private const val userMarks = "-._"

/** Why a locator is refused: the reasons of `Endpoint.parse`. */
enum class LocatorProblem { Empty, Whitespace, Password, NoHost, NoUser, NotAbsolute, UnsupportedScheme, Malformed }

/**
 * The verdict on a locator: its canonical [url] when the core would connect to it, otherwise the
 * [problem]. [scheme] (lower-case) is set with [LocatorProblem.UnsupportedScheme] for its sentence;
 * [locator] (trimmed) and [host] (as written, an IPv6 address in brackets) with [LocatorProblem.NoUser].
 */
data class LocatorCheck(val url: String?, val problem: LocatorProblem?, val scheme: String = "", val locator: String = "", val host: String = "")

/** Whether [url] is an ssh login (`ssh://user@host[:port]`): the SSH pool reaches it. */
fun isSshLocator(url: String): Boolean = url.trimStart { it in whitespace }.startsWith("ssh://", ignoreCase = true)

private fun refused(problem: LocatorProblem, scheme: String = "") = LocatorCheck(null, problem, scheme)

private fun isAsciiDigit(ch: Char) = ch in '0'..'9'
private fun isAsciiHex(ch: Char) = isAsciiDigit(ch) || ch in 'a'..'f' || ch in 'A'..'F'
private fun isAsciiAlphanumeric(ch: Char) = isAsciiDigit(ch) || ch in 'a'..'z' || ch in 'A'..'Z'

/**
 * What the core makes of a locator, by the rules of `Endpoint.parse` (`native/src/net/Endpoint.zig`),
 * in the same order: a socket path (`/abs/path`, or `unix:///abs/path`); `https://`, `http://` with
 * a DNS name, an IPv4 address or a bracketed IPv6 address and an optional port 1-65535, a bare
 * host being `https://`; the path, query and fragment of those mean nothing and are dropped;
 * `ssh://USER@HOST[:PORT]` with nothing after it. An ssh locator without a user is
 * [LocatorProblem.NoUser] once its host and port are good (`Core.locate`), and a user or host
 * that starts with `-` (it would read as an ssh option), or a user outside [userMarks], is
 * malformed, as the SSH pool has it. The [LocatorCheck.url] is canonical: the host in lower
 * case, the scheme's default port left out (22 for ssh, the SSH pool's own form of the
 * login), the user as typed, a socket path verbatim.
 */
fun checkLocator(raw: String): LocatorCheck {
    val trimmed = raw.trim { it in whitespace }
    if (trimmed.isEmpty()) return refused(LocatorProblem.Empty)
    if (trimmed[0] == '/') return LocatorCheck(trimmed, null)

    var scheme = "https"
    var rest = trimmed
    val separator = trimmed.indexOf("://")
    if (separator >= 0) {
        scheme = trimmed.substring(0, separator).map { if (it in 'A'..'Z') it + 32 else it }.joinToString("")
        rest = trimmed.substring(separator + 3)
        when (scheme) {
            "https", "http", "ssh" -> Unit
            "unix" -> return if (rest.startsWith('/')) LocatorCheck(rest, null) else refused(LocatorProblem.NotAbsolute)
            else -> return refused(LocatorProblem.UnsupportedScheme, scheme)
        }
    }
    if (trimmed.any { it in whitespace }) return refused(LocatorProblem.Whitespace)
    val ssh = scheme == "ssh"

    var authority = rest
    val end = rest.indexOfAny(charArrayOf('/', '?', '#'))
    if (end >= 0) {
        if (ssh) return refused(LocatorProblem.Malformed)
        authority = rest.substring(0, end)
    }

    var user = ""
    val at = authority.indexOf('@')
    if (at >= 0) {
        val info = authority.substring(0, at)
        if (info.contains(':')) return refused(LocatorProblem.Password)
        if (!ssh) return refused(LocatorProblem.Malformed)
        user = info
        authority = authority.substring(at + 1)
    }

    val host: String
    var portText: String? = null
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return refused(LocatorProblem.Malformed)
        host = authority.substring(1, close)
        val tail = authority.substring(close + 1)
        if (tail.isNotEmpty()) {
            if (tail[0] != ':') return refused(LocatorProblem.Malformed)
            portText = tail.substring(1)
        }
        if (host.isEmpty()) return refused(LocatorProblem.NoHost)
        val v6 = host.length <= maxIpv6Length && host.contains(':') && host.all { isAsciiHex(it) || it == ':' || it == '.' }
        if (!v6) return refused(LocatorProblem.Malformed)
    } else {
        val colon = authority.indexOf(':')
        if (colon >= 0) {
            host = authority.substring(0, colon)
            portText = authority.substring(colon + 1)
        } else {
            host = authority
        }
        if (host.isEmpty()) return refused(LocatorProblem.NoHost)
        val name = host.length <= maxNameLength && host.all { isAsciiAlphanumeric(it) || it == '.' || it == '-' || it == '_' }
        if (!name) return refused(LocatorProblem.Malformed)
    }

    val defaultPort = when (scheme) { "http" -> 80; "ssh" -> 22; else -> 443 }
    val port = if (portText == null) defaultPort else {
        val valid = portText.length in 1..5 && portText.all(::isAsciiDigit)
        val number = if (valid) portText.toInt() else 0
        if (number !in 1..65535) return refused(LocatorProblem.Malformed)
        number
    }
    if (ssh && user.isEmpty()) {
        val written = if (host.contains(':')) "[$host]" else host
        return LocatorCheck(null, LocatorProblem.NoUser, locator = trimmed, host = written)
    }
    if (ssh && (user.startsWith('-') || host.startsWith('-') || !user.all { isAsciiAlphanumeric(it) || it in userMarks })) {
        return refused(LocatorProblem.Malformed)
    }

    val shownHost = if (host.contains(':')) "[${host.lowercase()}]" else host.lowercase()
    val shownPort = if (port == defaultPort) "" else ":$port"
    val login = if (ssh) "$user@" else ""
    return LocatorCheck("$scheme://$login$shownHost$shownPort", null)
}

/**
 * The message for a refused locator, matching `Refusal.format` in `native/src/rex/Core.zig`.
 * Returns null when the locator is usable or empty.
 */
fun locatorSentence(check: LocatorCheck): String? {
    if (check.url != null) return null
    return when (check.problem) {
        null, LocatorProblem.Empty -> null
        LocatorProblem.Whitespace -> "Use spaces only in Unix socket paths, not in server URLs."
        LocatorProblem.Password -> "Remove the password from the server address."
        LocatorProblem.NoHost -> "Enter the hostname or IP address of the server."
        LocatorProblem.NoUser -> "Missing SSH username in ${check.locator}. For example: ssh://user@${check.host}."
        LocatorProblem.NotAbsolute -> "Use a full Unix socket path starting with /, for example /tmp/rex.sock."
        LocatorProblem.UnsupportedScheme -> "Velo doesn't support ${check.scheme}:// server addresses."
        LocatorProblem.Malformed -> "Check the format of the server address."
    }
}

/**
 * The `host:port` an ssh login [url] keeps its host key under, as the SSH pool names it (Go's
 * `net.JoinHostPort`: the port written out, 22 too, an IPv6 address in brackets), from the
 * canonical login ([checkLocator]); null when [url] is not a usable ssh login. It equals the
 * `text` of that login's hostkey question.
 */
fun sshHostPort(url: String): String? {
    val login = checkLocator(url).url?.takeIf(::isSshLocator) ?: return null
    val authority = login.substringAfter('@')
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        val port = authority.substring(close + 1).removePrefix(":").ifEmpty { "22" }
        return authority.substring(0, close + 1) + ":" + port
    }
    return if (authority.contains(':')) authority else "$authority:22"
}

/** The canonical locator the core would connect to for [raw] (see [checkLocator]), or null when it would refuse it. */
fun usableUrl(raw: String): String? = checkLocator(raw).url

/** One machine, one key, however its URL is spelled: what remotes, tokens, sessions and connections are keyed by. */
fun hostKey(url: String): String = usableUrl(url) ?: normalizeEndpoint(url)

/** The remote that already reaches [url], however it is spelled; null when none does. */
fun remoteWithUrl(remotes: List<Remote>, url: String): Remote? {
    val key = hostKey(url)
    return remotes.firstOrNull { hostKey(it.url) == key }
}

/**
 * The sidebar's remote rows: the [saved] remotes in the order saved, with [removed] (the
 * active remote the user removed, kept until they leave it) where it was: after the last
 * saved remote that stood before it ([before], their ids), so removing one of those does not
 * move it. It is left out when a saved remote has its URL (it was saved again).
 */
fun remoteRows(saved: List<Remote>, removed: Remote?, before: Set<String>): List<Remote> {
    if (removed == null || remoteWithUrl(saved, removed.url) != null) return saved
    val index = saved.indexOfLast { it.id in before } + 1
    return saved.take(index) + removed + saved.drop(index)
}

/**
 * A label for a remote saved without one: the machine name (the first part of its DNS name), an
 * IP address as it is, or the file name of a socket path without its extension. An ssh login
 * keeps its user: `alice@studio`.
 */
fun remoteLabel(url: String): String {
    val key = hostKey(url)
    if (key.startsWith('/')) return key.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Remote" }
    val host = key.substringAfter("://").substringBefore('/')
    if (isSshLocator(key) && host.contains('@')) return host.substringBefore('@') + "@" + remoteLabel("https://" + host.substringAfter('@'))
    if (host.startsWith('[')) return host.substringAfter('[').substringBefore(']').ifEmpty { "Remote" }
    val name = host.substringBefore(':')
    val address = name.isNotEmpty() && name.all { it in '0'..'9' || it == '.' }
    return (if (address) name else name.substringBefore('.')).ifEmpty { "Remote" }
}
