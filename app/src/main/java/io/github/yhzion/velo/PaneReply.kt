package io.github.yhzion.velo

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.yhzion.velo.ui.TabAgent
import java.time.Instant

/** What the core said of a `paneWrite` (`Core.PaneWriteResult`). */
internal enum class PaneWriteResult(val wire: String) {
    /** The server wrote all of it. */
    Sent("sent"),
    /** The prompt was answered or changed, or another program is in front in the pane (#32): nothing was written. */
    Changed("changed"),
    /** Nothing was written: not connected, no such pane, or the check failed. */
    NotSent("not_sent"),
    /** The write went out and was not confirmed: part of it or all of it may be there. */
    Unconfirmed("unconfirmed");

    companion object {
        fun fromWire(result: String): PaneWriteResult = when (result) {
            Sent.wire -> Sent
            Changed.wire -> Changed
            NotSent.wire -> NotSent
            else -> Unconfirmed // Includes "unconfirmed" and any unknown result: do not retry.
        }
    }
}

/** The agents whose prompts were measured (research: watch-reply-research.md, section 2). */
internal enum class ReplyAgent { Claude, Codex }

/**
 * How a pane's prompt can be answered from its notification. Only prompts whose keys were
 * measured get one: a Claude Code question whose choices its message lists, and a Claude Code
 * or Codex permission request. omp's menu takes no number keys and its message lists no
 * choices, so the place of its free answer is not known; Codex questions were not measured.
 */
internal sealed interface ReplyPlan {
    /** Claude Code's question menu: [choices], then "Type something" at choices + 1. */
    data class Question(val choices: List<String>) : ReplyPlan
    data class Permission(val agent: ReplyAgent) : ReplyPlan
}

internal enum class ReplyAction(val intentAction: String) {
    Answer("io.github.yhzion.velo.PANE_ANSWER"), Approve("io.github.yhzion.velo.PANE_APPROVE"), Deny("io.github.yhzion.velo.PANE_DENY");

    companion object {
        fun of(action: String?) = entries.firstOrNull { it.intentAction == action }
    }
}

/**
 * A notification button: [choices] for the reply field of [ReplyAction.Answer]. [choice] is a
 * button that answers with that one choice, without a reply field: a watch's, which shows no
 * [choices] of a reply field (measured on Galaxy Watch, 2026-11-21).
 */
internal data class ReplyButton(
    val action: ReplyAction,
    val label: String,
    val authenticationRequired: Boolean,
    val choices: List<String>? = null,
    val choice: String? = null,
)

/**
 * The choices of a Claude Code question from its message, `answer: <question> (A · B · C)`.
 * Null without that form, for a message Claude shortened (`…`), or for fewer than two or more
 * than eight choices: then the number of its "Type something" entry is not known for sure.
 */
internal fun claudeChoices(message: String?): List<String>? {
    val text = message?.trim() ?: return null
    if (!text.startsWith("answer: ") || '…' in text || !text.endsWith(")")) return null
    var depth = 0
    var open = -1
    for (i in text.length - 1 downTo 0) {
        when (text[i]) {
            ')' -> depth++
            '(' -> if (--depth == 0) { open = i; break }
        }
    }
    if (open <= "answer: ".length || text[open - 1] != ' ') return null
    val choices = text.substring(open + 1, text.length - 1).split(" · ").map { it.trim() }
    if (choices.size !in 2..8 || choices.any { it.isEmpty() }) return null
    return choices
}

/**
 * The plan for a notification of [kind] with the program's [reason] (`kind` of its status),
 * from [agent], with the [choices] its message lists. Only a blocked status record with a
 * report time gets one: without it the answer could not be checked against the prompt that
 * is up. [record] is the record's id, "" for the root record, which is how Claude Code and
 * Codex report their own prompts.
 */
internal fun replyPlan(kind: PaneAlertKind, reason: String?, agent: String?, choices: List<String>?, record: String, updatedAt: String?): ReplyPlan? {
    if (kind != PaneAlertKind.Blocked || updatedAt.isNullOrEmpty()) return null
    val who = when (TabAgent.from(agent)) {
        TabAgent.Claude -> ReplyAgent.Claude
        TabAgent.Codex -> ReplyAgent.Codex
        else -> return null
    }
    return when (reason) {
        "question" -> if (who == ReplyAgent.Claude && choices != null) ReplyPlan.Question(choices) else null
        "permission" -> ReplyPlan.Permission(who)
        else -> null
    }
}

