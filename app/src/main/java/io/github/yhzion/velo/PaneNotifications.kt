package io.github.yhzion.velo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10
import kotlin.math.roundToInt
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import io.github.yhzion.velo.ui.TabAgent

internal enum class NotificationAccess { Allowed, RequestPermission, AppSettings, ChannelSettings }

internal fun notificationAccess(api: Int, granted: Boolean, appEnabled: Boolean, channelEnabled: Boolean) = when {
    api >= 33 && !granted -> NotificationAccess.RequestPermission
    !appEnabled -> NotificationAccess.AppSettings
    !channelEnabled -> NotificationAccess.ChannelSettings
    else -> NotificationAccess.Allowed
}

/** What a pane notification says: [title] and [text] collapsed, [expanded] in full, [program] in its header. */
internal data class PaneNoteText(val title: String, val text: String, val expanded: String, val program: String?)

private val NoteBreak = Regex("""[\p{Cc}\p{Zl}\p{Zp}]+""")

private fun String?.oneLine() = this?.replace(NoteBreak, " ")?.trim()?.takeIf { it.isNotEmpty() }

/** Before the program's own message on a notification that can be answered (#32). */
internal const val REPORTED_LABEL = "Program reported:"
/** Before the pane's last lines on a notification that can be answered (#32). */
internal const val SCREEN_LABEL = "On screen:"

/** Whether [alert]'s notification has buttons that answer the pane's prompt ([replyPlan]). */
internal fun asksScreen(alert: PaneAlert) =
    replyPlan(alert.kind, alert.reason, alert.agent, claudeChoices(alert.message), alert.record, alert.updatedAt) != null

/** The lines of [text] (`Core.screenTail`) that hold text, each without control characters. */
internal fun screenLines(text: String?): List<String> = text.orEmpty().split('\n').mapNotNull { it.oneLine() }

/**
 * The notification for [alert]: the state and the work the program names in the title, its
 * message in the text, and the message with the place it runs (remote · session · tab) when
 * expanded. Without a reported title the state is the title; without a message, the place.
 *
 * A notification that can be answered ([asksScreen]) says more, because any program that
 * writes to the pane can report a status in its own words, and the answer goes to whatever
 * prompt is up (#32): its message is marked as the program's report ([REPORTED_LABEL]), and
 * when expanded it shows [screen], the pane's last lines as the server has them, under
 * [SCREEN_LABEL]. Without [screen] (not read in time) it shows the rest all the same.
 */
internal fun paneNoteText(alert: PaneAlert, screen: String? = null): PaneNoteText {
    val answered = asksScreen(alert)
    fun reported(text: String?) = if (answered) text?.let { "$REPORTED_LABEL $it" } else text
    val lines = if (answered) screenLines(screen) else emptyList()
    val onScreen = lines.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "$SCREEN_LABEL\n")
    val state = if (alert.kind != PaneAlertKind.Blocked) alert.kind.label else when (alert.reason) {
        "question" -> "Question needs an answer"
        "permission" -> "Approval requested"
        "auth" -> "Authentication required"
        else -> alert.kind.label
    }
    val work = alert.title.oneLine()
    val message = alert.message?.trim()?.takeIf { it.isNotEmpty() }
    val place = alert.location.oneLine()
    val program = alert.agent.oneLine()?.let { TabAgent.from(it)?.label ?: it }
    return PaneNoteText(
        title = if (work == null) state else "$state: $work",
        text = reported(message.oneLine()) ?: place.orEmpty(),
        expanded = listOfNotNull(reported(message), onScreen, place).joinToString("\n\n"),
        program = program,
    )
}

/**
 * The notifications that wait for their pane's last lines before they are posted. Only the
 * newest wait of a notification (its tag and id) may post it: a wait that a newer report, a
 * post or a cancel overtook must not put older words back.
 */
internal class NoteWaits {
    private val newest = mutableMapOf<Pair<String, Int>, Long>()
    private var serial = 0L

    /** A notification starts to wait; the result names the wait for [take]. */
    fun hold(tag: String, id: Int): Long = (++serial).also { newest[tag to id] = it }

    /** True once, when [hold] is still the notification's newest wait: it may be posted. */
    fun take(tag: String, id: Int, hold: Long): Boolean = (newest[tag to id] == hold).also { if (it) newest.remove(tag to id) }

    /** The notification was posted or cancelled otherwise: a wait for it posts nothing. */
    fun drop(tag: String, id: Int) {
        newest.remove(tag to id)
    }
}

internal class PaneNotifications(private val context: Context) {
    private val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
    private val waits = NoteWaits()

    fun activeTokens() = manager.activeNotifications.mapNotNull { it.tag }.toSet()

    fun createChannels() {
        manager.createNotificationChannels(PaneAlertKind.entries.map {
            NotificationChannel(it.channel, it.label, NotificationManager.IMPORTANCE_DEFAULT)
        })
    }

