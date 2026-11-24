package io.github.yhzion.velo.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sign

class OverviewGridTest {
    private val near = 0.01f

    /** The LG Velvet's pane area in portrait, in px: 411×840 dp with the strip. */
    private val velvet = Stage(width = 1079f, height = 2205f, cardGap = 63f, pageGap = 63f, labelRoom = 115.5f,
        stripRoom = 252f, noteRoom = 262.5f, peek = 52.5f, rowPad = 42f)

    /** The same pane area as a grid of two columns and eight places. */
    private val grid2 = velvet.copy(labelRoom = 94.5f, grid = GridSpec(columns = 2, pad = 42f, gap = 31.5f, top = 21f, places = 8))

    /**
     * A grid of [places] at the default 13 sp font on a pane area [width]×[height] dp at [density], with TabStage's measures:
     * the strip's room by [stripRoomFor], the Undo note's room of about 100 dp at the Medium UI size.
     */
    private fun screen(width: Float, height: Float, density: Float, places: Int = 8): Stage {
        val w = width * density
        val h = height * density
        val pad = 16f * density
        val gap = 12f * density
        return Stage(w, h, cardGap = 24f * density, pageGap = 24f * density, labelRoom = 36f * density,
            stripRoom = stripRoomFor(h, 44f * density, 96f * density), noteRoom = 100f * density, peek = 20f * density,
            rowPad = 16f * density, grid = GridSpec(gridColumns(w, 13f, pad, gap), pad, gap, 8f * density, places))
    }

    /** The LG Velvet's pane area in landscape, 860×340 dp: a card is taller than the room between the strip and the note. */
    private val landscape = screen(860f, 340f, 2.625f)

    @Test fun gridColumnsFollowTheFontAndStayWithinOneToFour() {
        assertEquals(2, gridColumns(1079f, 13f, 42f, 31.5f))      // Velvet portrait
        assertEquals(2, gridColumns(2272.5f, 13f, 36f, 27f))      // Fold8 unfolded
        assertEquals(3, gridColumns(2272.5f, 18f, 36f, 27f))
        assertEquals(1, gridColumns(1079f, 8f, 42f, 31.5f))
        assertEquals(4, gridColumns(8000f, 24f, 42f, 31.5f))
    }

    @Test fun theSpecsScreensHaveTwoColumnsAtTheDefaultFont() {
        // The design's table (13 sp): the pane area in dp, its density, and the text on a card in dp.
        class Screen(val name: String, val width: Float, val height: Float, val density: Float, val text: Float)
        for (screen in listOf(
            Screen("Velvet portrait", 411f, 840f, 2.625f, 5.8f),
            Screen("Velvet landscape", 860f, 340f, 2.625f, 6.2f),
            Screen("Fold8 unfolded", 1010f, 760f, 2.25f, 6.2f),
            Screen("Fold8 half split", 500f, 760f, 2.25f, 5.9f),
            Screen("11-inch tablet portrait", 800f, 1180f, 2f, 6.1f),
            Screen("11-inch tablet landscape", 1200f, 700f, 2f, 6.3f),
        )) {
            val stage = screen(screen.width, screen.height, screen.density)
            assertEquals(screen.name, 2, stage.grid!!.columns)
            val card = cardBox(0, 0, 1f, 0f, stage)
            val text = 13f * (card.right - card.left) / stage.width
            assertEquals(screen.name, screen.text, text, 0.05f)
            assertTrue(screen.name, text >= MIN_CARD_TEXT)
        }
    }

    @Test fun gridPlacesRunAcrossThenDown() {
        val first = cardBox(0, 0, 1f, 0f, grid2)
        val fourth = cardBox(3, 0, 1f, 0f, grid2)
        assertEquals(42f, first.left, 1f)
        assertEquals(273f, first.top, 1f)
        assertEquals(0.4465f, (first.right - first.left) / grid2.width, 0.001f)
        assertEquals(first.right + 31.5f, fourth.left, 1f)
        assertEquals(first.bottom + 94.5f + 31.5f, fourth.top, 1f)
    }

    @Test fun oneColumnCardsAreNeverLargerThanTheRowsCard() {
        val one = grid2.copy(grid = grid2.grid!!.copy(columns = 1))
        val card = cardBox(0, 0, 1f, 0f, one)
        assertEquals(cardBox(1, 1, 1f, 0f, velvet).right - cardBox(1, 1, 1f, 0f, velvet).left, card.right - card.left, 1f)
        assertEquals(one.width / 2, (card.left + card.right) / 2, 1f)
    }

