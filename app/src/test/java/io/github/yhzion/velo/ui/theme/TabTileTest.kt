package io.github.yhzion.velo.ui.theme

import io.github.yhzion.velo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun laidOutByItsFaceTheTileTakesOnlyItsFace() {
        // The caption's 18 tile in its 26 box: the face at the box's lower left, 8 above it.
        val face = tileFaceBounds(18f)
        assertEquals(0f, face.left, 0f)
        assertEquals(8f, face.top, 0f)
        assertEquals(18f, face.right, 0f)
        assertEquals(26f, face.bottom, 0f)
        // The layers stand above the face, never past its sides (#217).
        for (size in listOf(16f, 18f, 30f, 44f)) for (panes in 1..6) for (layer in tileBackLayers(panes, size)) {
            val r = tileLayerRect(layer, size)
            assertTrue("$size $panes ${layer.number}", r.left >= tileFaceBounds(size).left && r.right <= tileFaceBounds(size).right)
        }
    }

    @Test fun layersBehindStandAboveSmallerAndDarker() {
        assertEquals(emptyList<TileLayer>(), tileBackLayers(1, 30f))
        val layers = tileBackLayers(6, 30f)
        // Back to front, numbered as the board names them: layer 4 is the farthest.
        assertEquals(listOf(4, 3, 2), layers.map { it.number })
        val far = layers.first()
        assertEquals(-3 * 3.45f, far.dy, 0.001f)
        assertEquals(1 - 3 * 0.12f, far.scale, 0.001f)
        assertEquals(1 - 3 * 0.12f, far.alpha, 0.001f)
        assertEquals(0.85f - 3 * 0.1f, far.brightness, 0.001f)
        val near = layers.last()
        assertEquals(-3.45f, near.dy, 0.001f)
        assertEquals(0.88f, near.scale, 0.001f)
        assertEquals(0.88f, near.alpha, 0.001f)
        assertEquals(0.75f, near.brightness, 0.001f)
    }

    @Test fun theLayersStandStraightBehindAndAboveTheFace() {
        // A 30 tile in its 41 box, the face from 11 down: layer 2 is 26.4 across from 1.8, its top 3.45 above the face's;
        // layer 4 is 19.2 across from 5.4, its top 0.65 from the box's.
        val layers = tileBackLayers(4, 30f).associateBy { it.number }
        val two = tileLayerRect(layers.getValue(2), 30f)
        assertEquals(1.8f, two.left, 0.01f)
        assertEquals(7.55f, two.top, 0.01f)
        assertEquals(26.4f, two.width, 0.01f)
        assertEquals(26.4f, two.height, 0.01f)
        val four = tileLayerRect(layers.getValue(4), 30f)
        assertEquals(5.4f, four.left, 0.01f)
        assertEquals(0.65f, four.top, 0.01f)
        assertEquals(19.2f, four.width, 0.01f)
        for (layer in layers.values) {
            val r = tileLayerRect(layer, 30f)
            // Centred on the face, one step higher each, and its bottom hidden behind the face.
            assertEquals(15f, r.center.x, 0.001f)
            assertEquals(tileBox(30f) - 30f + layer.dy, r.top, 0.001f)
            assertTrue("${layer.number}", r.bottom < tileBox(30f))
        }
        // Each layer's top edge shows above the one in front of it.
        assertTrue(tileLayerRect(layers.getValue(4), 30f).top < tileLayerRect(layers.getValue(3), 30f).top)
        assertTrue(tileLayerRect(layers.getValue(3), 30f).top < tileLayerRect(layers.getValue(2), 30f).top)
        assertTrue(tileLayerRect(layers.getValue(2), 30f).top < tileBox(30f) - 30f)
    }

    @Test fun everyLayerStaysInsideTheBox() {
        for (size in listOf(16f, 24f, 30f, 36f, 44f, 64f)) for (layer in tileBackLayers(4, size)) {
            val r = tileLayerRect(layer, size)
            assertTrue("$size ${layer.number} top", r.top >= -0.001f)
            assertTrue("$size ${layer.number} right", r.right <= tileBox(size) + 0.001f)
        }
    }

    @Test fun upToFourPanesALayerStandsForEachOtherPaneInLayoutOrder() {
        val c = TileBrand.Claude
        val o = TileBrand.OpenCode
        val x = TileBrand.Codex
        val t = TileBrand.Terminal
        assertEquals(emptyList<TileBrand>(), tileBehind(t, emptyList(), 1))
        // 2 panes: bash in front, Claude Code behind it.
        assertEquals(listOf(c), tileBehind(t, listOf(c), 2))
        // 3 and 4: the first other pane nearest, whatever the face is.
        assertEquals(listOf(o, c), tileBehind(t, listOf(o, c), 3))
        assertEquals(listOf(c, x, o), tileBehind(t, listOf(c, x, o), 4))
        assertEquals(listOf(t, t, c), tileBehind(c, listOf(t, t, c), 4))
        // A pane whose program is not known draws the tool look, as do the panes the row knows nothing of.
        assertEquals(listOf(t, t), tileBehind(c, emptyList(), 3))
        assertEquals(listOf(o, t, t), tileBehind(c, listOf(o), 4))
    }

    @Test fun fromFivePanesALayerStandsForEachOtherKindTheMostPanesFirst() {
        val c = TileBrand.Claude
        val o = TileBrand.OpenCode
        val x = TileBrand.Codex
        val t = TileBrand.Terminal
        val z = TileBrand.Zsh
        // 8 panes, bash in front: 4 Claude Code, 2 OpenCode, 1 Codex behind it.
        assertEquals(listOf(c, o, x), tileBehind(t, listOf(o, c, x, c, o, c, c), 8))
        // The face's own kind is not a layer while there are other kinds; a tie goes in layout order.
        assertEquals(listOf(x, o, z), tileBehind(c, listOf(c, x, c, o, z, z, o, x), 9))
        assertEquals(listOf(x, o, z), tileBehind(c, listOf(x, o, z, c), 5))
        // More kinds than layers: the three with the most panes.
        assertEquals(listOf(z, c, o), tileBehind(t, listOf(x, z, z, z, c, c, o, o, TileBrand.Git), 10))
        // Fewer kinds than layers: the kind with the most panes fills the rest, beside its own layer.
        assertEquals(listOf(c, c, o), tileBehind(t, listOf(c, c, c, o), 5))
        // The face's kind fills when it has the most panes; a tie goes to the face's kind.
        assertEquals(listOf(c, t, t), tileBehind(t, listOf(t, t, t, c, t), 6))
        assertEquals(listOf(c, o, t), tileBehind(t, listOf(c, c, t, o), 5))
        // One kind in all: it fills every layer.
        assertEquals(listOf(c, c, c), tileBehind(c, listOf(c, c, c, c, c), 6))
        // Panes the row knows nothing of are the tool look.
        assertEquals(listOf(t, t, t), tileBehind(c, emptyList(), 5))
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
            "gemini" to TileBrand.Gemini, "opencode" to TileBrand.OpenCode, "OpenCode" to TileBrand.OpenCode,
            "nvim" to TileBrand.Neovim, "neovim" to TileBrand.Neovim,
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
        assertEquals(setOf(TileBrand.Claude, TileBrand.Omp, TileBrand.Pi, TileBrand.Gemini, TileBrand.OpenCode, TileBrand.Neovim,
            TileBrand.Docker, TileBrand.Zsh, TileBrand.Htop, TileBrand.Python, TileBrand.Git), logos)
        // OpenCode's brand colour is black, which the glass would hide: its mark is drawn in the text colour.
        assertNull(TileBrand.OpenCode.ink)
        // Codex is a black C in Roboto Bold, as the Tile board sets it (owner decision 5).
        assertEquals("C", TileBrand.Codex.letter)
        for (brand in TileBrand.entries) assertTrue("$brand", (brand.logo != null) != (brand.letter != null))
    }

    @Test fun ompAndPiDrawTheirProgramMarks() {
        assertEquals(R.drawable.ic_agent_omp, TileBrand.Omp.logo)
        assertNull(TileBrand.Omp.letter)
        assertEquals(R.drawable.ic_mark_pi, TileBrand.Pi.logo)
        assertNull(TileBrand.Pi.letter)
    }
}
