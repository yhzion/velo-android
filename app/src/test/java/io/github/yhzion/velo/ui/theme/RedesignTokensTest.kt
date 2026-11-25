package io.github.yhzion.velo.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokens against the redesign's Tokens board (`redesign/tokens.json`, copied from the board).
 * The board gives every size at the Medium size, so sizes are compared at [UiSize.Medium].
 */
class RedesignTokensTest {
    private val board: JsonObject = Json.parseToJsonElement(
        RedesignTokensTest::class.java.classLoader!!.getResource("redesign/tokens.json")!!.readText(),
    ).jsonObject

    private val colors = VeloTheme.colors
    private val type = veloType(UiSize.Medium)
    private val radii = veloRadii(UiSize.Medium)
    private val sizes = veloSizes(UiSize.Medium)
    private val space = veloSpace(UiSize.Medium)

    /** A board colour: `#RRGGBB` or `rgba(r,g,b,a)`. */
    private fun parse(value: String): Color {
        if (value.startsWith("#")) return Color(0xFF000000 or value.substring(1).toLong(16))
        val parts = value.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim() }
        return Color(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), (parts[3].toFloat() * 255).let { Math.round(it) })
    }

    private fun boardColor(name: String) = parse(board.getValue("colors").jsonObject.getValue(name).jsonPrimitive.content)

    /** Two colours that are the same to a channel's 1/255 (an alpha of .45 is no whole step). */
    private fun assertSame(what: String, expected: Color, actual: Color) {
        val apart = listOf(expected.red - actual.red, expected.green - actual.green, expected.blue - actual.blue, expected.alpha - actual.alpha)
            .maxOf { kotlin.math.abs(it) }
        assertTrue("$what: board $expected, app $actual", apart <= 1f / 255f)
    }

    @Test fun everyColourOfTheBoardIsAToken() {
        val app: Map<String, List<Color>> = mapOf(
            "background" to listOf(colors.backdrop.surface),
            "sidebar" to listOf(colors.sidebar.surface),
            "chrome" to listOf(colors.chrome.surface, colors.sheet.surface),
            "raised" to listOf(colors.raised),
            "selected" to listOf(colors.sheet.selected, colors.chrome.selected),
            "divider" to listOf(colors.sheet.divider, colors.sidebar.divider, colors.chrome.divider),
            "glass" to listOf(colors.sheet.glass, colors.sidebar.glass),
            "glassEdge" to listOf(colors.sheet.glassEdge, colors.sidebar.glassEdge),
            "glassSelected" to listOf(colors.sheet.glassSelected, colors.sidebar.glassSelected, colors.chrome.glassSelected),
            "text" to listOf(colors.sheet.text, colors.sheet.onGlass, colors.sidebar.text),
            "secondary" to listOf(colors.sheet.secondary, colors.sidebar.secondary, colors.chrome.secondary),
            "disabled" to listOf(colors.sheet.disabled, colors.sidebar.disabled),
            "textOnRaised" to listOf(colors.textOnRaised, colors.overlay.secondary),
            "accent" to listOf(colors.sheet.accent, colors.sidebar.accent),
            "chromeAccent" to listOf(colors.chrome.accent, colors.overlay.accent),
            "paneFocus" to listOf(colors.paneFocus),
            "onAccent" to listOf(colors.sheet.onAccent, colors.chrome.onAccent, colors.overlay.onAccent),
            "ok" to listOf(colors.sheet.ok, colors.overlay.ok),
            "warning" to listOf(colors.sheet.warning, colors.overlay.warning),
            // A destructive dialog's confirm is filled with the sheet's red; the overlay's own red is lighter, to be read as a mark.
            "danger" to listOf(colors.sheet.danger, colors.sidebar.danger, colors.chrome.danger),
            "dangerOnOverlay" to listOf(colors.overlay.dangerText, colors.overlay.danger),
            "overlay" to listOf(colors.overlay.surface),
            "overlayGlass" to listOf(colors.overlay.glass),
            "overlayDivider" to listOf(colors.overlay.divider),
            "overlayText" to listOf(colors.overlay.text, colors.overlay.onGlass),
            "scrim" to listOf(colors.scrim),
        )
        val names = board.getValue("colors").jsonObject.keys
        assertEquals("every board colour is mapped", names, app.keys)
        for (name in names) for (value in app.getValue(name)) assertSame(name, boardColor(name), value)
    }

    @Test fun textStylesAreTheBoardsAtMedium() {
        val app: Map<String, TextStyle> = mapOf(
            "screen-title" to type.screenTitle,
            "page-title" to type.pageTitle,
            "dialog-title" to type.overlayTitle,
            "header-title" to type.title,
            "body" to type.body,
            "value" to type.value,
            "caption" to type.caption,
            "eyebrow" to type.eyebrow,
        )
        for ((name, spec) in board.getValue("type").jsonObject) {
            if (spec.jsonObject["mono"] != null) continue // the terminal's own Font size setting, not a style here
            val style = app[name] ?: error("no style for $name")
            assertEquals(name, spec.jsonObject.getValue("size").jsonPrimitive.float, style.fontSize.value, 0.01f)
            assertEquals(name, FontWeight(spec.jsonObject.getValue("weight").jsonPrimitive.int), style.fontWeight)
            spec.jsonObject["tracking"]?.jsonPrimitive?.floatOrNull?.let { assertEquals(name, it, style.letterSpacing.value, 0.001f) }
        }
        // A value and a cell of a segmented control are one size.
        assertEquals(type.value.fontSize.value, type.control.fontSize.value, 0.01f)
    }

    @Test fun radiiAreTheBoardsAtMedium() {
        val app: Map<String, Dp> = mapOf(
            "tile" to radii.tile, "badge" to radii.badge, "group" to radii.group, "card" to radii.card,
            "sheet" to radii.sheet, "pill" to radii.pill, "nav" to radii.nav, "dialog" to radii.overlay,
        )
        for ((name, value) in board.getValue("radii").jsonObject) {
            assertEquals(name, value.jsonPrimitive.float, app.getValue(name).value, 0.01f)
        }
        // The terminal pane is a card.
        assertEquals(radii.card.value, radii.pane.value, 0.01f)
    }

    @Test fun spacingIsTheBoardsAtMedium() {
        val spacing = board.getValue("spacing").jsonObject
        val steps = spacing.getValue("steps").jsonArray.map { it.jsonPrimitive.float }
        assertEquals(steps, listOf(space.s4, space.s8, space.s12, space.s16, space.s20, space.s24).map { Math.round(it.value * 100) / 100f })
        assertEquals(spacing.getValue("settingRow").jsonPrimitive.float, sizes.row.value, 0.01f)
        assertEquals(spacing.getValue("button").jsonPrimitive.float, sizes.control.value, 0.01f)
        assertEquals(spacing.getValue("touch").jsonPrimitive.float, MinTouch.value, 0.01f)
        assertEquals(spacing.getValue("gap").jsonPrimitive.float, sizes.gap.value, 0.01f)
    }

    @Test fun aMediumLengthIsTheSameAtMediumAndScalesWithTheOthers() {
        assertEquals(14f, mediumAt(UiSize.Medium, Dp(14f)).value, 0.001f)
        assertEquals(14f / 0.85f, mediumAt(UiSize.Large, Dp(14f)).value, 0.001f)
        assertEquals(14f * 0.72f / 0.85f, mediumAt(UiSize.Small, Dp(14f)).value, 0.001f)
    }

    @Test fun theSettingsCategoriesKeepTheirTints() {
        // Controls board, "분류 아이콘".
        assertEquals(Color(0xFF5B4FC4), CategoryTints.appearance)
        assertEquals(Color(0xFF3E4A5E), CategoryTints.keyboard)
        assertEquals(Color(0xFF9A3B62), CategoryTints.notifications)
        assertEquals(Color(0xFF2F6B57), CategoryTints.remotes)
        assertEquals(Color(0xFF2F5E9E), CategoryTints.about)
    }
}
