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
 * [col] are 0 when it has none. [set] says a program set the link, and [shown] that the text on
 * its cells is its target; a link read from the text always shows it.
 */
@Serializable
internal data class TerminalLink(
    val kind: String,
    val target: String,
    val line: Int = 0,
    val col: Int = 0,
    val set: Boolean = false,
    val shown: Boolean = true,
) {
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

    /**
     * A hyperlink a program set under text that is not its target, also under blank cells: the
     * person could not read where it goes, so the app shows the target and asks before [then]
     * (an [Open] or a [File]).
     */
    data class Hidden(val then: LinkTap) : LinkTap

    /** The file this tap opens, also after its question. */
    val file: File? get() = when (this) {
        is File -> this
        is Hidden -> then as? File
        else -> null
    }
}

/** The schemes that open without a question. Every other scheme launches some other app. */
private val directSchemes = setOf("http", "https", "mailto")

/** What the app does with a tap on [link]; null leaves the tap to the pane (focus, keyboard). */
internal fun linkTap(link: TerminalLink): LinkTap? {
    val tap = shownLinkTap(link)
    // [LinkTap.Ask] shows the target in its own question.
    return if (link.set && !link.shown && (tap is LinkTap.Open || tap is LinkTap.File)) LinkTap.Hidden(tap) else tap
}

/** What a tap on [link] does when the person can read its target. */
private fun shownLinkTap(link: TerminalLink): LinkTap? {
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
internal fun linkQuestionText(ask: LinkTap.Ask): String =
    "${cutForQuestion(ask.uri)}\n\nA program in the terminal printed this link. It opens another app on this phone."

private fun cutForQuestion(target: String): String =
    if (target.length > LINK_QUESTION_CHARS) target.take(LINK_QUESTION_CHARS) + "…" else target

private const val HIDDEN_LINK_WHY = "A program in the terminal printed this link under other text."

/** The question before a web or mail link opens that a program set under other text ([LinkTap.Hidden]). */
internal fun hiddenLinkTitle(open: LinkTap.Open): String = "Open ${open.uri.substringBefore(':').lowercase()} link?"

/** The link's target (cut after [LINK_QUESTION_CHARS]), which the terminal did not show, and where it opens. */
internal fun hiddenLinkText(open: LinkTap.Open): String {
    val web = open.uri.substringBefore(':').lowercase().startsWith("http")
    return "${cutForQuestion(open.uri)}\n\n$HIDDEN_LINK_WHY ${if (web) "It opens in the browser." else "It opens another app on this phone."}"
}

/** The question before a file opens whose link a program set under other text. */
internal const val HIDDEN_FILE_TITLE = "Open file?"

/** The file's [path] on the remote named [remote] (cut after [LINK_QUESTION_CHARS]), and what Open does. */
internal fun hiddenFileText(path: String, remote: String): String =
    "${cutForQuestion(path)}\n\n$HIDDEN_LINK_WHY Velo downloads the file from $remote and opens it on this phone."

/**
 * A question about a link from a pane of [conn], waiting for the person: [title] and [text] as
 * [linkQuestionTitle] and its kin word them, and [open], which runs when the person says Open.
 */
internal class LinkQuestion(val conn: HostConnection, val title: String, val text: String, val open: () -> Unit)

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
