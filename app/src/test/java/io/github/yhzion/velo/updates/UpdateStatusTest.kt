package io.github.yhzion.velo.updates

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateStatusTest {
    private val installed = 3_000_037L

    @Test fun availableTextStaysUntilInstalledAndTheDotClearsOnceSeen() {
        val found = UpdateStatus(tag = "v2.9.20", code = 3_000_038, seen = 0)
        assertEquals("2.9.20", found.available(installed))
        assertTrue(found.unseen(installed))

        val seen = found.copy(seen = 3_000_038)
        assertEquals("2.9.20", seen.available(installed))
        assertFalse(seen.unseen(installed))

        // A newer build than the one seen lights the dot again.
        assertTrue(seen.copy(tag = "v2.9.21", code = 3_000_039).unseen(installed))

        for (now in listOf(3_000_038L, 3_000_040L)) {
            assertNull(found.available(now))
            assertFalse(found.unseen(now))
        }
    }

    @Test fun nothingFoundShowsNothing() {
        val none = UpdateStatus(tag = null, code = 0, seen = 0)
        assertNull(none.available(installed))
        assertFalse(none.unseen(installed))
        assertNull(UpdateStatus(tag = null, code = 3_000_090, seen = 0).available(installed))
        assertEquals("2.6.0-preview.12", UpdateStatus("v2.6.0-preview.12", 3_000_100, 0).available(installed))
    }

    @Test fun foregroundCheckRunsOnlyWhenAutomaticAndTheLastSuccessIsTwelveHoursOld() {
        val hour = TimeUnit.HOURS.toMillis(1)
        val now = 1_000 * hour
        assertTrue(foregroundCheckDue(true, lastSuccess = 0, lastAttempt = 0, now = now))
        assertTrue(foregroundCheckDue(true, now - 12 * hour, 0, now))
        assertFalse(foregroundCheckDue(true, now - 12 * hour + 1, 0, now))
        assertFalse(foregroundCheckDue(false, 0, 0, now))
        assertFalse(foregroundCheckDue(false, now - 48 * hour, 0, now))
        // A clock set back makes the last check look like the future; it is not trusted.
        assertTrue(foregroundCheckDue(true, now + hour, 0, now))
    }

    @Test fun aFailedAttemptWaitsBeforeTheNextForegroundCheck() {
        val minute = TimeUnit.MINUTES.toMillis(1)
        val now = TimeUnit.DAYS.toMillis(100)
        assertFalse(foregroundCheckDue(true, 0, lastAttempt = now - 14 * minute, now = now))
        assertTrue(foregroundCheckDue(true, 0, lastAttempt = now - 15 * minute, now = now))
        assertTrue(foregroundCheckDue(true, 0, lastAttempt = now + minute, now = now))
    }

    @Test fun anOlderCheckNeverReplacesTheResultOfANewerOne() {
        assertTrue(replacesStoredCheck(startedAt = 200, storedStartedAt = 0, now = 300))
        assertTrue(replacesStoredCheck(startedAt = 200, storedStartedAt = 200, now = 300))
        assertFalse(replacesStoredCheck(startedAt = 199, storedStartedAt = 200, now = 300))
        // A stored start in the future means the clock was set back: it must not block every later check.
        assertTrue(replacesStoredCheck(startedAt = 100, storedStartedAt = 200, now = 150))
    }

    private fun release(tag: String, code: Long, vararg sections: ChangeSection) =
        AppRelease(tag, code, UpdateChannel.Stable, "", "", "", sections.toList())

    @Test fun whatsNewHasAHeadingPerVersionOnlyWhenSeveralReleasesAreNewer() {
        val fixed = ChangeSection(ChangeKind.Fixed, listOf("Hangul no longer looks flat."))
        val added = ChangeSection(ChangeKind.Added, listOf("A dot on Settings."))
        assertEquals(listOf(ReleaseChanges(null, listOf(added))), whatsNew(listOf(release("v2.9.20", 38, added))))
        assertEquals(
            listOf(ReleaseChanges("2.9.21", listOf(added)), ReleaseChanges("2.9.20", listOf(fixed))),
            whatsNew(listOf(release("v2.9.21", 39, added), release("v2.9.20", 38, fixed))),
        )
        // Releases without a block add nothing, but still make the list one of several versions.
        assertEquals(listOf(ReleaseChanges("2.9.20", listOf(fixed))),
            whatsNew(listOf(release("v2.9.21", 39), release("v2.9.20", 38, fixed))))
        assertEquals(emptyList<ReleaseChanges>(), whatsNew(listOf(release("v2.9.19", 37))))
        assertEquals(emptyList<ReleaseChanges>(), whatsNew(emptyList()))
    }
}