    fun access(kind: PaneAlertKind) = notificationAccess(
        api = Build.VERSION.SDK_INT,
        granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        appEnabled = manager.areNotificationsEnabled(),
        channelEnabled = manager.getNotificationChannel(kind.channel)?.importance != NotificationManager.IMPORTANCE_NONE,
    )

    fun settingsIntent(kind: PaneAlertKind) = if (access(kind) == NotificationAccess.ChannelSettings) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, kind.channel)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    /**
     * Post [alert]'s notification, and run [posted] when it is up. One that can be answered
     * ([asksScreen]) is posted with the pane's last lines: [screen] reads them and gives them
     * to its callback once, null when it could not or did not in time, and the notification
     * waits for that. Main thread, as the callback of [screen] must be.
     */
    fun post(alert: PaneAlert, screen: (lines: (String?) -> Unit) -> Unit, posted: () -> Unit) {
        createChannels()
        val tag = alert.subscription.token
        val id = alert.kind.ordinal
        waits.drop(tag, id)
        if (access(alert.kind) != NotificationAccess.Allowed) return
        if (!asksScreen(alert)) {
            if (show(alert, null)) posted()
            return
        }
        val hold = waits.hold(tag, id)
        screen { lines -> if (waits.take(tag, id, hold) && show(alert, lines)) posted() }
    }

    private fun show(alert: PaneAlert, screen: String?): Boolean {
        if (access(alert.kind) != NotificationAccess.Allowed) return false
        val sub = alert.subscription
        val text = paneNoteText(alert, screen)
        val target = ReplyTarget.of(alert, text.title)
        val plan = replyPlan(alert.kind, alert.reason, alert.agent, target.choices, alert.record, alert.updatedAt)
        val note = builder(alert.kind, sub.token, text.title, text.text, text.expanded, text.program)
            .addExtras(Bundle().apply { putString(PROMPT, target.prompt) })
            .apply { buttons(target, plan) }.build()
        return notify(sub.token, alert.kind.ordinal, note)
    }

    /**
     * The notification of [target] again, after an answer from it: [body] says what happened
     * and [history] is the answer the user gave. Posting it closes the reply field. With
     * [retry] (nothing was written) its buttons stay, so the answer can be given again; with
     * [dismiss] (the answer is in) it leaves the shade, and the badge, after a few seconds.
     */
    fun replied(target: ReplyTarget, body: String, history: String?, retry: Boolean, dismiss: Boolean) {
        if (access(PaneAlertKind.Blocked) != NotificationAccess.Allowed) return
        // A newer prompt of the pane took the notification's place: what it says stays.
        val shown = manager.activeNotifications.firstOrNull { it.tag == target.tag && it.id == target.id }
        if (shown != null && shown.notification.extras.getString(PROMPT) != target.prompt) return
        val program = target.agent?.trim()?.takeIf { it.isNotEmpty() }?.let { TabAgent.from(it)?.label ?: it }
        val note = builder(PaneAlertKind.Blocked, target.tag, target.title, body, body, program)
            .addExtras(Bundle().apply { putString(PROMPT, target.prompt) })
            // Shown under the text while the notification has its reply field (retry).
            .apply { history?.let { setRemoteInputHistory(arrayOf<CharSequence>(it)) } }
            .apply { if (retry) buttons(target, target.plan) }
            // Long enough to read what happened, short enough that the badge soon drops it.
            .apply { if (dismiss) setTimeoutAfter(REPLIED_DISMISS_MS) }
            .setOnlyAlertOnce(true).build()
        notify(target.tag, target.id, note)
    }

    private fun builder(kind: PaneAlertKind, token: String, title: String, text: String, expanded: String, program: String?): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN)
            .setData(Uri.Builder().scheme("velo-pane").authority("open").appendPath(token).build())
        val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, kind.channel).setSilent(true)
            .setSmallIcon(when (kind) {
                PaneAlertKind.Blocked -> R.drawable.ic_status_blocked
                PaneAlertKind.Error -> R.drawable.ic_status_error
                PaneAlertKind.Done -> R.drawable.ic_status_done
                PaneAlertKind.Working -> android.R.drawable.ic_popup_sync
            }).setContentTitle(title).setContentText(text)
            // Expanded on the phone and on a watch, the whole message and where it runs are readable.
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
            .apply { program?.let(::setSubText) }
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true)
            // The system suggests no replies or actions of its own: only measured keys are sent.
            .setAllowSystemGeneratedContextualActions(false)
            // Each posted notification counts once on the launcher icon's badge.
            .setNumber(1).setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
    }

    private fun notify(tag: String, id: Int, note: Notification) = try {
        manager.notify(tag, id, note)
        true
    } catch (_: SecurityException) {
        false
    }

    /**
     * The buttons of [plan]: the phone's ([replyButtons]) on the notification, and a watch's
     * ([wearButtons]) on its wearable extender. A watch shows only the extender's when it has
     * any, so a question's choices are buttons there and the phone keeps its chips.
     */
    private fun NotificationCompat.Builder.buttons(target: ReplyTarget, plan: ReplyPlan?) {
        for (button in replyButtons(plan)) addAction(action(target, button))
        val wear = wearButtons(plan)
        if (wear.isNotEmpty()) extend(NotificationCompat.WearableExtender().addActions(wear.mapIndexed { i, b -> action(target, b, i) }))
    }

    /**
     * The button [button] of [target]'s notification. Its intent starts [PaneReplyService] and
     * names the notification ([ReplyTarget.tag], [ReplyTarget.id]) and its prompt
     * ([ReplyTarget.prompt]) in its data, so every prompt and button has a pending intent of its
     * own: a late tap on an older notification, from a watch, still carries that prompt and is
     * refused by the check, rather than answering the newer one. The reply field's intent is
     * mutable, for the system to add the text; the others are not.
     */
    private fun action(target: ReplyTarget, button: ReplyButton, wearIndex: Int = -1): NotificationCompat.Action {
        val intent = target.into(Intent(context, PaneReplyService::class.java).setAction(button.action.intentAction)
            .setData(Uri.Builder().scheme("velo-reply").authority(target.id.toString()).appendPath(target.tag)
                .appendQueryParameter("prompt", target.prompt).appendQueryParameter("wear", wearIndex.toString()).build()))
            .apply { button.choice?.let { putExtra(PaneReplyService.CHOICE_KEY, it) } }
        val mutable = button.action == ReplyAction.Answer && button.choice == null
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        // Each button of each prompt has an intent of its own: a watch's choices differ only in their extra.
        val request = 31 * (31 * (31 * target.tag.hashCode() + target.id) + button.action.ordinal) + wearIndex
        val pending = PendingIntent.getForegroundService(context, request, intent, flags)
        return NotificationCompat.Action.Builder(0, button.label, pending)
            .setAuthenticationRequired(button.authenticationRequired)
            .setAllowGeneratedReplies(false).setShowsUserInterface(false)
            .apply {
                if (mutable) addRemoteInput(RemoteInput.Builder(PaneReplyService.ANSWER_KEY).setLabel("Answer")
                    .apply { button.choices?.let { setChoices(it.toTypedArray()) } }
                    .setAllowFreeFormInput(true).build())
            }.build()
    }

    private var player: MediaPlayer? = null
    private var boost: LoudnessEnhancer? = null
    private var lastSound = -1000L
    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))

    fun play(kind: PaneAlertKind, sound: ActivitySound, volume: Float) {
        if (volume <= 0 || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        val channel = manager.getNotificationChannel(kind.channel)
        if (channel != null && (channel.importance < NotificationManager.IMPORTANCE_DEFAULT || channel.sound == null)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSound < 300) return
        val uri = if (channel?.hasUserSetSound() == true) channel.sound
            else Uri.parse("android.resource://${context.packageName}/${sound.resource}")
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            next.setDataSource(context, uri)
            val gain = volume.coerceIn(0f, 1f)
            next.setVolume(gain, gain)
            next.setOnPreparedListener { if (player === it) it.start() }
            next.setOnCompletionListener { if (player === it) release() }
            next.setOnErrorListener { failed, _, _ -> if (player === failed) release(); true }
            release()
            player = next
            if (volume > 1f) {
                // Volume above the captured Mac default is optional on Android audio devices.
                boost = runCatching { LoudnessEnhancer(next.audioSessionId) }.getOrNull()
                boost?.let { effect -> runCatching {
                    effect.setTargetGain((2000f * log10(volume)).roundToInt())
                    effect.enabled = true
                } }
            }
            next.prepareAsync()
            lastSound = now
        } catch (_: Exception) {
            if (player === next) release() else next.release()
        }
    }

    fun release() {
        boost?.release()
        boost = null
        player?.release()
        player = null
    }

    fun cancel(token: String, kind: PaneAlertKind) {
        waits.drop(token, kind.ordinal)
        manager.cancel(token, kind.ordinal)
    }

    companion object {
        private const val OPEN = "io.github.yhzion.velo.OPEN_PANE_NOTIFICATION"
        /** How long the notification of a delivered answer stays, saying so ([replied]). */
        private const val REPLIED_DISMISS_MS = 4_000L
        /** The extra naming the prompt a notification is for ([ReplyTarget.prompt]). */
        private const val PROMPT = "io.github.yhzion.velo.prompt"
        fun token(intent: Intent?): String? = intent?.takeIf { it.action == OPEN }?.data
            ?.takeIf { it.scheme == "velo-pane" && it.authority == "open" }?.pathSegments?.singleOrNull()
    }
}
