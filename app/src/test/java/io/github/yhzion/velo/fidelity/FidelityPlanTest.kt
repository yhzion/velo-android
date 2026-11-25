package io.github.yhzion.velo.fidelity

import org.junit.Assert.assertEquals
import org.junit.Test

class FidelityPlanTest {
    @Test fun planKeepsOnlyRegisteredScreensAtTheBoardSize() {
        val plan = """{"captures": [
            {"screen": "settings-updates", "device": "bar", "board": "SetBarUpdates", "w": 393, "h": 1240},
            {"screen": "start-1", "device": "bar", "board": "StartBar1", "w": 393, "h": 894}
        ]}"""
        assertEquals(
            listOf(Capture("settings-updates", "bar", "SetBarUpdates", 393, 1240)),
            captures(plan, registered = setOf("settings-updates")),
        )
    }

    @Test fun withoutAPlanEveryRegisteredScreenIsTakenAtTheThreeDevices() {
        assertEquals(
            listOf(
                Capture("a", "bar", null, 393, 894),
                Capture("a", "closed", null, 475, 751),
                Capture("a", "open", null, 821, 1088),
            ),
            captures(null, registered = setOf("a")),
        )
    }

    @Test fun onlyTheNamedScreensWhenAFilterIsGiven() {
        assertEquals(listOf("b"), captures(null, setOf("a", "b"), only = setOf("b")).map { it.screen }.distinct())
    }

    @Test fun nodeBoxesAreInDp() {
        val n = TaggedNode("row", xPx = 42f, yPx = 105f, wPx = 1031f, hPx = 157.5f, density = 2.625f, text = "Updates")
        assertEquals(16f, n.x, 1e-4f)
        assertEquals(40f, n.y, 1e-4f)
        assertEquals(392.762f, n.w, 1e-3f)
        assertEquals(60f, n.h, 1e-4f)
    }

    @Test fun captureFileNamesJoinScreenAndDevice() {
        assertEquals("settings-updates@bar", Capture("settings-updates", "bar", null, 393, 894).fileName)
    }
}
