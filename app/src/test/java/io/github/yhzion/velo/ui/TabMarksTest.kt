package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.ui.theme.TileBrand
import io.github.yhzion.velo.ui.theme.tileBrand
import org.junit.Assert.*
import org.junit.Test

class TabMarksTest {
    private fun row(tab: TabInfo): TabRow {
        val state = UiState(status = "connected", sessions = listOf(SessionInfo("s", tabs = listOf(tab))))
        return sessionGroups(state, false, emptySet(), false, "").single().rows.single()
    }

    @Test
    fun programMarksHaveDistinctTilesAndAccessibleNames() {
        val hints = listOf("claude-code", "codex", "omp", "pi", "gemini", "opencode")
        assertEquals(listOf("Claude Code", "Codex", "omp", "pi", "Gemini", "OpenCode"), hints.map { TabAgent.from(it)?.label })
        assertEquals(TabAgent.Gemini, TabAgent.from("gemini-cli"))
        assertEquals(TabAgent.Gemini, TabAgent.from(" Gemini "))
        assertEquals(6, hints.map(::tileBrand).distinct().size)
        for (hint in listOf("2.1.329", "node", "bun", "my-codex-wrapper", "my-gemini-wrapper")) {
            assertNull(TabAgent.from(hint))
        }
        val tab = TabInfo("w", program = "2.1.329")
        assertEquals("Terminal", row(tab).name)
        assertEquals(TileBrand.Terminal, tileBrand(row(tab).icon))
        assertEquals("Claude Code", row(tab.copy(icon = "claude")).name)
        assertEquals("Claude Code", row(tab.copy(icon = "claude", title = "shell", titleIsLabel = true)).name)
        assertEquals("Gemini", row(tab.copy(icon = "gemini-cli")).name)
        assertEquals("OpenCode", row(tab.copy(icon = "opencode", program = "opencode.exe")).name)
        assertEquals("node", row(tab.copy(program = "node")).name)
    }

    @Test
    fun ompTitlesLoseTheirPiStateAndIconPrefix() {
        val tab = TabInfo("w", program = "omp")
        // Example omp titles: π, a state, a Nerd Font icon (U+F011C, U+F06D6).
        for ((title, name) in listOf(
            "π ⠼ \uDB80\uDD1C ISSUE: 예제 제목" to "ISSUE: 예제 제목",
            "π ⠇ \uDB81\uDED6 FONT: Fix the font picker" to "FONT: Fix the font picker",
            "π ⁝ \uDB81\uDED6 Waiting topic" to "Waiting topic",
            "π > \uF013 HYDRA: Deploy" to "HYDRA: Deploy",
            "π Plain topic" to "Plain topic",
        )) assertEquals(name, row(tab.copy(title = title)).name)
        assertEquals("π ⠼ topic", row(TabInfo("w", program = "bash", title = "π ⠼ topic")).name)
    }

    @Test
    fun openCodeTitlesLoseTheirOcPrefix() {
        // OpenCode 1.19.6 titles: "OpenCode" on its home screen, "OC | <session title>" in a session
        // (cut to 37 characters and an ellipsis past 40), "OC | <id>" on a plugin's screen.
        val tab = TabInfo("w", icon = "opencode", program = "opencode.exe")
        for ((title, name) in listOf(
            "OpenCode" to "OpenCode",
            "OC | Greeting and title probe" to "Greeting and title probe",
            "OC | Refactor the session list into two…" to "Refactor the session list into two…",
            "OC | OpenCode" to "OpenCode",
        )) assertEquals(name, row(tab.copy(title = title)).name)
        assertEquals("OC | topic", row(TabInfo("w", program = "bash", title = "OC | topic")).name)
    }

    @Test
    fun codexTitleFramesShareOneTitleAndOneAccessibleStatus() {
        val tab = TabInfo("w", program = "codex", title = "⠋ QA64 Local thread · qaagents")
        for (frame in "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏") {
            val r = row(tab.copy(title = "$frame QA64 Local thread · qaagents"))
            assertEquals("QA64 Local thread · qaagents", r.name)
            assertEquals("Codex is active", r.indicator?.description)
            for (title in listOf("QA64 $frame Local thread", "QA64 Local thread $frame")) {
                val reordered = row(tab.copy(title = title))
                assertEquals("QA64 Local thread", reordered.name)
                assertEquals(TabIndicator.CodexActive, reordered.indicator)
            }
        }
        assertEquals(TabIndicator.Working, row(tab.copy(activity = "working")).indicator)
        val building = row(tab.copy(icon = "codex", program = "cargo", activity = "working", statusSummary = "Compiling"))
        assertEquals("QA64 Local thread · qaagents", building.name)
        assertEquals(TabIndicator.Working, building.indicator)
        assertEquals("QA64 Local thread · qaagents", row(tab.copy(statusTitle = "Child task")).name)
        assertEquals("● Topic", row(tab.copy(title = "● ⠙ Topic")).name)
        assertEquals("Manual", row(tab.copy(label = "Manual")).name)
        assertEquals("Fix login", row(tab.copy(title = "Fix login")).name)
        assertNull(row(tab.copy(title = "Fix login")).indicator)
        val blocked = "[ ! ] Action Required · QA64 Codex thread · qaagents"
        for (title in listOf(blocked, blocked.replace("[ ! ]", "[ . ]"))) {
            val waiting = row(tab.copy(title = title, statusTitle = "QA64 Codex structured task",
                activity = "blocked", kind = "permission"))
            assertEquals(blocked, waiting.name)
            assertEquals(TabIndicator.Permission, waiting.indicatorWhile(true))
        }
        for (other in listOf(tab.copy(program = "bash"), tab.copy(title = "⠁ Topic"), tab.copy(title = "⠋pattern"))) {
            assertEquals(other.title, row(other).name)
            assertNull(row(other).indicator)
        }
    }

