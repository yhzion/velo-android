package io.github.yhzion.velo.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import io.github.yhzion.velo.ui.theme.TileBrand

/** The node tagged [tag], in the tree as drawn: the names and the drum's titles leave their parts out of the merged one. */
internal fun SemanticsNodeInteractionsProvider.drawn(tag: String) = onNodeWithTag(tag, useUnmergedTree = true)

internal fun SemanticsNodeInteractionsProvider.bounds(tag: String): DpRect = drawn(tag).getBoundsInRoot()

/**
 * The [TabTile][io.github.yhzion.velo.ui.theme.TabTile] tagged [tag] draws [brand]: it is called [description], and
 * its face holds the brand's logo or its letter, and nothing else.
 */
internal fun SemanticsNodeInteractionsProvider.assertTileOf(tag: String, brand: TileBrand, description: String = brand.label) {
    drawn(tag).assert(hasContentDescription(description, substring = false))
    if (brand.logo != null) {
        drawn("$tag-logo").assertExists()
        drawn("$tag-letter").assertDoesNotExist()
    } else {
        drawn("$tag-letter").assertTextEquals(brand.letter!!)
        drawn("$tag-logo").assertDoesNotExist()
    }
}

/** How the text tagged [tag] is laid out. */
internal fun SemanticsNodeInteractionsProvider.textLayout(tag: String): TextLayoutResult {
    val layouts = mutableListOf<TextLayoutResult>()
    drawn(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
    return layouts.first()
}
