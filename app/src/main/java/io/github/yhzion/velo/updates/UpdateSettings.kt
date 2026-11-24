package io.github.yhzion.velo.updates

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.util.concurrent.TimeUnit

internal object UpdateSettings {
    private const val JOB_ID = 0x525855
    internal const val GENERATION = "generation"
    private const val CHANNEL = "channel"
    private const val ENABLED = "enabled"
    private const val LAST_CHECK = "lastCheck"
    private const val LAST_NOTICE = "lastNotice"

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
            .putLong(GENERATION, generation).remove(LAST_CHECK).remove(LAST_NOTICE).apply()
        UpdateNotice.cancel(context)
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

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

    @Synchronized fun checked(context: Context, channel: UpdateChannel) {
        if (channel(context) == channel) prefs(context).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private fun activeGeneration(context: Context): Long? =
        if (enabled(context)) prefs(context).getLong(GENERATION, 0L) else null

    @Synchronized internal fun jobChannel(context: Context, generation: Long): UpdateChannel? =
        if (generation == activeGeneration(context)) channel(context) else null

    /** Commit a successful poll only if it still belongs to the same subscription. */
    @Synchronized internal fun finish(context: Context, generation: Long, release: AppRelease?) {
        val prefs = prefs(context)
        val edit = prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis())
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
