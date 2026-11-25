package io.github.yhzion.velo.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the tab tile, against the redesign's Tile board (Tile.dc.html, TabTiles.dc.html). */
class TabTileTest {
    @Test fun aTabDrawsOneLayerPerPaneUpToFour() {
        assertEquals(listOf(1, 1, 2, 3, 4, 4, 4), listOf(0, 1, 2, 3, 4, 5, 9).map(::tileLayers))
    }

    @Test fun thePaneCountShowsFromFivePanes() {
        assertFalse(tilePaneCountShown(1))
        assertFalse(tilePaneCountShown(4))
        assertTrue(tilePaneCountShown(5))
        assertTrue(tilePaneCountShown(12))
    }

    @Test fun theBoxHoldsTheFaceAndThreeSteps() {
        // The board's hint sizes: a 30 tile in a 41 box, 36 in 49, 44 in 60.
        assertEquals(41f, tileBox(30f), 0f)
        assertEquals(49f, tileBox(36f), 0f)
        assertEquals(60f, tileBox(44f), 0f)
        // A small tile still steps 2.5.
        assertEquals(2.5f, tileStep(16f), 0f)
    }

    @Test fun layersBehindStepUpRightSmallerAndDarker() {
        assertEquals(emptyList<TileLayer>(), tileBackLayers(1, 30f))
        val layers = tileBackLayers(6, 30f)
        // Back to front, numbered as the board names them: layer 4 is the farthest.
        assertEquals(listOf(4, 3, 2), layers.map { it.number })
        val far = layers.first()
        assertEquals(3 * 3.45f, far.dx, 0.001f)
        assertEquals(-3 * 3.45f, far.dy, 0.001f)
        assertEquals(1 - 3 * 0.12f, far.scale, 0.001f)
        assertEquals(1 - 3 * 0.2f, far.alpha, 0.001f)
        assertEquals(0.6f - 3 * 0.08f, far.brightness, 0.001f)
        val near = layers.last()
        assertEquals(3.45f, near.dx, 0.001f)
        assertEquals(0.88f, near.scale, 0.001f)
        assertEquals(0.8f, near.alpha, 0.001f)
        assertEquals(0.52f, near.brightness, 0.001f)
    }

    @Test fun everyLayerStaysInsideTheBox() {
        for (size in listOf(16f, 24f, 30f, 36f, 44f, 64f)) for (layer in tileBackLayers(4, size)) {
            val extra = tileBox(size) - size
            // A layer keeps its top right corner where it is moved to; it shrinks towards it.
            assertTrue("$size ${layer.number} right", size + layer.dx <= tileBox(size) + 0.001f)
            assertTrue("$size ${layer.number} top", extra + layer.dy >= -0.001f)
        }
    }

    @Test fun theStatusDotAndThePaneCountNeverOverlap() {
        // Owner decision 2: the dot keeps the upper right, the count sits at the front tile's lower right.
        for (size in listOf(20f, 24f, 30f, 36f, 44f, 64f)) for (panes in listOf(5, 12, 99)) {
            val dot = tileDotRect(size)
            val chip = tileCountRect(size, panes.toString().length)
            assertFalse("$size $panes", dot.overlaps(chip))
            // The dot is on the face's upper right corner, the count on its lower right.
            val extra = tileBox(size) - size
            assertTrue(dot.left < size && dot.right > size && dot.top < extra && dot.bottom > extra)
            assertTrue(chip.right > size && chip.bottom > tileBox(size) && chip.top < tileBox(size))
        }
    }

    @Test fun theDotAndCountSitWhereTheBoardPutsThem() {
        val dot = tileDotRect(30f)
        assertEquals(24f, dot.left, 0f)
        assertEquals(8f, dot.top, 0f)
        assertEquals(9f, dot.width, 0f)
        val chip = tileCountRect(30f, 1)
        assertEquals(34f, chip.right, 0f)
        assertEquals(45f, chip.bottom, 0f)
        assertEquals(16f, chip.height, 0f)
        assertEquals(16f, chip.width, 0f)
    }

    @Test fun theFaceIsDrawnInProportionToItsSize() {
        assertEquals(8f, tileCorner(30f), 0f)
        assertEquals(10f, tileCorner(36f), 0f)
        assertEquals(18f, tileGlyph(30f), 0f)
        assertEquals(17f, tileLetter(30f, mono = false), 0f)
        assertEquals(12f, tileLetter(30f, mono = true), 0f)
    }

    @Test fun theLabelSaysTheProgramAndThePanes() {
        assertEquals("Claude Code", tileLabel(TileBrand.Claude, 1))
        assertEquals("Codex, 2 panes", tileLabel(TileBrand.Codex, 2))
        assertEquals("Terminal, 6 panes", tileLabel(TileBrand.Terminal, 6))
    }

    @Test fun programsGetTheirBrand() {
        val expected = mapOf(
            "claude" to TileBrand.Claude, "claude-code" to TileBrand.Claude, "Claude" to TileBrand.Claude,
            "codex" to TileBrand.Codex, "openai-codex" to TileBrand.Codex,
            "gemini" to TileBrand.Gemini, "nvim" to TileBrand.Neovim, "neovim" to TileBrand.Neovim,
            "docker" to TileBrand.Docker, "zsh" to TileBrand.Zsh, "htop" to TileBrand.Htop,
            "python" to TileBrand.Python, "python3" to TileBrand.Python, "git" to TileBrand.Git,
            "az" to TileBrand.Azure, "heroku" to TileBrand.Heroku,
            "bash" to TileBrand.Terminal, "vim" to TileBrand.Terminal, "" to TileBrand.Terminal, null to TileBrand.Terminal,
        )
        for ((program, brand) in expected) assertEquals("$program", brand, tileBrand(program))
    }

    @Test fun onlyTheAgentsFillTheirTile() {
        assertEquals(setOf(TileBrand.Claude, TileBrand.Codex), TileBrand.entries.filter { it.filled }.toSet())
        // The tools draw a Simple Icons logo; the letter tiles and the default draw text.
        val logos = TileBrand.entries.filter { it.logo != null }.toSet()
        assertEquals(setOf(TileBrand.Claude, TileBrand.Codex, TileBrand.Gemini, TileBrand.Neovim, TileBrand.Docker,
            TileBrand.Zsh, TileBrand.Htop, TileBrand.Python, TileBrand.Git), logos)
        for (brand in TileBrand.entries) assertTrue("$brand", (brand.logo != null) != (brand.letter != null))
    }
}
