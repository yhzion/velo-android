package io.github.yhzion.velo.ui

import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.Remote
import io.github.yhzion.velo.core.SessionInfo
import io.github.yhzion.velo.core.TabInfo
import io.github.yhzion.velo.core.UiState
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val workstation = Remote("remote:1", "workstation", "https://workstation.tailnet.example")
private val mac = Remote("remote:2", "mac", "https://lab-laptop.tailnet.example")

/** A connected remote showing a session, a tab and a split pane. */
private val full = PaletteUnits(
    saved = listOf(workstation, mac), removable = true, connected = true,
    session = true, tab = true, pane = true, paneCount = 2, floating = false, zoomed = false,
)

private fun PaletteUnits.enabled() = paletteItems(this).filter { it.enabled }.map { it.action }.toSet()
private fun PaletteUnits.listed() = paletteItems(this).map { it.action }

class MenuModelTest {

    @Test
    fun autoSplitKeepsBothChildrenUsable() {
        assertEquals(PaneSplit.Right, automaticSplit(250f, 250f, 4f))
        assertEquals(PaneSplit.Below, automaticSplit(200f, 260f, 4f))
        assertEquals(PaneSplit.Below, automaticSplit(195f, 148f, 4f))
        assertEquals(PaneSplit.Right, automaticSplit(196f, 72f, 4f))
        assertNull(automaticSplit(195f, 147f, 4f))
        assertNull(automaticSplit(300f, 71f, 4f))
        assertNull(automaticSplit(95f, 300f, 4f))
    }

    @Test
    fun aRemotesMenuHoldsOnlyWhatActsOnTheRemote() {
        val menu = remoteMenu("connected", removed = false)

        assertEquals(listOf(RemoteAction.NewSession, RemoteAction.Rename, RemoteAction.Remove), menu.map { it.action })
        assertTrue(menu.all { it.enabled })
    }

    @Test
    fun aRemoteThatIsNotConnectedCannotMakeASessionButCanBeRenamedAndRemoved() {
        for (status in listOf("idle", "connecting", "reconnecting", "error")) {
            val menu = remoteMenu(status, removed = false).associate { it.action to it.enabled }
            assertEquals(status, mapOf(RemoteAction.NewSession to false, RemoteAction.Rename to true, RemoteAction.Remove to true), menu)
        }
    }

    @Test
    fun theRemovedRemoteHasNoMenu() {
        // It is gone from the phone: nothing is left to do on it.
        assertTrue(remoteMenu("connected", removed = true).isEmpty())
        assertTrue(remoteMenu("connected", removed = true, ssh = true).isEmpty())
    }

    // "SSH details" takes the place of the separate Forget host key and Forget password items.
    @Test
    fun onlyAnSshRemotesMenuOpensItsSshDetails() {
        val ssh = remoteMenu("connected", removed = false, ssh = true)
        assertEquals(listOf(RemoteAction.NewSession, RemoteAction.Rename, RemoteAction.SshDetails, RemoteAction.Remove), ssh.map { it.action })
        assertEquals(listOf("New Session", "Rename…", "SSH details", "Remove Remote…"), ssh.map { it.label })
        // What the phone keeps is shown and forgotten without a connection.
        for (status in listOf("idle", "connecting", "reconnecting", "error")) {
            assertEquals(status, true, remoteMenu(status, removed = false, ssh = true).single { it.action == RemoteAction.SshDetails }.enabled)
        }
        assertTrue(remoteMenu("connected", removed = false, ssh = false).none { it.action == RemoteAction.SshDetails })
        // The saved list in Settings: the same items but New Session.
        assertEquals(listOf(RemoteAction.Rename, RemoteAction.SshDetails, RemoteAction.Remove), savedRemoteMenu(ssh = true).map { it.action })
        assertEquals(listOf("Rename…", "SSH details", "Remove Remote…"), savedRemoteMenu(ssh = true).map { it.label })
        assertEquals(listOf(RemoteAction.Rename, RemoteAction.Remove), savedRemoteMenu(ssh = false).map { it.action })
        assertTrue((ssh + savedRemoteMenu(ssh = true)).all { it.enabled })
        // No menu forgets anything itself any more.
        assertTrue((ssh + savedRemoteMenu(ssh = true)).none { it.label.startsWith("Forget") })
    }

    @Test
    fun aSessionsAndATabsMenuRenameAndCloseTheirOwnUnit() {
        assertEquals(listOf(SessionAction.Rename, SessionAction.Close), sessionMenu().map { it.action })
        val tab = tabMenu(true, true, true)
        assertEquals(listOf(TabAction.Rename, TabAction.MoveForward, TabAction.MoveBackward, TabAction.Close), tab.map { it.action })
        assertEquals(listOf("Rename…", "Move Tab Forward", "Move Tab Backward", "Close Tab"), tab.map { it.label })
        assertTrue((sessionMenu() + tab).all { it.enabled })
    }

    @Test
    fun aTabMenuCannotMoveATabPastAnEnd() {
        assertEquals(listOf(TabAction.MoveForward), tabMenu(true, forward = false, backward = true).filter { !it.enabled }.map { it.action })
        assertEquals(listOf(TabAction.MoveBackward), tabMenu(true, true, false).filter { !it.enabled }.map { it.action })
    }

    @Test
    fun thePaletteMovesTheShownTabOnePlace() {
        val items = paletteItems(full.copy(tabForward = true, tabBackward = false))
        val labels = items.map { it.label }
        val forward = items.single { it.label == "Move Tab Forward" }
        val backward = items.single { it.label == "Move Tab Backward" }
        assertEquals(PaletteAction.MoveTabForward, forward.action)
        assertEquals(PaletteAction.MoveTabBackward, backward.action)
        assertTrue(forward.enabled)
        assertFalse(backward.enabled)
        assertEquals(labels.indexOf("Move Tab Forward") + 1, labels.indexOf("Move Tab Backward"))
        assertTrue(labels.indexOf("Rename Tab…") < labels.indexOf("Move Tab Forward"))
        assertTrue(labels.indexOf("Move Tab Backward") < labels.indexOf("Close Tab"))
        val noTab = paletteItems(full.copy(tab = false, tabForward = true, tabBackward = true))
        assertFalse(noTab.single { it.label == "Move Tab Forward" }.enabled)
        assertFalse(noTab.single { it.label == "Move Tab Backward" }.enabled)
    }

