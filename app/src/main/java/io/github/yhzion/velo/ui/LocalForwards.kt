package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.ForwardInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URISyntaxException

/**
 * A web link to the remote's own loopback, such as the `http://localhost:5173/` a development
 * server prints (#226). The remote's tunnel block connects to [remoteHost]:[port], and the phone
 * opens the link at the port it listens on ([onPhone]). [host] is the link's, with an IPv6
 * address in brackets; [rest] is its path, query and fragment as written.
 */
internal data class LoopbackLink(val scheme: String, val host: String, val port: Int, val rest: String) {
    val remoteHost: String get() = host.removePrefix("[").removeSuffix("]")

    /** The link as the person reads it, for messages. */
    val label: String get() = "$host:$port"

    /** The whole link, with its port written out, for the question that shows where it goes. */
    val url: String get() = "$scheme://$host:$port$rest"

    /**
     * The link as the phone opens it: the forward listens on the phone's `127.0.0.1:`[localPort].
     * A `localhost` name stays, so the page's origin, and the cookies and OAuth callbacks tied to
     * it, are the remote's when the port is the same.
     */
    fun onPhone(localPort: Int): String = "$scheme://${phoneHost()}:$localPort$rest"

    private fun phoneHost(): String = when {
        host == "localhost" || host.endsWith(".localhost") -> host
        host.startsWith("127.") -> "127.0.0.1"
        // [::1] and 0.0.0.0: the phone listens on IPv4 only.
        else -> "localhost"
    }
}

/** [url] as a [LoopbackLink]; null for another host, a scheme other than http(s), or no port. */
internal fun loopbackLink(url: String): LoopbackLink? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    val schemePort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> return null
    }
    val host = uri.host?.lowercase() ?: return null
    if (!isLoopbackHost(host)) return null
    val port = if (uri.port == -1) schemePort else uri.port
    if (port !in 1..65535) return null
    val rest = buildString {
        append(uri.rawPath.orEmpty())
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }
    return LoopbackLink(scheme, host, port, rest)
}

/** The hosts the core reads as the remote's own loopback (`isLoopbackHost` in `term/Links.zig`). */
private fun isLoopbackHost(host: String): Boolean =
    host == "localhost" || host == "[::1]" || host == "0.0.0.0" ||
        (host.length > ".localhost".length && host.endsWith(".localhost")) ||
        (host.startsWith("127.") && host.all { it.isDigit() || it == '.' })

/** The core's answer to `forwardOpen`: the phone's [port], or why there is no forward. */
@Serializable
internal data class ForwardAnswer(val status: String = "error", val port: Int = 0, val code: String = "", val message: String = "") {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String?): ForwardAnswer =
            text?.let { runCatching { json.decodeFromString<ForwardAnswer>(it) }.getOrNull() } ?: ForwardAnswer(code = "unreadable")
    }
}

/** What the notice says when [answer] made no forward for [link]; null when it made one. */
internal fun forwardFailure(answer: ForwardAnswer, link: LoopbackLink): String? = when {
    answer.status == "ok" && answer.port in 1..65535 -> null
    answer.code == "NotConnected" -> "The remote is not connected."
    answer.message.isNotEmpty() -> answer.message
    else -> "Velo could not forward ${link.label} (${answer.code.ifEmpty { "error" }})."
}

/**
 * True when [forwards], the remote's forwards the core reports, has the one a tap on [link] would
 * use: the core keeps one forward per host and port as the link writes them (`findForward`).
 */
internal fun hasForward(forwards: List<ForwardInfo>, link: LoopbackLink): Boolean =
    forwards.any { it.host == link.remoteHost && it.port == link.port }

/** The question before a tap on [link] makes a new forward: it names the port. */
internal fun forwardQuestionTitle(link: LoopbackLink): String = "Forward port ${link.port}?"

/**
 * The link (cut after [LINK_QUESTION_CHARS]) and what a forward of its port of the remote named
 * [remote] means: any program can print such a link, and any app on the phone can use the forward.
 */
internal fun forwardQuestionText(link: LoopbackLink, remote: String): String =
    "${cutForQuestion(link.url)}\n\nA program in the terminal printed this link. To open it, Velo forwards port ${link.port} of $remote to this phone. " +
        "While the forward is open, apps on this phone can reach that port of $remote. It stays open until you stop it in Settings → Remotes."

/**
 * What a tap on the loopback link [link] does on a remote named [remote] with [forwards]. A new
 * forward asks first, and so does a link the terminal did not show ([hidden]) whose forward
 * exists: the question comes back, and its Open runs [open]. Otherwise [open] runs at once and
 * null comes back. One question is enough for a hidden link: the forward's shows the link too.
 */
internal fun forwardTap(link: LoopbackLink, hidden: Boolean, forwards: List<ForwardInfo>, remote: String, open: () -> Unit): LinkQuestion? {
    if (!hasForward(forwards, link)) return LinkQuestion(forwardQuestionTitle(link), forwardQuestionText(link, remote), confirm = "Forward", open = open)
    if (hidden) return LinkTap.Open(link.url).let { LinkQuestion(hiddenLinkTitle(it), hiddenLinkText(it), open = open) }
    open()
    return null
}

/** The line under a forward in the remote's list: where the phone reaches it, and what it relays. */
internal fun forwardDetail(forward: ForwardInfo): String {
    val at = "On this phone at 127.0.0.1:${forward.localPort}"
    return when (forward.connections) {
        0 -> at
        1 -> "$at · 1 connection"
        else -> "$at · ${forward.connections} connections"
    }
}
