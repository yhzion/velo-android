package io.github.yhzion.velo.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.fidelity.CorePackage
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.fidelity.ShadowVeloCore
import io.github.yhzion.velo.fidelity.Speech
import io.github.yhzion.velo.fidelity.fakeConnection
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.VeloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a screen reader hears of the notices of the sidebar that covers the panes (#195), from the accessibility
 * events and nodes the Compose view hands a service, with TalkBack's rule: a content change whose source node is a
 * live region reads that node out, whatever changed. A notice that moves between the pane column and the sidebar is
 * not read again, and a keyboard that opens from the Filter field leaves that field the accessibility focus: Compose
 * leaves a node that another covers whole out of the tree, and TalkBack then puts the focus on the sidebar's first
 * item. An error notice says it is an error, as Velo's other errors do ("Error: …").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi",
    shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class NoticeSpeechTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)
    private val manager = ApplicationProvider.getApplicationContext<Application>().getSystemService(AccessibilityManager::class.java)
    private lateinit var view: View
    private var conn: HostConnection? = null

    private val state = UiState(status = "connected", server = "velo", session = "s",
        windows = listOf(WindowInfo("w1", layout = LayoutNode(block = "a"))))
    private val missing = "The remote has no file at /data/local/nosuch/missing-file.txt."

    /** A screen reader is on, before anything is composed: Compose sends its events only then. */
    @Before fun screenReaderOn() {
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
    }

    @After fun destroyConnection() { conn?.destroy() }

    private fun connection() = fakeConnection(ApplicationProvider.getApplicationContext(), state).also { conn = it }

    /** Lets Compose send the events it holds back (it sends them at most every 100 ms). */
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun mark() = shadowOf(manager).sentAccessibilityEvents.size

    /**
     * What TalkBack reads out of the events sent since [from]: each content change whose source node is a live
     * region, as the words of that node and of all its descendants (TalkBack reads a live region's whole subtree).
     * The same words twice are read once: TalkBack skips words it says, has queued or said less than a second ago,
     * and Compose sends the events of one change within a few hundred ms. A move comes seconds after the notice
     * was first read, so that window does not hide it on the phone; here it starts after [from].
     */
    private fun readOut(from: Int): List<List<String>> = compose.runOnIdle {
        val provider = view.accessibilityNodeProvider
        shadowOf(manager).sentAccessibilityEvents.drop(from)
            .filter { it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }
            .mapNotNull { e -> sourceId(e).takeIf { it != Int.MAX_VALUE }?.let(provider::createAccessibilityNodeInfo) }
            .filter { it.liveRegion != View.ACCESSIBILITY_LIVE_REGION_NONE }
            .map { subtreeWords(it) }
            .distinct()
    }

    /** The words of [info] and of every node under it, in tree order. */
    private fun subtreeWords(info: AccessibilityNodeInfo): List<String> {
        val provider = view.accessibilityNodeProvider
        val words = Speech.words(info).toMutableList()
        for (c in 0 until info.childCount) {
            val id = (AccessibilityNodeInfo::class.java.getMethod("getChildId", Int::class.javaPrimitiveType).invoke(info, c) as Long ushr 32).toInt()
            provider.createAccessibilityNodeInfo(id)?.let { words += subtreeWords(it) }
        }
        return words
    }

    /** The virtual view id of [event]'s source: the high half of its hidden source node id (Int.MAX_VALUE for none). */
    private fun sourceId(event: AccessibilityEvent): Int =
        (AccessibilityEvent::class.java.getMethod("getSourceNodeId").invoke(event) as Long ushr 32).toInt()

    private val device = DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok))

    // The move: the pane column's notices ([PaneNotices]) or the sidebar's that covers the panes, as Workspace draws them.

    /** The notices of [c] over a pane column, or in the sidebar while [inSidebar] says so; returns the switch. */
    private fun movingNotices(c: HostConnection): MutableState<Boolean> {
        val inSidebar = mutableStateOf(false)
        compose.setContent {
            view = LocalView.current
            VeloTheme {
                val heard = remember { HeardNotices() }
                if (inSidebar.value) Sidebar(device, emptyList(), Modifier.fillMaxSize(), actions(), notices = {
                    UploadToast(c, heard)
                    ErrorNotice(c, heard = heard)
                }) else Box(Modifier.fillMaxSize()) {
                    val px = LocalDensity.current.density
                    PaneNotices({ Rect(0f, 0f, 393 * px, 700 * px) }, keyBar = { null }, padding = 16.dp, gap = 8.dp, lift = 0.dp,
                        modifier = Modifier.matchParentSize()) {
                        UploadToast(c, heard)
                        ErrorNotice(c, heard = heard)
                    }
                }
            }
        }
        settle()
        return inSidebar
    }

    @Test fun aNewNoticeIsReadOnce() {
        val c = connection()
        movingNotices(c)
        val from = mark()
        c.error = missing
        settle()
        assertEquals(listOf(listOf("Error: $missing")), readOut(from))
    }

    @Test fun aNoticeThatMovesIntoTheSidebarIsNotReadAgain() {
        val c = connection()
        val inSidebar = movingNotices(c)
        c.error = missing
        c.uploading = "Uploading report.pdf…"
        settle()
        val from = mark()
        inSidebar.value = true
        settle()
        assertEquals(emptyList<List<String>>(), readOut(from))
        // And back to the pane column, when the sidebar closes.
        val back = mark()
        inSidebar.value = false
        settle()
        assertEquals(emptyList<List<String>>(), readOut(back))
    }

    @Test fun anotherNoticeAfterAMoveIsRead() {
        val c = connection()
        val inSidebar = movingNotices(c)
        c.error = missing
        settle()
        inSidebar.value = true
        settle()
        val from = mark()
        c.error = "Could not open the file."
        settle()
        assertEquals(listOf(listOf("Error: Could not open the file.")), readOut(from))
    }

    @Test fun theSameWordsShownAgainAfterTheNoticeWentAreReadAgain() {
        val c = connection()
        val inSidebar = movingNotices(c)
        c.error = missing
        settle()
        inSidebar.value = true
        settle()
        c.error = null
        settle()
        val from = mark()
        c.error = missing
        settle()
        assertEquals(listOf(listOf("Error: $missing")), readOut(from))
    }

    // The keyboard: it opens from the Filter field and leaves the sidebar shorter, with a notice in it.

    /** The sidebar [height] dp tall with the error notice of [c] in it; returns the height to change. */
    private fun sidebarWithNotice(c: HostConnection, height: Int): MutableState<Int> {
        val tall = mutableStateOf(height)
        compose.setContent {
            view = LocalView.current
            VeloTheme {
                val heard = remember { HeardNotices() }
                Box(Modifier.fillMaxWidth().height(tall.value.dp)) {
                    Sidebar(device, emptyList(), Modifier.fillMaxSize(), actions(), notices = {
                        UploadToast(c, heard)
                        ErrorNotice(c, Modifier.testTag("error"), heard = heard)
                    })
                }
            }
        }
        settle()
        c.error = missing
        settle()
        return tall
    }

    private fun filterId() = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id

    @Test fun theFilterKeepsTheAccessibilityFocusWhenTheKeyboardOpens() {
        val c = connection()
        // The Velvet upright: 894 dp, and about 520 with Gboard up.
        val tall = sidebarWithNotice(c, 860)
        val filter = filterId()
        compose.runOnIdle { view.accessibilityNodeProvider.performAction(filter, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        settle()
        val from = mark()
        tall.value = 520
        settle()
        val node = compose.runOnIdle { view.accessibilityNodeProvider.createAccessibilityNodeInfo(filter) }
        assertTrue("the Filter field is still in the tree", node != null)
        assertTrue("the Filter field keeps the accessibility focus", node!!.isAccessibilityFocused)
        assertEquals("nothing read out again", emptyList<List<String>>(), readOut(from))
        val field = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        val error = compose.onNodeWithTag("error").getBoundsInRoot()
        assertTrue("the notice $error under the Filter field $field", error.top >= field.bottom)
    }

    @Test fun anErrorNoticeSaysItIsAnError() {
        val c = connection()
        sidebarWithNotice(c, 860)
        val said = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        assertTrue("$said", listOf("Error: $missing") in said)
    }

    @Test fun theNoteThatReplacesALoginsCancelIsNoError() {
        val c = connection()
        compose.setContent {
            view = LocalView.current
            VeloTheme { ErrorNotice(c, text = { needsAnswerText("studio") }) }
        }
        settle()
        c.error = "Login canceled."
        settle()
        val said = compose.runOnIdle { Speech.focusableNodes(view).map { it.second } }
        assertEquals(listOf(listOf("studio needs your answer to connect.")), said)
    }

    private fun actions() = SidebarActions(
        toggleSidebar = {}, openSettings = {}, openPalette = {}, openDevice = {},
        selectRemote = {}, newSession = {}, renameRemote = {}, removeRemote = {}, sshDetails = {}, addRemote = {},
        found = emptyList(), addFound = {},
        selectSession = { _, _ -> }, newTab = { _, _ -> }, renameSession = { _, _ -> }, closeSession = { _, _ -> },
        selectTab = { _, _ -> }, renameTab = { _, _ -> }, moveTab = { _, _, _ -> }, closeTab = { _, _ -> },
        toggleDock = {},
    )
}
