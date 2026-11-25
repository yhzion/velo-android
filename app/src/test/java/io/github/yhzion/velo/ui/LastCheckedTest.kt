package io.github.yhzion.velo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class LastCheckedTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test fun aCheckEarlierTodaySaysTodayAndTheTime() {
        assertEquals("Last checked: today, 9:41 PM", lastCheckedText(at("2026-11-26T21:41:00"), at("2026-11-26T23:00:00"), zone, Locale.US))
    }

    @Test fun aCheckTheDayBeforeSaysYesterday() {
        assertEquals("Last checked: yesterday, 8:05 AM", lastCheckedText(at("2026-11-25T08:05:00"), at("2026-11-26T00:10:00"), zone, Locale.US))
    }

    @Test fun anOlderCheckSaysItsDate() {
        assertEquals("Last checked: Nov 20, 2026, 9:41 PM", lastCheckedText(at("2026-11-20T21:41:00"), at("2026-11-26T09:00:00"), zone, Locale.US))
    }

    @Test fun neverCheckedSaysNothing() {
        assertEquals(null, lastCheckedText(0L, at("2026-11-26T09:00:00"), zone, Locale.US))
    }
}
