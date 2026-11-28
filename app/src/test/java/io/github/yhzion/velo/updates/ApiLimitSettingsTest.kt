package io.github.yhzion.velo.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.VeloApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #264, #274: what the API told the checks of its limit, as the automatic checks read it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VeloApplication::class)
class ApiLimitSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val until = now + 30 * 60_000

    @Test fun anAnswerClearsTheStoredLimit() {
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
        UpdateSettings.apiSeen(context, now - 3_000, 0L) // nothing stored: still nothing
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
        UpdateSettings.apiSeen(context, now - 2_000, until)
        assertEquals(until, UpdateSettings.apiBlockedUntil(context))
        UpdateSettings.apiSeen(context, now - 1_000, 0L)
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
    }

    /** #274, case 1: within one check, a later page's refusal stores the limit after the first page's 200 cleared it. */
    @Test fun aRefusalAfterAPageOfTheSameCheckStoresTheLimit() {
        val limit = UpdateSettings.apiLimit(context, now - 1_000)
        limit.seen(0L)
        limit.seen(until)
        assertEquals(until, UpdateSettings.apiBlockedUntil(context))
    }

    /** #274, case 3: a slow check's late refusal does not undo the clearing of a check that started after it, nor the reverse. */
    @Test fun aLateAnswerOfAnEarlierCheckChangesNothing() {
        val slow = UpdateSettings.apiLimit(context, now - 60_000) // on the old network
        val manual = UpdateSettings.apiLimit(context, now - 1_000) // on another network
        UpdateSettings.apiSeen(context, now - 120_000, until) // the limit stored before both
        manual.seen(0L)
        slow.seen(until)
        assertEquals("the newer clearing stands", 0L, UpdateSettings.apiBlockedUntil(context))
        // And a newer limit stands over a late page of an earlier check.
        manual.seen(until)
        slow.seen(0L)
        assertEquals("the newer limit stands", until, UpdateSettings.apiBlockedUntil(context))
    }

    /** A stored start time ahead of the clock, which was set back since, says nothing: the next answer stands. */
    @Test fun aStartTimeAheadOfTheClockDoesNotHoldAnswersBack() {
        UpdateSettings.apiSeen(context, now + 86_400_000, until)
        UpdateSettings.apiSeen(context, now - 1_000, 0L)
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
    }
}
