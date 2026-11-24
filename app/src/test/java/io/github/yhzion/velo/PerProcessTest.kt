package io.github.yhzion.velo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

private class Controller(val n: Int)

private class Process {
    val made = mutableListOf<Controller>()
    val destroyed = mutableListOf<Controller>()
    val shared = PerProcess(create = { Controller(made.size + 1).also { made += it } }, destroy = { destroyed += it })
}

/** Owners are activity instances: each `Any()` below is one `MainActivity`. */
class PerProcessTest {

    @Test
    fun threeActivitiesInOneProcessShareOneController() {
        // Measured on the Fold8: three MainActivity records in one task made three controllers.
        val f = Process()

        val first = f.shared.acquire(Any())
        val second = f.shared.acquire(Any())
        val third = f.shared.acquire(Any())

        assertSame(first, second)
        assertSame(first, third)
        assertEquals(1, f.made.size)
    }

    @Test
    fun aRecreatedActivityFindsTheSameController() {
        val f = Process()
        val old = Any()
        val controller = f.shared.acquire(old)

        // The system reclaimed the activity, or a configuration change: not finishing.
        f.shared.release(old, forGood = false)
        val again = f.shared.acquire(Any())

        assertSame(controller, again)
        assertEquals(emptyList<Controller>(), f.destroyed)
        assertEquals(1, f.made.size)
    }

    @Test
    fun theLastActivityFinishingDestroysItAndTheNextLaunchGetsANewOne() {
        val f = Process()
        val activity = Any()
        val controller = f.shared.acquire(activity)

        f.shared.release(activity, forGood = true)

        assertEquals(listOf(controller), f.destroyed)
        val next = f.shared.acquire(Any())
        assertNotSame(controller, next)
        assertEquals(2, f.made.size)
        assertEquals(listOf(controller), f.destroyed)
    }

    @Test
    fun aFinishingActivityReportedAfterItsSuccessorStartedLeavesTheControllerAlone() {
        // Android may deliver the finished activity's onDestroy after the next one's onCreate.
        val f = Process()
        val finished = Any()
        val successor = Any()
        val controller = f.shared.acquire(finished)
        assertSame(controller, f.shared.acquire(successor))

        f.shared.release(finished, forGood = true)

        assertEquals(emptyList<Controller>(), f.destroyed)

        f.shared.release(successor, forGood = true)

        assertEquals(listOf(controller), f.destroyed)
    }

    @Test
    fun aControllerKeptForARecreationIsDestroyedWhenTheRecreatedActivityFinishes() {
        val f = Process()
        val old = Any()
        val recreated = Any()
        val controller = f.shared.acquire(old)
        f.shared.release(old, forGood = false)
        f.shared.acquire(recreated)

        f.shared.release(recreated, forGood = true)

        assertEquals(listOf(controller), f.destroyed)
    }

    @Test
    fun releasingTwiceOrWithoutAcquiringDestroysNothingMore() {
        val f = Process()
        val activity = Any()
        val controller = f.shared.acquire(activity)
        f.shared.acquire(activity)

        f.shared.release(Any(), forGood = true)
        assertEquals("a stranger does not end it", emptyList<Controller>(), f.destroyed)

        f.shared.release(activity, forGood = true)
        f.shared.release(activity, forGood = true)

        assertEquals("one owner acquired twice is one owner; destroyed once", listOf(controller), f.destroyed)
    }

    @Test
    fun ownersAreToldApartByIdentityNotEquality() {
        // Two owners that are equal as values are still two activities.
        val f = Process()
        val a = String(charArrayOf('x'))
        val b = String(charArrayOf('x'))
        val controller = f.shared.acquire(a)
        f.shared.acquire(b)

        f.shared.release(a, forGood = true)

        assertEquals(emptyList<Controller>(), f.destroyed)
        f.shared.release(b, forGood = true)
        assertEquals(listOf(controller), f.destroyed)
    }
}
