package io.github.yhzion.velo.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.app.job.JobParameters
import android.os.Build
import android.os.Looper
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.VeloApplication
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * #289: what the API's answers say of its limit reaches the stored limit through the real network code,
 * and each caller stores it as of its own start, so a late answer of an earlier check changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VeloApplication::class)
class UpdateCheckCallersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = 1_800_000_000_000L
    private val time = AtomicLong(base)
    private val asked = AtomicInteger()
    private val savedSource = ReleaseFeed.source
    private val savedClock = UpdateSettings.clock
    private val savedAbis = Build.SUPPORTED_ABIS
    private val savedFlags = context.applicationInfo.flags

    @Before fun fakeTheClock() {
        UpdateSettings.clock = { time.get() }
    }

    @After fun restore() {
        ReleaseFeed.source = savedSource
        UpdateSettings.clock = savedClock
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", savedAbis)
        context.applicationInfo.flags = savedFlags
        AutoUpdateCheck.resetForTests()
    }

    /** An answer of the API: [status] with [headers], and [body] for a 200. */
    private class Answer(uri: URI, val status: Int, val headers: Map<String, String>, val body: String, val serverDate: Long) :
        HttpURLConnection(uri.toURL()) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = headers[name]
        override fun getDate() = serverDate
        override fun getContentLengthLong() = body.length.toLong()
        override fun getInputStream(): InputStream = body.byteInputStream()
    }

    /** The API's headers for [remaining] requests left until [resetAt] (epoch ms) by the server's clock. */
    private fun limitHeaders(remaining: Int, resetAt: Long) =
        mapOf("X-RateLimit-Remaining" to "$remaining", "X-RateLimit-Reset" to "${resetAt / 1000}")

    @Test fun aPageWithNoRequestLeftStoresTheLimitByItsHeaders() {
        val serverNow = base - 5 * 60_000 // The phone's clock is five minutes ahead of GitHub's.
        val remaining = AtomicInteger(0)
        ReleaseFeed.source = ReleaseFeed.network(open = { uri ->
            assertTrue(uri.toString(), uri.toString().startsWith("https://api.github.com/repos/yhzion/velo-android/releases?"))
            Answer(uri, 200, limitHeaders(remaining.get(), serverNow + 25 * 60_000), "[]", serverNow)
        }, clock = { time.get() })

        assertEquals(emptyList<AppRelease>(), manualCheck(context, UpdateChannel.Preview, 0))
        // 25 minutes by the server's clock, counted from the phone's.
        assertEquals(base + 25 * 60_000, UpdateSettings.apiBlockedUntil(context))

        // A later 200 with requests left clears it.
        time.addAndGet(1_000)
        remaining.set(7)
        manualCheck(context, UpdateChannel.Preview, 0)
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
    }

    /** A 403 with no request left, through the real network code, stores the limit too. */
    @Test fun aRefusalStoresTheLimitByItsHeaders() {
        ReleaseFeed.source = ReleaseFeed.network(open = { uri ->
            Answer(uri, 403, limitHeaders(0, base + 40 * 60_000), "", base)
        }, clock = { time.get() })
        val refused = assertThrows(RateLimitedException::class.java) { manualCheck(context, UpdateChannel.Preview, 0) }
        assertEquals(base + 40 * 60_000, refused.resetAt)
        assertEquals(base + 40 * 60_000, UpdateSettings.apiBlockedUntil(context))
    }

    /**
     * The API refuses the check until [until], answering late: [whileAsked] runs first, while the check
     * waits, and the clock moves on 2 s before the refusal comes.
     */
    private fun refuseLate(until: Long, whileAsked: () -> Unit = {}) {
        ReleaseFeed.source = object : FeedSource {
            override fun page(page: Int): ApiPage {
                asked.incrementAndGet()
                whileAsked()
                time.addAndGet(2_000)
                throw RateLimitedException(until)
            }
            override fun manifest() = throw AssertionError("A Preview check does not read the manifest")
            override fun asset(tag: String, name: String) = throw AssertionError("No asset is read")
        }
    }

    /** While the check waits, a check that started 1 s after it clears the limit. */
    private fun aNewerCheckClears(startedAt: Long) = { UpdateSettings.apiSeen(context, startedAt + 1_000, 0L) }

    /** The start time of the check whose answer the stored limit is: what [UpdateSettings.apiSeen] keeps. */
    private fun storedBy() = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).getLong("apiSeenBy", -1)

    /**
     * Run [check] twice: a refusal with nothing else going on is stored as of the check's start, and one that
     * comes after a newer check cleared the limit changes nothing. The second starts [gap] after the first.
     */
    private fun eachCallerStoresAsOfItsStart(gap: Long, check: () -> Unit) {
        val first = time.get()
        refuseLate(first + 60_000)
        check()
        assertEquals(1, asked.get())
        assertEquals("the refusal is stored", first + 60_000, UpdateSettings.apiBlockedUntil(context))
        // Neither an earlier time (the system's clock, 0) nor a later one (the answer's) passes for the start.
        assertEquals("as of the check's start", first, storedBy())

        time.set(first + gap)
        val second = time.get()
        refuseLate(second + 30 * 60_000, whileAsked = aNewerCheckClears(second))
        check()
        assertEquals(2, asked.get())
        assertEquals("the newer check's clearing stands", 0L, UpdateSettings.apiBlockedUntil(context))
        assertEquals(second + 1_000, storedBy())
    }

    @Test fun theUpdatesPageStoresTheLimitAsOfItsStart() = eachCallerStoresAsOfItsStart(gap = 120_000) {
        assertThrows(RateLimitedException::class.java) { manualCheck(context, UpdateChannel.Preview, 0) }
    }

    @Test fun theForegroundCheckStoresTheLimitAsOfItsStart() {
        // A release build on an arm64 phone: the only one that checks.
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        context.applicationInfo.flags = savedFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        UpdateSettings.select(context, UpdateChannel.Preview)
        // The second check comes once the wait after the failed first has passed.
        eachCallerStoresAsOfItsStart(gap = FOREGROUND_RETRY_INTERVAL) {
            AutoUpdateCheck.onForeground(context)
            assertTrue("the check started", AutoUpdateCheck.running)
            settle { !AutoUpdateCheck.running }
        }
    }

    /**
     * #292: a failed foreground check holds off the next by the retry wait, and the reset that each test
     * ends with forgets it, so a later test's check at the same fake time is not skipped.
     */
    @Test fun aResetForgetsTheLastForegroundAttempt() {
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        context.applicationInfo.flags = savedFlags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        UpdateSettings.select(context, UpdateChannel.Preview)
        ReleaseFeed.source = object : FeedSource {
            override fun page(page: Int): ApiPage {
                asked.incrementAndGet()
                throw IOException("offline")
            }
            override fun manifest() = throw AssertionError("A Preview check does not read the manifest")
            override fun asset(tag: String, name: String) = throw AssertionError("No asset is read")
        }
        val check = {
            AutoUpdateCheck.onForeground(context)
            settle { !AutoUpdateCheck.running }
        }
        check()
        assertEquals("the first check ran", 1, asked.get())
        time.addAndGet(1_000)
        check()
        assertEquals("the failed attempt holds off the next", 1, asked.get())

        AutoUpdateCheck.resetForTests()
        check()
        assertEquals("after the reset the check runs", 2, asked.get())
    }

    @Test fun theBackgroundCheckStoresTheLimitAsOfItsStart() {
        UpdateSettings.select(context, UpdateChannel.Preview)
        UpdateSettings.enable(context, true)
        val generation = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).getLong(UpdateSettings.GENERATION, -1)
        eachCallerStoresAsOfItsStart(gap = 120_000) {
            val service = Robolectric.buildService(UpdateCheckJobService::class.java).create().get()
            val params = ReflectionHelpers.callConstructor(JobParameters::class.java) // Robolectric's own, as Android's is hidden.
            ReflectionHelpers.setField(params, "extras", PersistableBundle().apply { putLong(UpdateSettings.GENERATION, generation) })
            assertTrue(service.onStartJob(params))
            settle { shadowOf(service).isJobFinished }
        }
    }

    /** Runs the main looper until [done], while the check's I/O runs on another thread. */
    private fun settle(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!done()) {
            assertTrue("the check did not finish", System.nanoTime() < deadline)
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }
}
