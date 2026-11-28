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
 * "Check for updates automatically": when Velo comes to the foreground, a feed check of the
 * selected channel if one is due ([foregroundCheckDue]). It runs off the main thread, outlives
 * the activity that asked for it, and fails silently; its result is what [UpdateSettings.status]
 * reports. A debug build does not check: test installs and launches would use up the API limit
 * the whole network shares. Main thread only.
 */
internal object AutoUpdateCheck {
    // The process's own scope: a check that started is not tied to one activity instance.
    private val scope = MainScope()
    private var running = false
    private var lastAttempt = 0L

    fun onForeground(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
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
                val release = runInterruptible(Dispatchers.IO) { ReleaseFeed.update(channel, installedVersionCode(app), blockedUntil) }
                UpdateSettings.record(app, channel, now, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limited: RateLimitedException) {
                UpdateSettings.limitReached(app, limited.resetAt)
                Log.w("UpdateCheck", "Foreground update check waits for GitHub's limit to reset")
            } catch (failure: Exception) {
                Log.w("UpdateCheck", "Foreground update check failed", failure)
            } finally {
                running = false
            }
        }
    }
}
