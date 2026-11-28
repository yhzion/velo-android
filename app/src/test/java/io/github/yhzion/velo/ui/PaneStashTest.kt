package io.github.yhzion.velo.ui

import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.yhzion.velo.core.BlockInfo
import io.github.yhzion.velo.core.FloatingLayer
import io.github.yhzion.velo.core.LayoutNode
import io.github.yhzion.velo.core.WindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun tiled(vararg blocks: String): LayoutNode =
    blocks.map { LayoutNode(block = it) }.reduce { a, b -> LayoutNode(dir = "row", first = a, second = b) }

class PaneStashTest {

    @Test
    fun canStash() {
        assertTrue(canStash(WindowInfo("w", layout = tiled("a", "b")), "b"))
        assertFalse(canStash(WindowInfo("w", layout = tiled("a")), "a"))
        val mixed = WindowInfo("w", layout = tiled("a"), floating = listOf(FloatingLayer("b")))
        assertTrue(canStash(mixed, "b"))
        assertTrue(canStash(mixed, "a"))
        assertFalse(canStash(WindowInfo("w", floating = listOf(FloatingLayer("b"))), "b"))
        assertFalse(canStash(WindowInfo("w", layout = tiled("a", "b")), "x"))
    }

    @Test
    fun floatingToggle() {
        assertEquals(true, floatingToggle(WindowInfo("w", layout = tiled("a"), floating = listOf(FloatingLayer("b")))))
        assertNull(floatingToggle(WindowInfo("w", floating = listOf(FloatingLayer("b")))))
        assertEquals(false, floatingToggle(WindowInfo("w", layout = tiled("a"), minimized = true, minimizedLayers = listOf(listOf("c")))))
        assertNull(floatingToggle(WindowInfo("w", layout = tiled("a"))))
    }

    @Test
    fun capsulesListOnePerMinimizedLayerInOrder() {
        val window = WindowInfo("w", layout = tiled("a"), minimizedLayers = listOf(listOf("b"), listOf("c", "d"), emptyList()))
        val blocks = mapOf("b" to BlockInfo(title = "top"), "c" to BlockInfo(title = "", program = "vim"), "d" to BlockInfo(title = "x"))
        assertEquals(listOf(Capsule("b", "top", 0), Capsule("c", "vim", 1)), capsules(window, blocks))
        assertEquals(emptyList<Capsule>(), capsules(WindowInfo("w", layout = tiled("a")), blocks))
    }

    /** A capsule names its pane as every place does (#208, [paneName]), not by the server's label made from an ID. */
    @Test
    fun aCapsuleNamesItsPaneByTheOneRule() {
        fun named(info: BlockInfo?) = capsules(WindowInfo("w", minimizedLayers = listOf(listOf("z"))), listOfNotNull(info?.let { "z" to it }).toMap()).single().name
        assertEquals("Terminal", named(null))
        assertEquals("Terminal", named(BlockInfo()))
        assertEquals("Terminal", named(BlockInfo(title = "  ", program = " ")))
        assertEquals("vim", named(BlockInfo(program = "vim")))
        assertEquals("top", named(BlockInfo(title = "top", program = "vim")))
        // The issue's zsh pane: the label before the program said "Block 9ac42573".
        assertEquals("zsh", named(BlockInfo(title = "Block 9ac42573", titleIsLabel = true, program = "/bin/zsh")))
        assertEquals("Terminal", named(BlockInfo(title = "Block 9ac42573", titleIsLabel = true)))
        assertEquals("Claude Code", named(BlockInfo(title = "Block 9ac42573", titleIsLabel = true, icon = "claude")))
    }

    @Test
    fun capsuleTextCountsTheOtherPanes() {
        assertEquals("vim +1", Capsule("c", "vim", 1).text)
        assertEquals("top", Capsule("b", "top", 0).text)
    }

    @Test
    fun capsulesAreShownOnlyOutsideTheOverviewAndAPaneMove() {
        val window = WindowInfo("w", layout = tiled("a"), minimizedLayers = listOf(listOf("b")))
        val blocks = mapOf("b" to BlockInfo(title = "top"))
        val shown = listOf(Capsule("b", "top", 0))
        assertEquals(shown, shownCapsules(window, blocks, false, false))
        assertEquals(emptyList<Capsule>(), shownCapsules(null, blocks, false, false))
        assertEquals(emptyList<Capsule>(), shownCapsules(window, blocks, true, false))
        // A pane move has begun and the overview has not yet moved: no capsule for that frame.
        assertEquals(emptyList<Capsule>(), shownCapsules(window, blocks, false, true))
        // Every pane of the tab is minimized, so nothing is drawn: the capsules stay.
        val allMinimized = WindowInfo("w", minimizedLayers = listOf(listOf("b")))
        assertEquals(shown, shownCapsules(allMinimized, blocks, false, false))
    }

