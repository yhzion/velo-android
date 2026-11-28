package io.github.yhzion.velo.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** The installed build's version code, which every check compares releases with. */
internal fun installedVersionCode(context: Context): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

/**
 * Blocking I/O. The Updates page's check of [channel]: every release newer than [installed]. What the API
 * says of its limit is stored as of this check's start (#274): the time it is called (#289).
 */
internal fun manualCheck(context: Context, channel: UpdateChannel, installed: Long): List<AppRelease> {
    val startedAt = UpdateSettings.clock()
    return ReleaseFeed.newer(channel, installed, UpdateSettings.apiLimit(context.applicationContext, startedAt), startedAt)
}

/**
 * "Check for updates automatically": when Velo comes to the foreground, a feed check of the
 * selected channel if one is due ([foregroundCheckDue]). It runs off the main thread, outlives
 * the activity that asked for it, and fails silently; its result is what [UpdateSettings.status]
 * reports. A debug build does not check: test installs and launches would use up the API limit
 * the whole network shares. Main thread only.
 */
internal object AutoUpdateCheck {
    // The process's own scope: a check that started is not tied to one activity instance.
    private val scope = MainScope()
    /** A check is under way; read by tests to wait for it (#289). */
    internal var running = false
        private set
    private var lastAttempt = 0L

    /**
     * Forgets the last attempt, as a new process would. Tests only: Robolectric shares this object across
     * test classes, so a fake time left here could hold off a later test's check whose clock falls within
     * the retry wait after it (#292). A check still under way fails the test that left it, not a later one.
     */
    internal fun resetForTests() {
        check(!running) { "A foreground check is still under way" }
        lastAttempt = 0L
    }

    fun onForeground(context: Context) {
        val app = context.applicationContext
        val now = UpdateSettings.clock()
        // Release APKs are arm64 only: another device has no update to be told about.
        if (running || "arm64-v8a" !in Build.SUPPORTED_ABIS) return
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return
        if (!foregroundCheckDue(UpdateSettings.automatic(app), UpdateSettings.lastCheck(app), lastAttempt, now)) return
        running = true
        lastAttempt = now
        val channel = UpdateSettings.channel(app)
        scope.launch {
            try {
                val blockedUntil = UpdateSettings.apiBlockedUntil(app)
                val release = runInterruptible(Dispatchers.IO) {
                    ReleaseFeed.update(channel, installedVersionCode(app), blockedUntil, UpdateSettings.apiLimit(app, now), now)
                }
                UpdateSettings.record(app, channel, now, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limited: RateLimitedException) {
                // The feed stored the limit as the API told it ([UpdateSettings.apiSeen]).
                Log.w("UpdateCheck", "Foreground update check waits for GitHub's limit to reset")
            } catch (failure: Exception) {
                Log.w("UpdateCheck", "Foreground update check failed", failure)
            } finally {
                running = false
            }
        }
    }
}