/** The buttons of [plan]: approving runs a command, so it waits for the phone's unlock. */
internal fun replyButtons(plan: ReplyPlan?): List<ReplyButton> = when (plan) {
    null -> emptyList()
    is ReplyPlan.Question -> listOf(ReplyButton(ReplyAction.Answer, "Answer", authenticationRequired = false, choices = plan.choices))
    is ReplyPlan.Permission -> listOf(
        ReplyButton(ReplyAction.Approve, "Approve", authenticationRequired = true),
        ReplyButton(ReplyAction.Deny, "Deny", authenticationRequired = false),
    )
}

/**
 * The buttons a watch shows for [plan], in place of [replyButtons]: a question's choices each
 * as a button of its own, then the reply field (voice or keyboard) for a free answer. A watch
 * does not show a reply field's choices; the phone keeps [replyButtons].
 */
internal fun wearButtons(plan: ReplyPlan?): List<ReplyButton> = when (plan) {
    is ReplyPlan.Question -> plan.choices.map { ReplyButton(ReplyAction.Answer, it, authenticationRequired = false, choice = it) } +
        ReplyButton(ReplyAction.Answer, "Answer", authenticationRequired = false)
    else -> replyButtons(plan)
}

private val AnswerBreak = Regex("""(\r\n|[\t\n\r\u2028\u2029])+""")
private val AnswerControl = Regex("""[\u0000-\u001f\u007f-\u009f]""")

/**
 * [text] as one line the terminal takes as typed: line breaks and tabs become a space and other
 * control characters go (the rule of `protocol.validateLiteral`). The code adds the Enter that submits.
 */
internal fun cleanAnswer(text: String): String = text.replace(AnswerBreak, " ").replace(AnswerControl, "").trim()

/** The terminal writes of an answer, in order, and the words the notification shows for it. */
internal data class ReplyKeys(val writes: List<String>, val shown: String)

private const val ESC = "\u001b"

/**
 * The keys that answer [plan] with [action] and, for [ReplyAction.Answer], the reply [text]
 * (measured: watch-reply-research.md, section 2). Null when there is nothing to send.
 * - Claude question: a choice is its number, no Enter. Other text is the number of "Type
 *   something", then the text and Enter, as two writes.
 * - Claude permission: Approve is 1 ("Yes"); Deny is Esc, since the place of "No" varies.
 * - Codex permission: Approve is y ("Yes, proceed"); Deny is Esc.
 */
internal fun replyKeys(plan: ReplyPlan, action: ReplyAction, text: String?): ReplyKeys? = when (plan) {
    is ReplyPlan.Question -> if (action != ReplyAction.Answer) null else {
        val answer = cleanAnswer(text.orEmpty())
        val choice = plan.choices.indexOfFirst { cleanAnswer(it) == answer }
        when {
            answer.isEmpty() -> null
            choice >= 0 -> ReplyKeys(listOf("${choice + 1}"), plan.choices[choice])
            else -> ReplyKeys(listOf("${plan.choices.size + 1}", "$answer\r"), answer)
        }
    }
    is ReplyPlan.Permission -> when (action) {
        ReplyAction.Approve -> ReplyKeys(listOf(if (plan.agent == ReplyAgent.Claude) "1" else "y"), "Approved")
        ReplyAction.Deny -> ReplyKeys(listOf(ESC), "Denied")
        ReplyAction.Answer -> null
    }
}

/** What the app's own record of the pane says of an answer before it is sent ([replyCheck]). */
internal enum class ReplyCheck {
    /** The record is the one the notification was made for. */
    Same,
    /** The record is another report of the prompt, or of another kind. */
    Changed,
    /** The program is no longer blocked. */
    NotBlocked,
    /** The app has no record to compare: only the server's check decides. */
    Unknown,
}

private fun instant(text: String?) = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Compare the app's latest record of the pane ([seen], `PaneAlertStore.records`) with the
 * notification's [reason] and [updatedAt]. A known change stops the answer at once; the core
 * then checks the server's status again before it writes (`Core.paneWrite`).
 */
internal fun replyCheck(seen: SeenReport?, reason: String?, updatedAt: String?): ReplyCheck {
    if (seen == null) return ReplyCheck.Unknown
    if (seen.state != "blocked") return ReplyCheck.NotBlocked
    if (seen.kind != reason) return ReplyCheck.Changed
    val was = instant(updatedAt)
    val now = instant(seen.updatedAt)
    if (was == null || now == null) return if (updatedAt == seen.updatedAt) ReplyCheck.Same else ReplyCheck.Unknown
    return if (was == now) ReplyCheck.Same else ReplyCheck.Changed
}

