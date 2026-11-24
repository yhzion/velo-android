package io.github.yhzion.velo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.yhzion.velo.core.isSshLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an SSH login needs from the phone's stores or the user (rexssh.Question in
 * `native/tailscale/rexssh/questions.go`, the pool's JSON field for field):
 * - `hostkey`: [text] is the `host:port` the key is remembered under; [keyType] and
 *   [fingerprint] (`SHA256:…`) describe the key. Answered "trust" or "changed".
 * - `password`: [text] identifies the SSH username and host; answered with the password.
 * - `prompt`: a keyboard-interactive question; [echo] says whether the answer may be shown.
 *
 * [target] is the login, `ssh://user@host[:port]`: a saved ssh remote's URL. [retry] marks a
 * password or prompt asked again because the host refused the earlier answer in this login. A
 * password question carries it too when a login before ended with the refusal of a password that
 * no question told of (#292): the host hung up with its refusal (sshd's MaxAuthTries), closed
 * right after it, or refused the last try. The pool withdraws a retry question whose connection
 * ended. [afterStall] (`after_stall`, left out when false) marks every password and prompt of a
 * login that follows one whose answer the host left unanswered (a slow PAM, #284), or two that
 * the host closed after the password without a reply (#292): the saved password may be what it
 * never answered, so the user answers this login. With both marks the answer kept for the last
 * login goes and the saved password stays.
 */
@Serializable
data class SshQuestion(
    val id: Long,
    val kind: String,
    val target: String,
    val text: String,
    val keyType: String = "",
    val fingerprint: String = "",
    val echo: Boolean = false,
    val retry: Boolean = false,
    @SerialName("after_stall") val afterStall: Boolean = false,
)

/** How a question is answered without the user: [Answer] it, [Decline] it, or [AskUser]. */
sealed interface AutoAnswer {
    data class Answer(val text: String) : AutoAnswer {
        // It may be a password: never in a log.
        override fun toString() = "Answer(…)"
    }
    data object Decline : AutoAnswer
    data object AskUser : AutoAnswer
}

/** A host key answer: accept the key and remember it. */
const val SSH_TRUST = "trust"
/** A host key answer: the phone knows another key for this host:port, so the login fails as host_key_changed and asks nothing else. */
const val SSH_CHANGED = "changed"

/**
 * The answer the phone's stores give [q]: [hostKeys] by `host:port` (`TYPE SHA256:…`) and
 * [passwords] by login. A known host key that is the same is trusted, another one is "changed"
 * (no question to the user), and so is any key while the phone keeps two different ones for the
 * host ([SshSecrets.HOST_KEYS_DIFFER]); an unknown one is the user's. A saved password answers, unless
 * the host refused it ([SshQuestion.retry]: in this login, or at the end of the one before): then
 * it is the user's again, and the caller forgets it. In a login after a stalled answer ([SshQuestion.afterStall]) it never
 * answers: the user does, and a refusal there refused the user's answer, so the caller keeps the
 * saved one (ruling R4). Prompts are always the user's; a kind this app does not know is declined.
 */
fun autoAnswer(q: SshQuestion, hostKeys: Map<String, String>, passwords: Map<String, String>): AutoAnswer = when (q.kind) {
    "hostkey" -> when (hostKeys[q.text]) {
        null -> AutoAnswer.AskUser
        "${q.keyType} ${q.fingerprint}" -> AutoAnswer.Answer(SSH_TRUST)
        else -> AutoAnswer.Answer(SSH_CHANGED)
    }
    "password" -> passwords[q.target]?.takeIf { !q.retry && !q.afterStall }?.let { AutoAnswer.Answer(it) } ?: AutoAnswer.AskUser
    "prompt" -> AutoAnswer.AskUser
    else -> AutoAnswer.Decline
}

private val questionJson = Json { ignoreUnknownKeys = true }

/** The pool's questions from its JSON; null when there is none to read (no pool yet) or it cannot be read. */
internal fun parseQuestions(json: String?): List<SshQuestion>? {
    if (json == null) return null
    return try {
        questionJson.decodeFromString<List<SshQuestion>>(json)
    } catch (_: IllegalArgumentException) { // SerializationException is one
        null
    }
}

/** The pool's side of the questions: [NativeSshPool] in the app, a fake in the JVM tests. */
interface SshPool {
    fun questions(): String?
    fun answer(id: Long, answer: String, ok: Boolean): Boolean
    fun installKey(target: String, publicLine: String, timeoutMs: Int): Int
}

/**
 * A saved remote as the question poll sees it: its [url], its core's [status], the failure it
 * stopped on ([HostConnection.stoppedOn]), whether its first dial still [waiting] (for the
 * node or the SSH pool to start), and when it entered `reconnecting` ([reconnectingSince], on
 * the clock `questionPollMs` gets; null when it is not reconnecting).
 */
data class SshDialState(val url: String, val status: String, val stoppedOn: String?, val waiting: Boolean = false, val reconnectingSince: Long? = null)

/**
 * When a remote now in [status] entered `reconnecting`: [previous] while it stays there, [now]
 * when it just went there, null once it left. The core keeps `reconnecting` through its retries,
 * so the time is that of the link's drop.
 */
internal fun reconnectingSince(previous: Long?, status: String, now: Long): Long? =
    if (status == "reconnecting") previous ?: now else null

/**
 * How often the pool's questions are read; null: not at all. Every [SshQuestions.POLL_MS] while
 * the user is [asking] (a question on screen is followed until the pool drops it) or a saved ssh
 * remote dials for the first time since it was connected (`connecting`, `idle`): an unknown host
 * key or a password is asked then. As often for [SshQuestions.RELOGIN_POLL_MS] after a remote
 * lost its link (`reconnecting` since [SshDialState.reconnectingSince], on the clock [now]): each
 * new login asks about the host key, and maybe a saved password answers it, so neither waits
 * the slow read. Every [SshQuestions.SLOW_POLL_MS] while ssh remotes only fail and retry: the
 * core keeps `error` or `reconnecting` through its next dial, so a retry is not seen, and a
 * saved password or host key still answers it within seconds (a question waits 120 s). Not at
 * all when every ssh remote is connected, stopped until Reconnect, or waiting to start: then
 * nothing can ask.
 */
internal fun questionPollMs(remotes: List<SshDialState>, asking: Boolean, now: Long = 0L): Long? {
    if (asking) return SshQuestions.POLL_MS
    val live = remotes.filter { isSshLocator(it.url) && it.status != "connected" && !it.waiting && mayReconnectAutomatically(it.stoppedOn) }
    fun relogin(it: SshDialState) =
        it.status == "reconnecting" && it.reconnectingSince != null && now - it.reconnectingSince < SshQuestions.RELOGIN_POLL_MS
    return when {
        live.any { it.status == "connecting" || it.status == "idle" || relogin(it) } -> SshQuestions.POLL_MS
        live.isNotEmpty() -> SshQuestions.SLOW_POLL_MS
        else -> null
    }
}

/**
 * Whether the first dial of the remote [url] waits: for the app's node, which is starting
 * ([nodeStarting]), or, for an ssh login, for the SSH pool to start ([sshReady]). A dial before
 * the pool runs would read "SSH is still starting" and wait for the core's retry.
 */
internal fun firstDialWaits(url: String, nodeStarting: Boolean, sshReady: Boolean): Boolean =
    nodeStarting || (isSshLocator(url) && !sshReady)

/** Whether the SSH pool, now running, lets the waiting remote [url] dial: an ssh login, unless the node it also waits for is starting (its start dials it). */
internal fun dialsWhenSshReady(url: String, nodeStarting: Boolean): Boolean = isSshLocator(url) && !nodeStarting

/**
 * The SSH pool's questions, answered from [secrets] or by the user. [poll] reads the pool's
 * queue (the app polls as [questionPollMs] says); a question the stores answer
 * never reaches the user. One the user must answer is [pending] (the oldest), and its login is
 * in [needsAnswer] until the user answers it or reconnects, or the remote connects or goes:
 * a question the pool dropped unanswered (120 s) leaves the remote waiting for the user, and
 * the core asks nothing more until Reconnect. [notices] tell the app when a login starts and
 * stops waiting for the user (the off-screen notification). Main thread only.
 *
 * [reconnect] connects a login again: the user answered after the pool stopped waiting.
 * [background] runs the key install of "Use this phone's key from now on" off the main thread,
 * the reading of the phone key it installs too.
 */
class SshQuestions(
    private val pool: SshPool,
    private val secrets: SshSecrets,
    private val notices: Notices = Notices.None,
    private val reconnect: (target: String) -> Unit = {},
    private val background: (Runnable) -> Unit = { Thread(it, "ssh-install-key").start() },
    private val log: (String) -> Unit = {},
) {
    /** What the app does when a login starts ([ask]) and stops ([clear]) waiting for the user. */
    interface Notices {
        fun ask(question: SshQuestion)
        fun clear(target: String)

        object None : Notices {
            override fun ask(question: SshQuestion) {}
            override fun clear(target: String) {}
        }
    }

    private val pendingFlow = MutableStateFlow<SshQuestion?>(null)
    /** The question the user is asked now: the oldest that waits for them. */
    val pending: StateFlow<SshQuestion?> = pendingFlow.asStateFlow()

    private val needsAnswerFlow = MutableStateFlow(emptySet<String>())
    /** The logins (saved ssh remotes' URLs) that wait for the user's answer, or for Reconnect after none came. */
    val needsAnswer: StateFlow<Set<String>> = needsAnswerFlow.asStateFlow()

    /** The questions that wait for the user, by id, oldest first. */
    private val asked = LinkedHashMap<Long, SshQuestion>()
    /** The ids already answered or asked; the pool's ids only grow, so one is never seen twice. */
    private val handled = HashSet<Long>()
    /** Passwords the user gave, saved once their login connects ("a password that logged in"). */
    private val candidates = HashMap<String, String>()
    /** Logins whose host gets the phone key once they connect. */
    private val installs = HashSet<String>()
    /**
     * The logins under way, by login: whether the login asked for a password or a prompt's
     * answer. Every new SSH connection asks about the host key first (the pool asks even for a
     * key the phone knows), so that question starts the record; a connection that reused one
     * already logged in has none.
     */
    private val logins = HashMap<String, Boolean>()

    /** True while a question waits for the user. */
    val asking: Boolean get() = asked.isNotEmpty()

    /** The questions that wait for the user, oldest first. */
    fun waiting(): List<SshQuestion> = asked.values.toList()

    /** Read the pool's queue: answer what the stores can, ask the user the rest, drop what left. */
    fun poll() {
        val live = parseQuestions(pool.questions()) ?: return
        val ids = live.mapTo(HashSet()) { it.id }
        // Timed out or withdrawn: no dialog can answer it any more. Its login stays in needsAnswer.
        asked.keys.retainAll(ids)
        handled.retainAll(ids)
        for (q in live) {
            if (!handled.add(q.id)) continue
            when (q.kind) {
                "hostkey" -> logins[q.target] = false
                "password", "prompt" -> logins[q.target] = true
            }
            when (val decision = decide(q)) {
                is AutoAnswer.Answer -> pool.answer(q.id, decision.text, true)
                AutoAnswer.Decline -> pool.answer(q.id, "", false)
                AutoAnswer.AskUser -> {
                    asked[q.id] = q
                    needsAnswerFlow.value += q.target
                    notices.ask(q)
                }
            }
        }
        publish()
    }

    private fun decide(q: SshQuestion): AutoAnswer {
        // The host refused the last answer: an answer the user gave is not saved. Review Focus 4
        // (#245): the saved password, which answered, goes and the user is asked, once. After a
        // stall (ruling R4, #284) the saved password never answered: the user's was refused, and
        // the saved one stays.
        if (q.kind == "password" && q.retry) {
            candidates.remove(q.target)
            if (!q.afterStall) secrets.forgetPassword(q.target)
        }
        val hostKeys = if (q.kind != "hostkey") emptyMap() else secrets.hostKey(q.text)?.let { mapOf(q.text to it) }.orEmpty()
        val passwords = if (q.kind != "password" || q.retry || q.afterStall) emptyMap()
            else (candidates[q.target] ?: secrets.password(q.target))?.let { mapOf(q.target to it) }.orEmpty()
        return autoAnswer(q, hostKeys, passwords)
    }

    /**
     * The user's answer to question [id]; [ok] false declines it (the login stops as declined).
     * A host key is trusted whatever [text] says, and the trusted key then answers the other
     * logins that wait on the same `host:port` ("trust" or "changed"), so a second Trust cannot
     * replace it. A password is saved ([savePassword]) once its login connects, and then the
     * host gets the phone key ([installKey], "Use this phone's key from now on"). An answer that
     * comes after the pool stopped waiting connects the login again. A second answer to the same
     * question sends nothing.
     */
    fun answer(id: Long, text: String, ok: Boolean, savePassword: Boolean, installKey: Boolean) {
        val q = asked.remove(id) ?: return
        val accepted = if (!ok) pool.answer(id, "", false) else when (q.kind) {
            "hostkey" -> {
                secrets.trustHostKey(q.text, "${q.keyType} ${q.fingerprint}")
                pool.answer(id, SSH_TRUST, true).also { answerHostKeys(q.text) }
            }
            "password" -> {
                if (savePassword) candidates[q.target] = text
                if (installKey) installs += q.target
                pool.answer(id, text, true)
            }
            else -> pool.answer(id, text, true)
        }
        settleIfDone(q.target)
        publish()
        if (ok && !accepted) reconnect(q.target)
    }

    /** The host key questions about [hostPort] that wait for the user, answered from the key now kept. */
    private fun answerHostKeys(hostPort: String) {
        for (other in asked.values.filter { it.kind == "hostkey" && it.text == hostPort }) {
            val decision = decide(other) as? AutoAnswer.Answer ?: continue
            asked.remove(other.id)
            pool.answer(other.id, decision.text, true)
            settleIfDone(other.target)
        }
    }

    /**
     * The remote [target] connected: its login worked, so a password given for it is saved and
     * the phone key installed. A Keystore that fails here costs only that: the password is not
     * saved, the key not installed, and the connection carries on.
     *
     * A login that asked for no password and no prompt's answer was opened by the phone's key
     * (convenience A's last step): the password saved for it is forgotten, and one given in an
     * earlier try is not saved. A connection over an SSH connection already logged in tells
     * nothing ([logins] has no record of it) and changes nothing. The phone key is read and
     * installed on [background], never here.
     */
    fun connected(target: String) {
        if (logins.remove(target) == false) {
            candidates.remove(target)
            installs.remove(target)
            if (secrets.hasPassword(target)) {
                secrets.forgetPassword(target)
                log("key login to $target: saved password forgotten")
            }
        }
        candidates.remove(target)?.let { password ->
            try {
                if (!secrets.savePassword(target, password)) log("save password for $target: forgotten meanwhile")
            } catch (e: Exception) {
                log("save password for $target: ${e.javaClass.simpleName}")
            }
        }
        if (installs.remove(target)) background(Runnable {
            // Here, not on the main thread: the public line takes the secrets' lock, which an
            // import holds for seconds, and may open the key in the Keystore.
            val line = try {
                secrets.publicLine()
            } catch (e: Exception) {
                log("phone key for $target: ${e.javaClass.simpleName}")
                return@Runnable
            }
            val rc = pool.installKey(target, line, INSTALL_TIMEOUT_MS)
            log("install key on $target: $rc")
        })
        if (target in needsAnswerFlow.value) settle(target)
    }

    /** The user reconnects [target]: it no longer waits for an answer; the next login asks again if it must. */
    fun retry(target: String) {
        if (target in needsAnswerFlow.value) settle(target)
    }

    /** The user forgot the password of [target]: the saved one, and one given that waits for its login to connect. */
    fun forgetPassword(target: String) {
        candidates.remove(target)
        secrets.forgetPassword(target)
    }

    /** The remote [target] was removed: its questions are declined and its saved password goes with it. */
    fun removed(target: String) {
        for (q in asked.values.filter { it.target == target }) {
            asked.remove(q.id)
            pool.answer(q.id, "", false)
        }
        candidates.remove(target)
        installs.remove(target)
        logins.remove(target)
        secrets.forgetPassword(target)
        settle(target)
        publish()
    }

    private fun settleIfDone(target: String) {
        if (asked.values.none { it.target == target }) settle(target)
    }

    private fun settle(target: String) {
        needsAnswerFlow.value -= target
        notices.clear(target)
    }

    private fun publish() {
        pendingFlow.value = asked.values.firstOrNull()
    }

    companion object {
        /** How often the queue is read while an ssh remote dials or a question waits ([questionPollMs]). */
        const val POLL_MS = 300L
        /** How often the queue is read while ssh remotes only fail and retry ([questionPollMs]). */
        const val SLOW_POLL_MS = 5_000L
        /** How long after a remote lost its link the queue is still read every [POLL_MS] ([questionPollMs]). */
        const val RELOGIN_POLL_MS = 30_000L
        /** The longest a key install may take over a logged-in connection. */
        const val INSTALL_TIMEOUT_MS = 30_000
    }
}

/**
 * The notification "REMOTE needs your answer to connect", posted while the app is off screen and
 * a login waits for the user. It goes on a channel of its own, "SSH questions", of default
 * importance: a question waits only 120 s, so it sounds as the user's settings say, and the
 * user may let it pop up. It opens the app on the remote, whose question the app then shows.
 * Main thread only.
 */
internal class SshQuestionNotices(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))

    fun post(target: String, remote: String) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When an SSH login waits for your answer, such as a password." })
        val open = PendingIntent.getActivity(context, target.hashCode(),
            Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_TARGET, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Another question of the same login replaces it without a second sound.
        val note = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_status_blocked)
            .setContentTitle("$remote needs your answer to connect").setContentIntent(open).setAutoCancel(true)
            .setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_STATUS).build()
        // Without the permission to post, Android drops it: the dialog still waits in the app.
        manager.notify(TAG + target, ID, note)
    }

    fun cancel(target: String) = manager.cancel(TAG + target, ID)

    companion object {
        /** The channel of these notifications, apart from the background service's quiet one. */
        const val CHANNEL = "ssh_questions"
        const val CHANNEL_NAME = "SSH questions"
        const val ACTION = "io.github.yhzion.velo.SSH_QUESTION"
        /** The login whose question the notification opens. */
        const val EXTRA_TARGET = "sshTarget"
        private const val TAG = "ssh:"
        private const val ID = 0x5353
    }
}