    @Test
    fun theStageStaysBlankForACapsuleToBringAPaneBack() {
        assertTrue(blankStage(WindowInfo("w", minimizedLayers = listOf(listOf("b")))))
        assertFalse(blankStage(null))
        assertFalse(blankStage(WindowInfo("w")))
        assertFalse(blankStage(WindowInfo("w", layout = tiled("a"), minimizedLayers = listOf(listOf("b")))))
    }

    @Test
    fun theRowSitsInTheChosenCornerWhateverTheTextDirection() {
        assertEquals(AbsoluteAlignment.TopLeft, CapsuleCorner.TopLeft.alignment())
        assertEquals(AbsoluteAlignment.TopRight, CapsuleCorner.TopRight.alignment())
        assertEquals(AbsoluteAlignment.BottomLeft, CapsuleCorner.BottomLeft.alignment())
        assertEquals(AbsoluteAlignment.BottomRight, CapsuleCorner.BottomRight.alignment())
        assertEquals(listOf(false, false, true, true), CapsuleCorner.entries.map { it.bottom })
    }

    @Test
    fun theNoticesRiseOnlyForARowAtABottomCorner() {
        val row = 48.dp
        val gap = 10.dp
        for (corner in listOf(CapsuleCorner.BottomLeft, CapsuleCorner.BottomRight)) {
            assertEquals(58.dp, noticeLift(true, corner, row, gap))
            assertEquals(0.dp, noticeLift(false, corner, row, gap))
        }
        for (corner in listOf(CapsuleCorner.TopLeft, CapsuleCorner.TopRight)) {
            assertEquals(0.dp, noticeLift(true, corner, row, gap))
            assertEquals(0.dp, noticeLift(false, corner, row, gap))
        }
    }

    @Test
    fun aRowAtATopCornerStartsBelowWhatLiesAtTheTop() {
        val insets = CapsuleInsets(side = 16.dp, top = 64.dp)
        for (corner in CapsuleCorner.entries) {
            val padding = insets.padding(corner)
            // Absolute, as the corners are: the same in a right-to-left layout.
            for (direction in LayoutDirection.entries) {
                assertEquals(16.dp, padding.calculateLeftPadding(direction))
                assertEquals(16.dp, padding.calculateRightPadding(direction))
            }
            assertEquals(16.dp, padding.calculateBottomPadding())
            assertEquals(if (corner.bottom) 16.dp else 64.dp, padding.calculateTopPadding())
        }
    }

    @Test
    fun theRowHasAPlaceAtEachCorner() {
        // A 250 by 48 row in a 411 by 700 box, 16 from the sides and the bottom, 64 from the top at a top corner.
        val row = Size(250f, 48f)
        val box = Size(411f, 700f)
        assertEquals(Offset(16f, 64f), capsulePlace(CapsuleCorner.TopLeft, row, box, side = 16f, top = 64f))
        assertEquals(Offset(145f, 64f), capsulePlace(CapsuleCorner.TopRight, row, box, side = 16f, top = 64f))
        assertEquals(Offset(16f, 636f), capsulePlace(CapsuleCorner.BottomLeft, row, box, side = 16f, top = 64f))
        assertEquals(Offset(145f, 636f), capsulePlace(CapsuleCorner.BottomRight, row, box, side = 16f, top = 64f))
    }

    @Test
    fun theFarPlaceOfARowTooLargeForTwoIsTheNearOne() {
        // Wider than the box between its sides: right is left. Taller than the box below the top inset: bottom is top.
        assertEquals(Offset(16f, 636f), capsulePlace(CapsuleCorner.BottomRight, Size(400f, 48f), Size(411f, 700f), side = 16f, top = 64f))
        assertEquals(Offset(145f, 64f), capsulePlace(CapsuleCorner.BottomRight, Size(250f, 48f), Size(411f, 100f), side = 16f, top = 64f))
    }

    @Test
    fun theMoveToACornerLabelsNameTheCorner() {
        assertEquals("Move Minimized Windows to Top Left", moveCornerLabel(CapsuleCorner.TopLeft))
        assertEquals("Move Minimized Windows to Bottom Right", moveCornerLabel(CapsuleCorner.BottomRight))
    }

    @Test
    fun aFingerOnACapsuleHoldsTapsOrMovesBeforeTheLongPress() {
        val slop = 8f
        // Still within the slop, the slop itself included: the long press may yet come.
        assertEquals(CapsuleFinger.Holding, capsuleFinger(3f, -3f, slop, pressed = true, fingers = 1, consumed = false))
        assertEquals(CapsuleFinger.Holding, capsuleFinger(8f, 0f, slop, pressed = true, fingers = 1, consumed = false))
        // Lifted first: the capsule's tap, which shows its pane.
        assertEquals(CapsuleFinger.Tap, capsuleFinger(3f, 3f, slop, pressed = false, fingers = 0, consumed = false))
        // Past the slop in any direction: no long press; sideways, the row scrolls.
        assertEquals(CapsuleFinger.Moved, capsuleFinger(9f, 0f, slop, pressed = true, fingers = 1, consumed = false))
        assertEquals(CapsuleFinger.Moved, capsuleFinger(0f, -9f, slop, pressed = true, fingers = 1, consumed = false))
        assertEquals(CapsuleFinger.Moved, capsuleFinger(6f, 6f, slop, pressed = true, fingers = 1, consumed = false))
    }