    @Test
    fun codexMicrophoneAndApprovalFramesKeepTheSameTitleAndAccessibleStatus() {
        for (frame in listOf("[ . ]", "[ ! ]")) {
            val tab = TabInfo("w", program = "codex", activity = "blocked", kind = "permission",
                title = "● $frame Action Required · Review patch", statusTitle = "Child task")
            val waiting = row(tab)
            assertEquals("● [ ! ] Action Required · Review patch", waiting.name)
            assertEquals("Waiting for approval", waiting.indicatorWhile(true)?.description)
        }
    }

    @Test
    fun codexApprovalWithoutAThreadYieldsToTheReportedTitleThenSummary() {
        for (mic in listOf("", "● ")) {
            for (frame in listOf("[ . ]", "[ ! ]")) {
                val tab = TabInfo("w", program = "codex", title = "$mic$frame Action Required",
                    statusTitle = "배포 점검", statusSummary = "Checking patch")
                assertEquals("배포 점검", row(tab).name)
                assertEquals("Checking patch", row(tab.copy(statusTitle = "")).name)
                assertEquals("${mic}[ ! ] Action Required", row(tab.copy(statusTitle = "", statusSummary = "")).name)
            }
        }
    }

    @Test
    fun workingDotsAdvanceAndOnlyAnimateDuringConnectedActivity() {
        for (index in 0..2) {
            assertEquals(1f, workingDotAlpha(index.toFloat(), index), .0001f)
            assertEquals(.45f, workingDotAlpha(index.toFloat(), (index + 1) % 3), .0001f)
            assertEquals(workingDotAlpha(0f, index), workingDotAlpha(3f, index), .0001f)
            val halfway = workingDotAlpha(index + .5f, index)
            assertTrue(halfway in 0.7f..0.75f)
            assertEquals(halfway, workingDotAlpha(index + .5f, (index + 1) % 3), .0001f)
        }
        val tab = TabInfo("w", icon = "codex", title = "⠋ Topic")
        assertTrue(row(tab).indicatorWhile(true)!!.animates)
        val working = row(TabInfo("w", icon = "claude", activity = "working"))
        assertTrue(working.indicatorWhile(true)!!.animates)
        assertEquals("Disconnected", working.indicatorWhile(false)?.description)
        assertFalse(working.indicatorWhile(false)!!.animates)
        for ((info, label) in listOf(
            tab.copy(activity = "blocked", kind = "question") to "Waiting for an answer",
            tab.copy(activity = "blocked", kind = "auth") to "Waiting for sign-in",
            tab.copy(activity = "blocked", kind = "permission") to "Waiting for approval",
            tab.copy(activity = "blocked") to "Waiting for input",
            tab.copy(activity = "done") to "Completed", tab.copy(activity = "error") to "Error",
            tab.copy(reportedIdle = true) to "Idle",
        )) {
            val mark = row(info).indicatorWhile(true)!!
            assertEquals(label, mark.description)
            assertFalse(mark.animates)
        }
    }

    @Test
    fun statusSummaryIsOneLineAndYieldsToManualAndConversationTitles() {
        val tab = TabInfo("w", icon = "claude", statusSummary = "Checking\npatch")
        assertEquals("Checking patch", row(tab).name)
        assertEquals("Checking patch", row(tab.copy(title = "Shell", titleIsLabel = true)).name)
        assertEquals("Checking patch", row(tab.copy(icon = "codex", title = "⠋ codex")).name)
        assertEquals("OSC thread", row(tab.copy(title = "OSC thread")).name)
        assertEquals("Reported thread", row(tab.copy(statusTitle = "Reported thread")).name)
        assertEquals("Manual", row(tab.copy(label = "Manual")).name)

        for (tool in TabAgent.entries) {
            val generic = tab.copy(icon = tool.name.lowercase(), title = tool.name.lowercase())
            assertEquals("Checking patch", row(generic).name)
            assertEquals("Checking patch", row(generic.copy(statusTitle = tool.label)).name)
            assertEquals("OSC thread", row(generic.copy(title = "OSC thread", statusTitle = tool.label)).name)
            assertEquals("Reported thread", row(generic.copy(statusTitle = "Reported thread")).name)
            assertEquals("Manual", row(generic.copy(label = "Manual")).name)
        }

        val state = UiState.decode("""{"session":"s","window":"w","windows":[{"id":"w","layout":{"block":"b"}}],
            "blocks":{"b":{"statusSummary":"Checking patch"}}}""")
        assertEquals("Checking patch", shownTab(state)?.name)
    }

    @Test
    fun aStatusTitleChangeReachesBothRowsTheCaptionAndSearch() {
        fun snapshot(topic: String) = UiState.decode("""{
            "status":"connected","session":"active","window":"w","focused":"b",
            "sessions":[{"id":"active","tabs":[{"id":"w","block":"b","icon":"claude","program":"2.1.329","title":"Shell","statusTitle":"$topic"}]},
                {"id":"other","tabs":[{"id":"v","icon":"claude","program":"2.1.329","statusTitle":"$topic"}]}],
            "windows":[{"id":"w","icon":"claude","layout":{"block":"b"}}],
            "blocks":{"b":{"program":"2.1.329","title":"Shell","statusTitle":"$topic"}}}""")
        for (topic in listOf("QA64 Claude thread · 배포 점검", "Renamed thread")) {
            val state = snapshot(topic)
            val rows = sessionGroups(state, true, emptySet(), false, topic).flatMap { it.rows }
            assertEquals(listOf(topic, topic), rows.map { it.name })
            assertEquals(topic, tabTitle(state, state.windows.single()))
            assertEquals(topic, shownTab(state)?.name)
        }
    }
}
