package io.github.yhzion.velo

import org.junit.Assert.*
import org.junit.Test

class PaneReplyTest {
    private val at = "2026-11-21T09:00:00.000Z"

    @Test fun claudeChoicesReadTheListedChoicesOnly() {
        assertEquals(listOf("Red", "Green", "Blue"), claudeChoices("answer: Which color do you prefer? (Red · Green · Blue)"))
        assertEquals(listOf("빨강", "초록 (진한)", "파랑"), claudeChoices("answer: 무엇을 고를까요? (빨강 · 초록 (진한) · 파랑)"))
        assertEquals(listOf("Yes", "No"), claudeChoices("  answer: Ship (the release) now? (Yes · No)  "))
        // Shortened by Claude, no parentheses, one choice, an empty choice, another form, nothing.
        assertNull(claudeChoices("answer: Which color do you prefer? (Red · Green · Bl…"))
        assertNull(claudeChoices("answer: Which color do you prefer… (Red · Green · Blue)"))
        assertNull(claudeChoices("answer: Which color do you prefer?"))
        assertNull(claudeChoices("answer: Proceed? (Yes)"))
        assertNull(claudeChoices("answer: Proceed? (Yes ·  · No)"))
        assertNull(claudeChoices("answer:(Yes · No)"))
        assertNull(claudeChoices("approve Bash: touch /tmp/a.txt"))
        assertNull(claudeChoices("input needed"))
        assertNull(claudeChoices(null))
        assertNull(claudeChoices("answer: Many? (1 · 2 · 3 · 4 · 5 · 6 · 7 · 8 · 9)"))
    }

    private fun plan(reason: String?, agent: String?, choices: List<String>? = listOf("A", "B"), kind: PaneAlertKind = PaneAlertKind.Blocked,
                     record: String = "claude", updatedAt: String? = at) = replyPlan(kind, reason, agent, choices, record, updatedAt)

    @Test fun onlyMeasuredPromptsGetButtons() {
        val question = replyButtons(plan("question", "claude-code"))
        assertEquals(listOf(ReplyAction.Answer), question.map { it.action })
        assertEquals(listOf("A", "B"), question.single().choices)
        assertFalse(question.single().authenticationRequired)
        assertEquals(ReplyPlan.Question(listOf("A", "B")), plan("question", "claude"))

        for (agent in listOf("claude", "codex")) {
            val permission = replyButtons(plan("permission", agent))
            assertEquals(listOf(ReplyAction.Approve, ReplyAction.Deny), permission.map { it.action })
            assertEquals(listOf(true, false), permission.map { it.authenticationRequired })
            assertTrue(permission.all { it.choices == null })
        }

        // A Claude question without listed choices, omp, Codex questions, pi, unknown programs.
        assertNull(plan("question", "claude", choices = null))
        assertNull(plan("question", "omp"))
        assertNull(plan("permission", "omp"))
        assertNull(plan("question", "codex"))
        assertNull(plan("question", "pi"))
        assertNull(plan("question", "gemini"))
        assertNull(plan("permission", "gemini-cli"))
        assertNull(plan("permission", "vim"))
        assertNull(plan("permission", null))
        // Other blocked reasons and other states.
        assertNull(plan("auth", "claude"))
        assertNull(plan(null, "claude"))
        for (kind in listOf(PaneAlertKind.Done, PaneAlertKind.Error, PaneAlertKind.Working)) {
            assertNull(plan("permission", "claude", kind = kind))
            assertNull(plan("question", "claude", kind = kind))
        }
        // Nothing to check the prompt against.
        assertNull(plan("permission", "claude", updatedAt = null))
        assertNull(plan("permission", "claude", updatedAt = ""))
        // Claude Code and Codex report their own prompts on the root record, whose id is "".
        assertEquals(ReplyPlan.Permission(ReplyAgent.Claude), plan("permission", "claude-code", record = ""))
        assertEquals(emptyList<ReplyButton>(), replyButtons(null))
    }

    @Test fun aWatchGetsEachChoiceAsAButtonThatSendsOnlyItsNumber() {
        val question = ReplyPlan.Question(listOf("main", "release/2.4", "hotfix"))
        val wear = wearButtons(question)
        assertEquals(listOf("main", "release/2.4", "hotfix", "Answer"), wear.map { it.label })
        assertEquals(listOf("main", "release/2.4", "hotfix", null), wear.map { it.choice })
        assertTrue(wear.all { it.action == ReplyAction.Answer && !it.authenticationRequired && it.choices == null })
        // A choice button's text is the choice itself: its number alone, no "Type something".
        assertEquals(ReplyKeys(listOf("2"), "release/2.4"), replyKeys(question, ReplyAction.Answer, wear[1].choice))
        // A permission prompt's watch buttons are the phone's.
        val permission = ReplyPlan.Permission(ReplyAgent.Claude)
        assertEquals(replyButtons(permission), wearButtons(permission))
        assertEquals(emptyList<ReplyButton>(), wearButtons(null))
    }