    @Test
    fun aSessionAndATabOfARemoteThatIsOfflineCannotBeRenamedOrClosed() {
        // Its rows may still be listed from before it went away; both items ask the remote.
        assertEquals(listOf(SessionAction.Rename, SessionAction.Close), sessionMenu(connected = false).map { it.action })
        assertTrue((sessionMenu(connected = false) + tabMenu(false, true, true)).none { it.enabled })
    }

    @Test
    fun aPanesMenuHoldsOnlyWhatActsOnThePane() {
        val menu = paneMenu(floating = false, zoomed = false, paneCount = 2)

        assertEquals(
            listOf(
                PaneSplit.Right, PaneSplit.Below, PaneSplit.Left, PaneSplit.Above, PaneSplit.Auto, PaneAction.Float, PaneAction.Minimize, PaneAction.Zoom, PaneAction.Copy, PaneAction.Paste,
                PaneAction.Compose, PaneAction.TextLarger, PaneAction.TextSmaller, PaneAction.TextReset, PaneAction.Close,
            ),
            menu.map { it.action },
        )
        // The same words as the palette's commands for the same pane.
        assertEquals(
            listOf("Text Size Larger", "Text Size Smaller", "Text Size Reset"),
            menu.filter { it.action in setOf(PaneAction.TextLarger, PaneAction.TextSmaller, PaneAction.TextReset) }.map { it.label },
        )
        // Stashing needs a pane that is not the last one on screen: disabled until the caller says it can.
        assertTrue(menu.filter { it.action != PaneAction.Minimize }.all { it.enabled })
        assertFalse(menu.single { it.action == PaneAction.Minimize }.enabled)
        assertFalse(paneMenu(false, false, 2, canCompose = false).single { it.action == PaneAction.Compose }.enabled)
    }

    @Test
    fun withoutTheConnectionAPanesMenuKeepsItsItemsAndOnlyLocalOnesRun() {
        // The same items are listed; those that send the server a command are disabled.
        val online = paneMenu(floating = false, zoomed = false, paneCount = 2)
        val offline = paneMenu(floating = false, zoomed = false, paneCount = 2, connected = false)
        assertEquals(online.map { it.action }, offline.map { it.action })
        assertEquals(
            setOf(PaneAction.Copy, PaneAction.TextLarger, PaneAction.TextSmaller, PaneAction.TextReset),
            offline.filter { it.enabled }.map { it.action }.toSet(),
        )
        // Every state of the pane: float, dock, restore in a new tab, full screen, the last pane.
        for (menu in listOf(paneMenu(true, true, 3, connected = false), paneMenu(true, false, 1, hasTiledPane = false, connected = false))) {
            assertTrue(menu.filter { it.action !in setOf(PaneAction.Copy, PaneAction.TextLarger, PaneAction.TextSmaller, PaneAction.TextReset) }
                .none { it.enabled })
        }
    }

    @Test
    fun aFloatingPaneDocksAndAZoomedOneRestores() {
        val actions = paneMenu(floating = true, zoomed = true, paneCount = 3).map { it.action }

        assertTrue(PaneAction.Dock in actions && PaneAction.Float !in actions)
        assertTrue(PaneAction.Restore in actions && PaneAction.Zoom !in actions)
    }

    @Test
    fun theLastPaneOfATabOffersCloseTabFromItsMenu() {
        val menu = paneMenu(floating = false, zoomed = false, paneCount = 1)

        assertEquals(MenuItem(PaneAction.Close, "Close Tab"), menu.single { it.action == PaneAction.Close })
        assertTrue(menu.filter { it.action != PaneAction.Close && it.action != PaneAction.Minimize }.all { it.enabled })
    }

    @Test
    fun aHiddenPanePreventsTheVisiblePaneFromOfferingCloseTab() {
        val menu = paneMenu(floating = false, zoomed = false, paneCount = 1, minimized = true)

        assertTrue(menu.none { it.action == PaneAction.Close })
    }

    @Test
    fun aFloatingPaneKeepsItsOwnRestoreAction() {
        val dock = MenuItem(PaneAction.Dock, "Dock Pane in Split Layout")
        val newTab = MenuItem(PaneAction.RestoreInNewTab, "Restore Pane in New Tab")
        assertTrue(dock in paneMenu(true, false, 2))
        assertTrue(newTab in paneMenu(true, false, 1, hasTiledPane = false))
        assertEquals(newTab.label, paletteItems(full.copy(paneCount = 1, floating = true, hasTiledPane = false))
            .single { it.action == PaletteAction.Float }.label)

        val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
        runPaneLayoutAction(newTab.action, "block:original", { name, args -> commands.add(name to args.toMap()) }, {}, {})
        assertEquals(listOf("restoreInNewTab" to mapOf("block" to "block:original")), commands)
    }

    @Test
    fun moveItemsFollowTheFloatItem() {
        val menu = paneMenu(floating = false, zoomed = false, paneCount = 2, canMoveToNewTab = true, canMoveToTab = true)
        val actions = menu.map { it.action }
        val float = actions.indexOf(PaneAction.Float)

        assertEquals(listOf(PaneAction.Float, PaneAction.Minimize, PaneAction.MoveToTab, PaneAction.MoveToNewTab), actions.subList(float, float + 4))
        assertEquals(
            listOf("Move Pane to Tab…", "Move Pane to New Tab"),
            menu.filter { it.action == PaneAction.MoveToTab || it.action == PaneAction.MoveToNewTab }.map { it.label },
        )
        // A docked-able floating pane keeps the move items right after Dock.
        val floating = paneMenu(floating = true, zoomed = false, paneCount = 2, canMoveToNewTab = true, canMoveToTab = true).map { it.action }
        assertEquals(listOf(PaneAction.Dock, PaneAction.Minimize, PaneAction.MoveToTab, PaneAction.MoveToNewTab), floating.subList(floating.indexOf(PaneAction.Dock), floating.indexOf(PaneAction.Dock) + 4))
    }

