package io.github.yhzion.velo.updates

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle
import java.util.concurrent.TimeUnit

internal object UpdateSettings {
    private const val JOB_ID = 0x525855
    internal const val GENERATION = "generation"
    private const val CHANNEL = "channel"
    private const val ENABLED = "enabled"
    private const val LAST_CHECK = "lastCheck"
    private const val LAST_NOTICE = "lastNotice"
    private const val AUTOMATIC = "automatic"
    // The newest eligible release a check found, the start time of that check, and the code the Updates page showed.
    private const val AVAILABLE_TAG = "availableTag"
    private const val AVAILABLE_CODE = "availableCode"
    private const val CHECK_STARTED = "checkStarted"
    private const val SEEN = "seen"
    // Until when GitHub's API limit for this network is used up; it is shared by every channel.
    private const val API_BLOCKED_UNTIL = "apiBlockedUntil"

    private fun prefs(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun channel(context: Context): UpdateChannel {
        val selected = prefs(context).getString(CHANNEL, null)
        return UpdateChannel.entries.firstOrNull { it.name == selected } ?: UpdateChannel.Stable
    }

    @Synchronized fun select(context: Context, channel: UpdateChannel) {
        if (channel(context) == channel) return
        val prefs = prefs(context)
        val generation = prefs.getLong(GENERATION, 0L) + 1
        val scheduled = enabled(context) && schedule(context, generation)
        if (!scheduled) context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        prefs.edit().putString(CHANNEL, channel.name).putBoolean(ENABLED, scheduled)
            .putLong(GENERATION, generation).remove(LAST_CHECK).remove(LAST_NOTICE)
            // What another channel found, or was seen there, says nothing about this one.
            .remove(AVAILABLE_TAG).remove(AVAILABLE_CODE).remove(CHECK_STARTED).remove(SEEN).apply()
        UpdateNotice.cancel(context)
    }

    /** "Notify me about updates": the periodic background check and its Android notification. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    /** "Check for updates automatically": a check when Velo comes to the foreground ([AutoUpdateCheck]). */
    fun automatic(context: Context): Boolean = prefs(context).getBoolean(AUTOMATIC, true)

    fun setAutomatic(context: Context, automatic: Boolean) {
        prefs(context).edit().putBoolean(AUTOMATIC, automatic).apply()
    }

    fun status(context: Context): UpdateStatus {
        val prefs = prefs(context)
        return UpdateStatus(prefs.getString(AVAILABLE_TAG, null), prefs.getLong(AVAILABLE_CODE, 0L), prefs.getLong(SEEN, 0L))
    }

    /** Calls [onChange] on the main thread whenever a setting here changes, until the returned function is called. */
    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        // The preferences hold their listeners weakly: the returned function keeps this one alive.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    @Synchronized fun enable(context: Context, enabled: Boolean) {
        val prefs = prefs(context)
        val scheduler = requireNotNull(context.getSystemService(JobScheduler::class.java))
        if (enabled && enabled(context) && scheduler.getPendingJob(JOB_ID)?.extras?.getLong(GENERATION, -1L) ==
            prefs.getLong(GENERATION, 0L)) return
        val generation = prefs.getLong(GENERATION, 0L) + 1
        val scheduled = enabled && schedule(context, generation)
        val edit = prefs.edit().putBoolean(ENABLED, scheduled).putLong(GENERATION, generation)
        if (!scheduled) {
            scheduler.cancel(JOB_ID)
            UpdateNotice.cancel(context)
            edit.remove(LAST_NOTICE)
        }
        edit.apply()
    }

    private fun schedule(context: Context, generation: Long): Boolean {
        val scheduler = requireNotNull(context.getSystemService(JobScheduler::class.java))
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
            .setExtras(PersistableBundle().apply { putLong(GENERATION, generation) })
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.HOURS.toMillis(12))
            .setPersisted(true)
            .build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    fun lastCheck(context: Context): Long = prefs(context).getLong(LAST_CHECK, 0L)

