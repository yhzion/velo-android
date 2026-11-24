package io.github.yhzion.velo.core

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated JVM decode/merge comparison. Fixture construction and initial sync are not timed. */
class StateDecodeBenchmark {
    @Test fun fullVersusScalarPatch() {
        assumeTrue(System.getenv("VELO_STATE_BENCH") == "1")
        val sessions = (0 until 10).joinToString { s ->
            val tabs = (6 * s until 6 * s + 6).joinToString { i ->
                """{"id":"w$i","label":"tab $i","block":"b$i"}"""
            }
            """{"id":"s$s","label":"session $s","tabs":[$tabs]}"""
        }
        val windows = (0 until 6).joinToString { """{"id":"w$it","layout":{"block":"b$it"}}""" }
        val blocks = (0 until 60).joinToString { """"b$it":{"title":"terminal $it","cwd":"/home/alice","program":"sh"}""" }
        val groups = """"sessions":[$sessions],"windows":[$windows],"blocks":{$blocks}"""
        val scalars = Array(2) { i ->
            """"status":"connected","server":"rex","url":"wss://rex","account":"rex","hosts":[],"hostsHash":"","session":"s0","window":"w$i","focused":"b$i","forwards":[]""""
        }
        val full = Array(2) { "{${scalars[it]},$groups}" }
        val patch = Array(2) { "{${scalars[it]}}" }
        val iterations = 1_000

        fun batch(isFull: Boolean, count: Int): Long {
            val stream = StateAccumulator { error("fixture rejected") }
            checkNotNull(stream.accept(1, true, full[1]))
            val payload = if (isFull) full else patch
            var checksum = 0
            val start = System.nanoTime()
            repeat(count) { i ->
                val state = checkNotNull(stream.accept(i + 2L, isFull, payload[i and 1]))
                checksum += state.blocks.size + state.focused!!.last().digitToInt()
            }
            val elapsed = System.nanoTime() - start
            check(checksum == count * 60 + count / 2)
            return elapsed / count
        }

        repeat(3) { batch(true, 300); batch(false, 300) }
        repeat(5) { round ->
            for (isFull in listOf(round % 2 == 0, round % 2 != 0)) {
                val ns = batch(isFull, iterations)
                val mode = if (isFull) "full" else "scalar patch"
                println("state decode/merge: $mode round=${round + 1} $ns ns/op ($iterations updates)")
            }
        }
    }
}