    @Test
    fun stashingAPaneIsListedAfterFloatAndExplainsWhenItIsRefused() {
        val tiled = paneMenu(floating = false, zoomed = false, paneCount = 2, canStash = true)
        assertEquals(MenuItem(PaneAction.Minimize, "Float and Minimize"), tiled[tiled.indexOfFirst { it.action == PaneAction.Float } + 1])
        val floating = paneMenu(floating = true, zoomed = false, paneCount = 2, canStash = true)
        assertEquals(MenuItem(PaneAction.Minimize, "Minimize Floating Window"), floating[floating.indexOfFirst { it.action == PaneAction.Dock } + 1])

        // Connected, but the server would refuse: disabled, and says why.
        val blocked = paneMenu(floating = false, zoomed = false, paneCount = 1, canStash = false).single { it.action == PaneAction.Minimize }
        assertFalse(blocked.enabled)
        assertEquals("The last pane on screen", blocked.note)
        // Offline: disabled with no note, since the connection is the reason.
        val offline = paneMenu(floating = false, zoomed = false, paneCount = 2, canStash = true, connected = false).single { it.action == PaneAction.Minimize }
        assertFalse(offline.enabled)
        assertNull(offline.note)
    }

    @Test
    fun theStashCommandsOfThePaletteFollowTheFloatItem() {
        val items = paletteItems(full.copy(canStash = true, floatingToggle = true))
        val float = items.indexOfFirst { it.action == PaletteAction.Float }
        assertEquals(MenuItem(PaletteAction.Minimize, "Float and Minimize"), items[float + 1])
        assertEquals(MenuItem(PaletteAction.MinimizeAll, "Minimize Floating Windows"), items[float + 2])

        val floating = paletteItems(full.copy(floating = true, canStash = true))
        assertEquals("Minimize Floating Window", floating.single { it.action == PaletteAction.Minimize }.label)

        val refused = full.copy(canStash = false, floatingToggle = null)
        assertFalse(PaletteAction.Minimize in refused.enabled())
        assertFalse(PaletteAction.MinimizeAll in refused.enabled())
        assertTrue(PaletteAction.Minimize in full.copy(canStash = true).enabled())
        assertTrue(PaletteAction.MinimizeAll in full.copy(floatingToggle = false).enabled())
        assertFalse(PaletteAction.Minimize in full.copy(canStash = true, connected = false).enabled())
        assertFalse(PaletteAction.MinimizeAll in full.copy(floatingToggle = true, tab = false).enabled())
    }

    @Test
    fun theCapsuleCornerCommandsFollowThePaneMovesAndDisableTheShownCorner() {
        val items = paletteItems(full.copy(capsuleCorner = CapsuleCorner.TopRight))
        val moves = items.indexOfLast { it.action is PaletteAction.MovePane }
        assertEquals(
            CapsuleCorner.entries.map { MenuItem<PaletteAction>(PaletteAction.MoveCapsules(it), "Move Minimized Windows to ${it.label}", it != CapsuleCorner.TopRight) },
            items.subList(moves + 1, moves + 5),
        )
        assertEquals("Move Minimized Windows to Top Left", items[moves + 1].label)
        assertFalse(PaletteAction.MoveCapsules(CapsuleCorner.TopRight) in full.copy(capsuleCorner = CapsuleCorner.TopRight).enabled())
        assertTrue(PaletteAction.MoveCapsules(CapsuleCorner.TopLeft) in full.copy(capsuleCorner = CapsuleCorner.TopRight).enabled())
        // The unit's default is the app's default corner.
        assertFalse(PaletteAction.MoveCapsules(DefaultCapsuleCorner) in full.enabled())
    }

    @Test
    fun theMoveCommandsOfThePaletteFollowTheStashCommands() {
        val some = setOf(PaneDirection.Left, PaneDirection.Down)
        val items = paletteItems(full.copy(moves = some))
        val all = items.indexOfFirst { it.action == PaletteAction.MinimizeAll }
        assertEquals(
            PaneDirection.entries.map { MenuItem<PaletteAction>(PaletteAction.MovePane(it), it.label, it in some) },
            items.subList(all + 1, all + 5),
        )
        assertEquals(
            some.map { PaletteAction.MovePane(it) }.toSet(),
            full.copy(moves = some).enabled().filterIsInstance<PaletteAction.MovePane>().toSet(),
        )
        assertTrue(full.enabled().none { it is PaletteAction.MovePane })
        val every = PaneDirection.entries.toSet()
        assertTrue(full.copy(moves = every, pane = false).enabled().none { it is PaletteAction.MovePane })
        assertTrue(full.copy(moves = every, connected = false).enabled().none { it is PaletteAction.MovePane })
    }

    @Test
    fun typingMoveRunsAPaneMoveBeforeACornerMove() {
        // The palette runs the first enabled match on Enter: an enabled pane move comes before the corner moves, which
        // are always enabled (#220). `Remove Remote` and the tab moves match "move" too; here they are disabled.
        val items = paletteItems(full.copy(removable = false, moves = setOf(PaneDirection.Down)))
        assertEquals(PaletteAction.MovePane(PaneDirection.Down), items.first { it.enabled && it.label.contains("move", true) }.action)
    }

    @Test
    fun aDisabledStashCommandOfThePaletteSaysWhy() {
        fun note(u: PaletteUnits) = paletteItems(u).single { it.action == PaletteAction.Minimize }.note
        assertEquals("The last pane on screen", note(full.copy(canStash = false)))
        assertNull(note(full.copy(canStash = true)))
        // Offline the connection is the reason: no note.
        assertNull(note(full.copy(canStash = false, connected = false)))
        // No pane to stash: nothing to explain either.
        assertNull(note(full.copy(canStash = false, pane = false)))
    }

    @Test
    fun minimizeSendsMinimizePane() {
        val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
        runPaneLayoutAction(PaneAction.Minimize, "b", { name, args -> commands.add(name to args.toMap()) }, {}, {})
        assertEquals(listOf("minimizePane" to mapOf("block" to "b")), commands)
    }

