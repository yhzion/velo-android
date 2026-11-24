package io.github.yhzion.velo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OneByOneTest {
    private val thread = Executors.newSingleThreadExecutor()
    private val failures = Collections.synchronizedList(mutableListOf<String>())
    private val calls = OneByOne(thread) { failures += it.message.orEmpty() }
    private val ran = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun stop() {
        thread.shutdownNow()
    }

    private fun drain() {
        val done = CountDownLatch(1)
        calls.submit { done.countDown() }
        assertTrue("the calls did not end", done.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun anUnregistrationGivenBeforeItsRegistrationRanRunsAfterIt() {
        // The thread is busy (another caller's registration), so the gauge's registration waits; the gauge goes
        // meanwhile and gives its unregistration.
        val busy = CountDownLatch(1)
        calls.submit { busy.await(10, TimeUnit.SECONDS); ran += "other registration" }
        calls.submit { ran += "register battery" }
        calls.submit { ran += "unregister battery" }
        busy.countDown()
        drain()
        assertEquals(listOf("other registration", "register battery", "unregister battery"), ran)
    }

    @Test
    fun aCallThatThrowsIsReportedAndTheNextOneRuns() {
        calls.submit { throw IllegalArgumentException("Receiver not registered") }
        calls.submit { ran += "register network" }
        drain()
        assertEquals(listOf("Receiver not registered"), failures)
        assertEquals(listOf("register network"), ran)
    }
}