    @Test fun gridOpensWithTheAnchorInViewAtEitherEnd() {
        for (anchor in 0 until 8) {
            assertTrue(0f in scrollBounds(8, anchor, grid2))
            assertTrue(inView(cardBox(anchor, anchor, 1f, 0f, grid2), grid2))
        }
        // The last row is not pushed up past the content's end: no blank half screen under it.
        val last = cardBox(7, 7, 1f, 0f, grid2)
        assertTrue(last.bottom + grid2.labelRoom + grid2.noteRoom >= grid2.height - near)
    }

    @Test fun gridScrollIsFreeAndHeldWithinTheContent() {
        val bounds = scrollBounds(8, 0, grid2)
        assertEquals(0f, bounds.start, near)
        assertEquals(150f, restingScroll(100f, 200f, 8, 0, grid2), near)
        assertEquals(bounds.endInclusive, restingScroll(bounds.endInclusive, 5000f, 8, 0, grid2), near)
    }

    @Test fun aNewAnchorLeavesEveryGridCardWhereItStands() {
        val before = cardBox(5, 1, 1f, 120f, grid2)
        val after = cardBox(5, 6, 1f, rebasedScroll(120f, 1, 6, 1f, grid2), grid2)
        assertEquals(before.left, after.left, near)
        assertEquals(before.top, after.top, near)
    }

    @Test fun gridKeepsTheAnchorInViewWhenTheColumnsChange() {
        val three = grid2.copy(width = 2272.5f, grid = grid2.grid!!.copy(columns = 3))
        val scroll = restingScroll(0f, 0f, 8, 6, three)
        assertTrue(inView(cardBox(6, 6, 1f, scroll, three), three))
    }

    @Test fun theWorkspaceIsTheSamePageRowInEveryLayout() {
        for (i in listOf(0, 3, 7)) {
            for (scroll in listOf(0f, 120f)) {
                assertEquals(tabTransform(i, 3, 0f, scroll, velvet), tabTransform(i, 3, 0f, scroll, grid2))
            }
        }
    }

    @Test fun scrollingMovesGridCardsUpAndHomeCentresTheirPlace() {
        val rest = cardBox(5, 0, 1f, 0f, grid2)
        val scrolled = cardBox(5, 0, 1f, 200f, grid2)
        assertEquals(rest.left, scrolled.left, near)
        assertEquals(rest.top - 200f, scrolled.top, near)
        // Scrolled to place 5's own scroll, its card stands where it stands when the anchor is 5.
        val own = cardBox(5, 5, 1f, 0f, grid2)
        val there = cardBox(5, 0, 1f, cardScroll(5, 0, grid2), grid2)
        assertEquals(own.top, there.top, near)
    }

    @Test fun aSinglePlaceStandsInTheFirstColumnAndTheGridDoesNotScroll() {
        val one = grid2.copy(grid = grid2.grid!!.copy(places = 1))
        val card = cardBox(0, 0, 1f, 0f, one)
        assertEquals(42f, card.left, 1f)
        assertEquals(273f, card.top, 1f)
        assertEquals(0f, scrollBounds(1, 0, one).start, near)
        assertEquals(0f, scrollBounds(1, 0, one).endInclusive, near)
        assertEquals(0f, restingScroll(300f, 900f, 1, 0, one), near)
    }

    @Test fun aGridThatFitsThePaneAreaHasNoScroll() {
        val fits = grid2.copy(grid = grid2.grid!!.copy(places = 2))
        for (anchor in 0 until 2) {
            val bounds = scrollBounds(2, anchor, fits)
            assertEquals(0f, bounds.start, near)
            assertEquals(0f, bounds.endInclusive, near)
        }
    }

    @Test fun aLowPaneAreaKeepsTheAnchorInViewWithinItsBounds() {
        val low = grid2.copy(height = 600f)
        for (anchor in 0 until 8) {
            assertTrue(0f in scrollBounds(8, anchor, low))
            assertTrue(inView(cardBox(anchor, anchor, 1f, 0f, low), low))
        }
    }