    @Test
    fun aLoneFloatingPaneKeepsRestorePaneInNewTabAndHasNoMovePaneToNewTab() {
        val actions = paneMenu(true, false, 1, hasTiledPane = false, canMoveToNewTab = true, canMoveToTab = true).map { it.action }

        assertTrue(PaneAction.RestoreInNewTab in actions)
        assertFalse(PaneAction.MoveToNewTab in actions)
    }

    @Test
    fun moveItemsAreDisabledWithoutTheConnection() {
        val menu = paneMenu(false, false, 2, canMoveToNewTab = true, canMoveToTab = true, connected = false)

        for (action in listOf(PaneAction.MoveToTab, PaneAction.MoveToNewTab)) {
            assertFalse(menu.single { it.action == action }.enabled)
        }
    }

    @Test
    fun movePaneToTabIsAbsentWithoutAHandler() {
        // PaneMenu passes canMoveToTab only with a handler; false keeps the item out, and the other one stays.
        val menu = paneMenu(false, false, 2, canMoveToNewTab = true, canMoveToTab = false).map { it.action }

        assertFalse(PaneAction.MoveToTab in menu)
        assertTrue(PaneAction.MoveToNewTab in menu)
        assertTrue(paneMenu(false, false, 2).none { it.action == PaneAction.MoveToTab || it.action == PaneAction.MoveToNewTab })
    }

    @Test
    fun aLonePaneCanFloatButOnlyAnAlreadyZoomedPaneCanExitFullScreen() {
        val soloMenu = paneMenu(false, false, 1)
        assertTrue(soloMenu.single { it.action == PaneAction.Float }.enabled)
        assertTrue(soloMenu.none { it.action == PaneAction.Zoom || it.action == PaneAction.Restore })
        assertEquals("Exit Full Screen", paneMenu(false, true, 1).single { it.action == PaneAction.Restore }.label)
        assertFalse(PaletteAction.Zoom in full.copy(paneCount = 1).listed())
        assertTrue(PaletteAction.Zoom in full.copy(paneCount = 1, zoomed = true).enabled())
        assertTrue(PaletteAction.Zoom in full.enabled())
    }

    @Test
    fun theKeyboardPickerListsBothTiledAndFloatingPanesInDisplayOrder() {
        val window = WindowInfo(
            id = "window:1",
            layout = LayoutNode(
                dir = "row", first = LayoutNode(block = "left"), second = LayoutNode(block = "right"),
            ),
            floating = listOf(FloatingLayer("floating")),
        )

        assertEquals(listOf("left", "right", "floating"), keyboardPaneBlocks(window))
        val zoomed = window.copy(zoomed = "right")
        val visible = keyboardPaneBlocks(zoomed)
        assertEquals(listOf("right", "floating"), visible)
        assertEquals(listOf("Pane 2", "Pane 3"), visible.map { paneLabel(tabBlocks(zoomed), it) })
    }

    @Test
    fun aZoomedFloatingPaneIsDrawnOnceAsTheZoomedPane() {
        val window = WindowInfo(
            id = "window:1",
            layout = LayoutNode(block = "docked"),
            floating = listOf(FloatingLayer("first"), FloatingLayer("second")),
        )

        assertEquals(listOf("first", "second"), drawnFloating(window).map { it.block })
        assertEquals(listOf("second"), drawnFloating(window.copy(zoomed = "first")).map { it.block })
        assertEquals(listOf("first", "second"), drawnFloating(window.copy(zoomed = "docked")).map { it.block })
        assertEquals(emptyList<FloatingLayer>(), drawnFloating(window.copy(floating = listOf(FloatingLayer("first")), zoomed = "first")))
    }

    /** A tab whose [blocks] are docked side by side, in that order. */
    private fun tiled(vararg blocks: String): WindowInfo = WindowInfo(
        id = "window:1",
        layout = blocks.map { LayoutNode(block = it) }.reduceRight { node, rest -> LayoutNode(dir = "row", first = node, second = rest) },
    )

    @Test
    fun aTabWithOnePaneHasNoFocusCommand() {
        assertEquals(emptyList<FocusPaneCommand>(), focusPaneCommands(tiled("a"), mapOf("a" to BlockInfo(title = "zsh")), focused = "a"))
    }

    @Test
    fun everyPaneButTheFocusedOneGetsAFocusCommandInOrderWithItsLabelAndTitle() {
        val blocks = mapOf("a" to BlockInfo(title = "zsh"), "b" to BlockInfo(title = "vim"), "c" to BlockInfo(title = "htop"))

        assertEquals(
            listOf(FocusPaneCommand("Focus Pane: Pane 1 · zsh", "a"), FocusPaneCommand("Focus Pane: Pane 3 · htop", "c")),
            focusPaneCommands(tiled("a", "b", "c"), blocks, focused = "b"),
        )
    }

    @Test
    fun aPaneWithoutATitleIsNamedByItsLabelAlone() {
        // The label counts every pane of the tab, the focused one too: the third pane is "Pane 3".
        val blocks = mapOf("a" to BlockInfo(title = "zsh"), "b" to BlockInfo(title = "vim"))

        assertEquals(
            listOf(FocusPaneCommand("Focus Pane: Pane 2", "b"), FocusPaneCommand("Focus Pane: Pane 3", "c")),
            focusPaneCommands(tiled("a", "b", "c"), blocks - "b", focused = "a"),
        )
    }

    @Test
    fun aFocusCommandNamesItsPaneAsThePaneMenuNamesItsTarget() {
        val window = tiled("a", "b", "c")
        val blocks = mapOf("b" to BlockInfo(title = "vim"))

        // The menu's heading is the pane's label; the picker and the palette add the title to it.
        for (command in focusPaneCommands(window, blocks, focused = "a")) {
            val label = paneLabel(tabBlocks(window), command.block)!!
            assertTrue(command.label, command.label == "Focus Pane: $label" || command.label.startsWith("Focus Pane: $label · "))
        }
    }