    @Test
    fun anotherFingerOrATakenChangeEndsTheWaitForTheLongPress() {
        val slop = 8f
        assertEquals(CapsuleFinger.Taken, capsuleFinger(0f, 0f, slop, pressed = true, fingers = 2, consumed = false))
        // A scroll that began took the change.
        assertEquals(CapsuleFinger.Taken, capsuleFinger(0f, 0f, slop, pressed = true, fingers = 1, consumed = true))
        // The system's cancel comes as a lift already taken: it is no tap.
        assertEquals(CapsuleFinger.Taken, capsuleFinger(0f, 0f, slop, pressed = false, fingers = 0, consumed = true))
    }

    @Test
    fun aLiftedRowFollowsTheFingerAndDropsWhereItLifts() {
        assertEquals(LiftedFinger.Follow, liftedFinger(pressed = true, consumed = false))
        assertEquals(LiftedFinger.Follow, liftedFinger(pressed = true, consumed = true))
        assertEquals(LiftedFinger.Drop, liftedFinger(pressed = false, consumed = false))
        // The system's cancel: the row goes back, and nothing is saved.
        assertEquals(LiftedFinger.PutBack, liftedFinger(pressed = false, consumed = true))
    }

    /**
     * The corner a [row]-sized row at [shown] goes to when dragged by [dx], [dy] in a 411 by 700 box (16 at the sides,
     * 64 at the top), with a touch slop of 8.
     */
    private fun drop(shown: CapsuleCorner, dx: Float, dy: Float, row: Size = Size(250f, 48f)): CapsuleCorner? =
        dropCorner(shown, Offset(dx, dy), slop = 8f) { capsulePlace(it, row, Size(411f, 700f), side = 16f, top = 64f) }

    @Test
    fun aRowLetGoWhereItStoodSavesNothing() {
        // Nothing is written, so a user who never chose still follows the default.
        assertNull(drop(CapsuleCorner.BottomLeft, 0f, 0f))
        assertNull(drop(CapsuleCorner.BottomLeft, 5f, -5f))
        assertNull(drop(CapsuleCorner.TopRight, -20f, 30f))
    }

    @Test
    fun aRowGoesToTheNearestPlaceOnceItIsPastHalfway() {
        // Left and right places are 129 apart: past 64.5 the right one is nearer, wherever the finger held the row.
        assertNull(drop(CapsuleCorner.BottomLeft, 60f, 0f))
        assertEquals(CapsuleCorner.BottomRight, drop(CapsuleCorner.BottomLeft, 70f, 0f))
        // Bottom and top places are 572 apart: straight up past 286 is the top-left place, not the top-right one.
        assertNull(drop(CapsuleCorner.BottomLeft, 0f, -280f))
        assertEquals(CapsuleCorner.TopLeft, drop(CapsuleCorner.BottomLeft, 0f, -300f))
        // A long diagonal drag: the opposite corner.
        assertEquals(CapsuleCorner.TopRight, drop(CapsuleCorner.BottomLeft, 200f, -600f))
        assertEquals(CapsuleCorner.BottomLeft, drop(CapsuleCorner.TopRight, -200f, 600f))
    }

    @Test
    fun aTieKeepsTheShownCorner() {
        assertNull(drop(CapsuleCorner.BottomLeft, 64.5f, 0f))
    }

    @Test
    fun aRowAsWideAsTheBoxCannotMoveSideways() {
        // 379 fills the box between its sides; 400 is wider still: left and right are one place.
        for (width in listOf(379f, 400f)) {
            assertNull(drop(CapsuleCorner.BottomLeft, 200f, 0f, Size(width, 48f)))
            assertNull(drop(CapsuleCorner.BottomLeft, -200f, 0f, Size(width, 48f)))
            assertNull(drop(CapsuleCorner.BottomRight, -200f, 0f, Size(width, 48f)))
            assertEquals(CapsuleCorner.TopLeft, drop(CapsuleCorner.BottomLeft, 200f, -600f, Size(width, 48f)))
            // Up from the right: still the right, which is the same place, not the left.
            assertEquals(CapsuleCorner.TopRight, drop(CapsuleCorner.BottomRight, -200f, -600f, Size(width, 48f)))
        }
    }

    @Test
    fun aRowLetGoWithinTheTouchSlopSavesNothing() {
        // A 364 wide row: its left and right places are 15 apart, nearer than twice the slop.
        val row = Size(364f, 48f)
        assertNull(drop(CapsuleCorner.BottomLeft, 8f, 0f, row))
        assertEquals(CapsuleCorner.BottomRight, drop(CapsuleCorner.BottomLeft, 9f, 0f, row))
    }
}
