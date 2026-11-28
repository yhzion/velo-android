package io.github.yhzion.velo.updates

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

class UpdateCheckJobService : JobService() {
    private val scope = MainScope()
    private var work: Job? = null
    private var runningGeneration = -1L

    override fun onStartJob(params: JobParameters): Boolean {
        val generation = params.extras.getLong(UpdateSettings.GENERATION, -1L)
        val channel = UpdateSettings.jobChannel(this, generation) ?: return false
        work?.cancel()
        runningGeneration = generation
        val startedAt = System.currentTimeMillis()
        work = scope.launch {
            var blockedUntil = 0L
            try {
                val release = runInterruptible(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    val installed = packageManager.getPackageInfo(packageName, 0).longVersionCode
                    val service = this@UpdateCheckJobService
                    blockedUntil = UpdateSettings.apiBlockedUntil(service)
                    ReleaseFeed.update(channel, installed, blockedUntil) { UpdateSettings.apiAnswered(service) }
                }
                UpdateSettings.finish(this@UpdateCheckJobService, generation, startedAt, release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (limited: RateLimitedException) {
                // The stored limit itself, rethrown without asking the API: not stored again (#264).
                if (limited.resetAt != blockedUntil) UpdateSettings.limitReached(this@UpdateCheckJobService, limited.resetAt)
                Log.w("UpdateCheck", "Background update check waits for GitHub's limit to reset")
            } catch (failure: Exception) {
                // A failed feed request is not evidence that an announced release is gone.
                Log.w("UpdateCheck", "Background update check failed", failure)
            } finally {
                // Stop/destroy owns completion after cancellation, even if blocking I/O returned late.
                if (isActive) {
                    work = null
                    runningGeneration = -1L
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (params.extras.getLong(UpdateSettings.GENERATION, -1L) == runningGeneration) {
            work?.cancel()
            work = null
            runningGeneration = -1L
        }
        return false // Let the next periodic check run; never retry an obsolete subscription.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