    @Test fun answersBecomeTheMeasuredKeys() {
        val question = ReplyPlan.Question(listOf("Red", "Green", "Blue"))
        assertEquals(ReplyKeys(listOf("2"), "Green"), replyKeys(question, ReplyAction.Answer, "Green"))
        assertEquals(ReplyKeys(listOf("3"), "Blue"), replyKeys(question, ReplyAction.Answer, " Blue\n"))
        // A free answer: "Type something" is the entry after the choices, then the text and Enter.
        assertEquals(ReplyKeys(listOf("4", "망고\r"), "망고"), replyKeys(question, ReplyAction.Answer, "망고"))
        assertEquals(ReplyKeys(listOf("4", "green\r"), "green"), replyKeys(question, ReplyAction.Answer, "green"))
        assertNull(replyKeys(question, ReplyAction.Answer, " \n "))
        assertNull(replyKeys(question, ReplyAction.Answer, null))
        assertNull(replyKeys(question, ReplyAction.Approve, null))

        val claude = ReplyPlan.Permission(ReplyAgent.Claude)
        assertEquals(listOf("1"), replyKeys(claude, ReplyAction.Approve, null)?.writes)
        assertEquals(listOf("\u001b"), replyKeys(claude, ReplyAction.Deny, null)?.writes)
        val codex = ReplyPlan.Permission(ReplyAgent.Codex)
        assertEquals(listOf("y"), replyKeys(codex, ReplyAction.Approve, null)?.writes)
        assertEquals(listOf("\u001b"), replyKeys(codex, ReplyAction.Deny, null)?.writes)
        assertNull(replyKeys(codex, ReplyAction.Answer, "y"))
    }

    @Test fun answersLoseControlCharactersAndLineBreaks() {
        assertEquals("one two three", cleanAnswer("one\ntwo\r\nthree"))
        assertEquals("a b", cleanAnswer("a\tb"))
        // Control characters go; what remains is printable text, which only types itself.
        assertEquals("rm -rf", cleanAnswer("rm\u001b -rf\u0003"))
        assertEquals("[2K", cleanAnswer("\u001b[2K"))
        assertEquals("x y", cleanAnswer("x\u2028y\u0085"))
        assertEquals("한글 답", cleanAnswer("  한글 답\u0007 "))
        // An answer that is only control characters sends nothing.
        val question = ReplyPlan.Question(listOf("A", "B"))
        assertNull(replyKeys(question, ReplyAction.Answer, "\u001b\u0003"))
        val keys = replyKeys(question, ReplyAction.Answer, "fix it\nnow\u001b")!!
        assertEquals(listOf("3", "fix it now\r"), keys.writes)
        assertTrue(keys.writes.last().dropLast(1).none { it < ' ' })
    }

    @Test fun theAppsRecordStopsAChangedPrompt() {
        val same = SeenReport("blocked", "2026-11-21T09:00:00Z", "question", "claude")
        assertEquals(ReplyCheck.Same, replyCheck(same, "question", at))
        assertEquals(ReplyCheck.Changed, replyCheck(same.copy(updatedAt = "2026-11-21T09:00:01Z"), "question", at))
        assertEquals(ReplyCheck.Changed, replyCheck(same, "permission", at))
        assertEquals(ReplyCheck.NotBlocked, replyCheck(same.copy(state = "done"), "question", at))
        assertEquals(ReplyCheck.NotBlocked, replyCheck(same.copy(state = "clear"), "question", at))
        assertEquals(ReplyCheck.Unknown, replyCheck(null, "question", at))
        assertEquals(ReplyCheck.Unknown, replyCheck(same.copy(updatedAt = null), "question", at))
    }

    @Test fun paneWriteResultsMatchExactWireValuesAndFailClosed() {
        assertEquals(PaneWriteResult.Sent, PaneWriteResult.fromWire("sent"))
        assertEquals(PaneWriteResult.Changed, PaneWriteResult.fromWire("changed"))
        assertEquals(PaneWriteResult.NotSent, PaneWriteResult.fromWire("not_sent"))
        assertEquals(PaneWriteResult.Unconfirmed, PaneWriteResult.fromWire("unconfirmed"))
        for (unknown in listOf("?", "", "Sent", "sent ")) {
            assertEquals(PaneWriteResult.Unconfirmed, PaneWriteResult.fromWire(unknown))
        }
    }

    @Test fun eachPromptHasItsOwnIdentity() {
        val sub = PaneSubscription("token", "hostA", PaneAddress("s", "w", "b"), "Build")
        val alert = PaneAlert(sub, PaneAlertKind.Blocked, "question", message = "answer: Pick? (A · B)", agent = "claude", record = "claude", updatedAt = at)
        val target = ReplyTarget.of(alert, "Question needs an answer")
        assertEquals(listOf("A", "B"), target.choices)
        assertEquals(ReplyPlan.Question(listOf("A", "B")), target.plan)
        assertEquals("token", target.tag)
        assertEquals(PaneAlertKind.Blocked.ordinal, target.id)
        assertNotEquals(target.prompt, ReplyTarget.of(alert.copy(updatedAt = "2026-11-21T09:00:05Z"), "").prompt)
        assertNotEquals(target.prompt, ReplyTarget.of(alert.copy(reason = "permission"), "").prompt)
        assertEquals(target.prompt, ReplyTarget.of(alert, "other title").prompt)
    }
}
