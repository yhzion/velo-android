package io.github.yhzion.velo.updates

import java.util.concurrent.TimeUnit

/** How often an automatic check runs when Velo comes to the foreground. */
internal val FOREGROUND_CHECK_INTERVAL = TimeUnit.HOURS.toMillis(12)

/** How long a failed foreground check waits before Velo coming to the foreground tries again. */
internal val FOREGROUND_RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(15)

/** A tag without its `v`: the version people read. */
internal fun versionLabel(tag: String) = tag.removePrefix("v")

/**
 * What the checks remember: the newest eligible release they found ([tag], [code]; no tag when
 * the last check found none) and the version code shown on the Updates page ([seen]).
 */
internal data class UpdateStatus(val tag: String?, val code: Long, val seen: Long) {
    /** The version to name as available ("2.9.20 available"): until it, or a newer build, is installed. */
    fun available(installed: Long): String? = tag?.takeIf { code > installed }?.let(::versionLabel)

    /** Whether Settings shows the dot: an available build the Updates page has not shown yet. */
    fun unseen(installed: Long): Boolean = available(installed) != null && code > seen
}

/**
 * Whether coming to the foreground starts a check: automatic checks are on, the last successful
 * check is [FOREGROUND_CHECK_INTERVAL] old, and no attempt failed in the last
 * [FOREGROUND_RETRY_INTERVAL]. A time in the future (the clock was set back) counts as old.
 */
internal fun foregroundCheckDue(automatic: Boolean, lastSuccess: Long, lastAttempt: Long, now: Long): Boolean {
    fun older(time: Long, interval: Long) = time <= 0 || now < time || now - time >= interval
    return automatic && older(lastSuccess, FOREGROUND_CHECK_INTERVAL) && older(lastAttempt, FOREGROUND_RETRY_INTERVAL)
}

/**
 * Whether a check that started at [startedAt] may store its result, at [now], over one that started at
 * [storedStartedAt]: a slow check that started first must not hide a release a later one found. A
 * stored start after [now] (the clock was set back) is not trusted.
 */
internal fun replacesStoredCheck(startedAt: Long, storedStartedAt: Long, now: Long) =
    startedAt >= storedStartedAt || storedStartedAt > now

/** The changes of one release on the Updates page; [heading] names its version when several are newer. */
internal data class ReleaseChanges(val heading: String?, val sections: List<ChangeSection>)

/** "What's new" for [releases], newest first: one entry per release that has change notes. */
internal fun whatsNew(releases: List<AppRelease>): List<ReleaseChanges> {
    val several = releases.size > 1
    return releases.filter { it.changes.isNotEmpty() }
        .map { ReleaseChanges(if (several) versionLabel(it.tag) else null, it.changes) }
}