    @Test
    fun aZoomedTabOffersOnlyThePanesItDrawsNumberedInTheWholeTab() {
        // Zoomed, the tab draws "b" and the floating "f"; the docked "a" is not on screen.
        // The floating pane keeps the number it has in the whole tab, as in the pane menu and the picker.
        val window = tiled("a", "b").copy(floating = listOf(FloatingLayer("f")), zoomed = "b")

        assertEquals(
            listOf(FocusPaneCommand("Focus Pane: Pane 3", "f")),
            focusPaneCommands(window, emptyMap(), focused = "b"),
        )
        assertEquals(listOf("b", "f"), keyboardPaneBlocks(window))
    }

    @Test
    fun menuHeadingAndCommandsUseTheSameTarget() {
        val focused = "block:focused"
        val tapped = "block:tapped"
        val panes = listOf(focused, tapped)
        assertEquals("Pane 1", paneLabel(panes, focused))
        assertEquals("Pane 2", paneLabel(panes, tapped))
        assertEquals(null, paneLabel(panes, "block:removed"))

        val actions = paneMenu(floating = true, zoomed = true, paneCount = panes.size)
        val layoutActions = setOf(PaneSplit.Right, PaneSplit.Below, PaneSplit.Left, PaneSplit.Above, PaneSplit.Auto, PaneAction.Dock, PaneAction.Restore, PaneAction.Close)
        val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
        val closeRequests = mutableListOf<String>()
        for (item in actions.filter { it.action in layoutActions }) {
            assertTrue(item.enabled)
            runPaneLayoutAction(
                item.action, tapped,
                send = { name, args -> commands.add(name to args.toMap()) },
                onClose = { closeRequests.add(it) },
                onAutoSplit = { target -> commands.add("auto" to mapOf("block" to target)) },
            )
        }
        assertEquals(listOf(
            "split" to mapOf("block" to tapped, "dir" to "right"),
            "split" to mapOf("block" to tapped, "dir" to "below"),
            "split" to mapOf("block" to tapped, "dir" to "left"),
            "split" to mapOf("block" to tapped, "dir" to "above"),
            "auto" to mapOf("block" to tapped),
            "dock" to mapOf("block" to tapped),
            "unzoom" to mapOf("block" to tapped),
        ), commands)
        assertEquals(listOf(tapped), closeRequests)
    }

    @Test
    fun aCopyThatReadsNoTextKeepsTheClipboard() {
        // The core gives "" when it cannot read the pane, and for a blank screen or selection
        // (it trims blanks): the clipboard keeps what it had.
        assertNull(paneCopyText(""))
        // What the core returns, it returns trimmed: that is the copy.
        assertEquals("a  b", paneCopyText("a  b"))
        assertEquals("ls -la", paneCopyText("ls -la"))
    }

    @Test
    fun aPasteThatRunsNothingIsNotAskedAbout() {
        // An empty clipboard is not sent at all, so there is nothing to ask.
        assertNull(pasteQuestion("", bracketed = false))
        assertNull(pasteQuestion("ls -la", bracketed = false))
        // A tab completes or indents: it ends no line.
        assertNull(pasteQuestion("ls\t-la", bracketed = false))
    }

    @Test
    fun aPasteOfSeveralLinesAsksWithTheirNumber() {
        assertEquals("Paste 3 lines?", pasteQuestion("ls\npwd\nwhoami", bracketed = false))
        // The line break that ends the text starts no line.
        assertEquals("Paste 3 lines?", pasteQuestion("ls\npwd\nwhoami\n", bracketed = false))
        // A carriage return with its line feed is one line break, and one alone is one too.
        assertEquals("Paste 2 lines?", pasteQuestion("ls\r\npwd", bracketed = false))
        assertEquals("Paste 2 lines?", pasteQuestion("ls\r\npwd\r\n", bracketed = false))
        assertEquals("Paste 2 lines?", pasteQuestion("ls\rpwd", bracketed = false))
    }

    @Test
    fun aPasteOfOneLineThatRunsOrSteersAsksWithoutANumber() {
        // One line ending in a line break arrives with Enter, and the title says so.
        assertEquals("Paste this line and press Enter?", pasteQuestion("ls\n", bracketed = false))
        assertEquals("Paste this line and press Enter?", pasteQuestion("ls\r\n", bracketed = false))
        assertEquals("Paste this line and press Enter?", pasteQuestion("ls\r", bracketed = false))
        // An escape character starts a key or a terminal sequence, also on a line that ends.
        assertEquals("Paste text with control characters?", pasteQuestion("ls\u001b[A", bracketed = false))
        assertEquals("Paste text with control characters?", pasteQuestion("ls\u001b[A\n", bracketed = false))
    }

    @Test
    fun aPasteIntoATerminalWithBracketedPasteIsNeverAskedAbout() {
        // The program gets the text as one paste and runs nothing by itself.
        for (text in listOf("", "ls -la", "ls\n", "ls\npwd\nwhoami", "ls\r\npwd\r\n", "ls\t-la", "ls\u001b[A")) {
            assertNull(text, pasteQuestion(text, bracketed = true))
        }
    }

    @Test
    fun aKeyboardTextWithALineBreakOrAControlCharacterIsAPaste() {
        for (text in listOf("ls\npwd", "ls\r\npwd", "ls\n", "\nls", "ls\u001b[A", "a\u0003", "\n\n")) {
            assertTrue(text, keyboardTextIsPaste(text))
        }
    }

    @Test
    fun typedKeyboardTextAndItsEnterAreNoPaste() {
        // Letters, a syllable, an emoji and a tab are typed; so is the Enter a keyboard commits as one line break.
        for (text in listOf("a", "한", "받아쓰기🙂", "ls -la", "ls\t-la", "\t", "\n", "\r", "\r\n")) {
            assertFalse(text, keyboardTextIsPaste(text))
        }
    }

    @Test
    fun noMenuOfARemoteASessionATabOrAPaneNamesSettings() {
        val labels = remoteMenu("connected", false, ssh = true).map { it.label } + sessionMenu().map { it.label } + tabMenu(true, true, true).map { it.label } +
            paneMenu(false, false, 2).map { it.label }

        assertTrue(labels.toString(), labels.none { it.contains("Settings") })
        // The pane's menu makes no tab and no session.
        assertTrue(paneMenu(false, false, 2).none { it.label.startsWith("New") })
    }

