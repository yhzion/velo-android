package io.github.yhzion.velo

import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.ui.ComposerAttachment
import io.github.yhzion.velo.ui.ComposerDraft
import org.junit.Assert.*
import org.junit.Test

class PaneAlertsTest {
    private val target = PaneAddress("s1", "tab", "right")
    private val prefs = FakePrefs()
    private var store = PaneAlertStore(prefs)
    private fun receiveOne(remote: String, report: PaneReport): PaneAlert? =
        store.receive(remote, report).let { if (it.isEmpty()) null else it.single() }
    private fun set(kind: PaneAlertKind, on: Boolean = true) = store.set("hostA", target, "Build", kind, on)
    private fun report(state: String, id: String = "", at: String? = null, kind: String? = null) =
        receiveOne("hostA", PaneReport(target, id, state, kind, at))

    @Test fun settingsAreIndependentPersistAndNeverCrossHostsTabsOrSessions() {
        assertNull(report("blocked"))
        set(PaneAlertKind.Blocked)
        store = PaneAlertStore(prefs)
        val token = store.find("hostA", target)!!.token
        for (other in listOf(target.copy(session = "s2"), target.copy(window = "other"), target.copy(block = "left"))) {
            assertNull(receiveOne("hostA", PaneReport(other, state = "blocked")))
        }
        assertNull(receiveOne("hostB", PaneReport(target, state = "blocked")))
        assertEquals("permission", report("blocked", kind = "permission")?.reason)
        assertNull(report("done"))
        set(PaneAlertKind.Done)
        set(PaneAlertKind.Blocked, false)
        assertNull(report("blocked"))
        assertEquals(PaneAlertKind.Done, report("done")?.kind)
        set(PaneAlertKind.Done, false)
        assertFalse(store.byToken(token)!!.enabled(PaneAlertKind.Done))
        store.inherit("hostA", target)
        assertNull(store.byToken(token))
        set(PaneAlertKind.Done)
        assertNotEquals(token, store.find("hostA", target)!!.token)
        store.removeRemote("hostA")
        assertTrue(PaneAlertStore(prefs).forRemote("hostA").isEmpty())
    }

    @Test fun automaticActivityDoesNotRequireAPaneSubscriptionAndRestoresItsDestination() {
        fun activity(state: String) = store.receive("hostA", PaneReport(target, state = state), "Build")
        assertEquals(PaneAlertKind.Working, activity("working").single().kind)
        val first = activity("error").single()
        assertEquals(PaneAlertKind.Error, first.kind)
        assertTrue(activity("error").isEmpty())
        assertTrue(store.forRemote("hostA").isEmpty())
        val token = first.subscription.token
        store.rememberRoute(first.subscription, setOf(token))
        store = PaneAlertStore(prefs)
        assertEquals(target, store.byToken(token)?.target)
        assertTrue(store.receive("hostA", PaneReport(target, baseline = listOf(PaneStatusRecord("", "error"))), "Build").isEmpty())
        assertTrue(activity("error").isEmpty())
        assertEquals(token, activity("done").single().subscription.token)
        store.removeRemote("hostA")
        assertNull(store.byToken(token))
    }

    @Test fun soundAndNotificationPreferencesPersistIndependentlyForEachStatus() {
        val original = Settings(prefs)
        original.savePlays(PaneAlertKind.Blocked, false)
        original.savePosts(PaneAlertKind.Error, false)
        original.saveActivityVolume(.4f)
        original.saveSound(PaneAlertKind.Done, ActivitySound.Ready)
        val restored = Settings(prefs)
        assertFalse(restored.plays(PaneAlertKind.Working))
        assertFalse(restored.posts(PaneAlertKind.Working))
        assertEquals(ActivitySound.Ready, restored.soundFor(PaneAlertKind.Done))
        assertFalse(restored.plays(PaneAlertKind.Blocked))
        assertTrue(restored.posts(PaneAlertKind.Blocked))
        assertTrue(restored.plays(PaneAlertKind.Error))
        assertFalse(restored.posts(PaneAlertKind.Error))
        assertEquals(.4f, restored.activityVolume, 0f)
    }

    @Test fun reportsTrackEachRecordAndSuppressRepeatedAndOlderReportsAfterRestart() {
        set(PaneAlertKind.Blocked)
        set(PaneAlertKind.Done)
        val t1 = "2026-11-20T12:00:00.000123Z"
        val t2 = "2026-11-20T12:00:00.000124Z"
        for (state in listOf("idle", "working", "error")) assertNull(report(state))
        assertEquals("auth", report("blocked", "login", t1, "auth")?.reason)
        assertEquals(PaneAlertKind.Done, report("done", "compile")?.kind)
        store = PaneAlertStore(prefs)
        assertNull(report("blocked", "login", t1, "auth"))
        assertNull(report("blocked", "login", t2, "auth"))
        assertNull(report("done", "login", t1))
        assertNull(receiveOne("hostA", PaneReport(target, removed = listOf("login"))))
        store = PaneAlertStore(prefs)
        assertNull(report("blocked", "login", t2, "auth"))
        assertEquals("question", report("blocked", "login", "2026-11-20T12:00:01Z", "question")?.reason)
        assertNull(report("done", "compile"))
        assertEquals(PaneAlertKind.Done, report("done", "login")?.kind)
    }