    @Test fun halfwayTheGridCardIsTheBlendOfItsPageAndItsCell() {
        val cell = cardBox(3, 0, 1f, 0f, grid2)
        val page = tabTransform(3, 0, 0f, 0f, grid2)
        val half = tabTransform(3, 0, 0.5f, 0f, grid2)
        val cellScale = (cell.right - cell.left) / grid2.width
        assertEquals(3 * (grid2.width + grid2.pageGap), page.dx, near)
        assertEquals((page.dx + cell.left) / 2, half.dx, near)
        assertEquals((page.dy + cell.top) / 2, half.dy, near)
        assertEquals((1f + cellScale) / 2, half.scale, near)
    }

    @Test fun aDropRestsTheGridWhereItsRebasedScrollIsHeldWithinTheContent() {
        for (scroll in listOf(120f, -5000f, 9000f)) {
            val rebased = rebasedScroll(scroll, 1, 6, 1f, grid2)
            assertEquals(restingScroll(rebased, 0f, 8, 6, grid2), restAfterDrop(scroll, 1, 6, 8, 1f, grid2), near)
            assertEquals(rebased.coerceIn(scrollBounds(8, 6, grid2)), restAfterDrop(scroll, 1, 6, 8, 1f, grid2), near)
        }
    }

    @Test fun gridFingerSwapsTheAxes() {
        // Sideways first on a card lifts it; up or down first scrolls; a diagonal goes by the larger travel.
        assertEquals(RowFinger.Lift, rowFinger(along(30f, 5f, grid2), across(30f, 5f, grid2), 10f, false, true, true))
        assertEquals(RowFinger.Scroll, rowFinger(along(5f, 30f, grid2), across(5f, 30f, grid2), 10f, false, true, true))
        assertEquals(RowFinger.Scroll, rowFinger(along(20f, 24f, grid2), across(20f, 24f, grid2), 10f, false, true, true))
        assertEquals(RowFinger.Lift, rowFinger(along(5f, 30f, velvet), across(5f, 30f, velvet), 10f, false, true, true))
    }

    @Test fun theScrollExtentIsTheWidthInTheRowAndTheHeightInTheGrid() {
        assertEquals(velvet.width, scrollExtent(velvet), near)
        assertEquals(grid2.height, scrollExtent(grid2), near)
    }

    @Test fun gridKeepsTheCardsInViewAndOneRowAround() {
        val kept = keptPlaces(8, 0, 1f, 0f, grid2)
        assertEquals(setOf(0, 1, 2, 3, 4, 5), kept)
    }

    @Test fun theRowKeepsWhatItKeptBefore() {
        for (progress in listOf(0f, 0.5f, 1f)) for (scroll in listOf(0f, 1871.2f, -3000f)) {
            assertEquals(keptTabs(10, 4, centredTab(10, 4, progress, scroll, velvet)), keptPlaces(10, 4, progress, scroll, velvet))
        }
        assertEquals(setOf(2, 3, 4, 5, 6), keptPlaces(10, 4, 0f, 0f, velvet))
        assertEquals(setOf(2, 3, 4, 5, 6), keptPlaces(10, 4, 1f, 0f, velvet))
        assertEquals(setOf(4, 5, 6, 7, 8), keptPlaces(10, 4, 1f, cardScroll(6, 4, velvet), velvet))
        assertEquals(setOf(0, 1, 2, 4), keptPlaces(10, 4, 1f, cardScroll(0, 4, velvet), velvet))
    }

    @Test fun aGridInTheWorkspaceKeepsTheNeighboursOfTheAnchor() {
        // The page next to the anchor must have its surface, in whichever column the anchor stands.
        assertEquals(setOf(1, 2, 3, 4, 5), keptPlaces(8, 3, 0f, 0f, grid2))
        assertEquals(keptTabs(8, 3, 3), keptPlaces(8, 3, 0f, 0f, grid2))
        val grid4 = grid2.copy(grid = grid2.grid!!.copy(columns = 4))
        assertEquals(setOf(1, 2, 3, 4, 5), keptPlaces(8, 3, 0f, 0f, grid4))
        assertEquals(setOf(5, 6, 7), keptPlaces(8, 7, 0f, 0f, grid4))
        assertEquals(setOf(0, 1, 2), keptPlaces(8, 0, 0f, 0f, grid4))
    }

