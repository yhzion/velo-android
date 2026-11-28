package io.github.yhzion.velo.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.VeloApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #264: an API that answers again ends the limit an earlier check stored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = VeloApplication::class)
class ApiLimitSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun anAnswerClearsTheStoredLimit() {
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
        UpdateSettings.apiAnswered(context) // nothing stored: still nothing
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
        UpdateSettings.limitReached(context, 1_800_000_000_000L)
        assertEquals(1_800_000_000_000L, UpdateSettings.apiBlockedUntil(context))
        UpdateSettings.apiAnswered(context)
        assertEquals(0L, UpdateSettings.apiBlockedUntil(context))
    }
}