    @Test
    fun thePaletteListsEverySavedRemoteAndEveryUnitsCommands() {
        val listed = full.listed()

        assertTrue(PaletteAction.GoToRemote(workstation) in listed && PaletteAction.GoToRemote(mac) in listed)
        for (action in listOf(
            PaletteAction.AddRemote, PaletteAction.RemoveRemote, PaletteAction.Reconnect, PaletteAction.NewSession, PaletteAction.NewTab,
            PaletteAction.RenameSession, PaletteAction.RenameTab, PaletteAction.MoveTabForward, PaletteAction.MoveTabBackward,
            PaletteAction.CloseTab, PaletteAction.CloseSession,
            PaletteAction.TextLarger, PaletteAction.TextSmaller, PaletteAction.TextReset, PaletteAction.Settings,
            PaneSplit.Right, PaneSplit.Below, PaneSplit.Left, PaneSplit.Above, PaneSplit.Auto, PaletteAction.Zoom, PaletteAction.Float, PaletteAction.ClosePane,
            PaletteAction.Minimize, PaletteAction.MinimizeAll,
        ) + CapsuleCorner.entries.map { PaletteAction.MoveCapsules(it) } + PaneDirection.entries.map { PaletteAction.MovePane(it) }) assertTrue(action.toString(), action in listed)
        // Connected: every command runs, but Reconnect, which is for a remote that is down. A tab with
        // a place on each side can move either way; `full` alone says nothing of its place. The stash
        // commands run only where the tab allows them (`canStash`, `floatingToggle`), and a pane
        // moves only toward a neighbour (`moves`).
        val moves = PaneDirection.entries.toSet()
        assertEquals(
            listed.toSet() - PaletteAction.Reconnect - PaletteAction.MoveCapsules(DefaultCapsuleCorner),
            full.copy(tabForward = true, tabBackward = true, canStash = true, floatingToggle = true, moves = moves).enabled(),
        )
        assertEquals(
            setOf(PaletteAction.Minimize, PaletteAction.MinimizeAll, PaletteAction.MoveCapsules(DefaultCapsuleCorner)),
            listed.toSet() - full.copy(tabForward = true, tabBackward = true, moves = moves).enabled() - PaletteAction.Reconnect,
        )
    }

    @Test
    fun aCommandStaysListedWhenItsUnitIsMissingAndIsDisabled() {
        // Whatever is missing, the same commands are listed: only what can run changes.
        val none = full.copy(connected = false, session = false, tab = false, pane = false, paneCount = 0, removable = false)
        assertEquals(full.listed(), none.listed())
        assertEquals(
            // The corner of the capsules is the phone's own setting, as the text size is: it needs no connection.
            setOf(PaletteAction.GoToRemote(workstation), PaletteAction.GoToRemote(mac), PaletteAction.AddRemote, PaletteAction.Settings) +
                CapsuleCorner.entries.filter { it != DefaultCapsuleCorner }.map { PaletteAction.MoveCapsules(it) },
            none.enabled(),
        )
    }

    @Test
    fun thePaletteReconnectsARemoteThatIsDownWhenTheRailWould() {
        // The keyboard reaches Reconnect through the palette: the rail's menu is not focusable.
        val down = full.copy(connected = false, session = false, tab = false, pane = false, paneCount = 0)
        for (status in listOf("reconnecting", "error", "offline")) {
            assertTrue(status, PaletteAction.Reconnect in down.copy(canReconnect = connectionMark(status, removed = false).canReconnect).enabled())
        }
        // A first attempt that is under way, and the removed remote, are not connected again.
        for ((status, removed) in listOf("connecting" to false, "error" to true)) {
            assertFalse(status, PaletteAction.Reconnect in down.copy(canReconnect = connectionMark(status, removed).canReconnect).enabled())
        }
        assertEquals("Reconnect", paletteItems(down).single { it.action == PaletteAction.Reconnect }.label)
    }

    @Test
    fun withoutTheConnectionThePalettesPaneCommandsStopButTheTextSizeOfAShownPane() {
        // As in the pane's menu: the text size is the phone's own, the rest goes to the server.
        val offline = full.copy(connected = false).enabled()
        for (on in listOf(PaletteAction.TextLarger, PaletteAction.TextSmaller, PaletteAction.TextReset)) assertTrue(on.toString(), on in offline)
        for (off in listOf(PaneSplit.Right, PaletteAction.Zoom, PaletteAction.Float, PaletteAction.ClosePane, PaletteAction.NewTab)) {
            assertFalse(off.toString(), off in offline)
        }
    }

    @Test
    fun withoutAPaneThePaneCommandsAreOffAndTheTabsStayOn() {
        val noPane = full.copy(pane = false, paneCount = 0).enabled()

        for (off in listOf(PaneSplit.Right, PaneSplit.Below, PaneSplit.Left, PaneSplit.Above, PaneSplit.Auto, PaletteAction.Zoom, PaletteAction.Float,
            PaletteAction.ClosePane, PaletteAction.TextLarger, PaletteAction.TextSmaller, PaletteAction.TextReset)) {
            assertFalse(off.toString(), off in noPane)
        }
        for (on in listOf(PaletteAction.NewTab, PaletteAction.RenameTab, PaletteAction.CloseTab, PaletteAction.CloseSession)) {
            assertTrue(on.toString(), on in noPane)
        }
    }

    @Test
    fun withoutATabOnlyTheSessionsAndTheRemotesCommandsRun() {
        val noTab = full.copy(tab = false, pane = false, paneCount = 0).enabled()

        assertFalse(PaletteAction.RenameTab in noTab || PaletteAction.CloseTab in noTab)
        assertTrue(PaletteAction.NewTab in noTab && PaletteAction.RenameSession in noTab && PaletteAction.CloseSession in noTab)
    }