    @Test fun aScrolledGridKeepsOneRowAroundTheView() {
        val first = cardBox(0, 7, 1f, 0f, grid2)
        val room = first.bottom - first.top + grid2.labelRoom + grid2.grid!!.gap
        // The last row is in view; the rows above it are kept down to one row pitch above the pane area.
        assertEquals(setOf(2, 3, 4, 5, 6, 7), keptPlaces(8, 7, 1f, 0f, grid2))
        // The first row's bottom is just inside the margin above the pane area: kept; 2 px more and it is out.
        assertEquals((0..7).toSet(), keptPlaces(8, 7, 1f, first.bottom + room - 1f, grid2))
        assertEquals(setOf(2, 3, 4, 5, 6, 7), keptPlaces(8, 7, 1f, first.bottom + room + 1f, grid2))
    }

    @Test fun keptPlacesHoldsOnlyPlacesThatExist() {
        assertEquals(emptySet<Int>(), keptPlaces(0, 0, 1f, 0f, grid2))
        assertEquals(emptySet<Int>(), keptPlaces(0, 0, 0f, 0f, grid2))
        for (anchor in listOf(-1, 8, 9)) for (progress in listOf(0f, 1f)) {
            val kept = keptPlaces(8, anchor, progress, 0f, grid2)
            assertTrue(kept.all { it in 0 until 8 })
            assertTrue(anchor !in kept)
        }
    }