    /** Until when automatic checks leave the API alone ([ReleaseFeed.update]); 0 when no limit was reported. */
    fun apiBlockedUntil(context: Context): Long = prefs(context).getLong(API_BLOCKED_UNTIL, 0L)

    /** A check was told GitHub's limit is used up until [resetAt] ([RateLimitedException]). */
    fun limitReached(context: Context, resetAt: Long) {
        prefs(context).edit().putLong(API_BLOCKED_UNTIL, resetAt).apply()
    }

    /** The API returned a page ([ReleaseFeed.check]): a limit stored before no longer holds (#264). */
    fun apiAnswered(context: Context) {
        val prefs = prefs(context)
        if (prefs.contains(API_BLOCKED_UNTIL)) prefs.edit().remove(API_BLOCKED_UNTIL).apply()
    }

    /**
     * Store a successful check of [channel] that started at [startedAt] and found [release] (null:
     * none). A check of a channel no longer selected stores nothing.
     */
    @Synchronized fun record(context: Context, channel: UpdateChannel, startedAt: Long, release: AppRelease?) {
        if (channel(context) != channel) return
        val prefs = prefs(context)
        val edit = prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis())
        remember(prefs, edit, startedAt, release)
        edit.apply()
    }

    private fun remember(prefs: SharedPreferences, edit: SharedPreferences.Editor, startedAt: Long, release: AppRelease?) {
        if (!replacesStoredCheck(startedAt, prefs.getLong(CHECK_STARTED, 0L), System.currentTimeMillis())) return
        edit.putLong(CHECK_STARTED, startedAt)
        if (release == null) edit.remove(AVAILABLE_TAG).remove(AVAILABLE_CODE)
        else edit.putString(AVAILABLE_TAG, release.tag).putLong(AVAILABLE_CODE, release.versionCode)
    }

    /** The Updates page showed [code] for [channel]: the dot on Settings goes out until a newer build is found. */
    @Synchronized fun seen(context: Context, channel: UpdateChannel, code: Long) {
        if (channel(context) == channel) prefs(context).edit().putLong(SEEN, code).apply()
    }

    private fun activeGeneration(context: Context): Long? =
        if (enabled(context)) prefs(context).getLong(GENERATION, 0L) else null

    @Synchronized internal fun jobChannel(context: Context, generation: Long): UpdateChannel? =
        if (generation == activeGeneration(context)) channel(context) else null

    /** Commit a successful poll that started at [startedAt] only if it still belongs to the same subscription. */
    @Synchronized internal fun finish(context: Context, generation: Long, startedAt: Long, release: AppRelease?) {
        val prefs = prefs(context)
        val edit = prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis())
        // Applied only past the stale check below: the same generation means the same channel.
        remember(prefs, edit, startedAt, release)
        // A stable build can be found through any subscription. Remember its release stream,
        // not the subscription that found it; switching channels must not re-announce it.
        val announcedKey = release?.let { "announced.${it.channel.name}" }
        val announced = if (announcedKey == null) 0L else prefs.getLong(announcedKey, 0L)
        val action = noticeAction(generation, activeGeneration(context), release?.versionCode, announced,
            prefs.getLong(LAST_NOTICE, 0L))
        when (action) {
            NoticeAction.IgnoreStaleCheck -> return
            NoticeAction.Keep -> Unit
            NoticeAction.Cancel -> {
                UpdateNotice.cancel(context)
                edit.remove(LAST_NOTICE)
            }
            NoticeAction.Post -> {
                requireNotNull(release)
                if (UpdateNotice.post(context, release)) {
                    edit.putLong(requireNotNull(announcedKey), release.versionCode).putLong(LAST_NOTICE, release.versionCode)
                } else {
                    UpdateNotice.cancel(context)
                    edit.remove(LAST_NOTICE)
                }
            }
        }
        edit.apply()
    }
}