    @Test
    fun withoutASessionOnlyTheRemotesCommandsRun() {
        val noSession = full.copy(session = false, tab = false, pane = false, paneCount = 0).enabled()

        for (off in listOf(PaletteAction.NewTab, PaletteAction.RenameSession, PaletteAction.CloseSession)) assertFalse(off.toString(), off in noSession)
        assertTrue(PaletteAction.NewSession in noSession && PaletteAction.RemoveRemote in noSession)
    }

    @Test
    fun aRemoteThatIsOfflineMakesNothingAndCanStillBeLeftOrRemoved() {
        val offline = full.copy(connected = false).enabled()

        assertFalse(PaletteAction.NewSession in offline || PaletteAction.NewTab in offline)
        assertTrue(PaletteAction.RemoveRemote in offline && PaletteAction.GoToRemote(mac) in offline)
    }

    @Test
    fun theOnlyPaneOfATabOffersOnlyCloseTabInThePalette() {
        val onlyPane = full.copy(paneCount = 1)
        assertFalse(PaletteAction.ClosePane in onlyPane.listed())
        assertTrue(PaletteAction.CloseTab in onlyPane.enabled())
    }

    @Test
    fun theRailsMarkOffersReconnectWhileTheRemoteIsDownAndNoFirstAttemptRuns() {
        assertFalse(connectionMark("connected", removed = false).canReconnect)
        // The first attempt is under way: there is nothing to restart.
        assertFalse(connectionMark("connecting", removed = false).canReconnect)
        assertFalse(connectionMark("idle", removed = false).canReconnect)
        assertTrue(connectionMark("error", removed = false).canReconnect)
        assertTrue(connectionMark("reconnecting", removed = false).canReconnect)
        assertTrue(connectionMark("handshaking", removed = false).canReconnect)
        // The removed remote has no connection left to restart.
        assertFalse(connectionMark("error", removed = true).canReconnect)
    }

    @Test
    fun theRailsMarkSaysWhatTheRemotesRowSays() {
        assertEquals(ConnectionLevel.Up, connectionMark("connected", removed = false).level)
        assertEquals(ConnectionLevel.Busy, connectionMark("connecting", removed = false).level)
        assertEquals(ConnectionLevel.Down, connectionMark("error", removed = false).level)
        // The core is at work on it, as on a first attempt.
        assertEquals(ConnectionLevel.Busy, connectionMark("reconnecting", removed = false).level)
        assertEquals(ConnectionLevel.Down, connectionMark("connected", removed = true).level)
        // Five states read apart.
        assertEquals(5, listOf("connected", "connecting", "reconnecting", "error", "handshaking").map { connectionMark(it, removed = false).word }.toSet().size)
    }

    @Test
    fun aStatusIsOnePhaseWithItsWordsAndItsLevel() {
        val phases = listOf(
            "connected" to ConnectionPhase.Connected,
            "connecting" to ConnectionPhase.Connecting,
            // Before the core's first report.
            "idle" to ConnectionPhase.Connecting,
            "reconnecting" to ConnectionPhase.Reconnecting,
            "error" to ConnectionPhase.Failed,
            // A status this app does not know.
            "handshaking" to ConnectionPhase.Offline,
        )
        for ((status, phase) in phases) {
            assertEquals(status, phase, connectionPhase(status, removed = false))
            // The removed remote is Removed whatever its connection said.
            assertEquals(status, ConnectionPhase.Removed, connectionPhase(status, removed = true))
        }
        assertEquals(
            listOf("Connected", "Connecting…", "Reconnecting…", "Connection failed", "Offline", "Removed"),
            ConnectionPhase.entries.map { it.words },
        )
        assertEquals(
            listOf(ConnectionLevel.Up, ConnectionLevel.Busy, ConnectionLevel.Busy, ConnectionLevel.Down, ConnectionLevel.Down, ConnectionLevel.Down),
            ConnectionPhase.entries.map { it.level },
        )
        assertEquals(
            listOf(ConnectionPhase.Reconnecting, ConnectionPhase.Failed, ConnectionPhase.Offline),
            ConnectionPhase.entries.filter { it.canReconnect },
        )
    }

    @Test
    fun theSurfacesNameTheConnectionAlikeForEveryStatus() {
        for (status in listOf("connected", "connecting", "idle", "reconnecting", "error", "handshaking")) {
            val phase = connectionPhase(status, removed = false)
            // The rail's mark. The capsule over paused panes and the sidebar's dot draw this mark too.
            assertEquals(status, ConnectionMark(phase.level, phase.words, phase.canReconnect), connectionMark(status, removed = false))
            // The sidebar's second line: the same words in lower case, and nothing while connected.
            assertEquals(status, phase.words.lowercase().takeIf { phase != ConnectionPhase.Connected }, remoteRowNote(status, removed = false))
            if (phase == ConnectionPhase.Connected) continue
            // The empty pane area and the capsule's text, which are seen while the remote is down.
            assertEquals(status, phase.words, emptyText(UiState(status = status), "mac"))
            assertEquals(status, "${phase.words} · Input is paused", inputPausedText(status))
        }
        // The removed remote has no dot, no capsule, and its own sentence in the empty pane area.
        for (status in listOf("connected", "idle", "error")) {
            assertEquals(status, ConnectionMark(ConnectionLevel.Down, "Removed", canReconnect = false), connectionMark(status, removed = true))
            assertEquals(status, "Removed", remoteRowNote(status, removed = true))
        }
    }