    @Test fun reconnectSilentlyReplacesTheBaselineBeforeTheFirstLiveTransition() {
        set(PaneAlertKind.Blocked)
        set(PaneAlertKind.Done)
        val before = "2026-11-20T12:00:00Z"
        val offline = "2026-11-20T12:01:00Z"
        val live = "2026-11-20T12:02:00Z"
        assertNotNull(report("blocked", "worker", before))
        assertNotNull(report("blocked", "removed", before))
        store = PaneAlertStore(prefs)
        val snapshot = PaneReport(target, baseline = listOf(
            PaneStatusRecord("worker", "working", offline),
            PaneStatusRecord("other", "done", offline),
        ))
        assertNull(receiveOne("hostA", snapshot))
        assertNull(report("done", "other", live))
        assertNull(report("blocked", "removed", before))
        assertEquals(PaneAlertKind.Blocked, report("blocked", "worker", live)?.kind)
        assertNull(report("blocked", "worker", live))
        assertEquals(PaneAlertKind.Blocked, report("blocked", "removed", live)?.kind)
        assertNull(receiveOne("hostA", PaneReport(target, baseline = emptyList())))
        assertNull(report("blocked", "worker", live))
    }

    @Test fun reconnectOverlappingLiveTransitionsAlertOnceAndKeepOfflineRecordsSilent() {
        set(PaneAlertKind.Blocked)
        val before = "2026-11-20T12:00:00Z"
        val working = "2026-11-20T12:01:00Z"
        val blocked = "2026-11-20T12:02:00Z"
        assertNotNull(report("blocked", "worker", before))
        store = PaneAlertStore(prefs)
        val response = PaneReport(target, baseline = listOf(
            PaneStatusRecord("worker", "blocked", blocked),
            PaneStatusRecord("other", "blocked", blocked),
        ), pending = listOf(
            PaneReport(target, "worker", "working", updatedAt = working),
            PaneReport(target, "worker", "blocked", kind = "question", updatedAt = blocked),
            PaneReport(target, "worker", "blocked", updatedAt = blocked),
        ))
        val alert = store.receive("hostA", response).single()
        assertEquals(PaneAlertKind.Blocked, alert.kind)
        assertEquals("question", alert.reason)
        store = PaneAlertStore(prefs)
        assertTrue(store.receive("hostA", response).isEmpty())
        assertNull(report("blocked", "other", blocked))
    }

    @Test fun liveReportCarriesTheProgramsTitleAndMessageIntoTheAlert() {
        set(PaneAlertKind.Blocked)
        val alert = receiveOne("hostA", PaneReport(target, "worker", "blocked", "question",
            agent = "claude-code", title = "Release review", msg = "Which branch ships?"))!!
        assertEquals("Release review", alert.title)
        assertEquals("Which branch ships?", alert.message)
        assertEquals("claude-code", alert.agent)
    }

    @Test fun notificationNamesTheWorkAndItsMessageAndExpandsToTheWholeMessageAndPlace() {
        val sub = PaneSubscription("t", "hostA", target, "rex · deploy · Build")
        val message = "Push to main or open a PR?\n1. main\n2. PR"
        val full = paneNoteText(PaneAlert(sub, PaneAlertKind.Blocked, "question", " Release review ", message, "claude-code"))
        assertEquals("Question needs an answer: Release review", full.title)
        assertEquals("Push to main or open a PR? 1. main 2. PR", full.text)
        assertEquals("$message\n\nrex · deploy · Build", full.expanded)
        assertEquals("Claude Code", full.program)
        val bare = paneNoteText(PaneAlert(sub, PaneAlertKind.Done, null, title = "", message = "  "))
        assertEquals("Work completed", bare.title)
        assertEquals("rex · deploy · Build", bare.text)
        assertEquals("rex · deploy · Build", bare.expanded)
        assertNull(bare.program)
    }

    @Test fun backgroundServiceRunsOnlyOffScreenWithRemotesWhileTheUserAllowsIt() {
        assertTrue(BackgroundConnection.wanted(enabled = true, foreground = false, remotes = 1))
        assertFalse(BackgroundConnection.wanted(enabled = true, foreground = true, remotes = 1))
        assertFalse(BackgroundConnection.wanted(enabled = true, foreground = false, remotes = 0))
        assertFalse(BackgroundConnection.wanted(enabled = false, foreground = false, remotes = 2))
        assertEquals("Connected to 2 remotes · notifications stay on", BackgroundConnection.statusText(2))
        // A forward keeps the connection, and the browser's pages, also with the setting off.
        assertTrue(BackgroundConnection.wanted(enabled = false, foreground = false, remotes = 1, forwards = 1))
        assertFalse(BackgroundConnection.wanted(enabled = false, foreground = true, remotes = 1, forwards = 1))
        assertEquals("Forwarding 1 port to this phone", BackgroundConnection.statusText(1, forwards = 1))
        assertEquals("Forwarding 2 ports to this phone", BackgroundConnection.statusText(1, forwards = 2))
    }

