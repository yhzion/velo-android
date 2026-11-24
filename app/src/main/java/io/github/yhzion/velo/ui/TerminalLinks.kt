package io.github.yhzion.velo.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yhzion.velo.HostConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The link under a finger, as the core reads it ([HostConnection.linkAt]): an OSC 8 hyperlink a
 * program set, else a URL or a path in the text there. [kind] is `url`, `localhost` (an http URL to
 * the remote's own loopback) or `file` (a path or `file:` URL on the remote). A path's [line] and
 * [col] are 0 when it has none.
 */
@Serializable
internal data class TerminalLink(val kind: String, val target: String, val line: Int = 0, val col: Int = 0) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String?): TerminalLink? =
            if (text.isNullOrEmpty()) null else runCatching { json.decodeFromString<TerminalLink>(text) }.getOrNull()
    }
}

/** What a tap on a link does. */
internal sealed interface LinkTap {
    /** A web or mail link: it opens at once, as on the desktop. */
    data class Open(val uri: String) : LinkTap

    /** A link that launches another app ([scheme], such as ssh): any program can print one, so the app asks first. */
    data class Ask(val uri: String, val scheme: String) : LinkTap

    /** A path or `file:` URL: the app downloads the remote's file and opens the copy ([RemoteFileOpener]). */
    data class File(val target: String) : LinkTap

    /** A web link to the remote's loopback: the app forwards its port to the phone and opens it there. */
    data class Forward(val link: LoopbackLink) : LinkTap
}

/** The schemes that open without a question. Every other scheme launches some other app. */
private val directSchemes = setOf("http", "https", "mailto")

/** What the app does with a tap on [link]; null leaves the tap to the pane (focus, keyboard). */
internal fun linkTap(link: TerminalLink): LinkTap? {
    if (link.kind == "file") return LinkTap.File(link.target)
    if (link.kind == "localhost") return loopbackLink(link.target)?.let(LinkTap::Forward)
    if (link.kind != "url") return null
    val colon = link.target.indexOf(':')
    if (colon <= 0) return null
    val scheme = link.target.substring(0, colon).lowercase()
    return if (scheme in directSchemes) LinkTap.Open(link.target) else LinkTap.Ask(link.target, scheme)
}

/**
 * Lets one tap open a link, then none for [windowMs] after the last tap: the second tap of a
 * double tap would open the link again before the other app has covered Velo.
 */
internal class LinkTapGate(private val windowMs: Long) {
    private var last: Long? = null

    /** True when the tap at [atMs] may open its link. */
    fun take(atMs: Long): Boolean {
        val previous = last
        last = atMs
        return previous == null || atMs - previous >= windowMs
    }
}

/** How much of a link the question shows. */
internal const val LINK_QUESTION_CHARS = 200

/** The question before a link that launches another app opens: it names the link's scheme. */
internal fun linkQuestionTitle(ask: LinkTap.Ask): String = "Open ${ask.scheme} link?"

/** The link (cut after [LINK_QUESTION_CHARS]) and why the app asks. */
internal fun linkQuestionText(ask: LinkTap.Ask): String {
    val shown = if (ask.uri.length > LINK_QUESTION_CHARS) ask.uri.take(LINK_QUESTION_CHARS) + "…" else ask.uri
    return "$shown\n\nA program in the terminal printed this link. It opens another app on this phone."
}

/** A question about [ask], from a pane of [conn], waiting for the person ([linkQuestionTitle]). */
internal class LinkQuestion(val conn: HostConnection, val ask: LinkTap.Ask)

/**
 * Opens [uri] in the app this phone uses for it, as a browser opens a link: only an app that takes
 * links from browsers (`CATEGORY_BROWSABLE`) gets it. A link no such app opens is told on [conn]'s notice.
 */
internal fun openTerminalLink(context: Context, conn: HostConnection, uri: String) {
    val scheme = uri.substringBefore(':').lowercase()
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
        Log.i("velo.link", "opened a $scheme link")
    } catch (_: ActivityNotFoundException) {
        Log.i("velo.link", "no app opens $scheme links")
        conn.error = "No app on this phone opens $scheme links."
    } catch (e: SecurityException) {
        Log.w("velo.link", "the $scheme link was refused", e)
        conn.error = "This phone did not let Velo open the link."
    }
}

/**
 * Where a pane sends a tap on a link it opens ([linkTap]): the workspace opens it, or asks first.
 * The pane's block comes with it, and what the tap does when the link is no file after all.
 */
internal fun interface LinkTaps {
    fun tap(conn: HostConnection, block: String, tap: LinkTap, notAFile: () -> Unit)
}

internal val LocalLinkTaps = staticCompositionLocalOf { LinkTaps { _, _, _, _ -> } }