/**
 * The prompt a notification answers, as its buttons carry it. [tag] and [id] are the
 * notification's; [record], [reason] and [updatedAt] the server's status record.
 */
internal data class ReplyTarget(
    val remote: String,
    val address: PaneAddress,
    val record: String,
    val reason: String?,
    val updatedAt: String?,
    val agent: String?,
    val choices: List<String>?,
    val tag: String,
    val id: Int,
    val title: String,
) {
    val plan: ReplyPlan? get() = replyPlan(PaneAlertKind.Blocked, reason, agent, choices, record, updatedAt)
    /** Names the prompt: its pane's record and report. A newer prompt has another. */
    val prompt: String get() = listOf(address.session, address.block, record, reason.orEmpty(), updatedAt.orEmpty()).joinToString("\u0000")

    fun into(intent: Intent): Intent = intent.putExtra(REMOTE, remote).putExtra(SESSION, address.session)
        .putExtra(WINDOW, address.window).putExtra(BLOCK, address.block).putExtra(RECORD, record)
        .putExtra(REASON, reason).putExtra(UPDATED_AT, updatedAt).putExtra(AGENT, agent)
        .putExtra(CHOICES, choices?.toTypedArray()).putExtra(TAG, tag).putExtra(ID, id).putExtra(TITLE, title)

    companion object {
        private const val REMOTE = "remote"
        private const val SESSION = "session"
        private const val WINDOW = "window"
        private const val BLOCK = "block"
        private const val RECORD = "record"
        private const val REASON = "reason"
        private const val UPDATED_AT = "updatedAt"
        private const val AGENT = "agent"
        private const val CHOICES = "choices"
        private const val TAG = "tag"
        private const val ID = "id"
        private const val TITLE = "title"

        fun of(alert: PaneAlert, title: String): ReplyTarget = ReplyTarget(
            alert.subscription.remote, alert.subscription.target, alert.record, alert.reason, alert.updatedAt,
            alert.agent, claudeChoices(alert.message), alert.subscription.token, alert.kind.ordinal, title,
        )

        fun from(intent: Intent): ReplyTarget? {
            fun text(name: String) = intent.getStringExtra(name)
            return ReplyTarget(
                remote = text(REMOTE) ?: return null,
                address = PaneAddress(text(SESSION) ?: return null, text(WINDOW) ?: return null, text(BLOCK) ?: return null),
                record = text(RECORD) ?: return null,
                reason = text(REASON),
                updatedAt = text(UPDATED_AT),
                agent = text(AGENT),
                choices = intent.getStringArrayExtra(CHOICES)?.toList(),
                tag = text(TAG) ?: return null,
                id = intent.getIntExtra(ID, PaneAlertKind.Blocked.ordinal),
                title = text(TITLE).orEmpty(),
            )
        }
    }
}

/**
 * Writes an answer given in a pane notification to the pane's terminal, then stops. A tap on
 * the notification starts it, so Android lets it start in the foreground from the background,
 * and no activity is opened (Android 12 forbids notification trampolines). It holds the
 * app's controller while it runs, which on a process the tap started connects to the remotes.
 */
class PaneReplyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var controller: AppController? = null
    private var running = 0
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        enterForeground()
        val target = intent?.let(ReplyTarget::from)
        val action = ReplyAction.of(intent?.action)
        if (target == null || action == null) {
            finishIfIdle()
            return START_NOT_STICKY
        }
        // A choice button carries its choice; the reply field brings what was typed or spoken.
        val text = intent.getStringExtra(CHOICE_KEY)
            ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ANSWER_KEY)?.toString()
        running++
        answer(target, action, text) {
            running--
            finishIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val note = progressNote()
        try {
            // Android 14 and later need the type; before it, a start without one is allowed.
            if (Build.VERSION.SDK_INT >= 34) startForeground(PROGRESS_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(PROGRESS_ID, note)
        } catch (e: RuntimeException) {
            // A tap on a notification is exempt from the background start limits; should Android
            // refuse all the same, the answer is still written while the process lives.
            Log.w("velo.reply", "foreground service refused", e)
        }
    }

    private fun progressNote(): Notification {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "Sending answers", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false) })
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL).setSmallIcon(R.drawable.ic_status_blocked)
            .setContentTitle("Sending your answer").setSilent(true).setShowWhen(false).build()
    }

    private fun answer(target: ReplyTarget, action: ReplyAction, text: String?, done: () -> Unit) {
        val app = controller ?: (application as VeloApplication).controller.acquire(this).also { controller = it }
        val notes = app.notifications
        val plan = target.plan
        val keys = plan?.let { replyKeys(it, action, text) }
        val history = if (action == ReplyAction.Answer) keys?.shown else null
        fun finish(body: String, retry: Boolean = false, dismiss: Boolean = false) {
            notes.replied(target, body, history, retry, dismiss)
            done()
        }
        if (keys == null) return finish("Nothing to send.", retry = plan != null)
        // The phone asks to unlock before it sends an Approve, but a watch may not: a locked
        // phone never approves, whichever device the tap came from.
        if (action == ReplyAction.Approve && getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
            return finish("Not approved: unlock your phone to approve.", retry = true)
        // The reply field closes as soon as the notification is posted again.
        notes.replied(target, "Sending: ${keys.shown}", history, retry = false, dismiss = false)
        when (replyCheck(app.paneAlerts.records(target.remote, target.address)[target.record], target.reason, target.updatedAt)) {
            ReplyCheck.Changed, ReplyCheck.NotBlocked -> return finish(CHANGED)
            ReplyCheck.Same, ReplyCheck.Unknown -> {}
        }
        val conn = app.savedConnection(target.remote) ?: return finish("Not sent: the remote is no longer saved on this phone.")
        val deadline = SystemClock.uptimeMillis() + CONNECT_WAIT_MS
        fun write(step: Int) {
            var settled = false
            lateinit var tag: String
            val timeout = Runnable {
                if (settled) return@Runnable
                settled = true
                conn.forgetPaneWrite(tag)
                finish("Sent, not confirmed: the remote did not answer. Check the pane.")
            }
            // The first write is checked against the record and its report time; a later one
            // against the record alone, since the first key may already have moved the prompt.
            tag = conn.paneWrite(target.address.session, target.address.block, keys.writes[step], target.record, target.reason,
                if (step == 0) target.updatedAt else null) { result, message ->
                if (settled) return@paneWrite
                settled = true
                main.removeCallbacks(timeout)
                val partial = step > 0
                when {
                    result == PaneWriteResult.Sent && step + 1 < keys.writes.size -> main.postDelayed({ write(step + 1) }, STEP_MS)
                    result == PaneWriteResult.Sent -> finish(if (action == ReplyAction.Answer) "Answered: ${keys.shown}" else keys.shown, dismiss = true)
                    partial -> finish("Only part was sent: ${reasonText(message)}. Check the pane.")
                    result == PaneWriteResult.Changed -> finish(CHANGED)
                    result == PaneWriteResult.NotSent -> finish("Not sent: ${reasonText(message)}.", retry = true)
                    else -> finish("Sent, not confirmed: ${reasonText(message)}. Check the pane.")
                }
            }
            main.postDelayed(timeout, WRITE_TIMEOUT_MS)
        }
        val waitForConnection = object : Runnable {
            override fun run() {
                when {
                    conn.connected -> write(0)
                    SystemClock.uptimeMillis() >= deadline -> finish("Not sent: the remote is not connected.", retry = true)
                    else -> main.postDelayed(this, 250)
                }
            }
        }
        waitForConnection.run()
    }

    private fun reasonText(message: String) = message.ifBlank { "no reason given" }

    private fun finishIfIdle() {
        if (running > 0) return
        controller?.let { (application as VeloApplication).controller.release(this, forGood = true) }
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStart)
    }

    /** A short service has about three minutes (Android 14); answers still waiting are let go. */
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        controller?.let { (application as VeloApplication).controller.release(this, forGood = true) }
        controller = null
        super.onDestroy()
    }

    companion object {
        /** The [RemoteInput] key of the reply field. */
        internal const val ANSWER_KEY = "answer"
        internal const val CHOICE_KEY = "choice"
        private const val PROGRESS_CHANNEL = "pane_reply"
        private const val PROGRESS_ID = 0x5246
        private const val CHANGED = "Already answered or changed. Nothing was sent."
        /** The longest an answer waits for its remote to connect (a process the tap started dials first). */
        private const val CONNECT_WAIT_MS = 8_000L
        /** The longest one write waits for the core's answer. */
        private const val WRITE_TIMEOUT_MS = 10_000L
        /** Between "Type something" and its text: the field must be up before the text arrives. */
        private const val STEP_MS = 300L
    }
}