    @Test fun aSeenPaneFindsEveryTokenItsNotificationsCarryAndNoOtherPanes() {
        val automatic = store.receive("hostA", PaneReport(target, state = "done"), "Build").single().subscription
        store.rememberRoute(automatic, setOf(automatic.token))
        set(PaneAlertKind.Done)
        val explicit = store.find("hostA", target)!!.token
        assertEquals(setOf(automatic.token, explicit), store.tokensFor("hostA", target))
        assertTrue(store.tokensFor("hostA", target.copy(block = "left")).isEmpty())
        assertTrue(store.tokensFor("hostB", target).isEmpty())
    }

    @Test fun notificationLaunchWaitsForTheUserToFinishTheOriginalDraft() {
        for (next in listOf("original", "another")) {
            val launches = PaneNotificationLaunches()
            launches.open("original")
            val workspace = launches.generation
            launches.draftOpened()
            launches.open(next)
            assertEquals("original", launches.token)
            assertEquals(workspace, launches.generation)
            assertEquals(next, launches.pending)
            launches.draftClosed()
            assertEquals(next, launches.token)
            assertEquals(workspace + 1, launches.generation)
            assertNull(launches.pending)
        }
    }

    @Test fun androidPermissionAndAppAndChannelSettingsGateDelivery() {
        for (api in listOf(30, 31, 32)) {
            assertEquals(NotificationAccess.Allowed, notificationAccess(api, false, true, true))
            assertEquals(NotificationAccess.AppSettings, notificationAccess(api, false, false, true))
            assertEquals(NotificationAccess.ChannelSettings, notificationAccess(api, false, true, false))
        }
        for (api in listOf(33, 37)) {
            assertEquals(NotificationAccess.RequestPermission, notificationAccess(api, false, false, true))
            assertEquals(NotificationAccess.RequestPermission, notificationAccess(api, false, true, true))
            assertEquals(NotificationAccess.Allowed, notificationAccess(api, true, true, true))
            assertEquals(NotificationAccess.ChannelSettings, notificationAccess(api, true, true, false))
        }
    }

    @Test fun anOpenedWorkspaceKeepsItsDraftWhenTheNotificationPaneDisconnects() {
        val ready = UiState(status = "connected", session = target.session, window = target.window,
            focused = target.block, windows = listOf(WindowInfo(target.window, layout = LayoutNode(block = target.block))))
        var state = ready
        val gate = PaneNotificationGate(opened = true, opening = false, targetIsOpen = { target.isOpen(state) }, leave = {})
        val file = ComposerAttachment("result.txt", byteArrayOf(1))
        val draft = ComposerDraft().apply { text = "Please check"; attachment = file }
        assertTrue(gate.canUsePane())
        state = ready.copy(status = "reconnecting")
        assertTrue(gate.opened)
        assertFalse(gate.canUsePane())
        assertFalse(draft.beginSend(gate.canUsePane()))
        state = ready.copy(focused = "other")
        assertFalse(gate.canUsePane())
        state = ready
        assertTrue(gate.canUsePane())
        assertFalse(draft.beginSend(gate.canUsePane()))
        assertEquals("Please check", draft.text)
        assertSame(file, draft.attachment)
    }

    @Test fun openingWaitsForTheOriginalSessionTabAndPaneAndRejectsStaleTargets() {
        val tab = WindowInfo("tab", layout = LayoutNode(block = "left"), floating = listOf(FloatingLayer("right")))
        val ready = UiState(status = "connected", sessions = listOf(SessionInfo("s1")), session = "s1",
            windows = listOf(tab, WindowInfo("other")), window = "tab", focused = "right")
        assertEquals(OpenPaneStep.Wait, target.openStep(ready.copy(status = "connecting")))
        assertEquals(OpenPaneStep.Gone, target.openStep(ready.copy(sessions = emptyList())))
        assertEquals(OpenPaneStep.SelectSession, target.openStep(ready.copy(session = "elsewhere")))
        assertEquals(OpenPaneStep.Wait, target.openStep(ready.copy(windows = emptyList())))
        assertEquals(OpenPaneStep.SelectWindow, target.openStep(ready.copy(window = "other")))
        assertEquals(OpenPaneStep.Unzoom, target.openStep(ready.copy(windows = listOf(tab.copy(zoomed = "left")))))
        val minimized = tab.copy(floating = emptyList(), minimizedBlocks = listOf("right"))
        assertEquals(OpenPaneStep.Restore, target.openStep(ready.copy(windows = listOf(minimized))))
        assertFalse(target.isOpen(ready.copy(windows = listOf(minimized))))
        assertEquals(OpenPaneStep.Focus, target.openStep(ready.copy(focused = "left")))
        assertEquals(OpenPaneStep.Ready, target.openStep(ready))
        val moved = ready.copy(windows = listOf(tab.copy(floating = emptyList()), tab.copy(id = "other")))
        assertEquals(OpenPaneStep.Gone, target.openStep(moved))
        assertFalse(target.isOpen(moved))
    }
}
