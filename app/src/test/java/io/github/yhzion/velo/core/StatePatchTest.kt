package io.github.yhzion.velo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StatePatchTest {
    @Test fun patchWithoutFullBaselineRequestsOnlyOneResync() {
        var requests = 0
        val stream = StateAccumulator { requests++ }
        assertNull(stream.accept(2, false, "{}"))
        assertNull(stream.accept(3, false, "{}"))
        assertEquals(1, requests)
    }

    private val scalars = """"status":"connected","server":"rex","url":"wss://rex","account":"me","hosts":[],"hostsHash":"","session":"s","window":"w","focused":"b","forwards":[]"""
    private val collections = """"sessions":[{"id":"s","tabs":[{"id":"w"}]}],"windows":[{"id":"w"}],"blocks":{"b":{"title":"old"}}"""
    private val full = "{$scalars,$collections}"

    @Test fun consecutiveCallbacksUseNativeBaselineBeforeMainDrains() {
        val stream = StateAccumulator { error("unexpected resync") }
        val queued = ArrayList<UiState>()
        fun receive(generation: Long, isFull: Boolean, text: String) {
            queued += checkNotNull(stream.accept(generation, isFull, text))
        }
        receive(8, true, full)
        receive(9, false, """{"blocks":{"b":{"title":"new"}}}""")
        receive(10, false, "{\"sessions\":[]}")
        val cleared = scalars.substringBefore("\"session\":") + """"session":null,"window":null,"focused":null,"forwards":[]"""
        receive(11, false, "{$cleared}")

        val (first, second, third, last) = queued
        assertEquals("old", first.blocks.getValue("b").title)
        assertEquals("new", second.blocks.getValue("b").title)
        assertSame(first.sessions, second.sessions)
        assertEquals("b", third.focused)
        assertEquals("w", third.window)
        assertEquals("s", third.session)
        assertSame(second.blocks, third.blocks)
        assertEquals(emptyList<SessionInfo>(), third.sessions)
        assertSame(third.sessions, last.sessions)
        assertSame(first.windows, last.windows)
        assertSame(third.blocks, last.blocks)
        assertNull(last.session)
        assertNull(last.window)
        assertNull(last.focused)
    }

    @Test fun windowDecodesMinimizedLayersGroupedByLayer() {
        val grouped = UiState.decode("""{"windows":[{"id":"w","minimizedBlocks":["b","c","d"],"minimizedLayers":[["b"],["c","d"]]}]}""")
        assertEquals(listOf(listOf("b"), listOf("c", "d")), grouped.windows.single().minimizedLayers)
        assertEquals(listOf("b", "c", "d"), grouped.windows.single().minimizedBlocks)
        val plain = UiState.decode("""{"windows":[{"id":"w"}]}""")
        assertEquals(emptyList<List<String>>(), plain.windows.single().minimizedLayers)
    }

    @Test fun gapDropsSubsequentPatchesUntilNewFull() {
        var requests = 0
        val stream = StateAccumulator { requests++ }
        checkNotNull(stream.accept(1, true, full))
        assertNull(stream.accept(3, false, """{"windows":[]}"""))
        assertNull(stream.accept(4, false, """{"sessions":[]}"""))
        assertEquals(1, requests)
        val recovered = checkNotNull(stream.accept(5, true, full))
        val updated = checkNotNull(stream.accept(6, false, """{"windows":[],"blocks":{}}"""))
        assertSame(recovered.sessions, updated.sessions)
        assertEquals(emptyList<WindowInfo>(), updated.windows)
        assertEquals(emptyMap<String, BlockInfo>(), updated.blocks)
        assertNull(stream.accept(8, false, """{"blocks":{}}"""))
        assertEquals(2, requests)
    }

    @Test fun staleAndDuplicateFullOrPatchCannotReplaceBaseline() {
        for (isFull in listOf(true, false)) {
            for (generation in listOf(8L, 9L)) {
                var requests = 0
                val stream = StateAccumulator { requests++ }
                checkNotNull(stream.accept(9, true, full))
                assertNull(stream.accept(generation, isFull, if (isFull) full else """{"windows":[]}"""))
                assertEquals(1, requests)
                checkNotNull(stream.accept(10, true, full))
            }
        }
    }

    @Test fun invalidUpdatesNeverAdvanceAndMalformedFullDoesNotSelfRequestForever() {
        for (bad in listOf("{", "{}", "[]", """{"sessions":null}""", """{"windows":[{}]}""",
            """{"session":null}""", """{"blocks":{},"sessions":[false]}""")) {
            var requests = 0
            val stream = StateAccumulator { requests++ }
            checkNotNull(stream.accept(5, true, full))
            assertNull(stream.accept(6, false, bad))
            repeat(3) { assertNull(stream.accept(7, true, "{$scalars}")) }
            assertEquals(1, requests)
            // A good full at the rejected patch's generation proves neither failure committed.
            assertEquals("old", checkNotNull(stream.accept(6, true, full)).blocks.getValue("b").title)
        }
    }

    @Test fun forwardsTravelWithTheScalars() {
        val stream = StateAccumulator { error("unexpected resync") }
        assertEquals(emptyList<ForwardInfo>(), checkNotNull(stream.accept(1, true, full)).forwards)
        val forwarding = scalars.replace(""""forwards":[]""", """"forwards":[{"block":"block:f","host":"localhost","port":5173,"localPort":41000,"connections":2}]""")
        val next = checkNotNull(stream.accept(2, false, "{$forwarding}"))
        assertEquals(listOf(ForwardInfo("block:f", "localhost", 5173, 41000, 2)), next.forwards)
        // A patch of another group keeps them.
        assertEquals(next.forwards, checkNotNull(stream.accept(3, false, """{"blocks":{}}""")).forwards)
    }
}
