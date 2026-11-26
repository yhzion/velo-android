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

    @Test fun laidOutByItsFaceTheTileTakesOnlyTheStepsItDraws() {
        // The caption's 18 tile in its 26 box: the face at the box's lower left, 8 above it.
        val one = tileFaceBounds(1, 18f)
        assertEquals(0f, one.left, 0f)
        assertEquals(8f, one.top, 0f)
        assertEquals(18f, one.right, 0f)
        assertEquals(26f, one.bottom, 0f)
        // One step of 2.5 to the right for each layer behind the face, none past four panes.
        assertEquals(20.5f, tileFaceBounds(2, 18f).right, 0.001f)
        assertEquals(25.5f, tileFaceBounds(4, 18f).right, 0.001f)
        assertEquals(25.5f, tileFaceBounds(9, 18f).right, 0.001f)
        for (size in listOf(16f, 18f, 30f, 44f)) for (panes in 1..6) {
            val bounds = tileFaceBounds(panes, size)
            // As high as the face, and as wide as the face or the farthest layer reaches.
            assertEquals(size, bounds.height, 0.001f)
            val farthest = tileBackLayers(panes, size).firstOrNull()?.let { tileLayerRect(it, size).right } ?: size
            assertEquals("$size $panes", farthest, bounds.right, 0.001f)
        }
    }

    @Test fun aRowThatCentresTheFaceHoldsEveryLayer() {
        // The caption's 18: the box's 8 above the face, and 8 below it too.
        assertEquals(34f, tileCentredBox(18f), 0f)
        for (size in listOf(16f, 18f, 30f, 44f)) {
            val farthest = tileLayerRect(tileBackLayers(4, size).first(), size)
            // The face centred, the farthest layer's top stays inside.
            val faceTop = (tileCentredBox(size) - size) / 2
            assertTrue("$size", faceTop - (tileBox(size) - size - farthest.top) >= -0.001f)
        }
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

    @Test fun theLayersStandWhereTheBoardDrawsThem() {
        // Sidebar board, a 30 tile in its 41 box: layer 2 at 7.05, 7.55, 26.4 across; layer 4 at 21.2, 0.65, 19.2.
        val layers = tileBackLayers(4, 30f).associateBy { it.number }
        val two = tileLayerRect(layers.getValue(2), 30f)
        assertEquals(7.05f, two.left, 0.01f)
        assertEquals(7.55f, two.top, 0.01f)
        assertEquals(26.4f, two.width, 0.01f)
        assertEquals(26.4f, two.height, 0.01f)
        val four = tileLayerRect(layers.getValue(4), 30f)
        assertEquals(21.15f, four.left, 0.01f)
        assertEquals(0.65f, four.top, 0.01f)
        assertEquals(19.2f, four.width, 0.01f)
        // Every layer keeps its top right corner where it is moved to: the face's, one step further each.
        for (layer in layers.values) {
            val r = tileLayerRect(layer, 30f)
            assertEquals(30f + layer.dx, r.right, 0.001f)
            assertEquals(tileBox(30f) - 30f + layer.dy, r.top, 0.001f)
        }
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
            "omp" to TileBrand.Omp, "oh-my-pi" to TileBrand.Omp, "pi" to TileBrand.Pi, "pi-coding-agent" to TileBrand.Pi,
            // A login shell, as the process list names it.
            "-zsh" to TileBrand.Zsh,
            "bash" to TileBrand.Terminal, "vim" to TileBrand.Terminal, "" to TileBrand.Terminal, null to TileBrand.Terminal,
        )
        for ((program, brand) in expected) assertEquals("$program", brand, tileBrand(program))
    }

    @Test fun onlyTheAgentsFillTheirTile() {
        assertEquals(setOf(TileBrand.Claude, TileBrand.Codex, TileBrand.Omp, TileBrand.Pi), TileBrand.entries.filter { it.filled }.toSet())
        // The tools draw a Simple Icons logo; the letter tiles and the default draw text.
        val logos = TileBrand.entries.filter { it.logo != null }.toSet()
        assertEquals(setOf(TileBrand.Claude, TileBrand.Omp, TileBrand.Gemini, TileBrand.Neovim, TileBrand.Docker,
            TileBrand.Zsh, TileBrand.Htop, TileBrand.Python, TileBrand.Git), logos)
        // Codex is a black C in Roboto Bold, as the Tile board sets it (owner decision 5).
        assertEquals("C", TileBrand.Codex.letter)
        for (brand in TileBrand.entries) assertTrue("$brand", (brand.logo != null) != (brand.letter != null))
    }
}