    @Test
    fun theEmptyPaneAreaOffersTheWayOutForThePhase() {
        // Connected: something to show is made here.
        assertEquals(listOf(EmptyAction.NewSession), emptyActions(UiState(status = "connected"), removed = false, sidebar = false))
        assertEquals(listOf(EmptyAction.NewTab), emptyActions(noTabs, removed = false, sidebar = false))
        // Down: try again now where the phase offers it, or go to another remote.
        for (status in listOf("reconnecting", "error", "handshaking")) {
            assertEquals(status, listOf(EmptyAction.Reconnect, EmptyAction.ShowRemotes), emptyActions(noTabs.copy(status = status), removed = false, sidebar = false))
        }
        // The first attempt is under way: there is nothing to restart.
        for (status in listOf("connecting", "idle")) {
            assertEquals(status, listOf(EmptyAction.ShowRemotes), emptyActions(UiState(status = status), removed = false, sidebar = false))
        }
        // The removed remote can only be left.
        assertEquals(listOf(EmptyAction.ShowRemotes), emptyActions(UiState(status = "idle"), removed = true, sidebar = false))
        // The open sidebar already shows the remotes.
        assertEquals(listOf(EmptyAction.Reconnect), emptyActions(UiState(status = "error"), removed = false, sidebar = true))
        assertEquals(emptyList<EmptyAction>(), emptyActions(UiState(status = "idle"), removed = true, sidebar = true))
        // Copy command is offered only for a failure with a one-line fix (SshDialogsTest).
        assertEquals(listOf("New Session", "New Tab", "Reconnect", "Copy command", "Show Remotes"), EmptyAction.entries.map { it.label })
    }

    private val sessionA = SessionInfo("session:a", tabs = listOf(TabInfo("window:a1")))

    /** Connected, with `session:b` selected, [listed] in the list, and [windows] from the core's own view of it. */
    private fun selected(vararg listed: SessionInfo, windows: List<WindowInfo> = emptyList()) =
        UiState(status = "connected", sessions = listed.toList(), session = "session:b", windows = windows, window = windows.firstOrNull()?.id)

    /**
     * The moments after a session is selected, as the core publishes them: before the attach
     * answer `windows` is empty and the session's row has no tabs (none of its views was read),
     * the tabs its follow read, or is not listed yet (a session just made).
     */
    private val waiting = listOf(
        selected(sessionA, SessionInfo("session:b")),
        selected(sessionA, SessionInfo("session:b", tabs = listOf(TabInfo("window:b1"), TabInfo("window:b2")))),
        selected(sessionA),
    )
    /** After the answer, or before it when its follow read it so: the session really has no tab. */
    private val noTabs = selected(sessionA, SessionInfo("session:b", tabs = emptyList()))
    /** After the answer: a docked tab, and a tab whose only pane floats (it has no layout). */
    private val docked = WindowInfo("window:b1", layout = LayoutNode(block = "block:b1"))
    private val allFloating = WindowInfo("window:b1", floating = listOf(FloatingLayer("block:b1", 0.1f, 0.1f, 0.5f, 0.5f)))
    /** After the answer: the session's own view, with a tab. */
    private val shown = selected(sessionA, SessionInfo("session:b", tabs = listOf(TabInfo("window:b1", active = true))), windows = listOf(docked))
    /** The attach was refused, or the user detached: sessions are listed and none is selected. */
    private val unchosen = UiState(status = "connected", sessions = listOf(sessionA, SessionInfo("session:b")))
    private val noSessions = UiState(status = "connected")

    @Test
    fun theEmptyPaneAreaSaysLoadingUntilTheSelectedSessionsOwnViewArrives() {
        for (state in waiting) {
            assertEquals(state.toString(), "Loading…", emptyText(state, "mac"))
            // New Tab would make a real tab in a session that may have some.
            for (sidebar in listOf(false, true)) assertEquals(state.toString(), emptyList<EmptyAction>(), emptyActions(state, removed = false, sidebar = sidebar))
        }
        assertEquals("No tabs in this session", emptyText(noTabs, "mac"))
        assertEquals(listOf(EmptyAction.NewTab), emptyActions(noTabs, removed = false, sidebar = false))
        assertEquals(listOf(EmptyAction.NewTab), emptyActions(noTabs, removed = false, sidebar = true))
    }

    @Test
    fun aShownTabIsDrawnWhetherItsPanesAreDockedOrAllFloating() {
        // The empty text is for a session without a tab, never for a tab that has panes.
        assertTrue(drawsPanes(docked))
        assertTrue(drawsPanes(allFloating))
        // A zoomed floating pane is still a pane of the tab.
        assertTrue(drawsPanes(allFloating.copy(zoomed = "block:b1")))
        // A tab the core lists with no pane at all (no layout, nothing floating) has nothing to draw.
        assertFalse(drawsPanes(WindowInfo("window:b1")))
    }

    @Test
    fun withoutASelectedSessionTheEmptyPaneAreaSaysWhetherThereIsOneToChoose() {
        assertEquals("No sessions on mac", emptyText(noSessions, "mac"))
        assertEquals("No sessions", emptyText(noSessions, ""))
        assertEquals(listOf(EmptyAction.NewSession), emptyActions(noSessions, removed = false, sidebar = false))
        assertEquals(listOf(EmptyAction.NewSession), emptyActions(noSessions, removed = false, sidebar = true))
        // The listed sessions are in the sidebar; Show Remotes opens it.
        assertEquals("Choose a session in the sidebar.", emptyText(unchosen, "mac"))
        assertEquals("Choose a session in the sidebar.", emptyText(unchosen, ""))
        assertEquals(listOf(EmptyAction.ShowRemotes, EmptyAction.NewSession), emptyActions(unchosen, removed = false, sidebar = false))
        assertEquals(listOf(EmptyAction.NewSession), emptyActions(unchosen, removed = false, sidebar = true))
    }

    @Test
    fun aRemoteThatIsDownSaysItsPhaseWhateverItsSessionsSay() {
        for (status in listOf("connecting", "idle", "reconnecting", "error", "handshaking")) {
            val phase = connectionPhase(status, removed = false)
            for (state in waiting + listOf(noTabs, shown, shown.copy(windows = listOf(allFloating)), unchosen, noSessions)) {
                val down = state.copy(status = status)
                assertEquals("$status $state", phase.words, emptyText(down, "mac"))
                assertEquals("$status $state", listOfNotNull(EmptyAction.Reconnect.takeIf { phase.canReconnect }, EmptyAction.ShowRemotes),
                    emptyActions(down, removed = false, sidebar = false))
                // The removed remote can only be left, whatever its core last said.
                assertEquals("$status $state", listOf(EmptyAction.ShowRemotes), emptyActions(down, removed = true, sidebar = false))
            }
        }
    }
}
