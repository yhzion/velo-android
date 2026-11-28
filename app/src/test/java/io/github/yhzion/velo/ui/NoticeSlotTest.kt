package io.github.yhzion.velo.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.fidelity.HostActivityRule
import io.github.yhzion.velo.ui.theme.Status
import io.github.yhzion.velo.ui.theme.ToastSurface
import io.github.yhzion.velo.ui.theme.VeloTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import io.github.yhzion.velo.HostConnection
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import io.github.yhzion.velo.fidelity.CorePackage
import io.github.yhzion.velo.fidelity.ShadowVeloCore
import io.github.yhzion.velo.fidelity.fakeConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where the notices are drawn (#165): the sidebar's slot under its Filter field while it covers the panes, and
 * [PaneNotices] over the pane column, which measures them at their own height and keeps them inside the column.
 * Two or three over a one-line pane go side by side above the key bar (#169): those scenes draw the real notices,
 * with a fake connection over [ShadowVeloCore].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w393dp-h894dp-420dpi",
    shadows = [ShadowVeloCore::class], instrumentedPackages = [CorePackage])
class NoticeSlotTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(HostActivityRule()).around(compose)

    @Test fun theSidebarHoldsTheNoticesUnderItsFilterFieldAndInsideItself() {
        compose.setContent {
            VeloTheme {
                Sidebar(DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok)), emptyList(),
                    Modifier.fillMaxSize(), actions(), notices = { ToastSurface("Uploading x…") })
            }
        }
        val toast = compose.onNodeWithTag("toast").getBoundsInRoot()
        val filter = compose.onNodeWithTag("sidebar-filter").getBoundsInRoot()
        val sidebar = compose.onNodeWithTag("sidebar").getBoundsInRoot()
        assertTrue("toast $toast under the filter $filter", toast.top >= filter.bottom)
        assertTrue("toast $toast in the sidebar $sidebar", toast.left >= sidebar.left && toast.right <= sidebar.right && toast.bottom <= sidebar.bottom)
        assertTrue("toast $toast has its height", toast.bottom - toast.top >= 48.dp)
    }

    @Test fun aSidebarTooShortForItsFilterBandBesideTheNoticesHasThemWholeOverItsFoot() {
        // Landscape with the keyboard up on the Velvet: the sidebar about 130 dp tall, its toolbar and This Device about
        // 125 (#165's review). The notices keep their own height, over the sidebar's foot and inside it (#195).
        compose.setContent {
            VeloTheme {
                Box(Modifier.width(800.dp).height(130.dp)) {
                    Sidebar(DeviceRow("velo-phone", "Running", "Running", TailnetChip("Connected", Status.Ok)), emptyList(),
                        Modifier.fillMaxSize(), actions(), notices = { ToastSurface("Uploading x…") })
                }
            }
        }
        val toast = compose.onNodeWithTag("toast").getBoundsInRoot()
        val sidebar = compose.onNodeWithTag("sidebar").getBoundsInRoot()
        assertTrue("toast $toast has its height", toast.bottom - toast.top >= 48.dp)
        assertTrue("toast $toast in the sidebar $sidebar, 12 dp above its foot",
            toast.top >= sidebar.top && toast.bottom <= sidebar.bottom - 12.dp + 0.5.dp)
    }

    @Test fun aShortPaneAreaHasTheToastAtPaddingWithItsWholeHeight() {
        // In px of the column: first a tall pane area, which shows the toast's own height, then a one-line one.
        var pane by mutableStateOf(Rect(0f, 0f, 1000f, 2000f))
        compose.setContent {
            VeloTheme {
                Box(Modifier.width(380.dp).height(800.dp)) {
                    PaneNotices({ pane }, keyBar = { null }, padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                        ToastSurface("Uploading x…")
                    }
                }
            }
        }
        val tall = compose.onNodeWithTag("toast").getBoundsInRoot()
        pane = Rect(0f, 0f, 1000f, 88f)
        compose.waitForIdle()
        val short = compose.onNodeWithTag("toast").getBoundsInRoot()
        assertEquals(16f, short.top.value, 0.5f)
        assertEquals((tall.bottom - tall.top).value, (short.bottom - short.top).value, 0.5f)
        // Not cut to the pane's 88 px: a toast is a control's height at least.
        assertTrue("toast $short", short.bottom - short.top >= 48.dp)
    }

    @Test fun aStackTallerThanTheSpaceUnderTheShortPaneEndsInsideTheColumn() {
        // A column that ends at the keyboard, 130 dp tall, under a one-line pane: two toasts (48 dp each and a gap of 8)
        // started 16 dp down would end past 114 dp.
        compose.setContent {
            VeloTheme {
                Box(Modifier.width(380.dp).height(200.dp)) {
                    Box(Modifier.testTag("column").width(380.dp).height(130.dp)) {
                        PaneNotices({ Rect(0f, 0f, 1000f, 88f) }, keyBar = { null }, padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                            ToastSurface("Moved")
                            ToastSurface("Uploading x…")
                        }
                    }
                }
            }
        }
        val column = compose.onNodeWithTag("column").getBoundsInRoot()
        val last = compose.onAllNodesWithTag("toast")[1].getBoundsInRoot()
        assertTrue("last toast $last ends 16 dp inside the column $column", last.bottom <= column.bottom - 16.dp + 0.5.dp)
        assertTrue("first toast still in the column", compose.onAllNodesWithTag("toast")[0].getBoundsInRoot().top >= column.top - 0.5.dp)
    }

    /** Two toasts (104 dp with the gap) over a short pane under a search bar (from 20 dp to 52), the key bar from [keyBarTop] dp. */
    private fun underTheSearchBar(keyBarTop: Int): Pair<DpRect, DpRect> {
        compose.setContent {
            VeloTheme {
                val px = LocalDensity.current.density
                Box(Modifier.width(380.dp).height(200.dp)) {
                    PaneNotices({ Rect(0f, 20 * px, 380 * px, 52 * px) }, keyBar = { Rect(0f, keyBarTop * px, 380 * px, (keyBarTop + 40) * px) },
                        padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                        ToastSurface("Moved")
                        ToastSurface("Uploading x…")
                    }
                }
            }
        }
        return compose.onAllNodesWithTag("toast")[0].getBoundsInRoot() to compose.onAllNodesWithTag("toast")[1].getBoundsInRoot()
    }

    @Test fun twoNoticesThatFitAboveTheKeyBarFromThePanesTopStayStackedAboveIt() {
        val (first, last) = underTheSearchBar(keyBarTop = 150)
        assertTrue("stacked: $first over $last", last.top >= first.bottom)
        assertTrue("last toast $last ends 16 dp above the key bar", last.bottom <= 150.dp - 16.dp + 0.5.dp)
        assertTrue("first toast $first under the search bar", first.top >= 20.dp - 0.5.dp)
    }

    @Test fun twoNoticesThatWouldStackOverTheSearchBarGoSideBySide() {
        // From the column's top they would fit above the key bar, but only over the search field (#169 review, N3).
        val (first, last) = underTheSearchBar(keyBarTop = 130)
        listOf(first, last).forEach {
            assertTrue("toast $it under the search bar", it.top >= 20.dp - 0.5.dp)
            assertTrue("toast $it ends above the key bar", it.bottom <= 130.dp + 0.5.dp)
        }
        assertTrue("side by side: $first then $last", last.left >= first.right)
    }

    // The real notices of the pane column (#169), in their order in the Workspace: the move's Undo, the upload
    // toast and the error notice, drawn by one fake connection. The Undo is for pane b, moved to a tab of its own.
    private val before = listOf(WindowInfo("w1", layout = LayoutNode(dir = "row", first = LayoutNode(block = "a"), second = LayoutNode(block = "b"))))
    private val moved = UiState(status = "connected", server = "velo", session = "s",
        windows = listOf(WindowInfo("w1", layout = LayoutNode(block = "a")), WindowInfo("w2", layout = LayoutNode(block = "b"))))
    private val uploadName = "17957915015754169069198214825020.png"
    private val errorText = "Another send is still in progress."
    private var conn: HostConnection? = null

    @After fun destroyConnection() { conn?.destroy() }

    /**
     * The real notices over a short column [width] dp wide, as on the Velvet in landscape with the keyboard up: 137 dp
     * tall, the pane one line (32 dp) at its top and the key bar from 83 dp. Undo, upload and error show as [undo],
     * [upload] and [error] say; the connection is returned to change them.
     */
    private fun realNotices(width: Int, undo: Boolean = true, upload: Boolean = true, error: Boolean = true,
                            fontScale: Float = 1f, rtl: Boolean = false, errorWords: String = errorText,
                            uploadWords: String = "Uploading $uploadName…"): HostConnection {
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), moved).also { conn = it }
        val notice = MoveNotice("b", MovePlace.NewTab, before, undoFor(before, "b", MovePlace.NewTab) { it.label })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                VeloTheme {
                    val px = LocalDensity.current.density
                    Box(Modifier.testTag("column").width(width.dp).height(137.dp)) {
                        PaneNotices({ Rect(0f, 0f, width * px, 32 * px) }, keyBar = { Rect(0f, 83 * px, width * px, 120 * px) },
                            padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                            if (undo) UndoNotice(c, notice, onDone = {}, Modifier.testTag("undo"))
                            val heard = remember { HeardNotices() }
                            UploadToast(c, heard, waits = true, Modifier.testTag("upload"))
                            ErrorNotice(c, Modifier.testTag("error"), heard = heard, waits = true)
                        }
                    }
                }
            }
        }
        // After the fake core's state is applied: a connected state clears the error notice.
        compose.waitForIdle()
        if (upload) c.uploading = uploadWords
        if (error) c.error = errorWords
        compose.waitForIdle()
        return c
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot()

    /** The upload toast's text as it is laid out. */
    private fun toastText(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("toast-message").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    /** The notices end above the key bar (83 dp), inside the column's padding, side by side in [nodes]' order from the left. */
    private fun assertOneRowAboveTheKeyBar(width: Int, nodes: List<DpRect>) {
        nodes.forEach { n ->
            assertTrue("notice $n ends above the key bar (83 dp)", n.bottom <= 83.dp + 0.5.dp)
            assertTrue("notice $n starts in the column", n.top >= (-0.5).dp)
            assertTrue("notice $n has a control's height", n.bottom - n.top >= 48.dp - 0.5.dp)
            assertTrue("notice $n inside the column's padding", n.left >= 16.dp - 0.5.dp && n.right <= width.dp - 16.dp + 0.5.dp)
        }
        nodes.zipWithNext { a, b -> assertTrue("$a then $b, side by side in order", b.left >= a.right + 8.dp - 0.5.dp) }
    }

    /** The upload toast's file name is on one line, not split inside itself. */
    private fun assertTheNameIsWhole() {
        val t = toastText()
        val start = t.layoutInput.text.text.indexOf(uploadName)
        val lines = (0 until t.lineCount).map { t.layoutInput.text.text.substring(t.getLineStart(it), t.getLineEnd(it)) }
        assertEquals("the name on one line: $lines", t.getLineForOffset(start), t.getLineForOffset(start + uploadName.length - 1))
    }

    /**
     * The error notice is its text's width and the Snackbar's 16 dp on each side, its text one line. The notice says
     * its words as one node ("Error: …", #195), so its text is found in the tree as it is laid out.
     */
    private fun assertTheErrorKeepsItsOwnWidth() {
        val error = bounds("error")
        val text = compose.onNodeWithText(errorText, useUnmergedTree = true).getBoundsInRoot()
        assertEquals("error $error around its text $text", (text.right - text.left + 32.dp).value, (error.right - error.left).value, 1f)
        assertTrue("one line $text", text.bottom - text.top < 30.dp)
    }

    // Robolectric draws text about 1.43 times as wide as the Velvet: Undo 254 dp (177 on the Velvet), the upload toast
    // 509 (355), the error notice's text 266 (190). A column of 1088 dp cuts the toast to 96 % of its own width, as the
    // Velvet's 786 dp does (976 px to 934).
    private val velvetColumn = 1088

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun twoNoticesUnderAOneLinePaneLieSideBySideAtTheirOwnWidths() {
        realNotices(velvetColumn, undo = false)
        assertOneRowAboveTheKeyBar(velvetColumn, listOf(bounds("upload"), bounds("error")))
        assertEquals(1, toastText().lineCount)
        assertTheErrorKeepsItsOwnWidth()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun threeNoticesUnderAOneLinePaneCutOnlyTheToastAndKeepItsNameWhole() {
        val c = realNotices(velvetColumn, upload = false, error = false)
        val ownUndo = bounds("undo")
        c.uploading = "Uploading $uploadName…"
        c.error = errorText
        compose.waitForIdle()
        val undo = bounds("undo")
        assertOneRowAboveTheKeyBar(velvetColumn, listOf(undo, bounds("upload"), bounds("error")))
        assertEquals("Undo $undo keeps its own width $ownUndo", (ownUndo.right - ownUndo.left).value, (undo.right - undo.left).value, 0.5f)
        assertTheErrorKeepsItsOwnWidth()
        assertEquals(2, toastText().lineCount)
        assertTheNameIsWhole()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun threeNoticesInRtlGoFromTheRight() {
        realNotices(velvetColumn, rtl = true)
        // The first notice at the start, which is the right.
        assertOneRowAboveTheKeyBar(velvetColumn, listOf(bounds("error"), bounds("upload"), bounds("undo")))
        assertTheErrorKeepsItsOwnWidth()
        assertTheNameIsWhole()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun oneNoticeUnderAOneLinePaneKeepsItsPlace() {
        // #165's place: from the pane's top down, padding below the column's top, over the caption.
        realNotices(velvetColumn, undo = false, error = false)
        val toast = bounds("upload")
        assertEquals(16f, toast.top.value, 0.5f)
        assertEquals(velvetColumn.toFloat(), (toast.left + toast.right).value, 1f)
    }

    // A larger text size (#169 review, S2; #192): the notices wrap to more lines, and a row of them would cover the key
    // bar (83 dp) or run past the column (137 dp). Where two or more do not end 16 dp above the key bar, the notices
    // without a control wait, the oldest first, until the rest fit or one is left; the Undo never waits. Before #192,
    // three at 1.3 ended at 96.8 dp, two at 2.0 at 126.5 and three at 2.0 at 162.3.

    /**
     * Whether the notice [tag] is on screen: a waiting one is not placed. Its tag is given outside what it hides from
     * a screen reader while it waits, so the node is found either way.
     */
    private fun placed(tag: String): Boolean {
        val nodes = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        assertEquals("one node tagged $tag", 1, nodes.size)
        return nodes.single().layoutInfo.isPlaced
    }

    /**
     * The notices [shown] are on screen side by side in that order, inside the column's padding and clear of the key bar;
     * two or more keep 16 dp above it. The notices [waiting] are not on screen.
     */
    private fun assertShownAndWaiting(shown: List<String>, waiting: List<String>) {
        val nodes = shown.map { bounds(it) }
        val floor = if (shown.size > 1) 83.dp - 16.dp else 83.dp
        nodes.forEach { n ->
            assertTrue("notice $n ends above $floor", n.bottom <= floor + 0.5.dp)
            assertTrue("notice $n starts in the column", n.top >= (-0.5).dp)
            assertTrue("notice $n inside the column's padding", n.left >= 16.dp - 0.5.dp && n.right <= velvetColumn.dp - 16.dp + 0.5.dp)
        }
        nodes.zipWithNext { a, b -> assertTrue("$a then $b, side by side", b.left >= a.right) }
        waiting.forEach { assertTrue("$it waits", !placed(it)) }
    }

    /** The Undo shows its button, which a tap reaches. */
    private fun assertTheUndoButtonShows() = compose.onNodeWithText("Undo").assertIsDisplayed().assertHasClickAction()

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun twoNoticesAtFontScale1_3StayAboveTheKeyBar() {
        realNotices(velvetColumn, undo = false, fontScale = 1.3f)
        assertShownAndWaiting(listOf("upload", "error"), waiting = emptyList())
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun threeNoticesAtFontScale1_3FoldTheOlderToastBehindTheError() {
        realNotices(velvetColumn, fontScale = 1.3f)
        assertShownAndWaiting(listOf("undo", "error"), waiting = listOf("upload"))
        assertTheUndoButtonShows()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun twoNoticesAtFontScale2ShowOnlyTheNewer() {
        realNotices(velvetColumn, undo = false, fontScale = 2f)
        assertShownAndWaiting(listOf("error"), waiting = listOf("upload"))
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun threeNoticesAtFontScale2KeepTheUndoAndTheNewer() {
        realNotices(velvetColumn, fontScale = 2f)
        assertShownAndWaiting(listOf("undo", "error"), waiting = listOf("upload"))
        assertTheUndoButtonShows()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theUndoAndTheToastAtFontScale2LeaveTheToastWaitingBehindTheUndo() {
        // Side by side they ended at 90.7 dp, 7.7 over the key bar: the toast waits until the Undo goes.
        realNotices(velvetColumn, error = false, fontScale = 2f)
        assertShownAndWaiting(listOf("undo"), waiting = listOf("upload"))
        assertTheUndoButtonShows()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun aToastNewerThanTheErrorShowsAndTheErrorWaits() {
        val c = realNotices(velvetColumn, undo = false, upload = false, fontScale = 2f)
        c.uploading = "Uploading $uploadName…"
        compose.waitForIdle()
        assertShownAndWaiting(listOf("upload"), waiting = listOf("error"))
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theWaitingToastComesBackWhenTheErrorGoes() {
        val c = realNotices(velvetColumn, undo = false, fontScale = 2f)
        assertShownAndWaiting(listOf("error"), waiting = listOf("upload"))
        c.error = null
        compose.waitForIdle()
        // Alone, it has #165's place: from the pane's top down, 16 dp below the column's top.
        assertShownAndWaiting(listOf("upload"), waiting = emptyList())
        assertEquals(16f, bounds("upload").top.value, 0.5f)
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theWaitingNoticesComeBackWhenTheKeyBarHasRoomAgain() {
        // The keyboard goes down: the pane area is tall again, and the notices stack in it as at #165.
        var paneBottom by mutableStateOf(32)
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), moved).also { conn = it }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                VeloTheme {
                    val px = LocalDensity.current.density
                    Box(Modifier.width(velvetColumn.dp).height(360.dp)) {
                        PaneNotices({ Rect(0f, 0f, velvetColumn * px, paneBottom * px) }, keyBar = { Rect(0f, 83 * px, velvetColumn * px, 120 * px) },
                            padding = 16.dp, gap = 8.dp, lift = 0.dp, modifier = Modifier.matchParentSize()) {
                            val heard = remember { HeardNotices() }
                            UploadToast(c, heard, waits = true, Modifier.testTag("upload"))
                            ErrorNotice(c, Modifier.testTag("error"), heard = heard, waits = true)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        c.uploading = "Uploading $uploadName…"
        c.error = errorText
        compose.waitForIdle()
        assertTrue("the toast waits", !placed("upload"))
        paneBottom = 300
        compose.waitForIdle()
        assertTrue("the toast is back", placed("upload") && placed("error"))
        assertTrue("stacked in the pane area", bounds("error").top >= bounds("upload").bottom && bounds("error").bottom <= 300.dp - 16.dp + 0.5.dp)
    }

    // Errors that wrap to more lines (#192 review, M1 and M2): the Snackbar answers one line's height whatever its
    // text, so the error notice answers its own; and where notices wait, the ones left stay off the key bar too.

    private val longError = "The remote has no file at /data/local/nosuch/missing-file.txt."
    private val longerError = "Could not connect to the remote: ssh: handshake failed: ssh: unable to authenticate, " +
        "attempted methods [none publickey], no supported methods remain on the server."

    /** The notices [tags] that show end above the key bar (83 dp) and start in the column; at least one shows. */
    private fun assertClearOfTheKeyBar(vararg tags: String) {
        val shown = tags.filter(::placed)
        assertTrue("some notice shows", shown.isNotEmpty())
        shown.forEach {
            val n = bounds(it)
            assertTrue("$it $n ends above the key bar", n.bottom <= 83.dp + 0.5.dp)
            assertTrue("$it $n starts in the column", n.top >= (-0.5).dp)
        }
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theUndoAndAThreeLineErrorAtFontScale1_3StayOffTheKeyBar() {
        realNotices(velvetColumn, upload = false, fontScale = 1.3f, errorWords = longerError)
        assertClearOfTheKeyBar("undo", "error")
        assertTheUndoButtonShows()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theUndoAndATwoLineErrorAtFontScale2StayOffTheKeyBar() {
        realNotices(velvetColumn, upload = false, fontScale = 2f, errorWords = longError)
        assertClearOfTheKeyBar("undo", "error")
        assertTheUndoButtonShows()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theUndoAndAnErrorTallerThanTheColumnLeaveTheErrorWaiting() {
        realNotices(velvetColumn, upload = false, fontScale = 2f, errorWords = longerError)
        assertShownAndWaiting(listOf("undo"), waiting = listOf("error"))
        assertTheUndoButtonShows()
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun aShortToastAndAThreeLineErrorAtFontScale1_3LeaveTheErrorAloneFromTheColumnsTop() {
        // A known limit: the error alone is 95.2 dp tall, more than the 83 dp above the key bar, so it still covers it.
        // One notice keeps #165's place (#169), and only where others wait does it go from the column's top.
        realNotices(velvetColumn, undo = false, fontScale = 1.3f, errorWords = longerError, uploadWords = "Uploading a.png…")
        assertTrue("the toast waits", !placed("upload"))
        assertEquals(0f, bounds("error").top.value, 0.5f)
        assertEquals(95.2f, bounds("error").bottom.value, 0.5f)
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun aShortToastAndATwoLineErrorAtFontScale2StayOffTheKeyBar() {
        realNotices(velvetColumn, undo = false, fontScale = 2f, errorWords = longError, uploadWords = "Uploading a.png…")
        assertClearOfTheKeyBar("upload", "error")
    }

    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theErrorLeftAloneAtFontScale1_3StaysOffTheKeyBar() {
        // Side by side the toast and this error end at 70.9 dp, inside the 16 dp above the key bar: the toast waits,
        // and the error, 68.6 dp tall, goes from the column's top rather than 16 dp down, over the key bar.
        realNotices(velvetColumn, undo = false, fontScale = 1.3f, errorWords = longError)
        assertShownAndWaiting(listOf("error"), waiting = listOf("upload"))
        assertClearOfTheKeyBar("error")
    }

    /** The error notice's own height ([snackbarHeight]) is the height its Snackbar lays itself out at. */
    @Config(qualifiers = "w1200dp-h393dp-land-420dpi")
    @Test fun theErrorNoticeAnswersTheHeightItIsDrawnAt() {
        val c = fakeConnection(ApplicationProvider.getApplicationContext(), moved).also { conn = it }
        var scale by mutableStateOf(1f)
        var width by mutableStateOf(300)
        var own = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                VeloTheme {
                    // Tall enough for the longest error at the narrowest width: a shorter box cuts the Snackbar.
                    Box(Modifier.width(width.dp).requiredHeight(1000.dp)) {
                        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                        io.github.yhzion.velo.ui.theme.VeloSurface(VeloTheme.colors.overlay) {
                            val style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                            val px = LocalDensity.current
                            c.error?.let { own = snackbarHeight(measurer, it, style, px, with(px) { width.dp.roundToPx() }) }
                        }
                        ErrorNotice(c, Modifier.testTag("error"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val px = compose.density.density
        for (s in listOf(1f, 1.3f, 2f)) for (words in listOf(errorText, longError, longerError)) for (w in listOf(250, 400, 700, 1000)) {
            scale = s
            width = w
            c.error = words
            compose.waitForIdle()
            val drawn = bounds("error").let { (it.bottom - it.top).value * px }
            assertEquals("scale $s, ${words.length} letters, $w dp", drawn, own.toFloat(), 1f)
        }
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