    @Test fun aScrolledGridThatClosesKeepsTheAnchorsNeighbours() {
        // Closed with no pick from the top of its content, the grid zooms into the anchor's page: its neighbour comes into
        // view on the way, from a cell far below the view.
        val top = scrollBounds(9, 7, grid2).start
        assertTrue(inView(cardBox(6, 7, 0.3f, top, grid2), grid2))
        assertTrue(6 in keptPlaces(8, 7, 0.3f, top, grid2))
        // Open, the grid keeps what it kept: the cards in view, one row around them, and the anchor.
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 7), keptPlaces(8, 7, 1f, top, grid2))
    }

    @Test fun everyGridCardInViewDuringTheZoomKeepsItsSurface() {
        val spec = grid2.grid!!
        val grid4 = grid2.copy(grid = spec.copy(columns = 4))
        val one = grid2.copy(grid = spec.copy(columns = 1))
        for (stage in listOf(grid2, grid4, one, landscape)) for (anchor in 0 until 8) {
            val bounds = scrollBounds(9, anchor, stage)
            for (scroll in listOf(bounds.start, (bounds.start + bounds.endInclusive) / 2, bounds.endInclusive)) for (step in 0..20) {
                val progress = step * 0.05f
                val kept = keptPlaces(8, anchor, progress, scroll, stage)
                for (place in 0 until 8) if (inView(cardBox(place, anchor, progress, scroll, stage), stage)) {
                    assertTrue("${stage.grid} place $place, anchor $anchor, progress $progress, scroll $scroll", place in kept)
                }
            }
        }
    }

    @Test fun aCardTallerThanTheRoomUnderTheStripStartsUnderIt() {
        // The Velvet in landscape: a card of about 161 dp, with 144 dp between the strip and the note.
        val first = cardBox(0, 0, 1f, 0f, landscape)
        assertTrue(first.bottom - first.top > landscape.height - landscape.stripRoom - landscape.noteRoom)
        assertEquals(landscape.stripRoom + landscape.grid!!.top, first.top, near)
        // Each place opens with its card's top under the strip, not hidden by it, and in view.
        for (anchor in 0 until 8) {
            val card = cardBox(anchor, anchor, 1f, 0f, landscape)
            assertTrue("place $anchor", card.top >= landscape.stripRoom - near)
            assertTrue(0f in scrollBounds(9, anchor, landscape))
        }
    }

    @Test fun aGridAtProgressZeroKeepsWhatTheRowKeeps() {
        assertEquals(keptTabs(8, 3, 3), keptPlaces(8, 3, 0f, 0f, grid2))
    }

    @Test fun aScrolledGridKeepsTheAnchorToo() {
        val kept = keptPlaces(8, 0, 1f, scrollBounds(8, 0, grid2).endInclusive, grid2)
        assertTrue(0 in kept)
        assertTrue(7 in kept)
    }

    @Test fun aGridCardDropsOnTheNearestPlaceInBothAxes() {
        val target = cardBox(3, 0, 1f, 0f, grid2)
        assertEquals(3, dropPlace((target.left + target.right) / 2 + 10f, (target.top + target.bottom) / 2 - 10f, 8, 0, 1f, 0f, grid2))
    }

    @Test fun aCardsMiddleIsItsCornerAndHalfItsSize() {
        val card = cardBox(0, 0, 1f, 0f, grid2)
        assertEquals(Offset(100f + (card.right - card.left) / 2, 200f + (card.bottom - card.top) / 2), cardCentre(Offset(100f, 200f), 1f, grid2))
        // In the row the middle across is what the drag measured before, to the bit.
        for (progress in listOf(0.5f, 1f)) {
            val width = cardBox(0, 0, progress, 0f, velvet).let { it.right - it.left }
            assertEquals(812.3f + width / 2, cardCentre(Offset(812.3f, 40f), progress, velvet).x, 0f)
        }
    }

    @Test fun aGridCardHeldStillDropsOnItsOwnPlaceWhereverItIsHeld() {
        val scroll = 300f
        for (place in 0 until 7) {
            val box = cardBox(place, 0, 1f, scroll, grid2)
            for (grab in listOf(Offset.Zero, Offset(box.right - box.left - 1f, box.bottom - box.top - 1f), Offset(30f, box.bottom - box.top - 1f))) {
                val finger = Offset(box.left, box.top) + grab
                val middle = cardCentre(finger - grab, 1f, grid2)
                assertEquals("place $place held at $grab", place, dropPlace(middle.x, middle.y, 7, 0, 1f, scroll, grid2))
            }
        }
    }

    @Test fun aGridCardDropsByItsMiddleNotByTheFinger() {
        // Held near its bottom edge and moved down by less than half a row: the card's middle is still nearest its own place,
        // while the finger is already nearer the row below.
        val box = cardBox(0, 0, 1f, 0f, grid2)
        val rowStep = cardBox(2, 0, 1f, 0f, grid2).top - box.top
        val grab = Offset((box.right - box.left) / 2, box.bottom - box.top - 1f)
        val finger = Offset(box.left, box.top) + grab + Offset(0f, rowStep / 2 - 10f)
        val middle = cardCentre(finger - grab, 1f, grid2)
        assertEquals(0, dropPlace(middle.x, middle.y, 7, 0, 1f, 0f, grid2))
        assertEquals(2, dropPlace(middle.x, finger.y, 7, 0, 1f, 0f, grid2))
    }

    @Test fun theGridsTopEdgeBandStartsUnderTheStripsRoom() {
        // 48 dp and 12 dp at the Velvet's density.
        val edge = 126f
        val step = 31.5f
        // Just under the strip's room the finger is in the top band, to its end; in the room itself it scrolls up too.
        assertEquals(-step, edgeScrollAt(Offset(500f, grid2.stripRoom + 1f), grid2, edge, step), near)
        assertEquals(-step, edgeScrollAt(Offset(500f, grid2.stripRoom + edge - 1f), grid2, edge, step), near)
        assertEquals(-step, edgeScrollAt(Offset(500f, 10f), grid2, edge, step), near)
        assertEquals(0f, edgeScrollAt(Offset(500f, grid2.stripRoom + edge + 1f), grid2, edge, step), near)
        // The bottom band is the pane area's last 48 dp, as before.
        assertEquals(step, edgeScrollAt(Offset(500f, grid2.height - 1f), grid2, edge, step), near)
        assertEquals(0f, edgeScrollAt(Offset(500f, grid2.height - edge - 1f), grid2, edge, step), near)
        // A grid scrolls up and down only: a finger at a side scrolls nothing.
        assertEquals(0f, edgeScrollAt(Offset(5f, 1000f), grid2, edge, step), near)
        // A grid with no strip's room has its top band at the top.
        val noStrip = grid2.copy(stripRoom = 0f)
        assertEquals(-step, edgeScrollAt(Offset(500f, edge - 1f), noStrip, edge, step), near)
        assertEquals(0f, edgeScrollAt(Offset(500f, edge + 1f), noStrip, edge, step), near)
    }

    @Test fun aPaneMovesTopBandOverTheGridLeavesTheStripsRoomOut() {
        val edge = 126f
        val step = 31.5f
        // A long press on an upper pane's handle starts in the strip's room, where the move's capsule and Cancel stand: a
        // finger resting there scrolls nothing.
        for (y in listOf(0f, 10f, grid2.stripRoom - 1f)) assertEquals(0f, edgeScrollAt(Offset(500f, y), grid2, edge, step, roomScrolls = false), near)
        // Under the room the top band is the tab drag's, 48 dp deep; the bottom band is the same too.
        assertEquals(-step, edgeScrollAt(Offset(500f, grid2.stripRoom), grid2, edge, step, roomScrolls = false), near)
        assertEquals(-step, edgeScrollAt(Offset(500f, grid2.stripRoom + edge - 1f), grid2, edge, step, roomScrolls = false), near)
        assertEquals(0f, edgeScrollAt(Offset(500f, grid2.stripRoom + edge + 1f), grid2, edge, step, roomScrolls = false), near)
        assertEquals(step, edgeScrollAt(Offset(500f, grid2.height - 1f), grid2, edge, step, roomScrolls = false), near)
        // A tab drag's band takes the room in, as before.
        assertEquals(-step, edgeScrollAt(Offset(500f, 10f), grid2, edge, step, roomScrolls = true), near)
        // With no strip's room there is no room to leave out: the top band is at the top.
        val noStrip = grid2.copy(stripRoom = 0f)
        assertEquals(-step, edgeScrollAt(Offset(500f, 1f), noStrip, edge, step, roomScrolls = false), near)
    }

    @Test fun theRowsEdgeBandsAreTheSameForAPaneMove() {
        for (x in listOf(0f, 10f, 125f, 126f, 127f, 500f, 952f, 953f, 954f, 1078f)) for (y in listOf(0f, 10f, 300f, 2000f)) {
            assertEquals(edgeScroll(x, velvet.width, 126f, 31.5f), edgeScrollAt(Offset(x, y), velvet, 126f, 31.5f, roomScrolls = false), 0f)
        }
    }

    @Test fun aFingerOnAPaneMovesCapsuleScrollsNoGrid() {
        // A grid with no strip's room (a low pane area): the capsule stands in its top band.
        val low = grid2.copy(stripRoom = 0f)
        val capsule = Rect(300f, 30f, 780f, 160f)
        assertNull(moveEdgeFinger(Offset(500f, 100f), aimed = true, capsule, low))
        assertEquals(0f, moveEdgeFinger(Offset(500f, 100f), aimed = true, capsule, low)?.let { edgeScrollAt(it, low, 126f, 31.5f, roomScrolls = false) } ?: 0f, near)
        // Beside it the top band scrolls; a move that has not aimed yet scrolls nothing anywhere.
        assertEquals(Offset(200f, 100f), moveEdgeFinger(Offset(200f, 100f), aimed = true, capsule, low))
        assertNull(moveEdgeFinger(Offset(200f, 100f), aimed = false, capsule, low))
        assertNull(moveEdgeFinger(null, aimed = true, capsule, low))
        // The row's bands are at its sides, and the capsule changes nothing there.
        assertEquals(Offset(10f, 100f), moveEdgeFinger(Offset(10f, 100f), aimed = true, Rect(0f, 30f, 1079f, 160f), velvet))
    }

    @Test fun theRowsEdgeBandsAreWhereTheyWere() {
        for (x in listOf(0f, 10f, 125f, 126f, 127f, 500f, 952f, 953f, 954f, 1078f)) for (y in listOf(0f, 300f, 2000f)) {
            assertEquals(edgeScroll(x, velvet.width, 126f, 31.5f), edgeScrollAt(Offset(x, y), velvet, 126f, 31.5f), 0f)
        }
    }

    @Test fun noPlaceOfAPaneMoveLiesUnderTheStripsRoomOverTheGrid() {
        // Grid cards scrolled up pass under the strip's room, which hides them.
        assertTrue(cardBox(0, 0, 1f, 200f, grid2).top < grid2.stripRoom)
        assertTrue(inStripRoom(0f, grid2))
        assertTrue(inStripRoom(grid2.stripRoom - 1f, grid2))
        assertFalse(inStripRoom(grid2.stripRoom, grid2))
        // The row's cards stand below that room: nothing changes there.
        assertFalse(inStripRoom(0f, velvet))
        assertFalse(inStripRoom(0f, grid2.copy(stripRoom = 0f)))
    }

    @Test fun aGridCardFliesTheWholeWidthAway() = assertEquals(grid2.width, liftDistance(1f, grid2), near)

    @Test fun aGridCardFollowsAFingerAsideEitherWay() {
        for (dx in listOf(300f, -300f)) {
            val lift = cardAside(dx, grid2.labelRoom, closable = true)
            assertEquals(300f, lift, near)
            // The lift is a distance; the finger's side is where it goes.
            assertEquals(Offset(dx, 0f), liftOffset(lift, sign(dx)))
        }
        assertEquals(0f, cardAside(0f, grid2.labelRoom, closable = true), near)
    }

    @Test fun anOfflineGridCardIsHeldBackAsideAndDoesNotClose() {
        for (dx in listOf(300f, -300f, 3000f, -3000f)) {
            val lift = cardAside(dx, grid2.labelRoom, closable = false)
            assertTrue(lift > 0f && lift < grid2.labelRoom)
            assertFalse(dismisses(lift / dismissWay(1f, grid2), 0f))
        }
    }

    @Test fun aGridCardLetGoPastHalfItsWidthFliesOutOfViewOnItsSide() {
        val way = dismissWay(1f, grid2)
        assertTrue(dismisses(cardAside(-way * 0.6f, grid2.labelRoom, closable = true) / way, 0f))
        assertFalse(dismisses(cardAside(way * 0.4f, grid2.labelRoom, closable = true) / way, 0f))
        // Flown its whole flight, a card of either column has left the pane area on either side, its name with it.
        val flight = liftDistance(1f, grid2)
        for (place in 0..1) for (side in listOf(-1f, 1f)) {
            val box = cardBox(place, 0, 1f, 0f, grid2)
            val off = liftOffset(flight, side)
            val flown = CardBox(box.left + off.x, box.top + off.y, box.right + off.x, box.bottom + off.y + grid2.labelRoom)
            assertFalse("place $place to side $side", inView(flown, grid2))
        }
    }

    @Test fun aScreenReadersCloseFliesAGridCardOutOnTheNearerSide() {
        // The left column goes out on the left, the right one on the right, in whichever row and at whatever scroll.
        for (scroll in listOf(0f, 700f)) for (place in 0 until 8) {
            val box = cardBox(place, 0, 1f, scroll, grid2)
            assertEquals("place $place", if (place % 2 == 0) -1f else 1f, nearerSide(Offset(box.left, box.top), 1f, grid2))
        }
        val spec = grid2.grid!!
        val grid4 = grid2.copy(grid = spec.copy(columns = 4))
        assertEquals(listOf(-1f, -1f, 1f, 1f), (0 until 4).map { cardBox(it, 0, 1f, 0f, grid4).let { b -> nearerSide(Offset(b.left, b.top), 1f, grid4) } })
        // A card in the middle (one column) goes right, as every card did before.
        val one = grid2.copy(grid = spec.copy(columns = 1))
        val middle = cardBox(0, 0, 1f, 0f, one)
        assertEquals(1f, nearerSide(Offset(middle.left, middle.top), 1f, one))
    }

    @Test fun theRowsDismissWayIsItsLiftDistance() {
        for (stage in listOf(velvet, velvet.copy(stripRoom = 0f, noteRoom = 0f))) for (progress in listOf(0f, 0.5f, 0.75f, 1f)) {
            assertEquals(liftDistance(progress, stage), dismissWay(progress, stage), 0f)
        }
    }

    @Test fun aGridCardsDismissWayIsItsDrawnWidth() {
        val grid4 = grid2.copy(grid = grid2.grid!!.copy(columns = 4))
        for (stage in listOf(grid2, grid4)) for (progress in listOf(0.5f, 1f)) {
            val box = cardBox(3, 0, progress, 0f, stage)
            assertEquals(box.right - box.left, dismissWay(progress, stage), near)
        }
        // Narrower columns, a shorter way: it is the card's, not the pane area's.
        assertTrue(dismissWay(1f, grid4) < dismissWay(1f, grid2))
        assertTrue(dismissWay(1f, grid2) < liftDistance(1f, grid2))
    }

    @Test fun anOuterGridCardPulledOutwardByHalfItsWidthIsStillInView() {
        val half = dismissWay(1f, grid2) / 2
        // At half its way the card closes when let go, and the finger feels it, while part of the card still shows.
        assertTrue(dismisses(half / dismissWay(1f, grid2), 0f))
        assertEquals(true, liftCrossing(0f, half / dismissWay(1f, grid2)))
        for ((place, side) in listOf(0 to -1f, 1 to 1f)) {
            val box = cardBox(place, 0, 1f, 0f, grid2)
            val off = liftOffset(half, side)
            assertTrue("place $place to side $side", inView(CardBox(box.left + off.x, box.top, box.right + off.x, box.bottom), grid2))
        }
    }

    @Test fun aFingerThatLandsStopsAMovingGridButNotTheRow() {
        assertTrue(catchesScroll(grid2, moving = true))
        assertFalse(catchesScroll(grid2, moving = false))
        // The row goes on to rest on its card, as it did.
        assertFalse(catchesScroll(velvet, moving = true))
        assertFalse(catchesScroll(velvet, moving = false))
    }

    @Test fun aThrownGridComesToRestWithoutASubPixelTail() {
        // How long, in ms, a throw of 300 px at 2000 px per second still moves once it is within 1 px of its rest.
        fun tail(spec: AnimationSpec<Float>): Long {
            val thrown = TargetBasedAnimation(spec, Float.VectorConverter, 0f, 300f, 2000f)
            val end = thrown.durationNanos
            var within = end
            while (within > 0 && abs(thrown.getValueFromNanos(within - 1_000_000) - 300f) <= 1f) within -= 1_000_000
            return (end - within) / 1_000_000
        }
        assertTrue(tail(throwSpring(grid2)) < 100)
        // The default spring's tail kept the grid moving, and a tap was only a catch, for about 0.3 s.
        assertTrue(tail(spring(dampingRatio = 1f, stiffness = 320f)) > 200)
        // The row's spring is the one it had.
        assertEquals(spring<Float>(dampingRatio = 1f, stiffness = 320f), throwSpring(velvet))
    }

    @Test fun aScreenReaderStepsTheRowByOnePlaceAsBefore() {
        for (selected in listOf(0, 4, 8)) for (scroll in listOf(0f, 900f, -1800f)) for (forward in listOf(true, false)) {
            assertEquals(nextPlace(centredTab(9, selected, 1f, scroll, velvet), forward, 9), readerStep(scroll, forward, 9, selected, 1f, velvet))
        }
    }

    @Test fun aScreenReaderStepsTheGridByOneRow() {
        // Rows start at places 0, 2, 4 and 6; a step goes to the next row's scroll, which centres it.
        assertEquals(2, readerStep(0f, true, 8, 0, 1f, grid2))
        assertNull(readerStep(0f, false, 8, 0, 1f, grid2))
        assertEquals(4, readerStep(cardScroll(2, 0, grid2), true, 8, 0, 1f, grid2))
        assertEquals(0, readerStep(cardScroll(2, 0, grid2), false, 8, 0, 1f, grid2))
        assertNull(readerStep(cardScroll(6, 0, grid2), true, 8, 0, 1f, grid2))
        // From between two rows (after a fling), the next row that way.
        val between = (cardScroll(2, 0, grid2) + cardScroll(4, 0, grid2)) / 2
        assertEquals(4, readerStep(between, true, 8, 0, 1f, grid2))
        assertEquals(2, readerStep(between, false, 8, 0, 1f, grid2))
    }

    @Test fun aScreenReaderStepPassesRowsThatShareTheScroll() {
        // Four small columns on a tall pane area: the first two rows both rest at the top of the content.
        val four = grid2.copy(grid = grid2.grid!!.copy(columns = 4, places = 16))
        assertEquals(0f, cardScroll(4, 0, four), near)
        assertEquals(8, readerStep(0f, true, 16, 0, 1f, four))
        val back = readerStep(cardScroll(8, 0, four), false, 16, 0, 1f, four)!!
        assertEquals(0f, cardScroll(back, 0, four), near)
        assertNull(readerStep(cardScroll(12, 0, four), true, 16, 0, 1f, four))
    }

    @Test fun aGridCardsNameGoesOutOfViewWithItUpAndDown() {
        val end = scrollBounds(8, 0, grid2).endInclusive
        // Scrolled to the end, the first card and its name are above the pane area; the last row's are below it at the start.
        assertNull(labelSpan(cardBox(0, 0, 1f, end, grid2), grid2))
        assertNull(labelSpan(cardBox(6, 0, 1f, 0f, grid2), grid2))
        // A card just above the top still has its name in view.
        val card = cardBox(0, 0, 1f, 0f, grid2)
        val scroll = card.bottom + 10f
        val span = labelSpan(cardBox(0, 0, 1f, scroll, grid2), grid2)
        assertEquals(card.left..card.right, span)
    }
}
