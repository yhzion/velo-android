package io.github.yhzion.velo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Every colour of the app; the corner radii, sizes and text styles are in Metrics.kt. The values
// are read from images of Rex for iOS; docs/design-tokens.md gives, for each token, the image and
// the pixel it was read from. The id after a value (D12, L06, MB2, …) names the image in that
// file. A value no image shows is marked "derived" with what it is derived from. A value the
// redesign canvas sets names its board (Tokens, Controls, Overlays); the redesign keeps the dark
// values read from the images and adds the sheet's group and the raised card.

/**
 * The colours of one kind of surface and of what is drawn on it. An appearance has five
 * ([VeloColors]); a screen area reads the group of the surface it sits on, never a colour of
 * another group.
 */
@Immutable
class SurfaceColors(
    /** The fill of the area itself. */
    val surface: Color,
    /** Body text and the icons that go with it. */
    val text: Color,
    /** Captions, section headers, placeholders and icons that are not the subject of their row. */
    val secondary: Color,
    /** Text and icons of something that takes no tap. Not held to a contrast ratio. */
    val disabled: Color,
    /** The fill of a glass control (a disc, a capsule, a field) on this surface. */
    val glass: Color,
    /** The light edge of a glass control: one line along its rim. */
    val glassEdge: Color,
    /** The glyph or label inside a glass control. */
    val onGlass: Color,
    /** The cell of a glass capsule that is selected or switched on. */
    val glassSelected: Color,
    /** The pill behind the selected row. */
    val selected: Color,
    /** A hairline between rows or groups. */
    val divider: Color,
    /** "This one is active": a switched-on toggle, an armed key, the working mark, a link. */
    val accent: Color,
    /** Text and glyphs on an [accent] fill. */
    val onAccent: Color,
    /** Connected, running, finished. */
    val ok: Color,
    /** Connecting, starting, waiting for the user. */
    val warning: Color,
    /** Failed or not connected; a destructive action, as a mark, an outline or a fill. */
    val danger: Color,
    /** A destructive action and an error, as text: an outlined button's label, a field's error line, a menu item. */
    val dangerText: Color,
    /** Text on a [danger] fill (a destructive confirm button). */
    val onDanger: Color,
)

/** The colours of one appearance. */
@Immutable
class VeloColors(
    val dark: Boolean,
    /** What shows between and around the panes, and behind the tab overview. */
    val backdrop: SurfaceColors,
    /** The sidebar and its search field. Near-black in both appearances, as on the Duo. */
    val sidebar: SurfaceColors,
    /** Chrome beside the panes: the pane header, the key bar, the rail, the full screens. */
    val chrome: SurfaceColors,
    /** Surfaces that open over the panes: menus, dialogs, popovers, the command palette. */
    val overlay: SurfaceColors,
    /** Settings: chrome's fill with the sidebar's controls (redesign, Tokens board). */
    val sheet: SurfaceColors,
    /** A card that stands out of the sheet or the start screen (redesign: "raised"). Its content keeps the colours of the surface around it. */
    val raised: Color,
    /** A paragraph inside a card or a dialog: between text and secondary (redesign: "textOnRaised"). */
    val textOnRaised: Color,
    /**
     * What is behind an overlay, as it looks under the scrim, where the overlay is exactly its
     * [SurfaceColors.surface]: the ground the surface colour was measured over.
     */
    val overlayAnchor: Color,
    /** How much of the lightness of what is behind an overlay shows in it: 0 none, 1 all of it. */
    val overlayLight: Float,
    /** How much of the colour of what is behind an overlay shows in it. */
    val overlayTint: Float,
    /** Laid over everything behind an open overlay. */
    val scrim: Color,
    /** The edge of the pane that takes the keys. Appearance → Active pane can replace its colour, not its strength ([VeloTheme.paneFocus]). */
    val paneFocus: Color,
) {
    /**
     * The material of an overlay as a colour matrix: twelve numbers, for red, green and blue each
     * the weights of the red, green and blue behind the overlay (0 to 1) and a constant. It is
     * applied to the blurred picture of what is behind the overlay, and the result is the
     * overlay's fill: its surface colour, moved by [overlayLight] of the lightness and
     * [overlayTint] of the colour of what is behind it under the scrim.
     */
    fun overlayMaterial(): FloatArray {
        val luma = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val dimmed = 1f - scrim.alpha
        val anchor = overlayAnchor.red * luma[0] + overlayAnchor.green * luma[1] + overlayAnchor.blue * luma[2]
        val surface = floatArrayOf(overlay.surface.red, overlay.surface.green, overlay.surface.blue)
        return FloatArray(12) { i ->
            val channel = i / 4
            val from = i % 4
            if (from == 3) surface[channel] - overlayLight * anchor
            else dimmed * ((overlayLight - overlayTint) * luma[from] + if (from == channel) overlayTint else 0f)
        }
    }

    /** The colour an overlay shows where its blur holds [behind]: [overlayMaterial] applied to it. */
    fun overlayOver(behind: Color): Color {
        val m = overlayMaterial()
        fun channel(c: Int) = (m[c * 4] * behind.red + m[c * 4 + 1] * behind.green + m[c * 4 + 2] * behind.blue + m[c * 4 + 3]).coerceIn(0f, 1f)
        return Color(channel(0), channel(1), channel(2))
    }
}

object VeloPalettes {
    // States, on a dark surface. No image shows a state colour: these are the iOS system green,
    // orange and red of the dark appearance, which the app uses today.
    private val OkOnDark = Color(0xFF30D158)
    private val WarningOnDark = Color(0xFFFF9F0A)
    private val DangerOnDark = Color(0xFFFF453A)
    /** Destructive text on a dark surface. Redesign: the Controls board's outlined button and field error. */
    private val DangerTextOnDark = Color(0xFFFF6B61)

    /** The overview backdrop and the gutters between panes: the same in both appearances. */
    private val Backdrop = SurfaceColors(
        surface = Color(0xFF000000), // L09 overview backdrop; L20 gutters
        text = Color(0xFFFFFFFF), // L09 status clock in the overview
        secondary = Color(0xFF8C8F94), // derived: the sidebar's secondary (MB2); the overview shows no secondary text
        disabled = Color(0xFF5F6064), // derived: the sidebar's
        glass = Color(0xFF777777), // L09 rail discs in the overview
        glassEdge = Color(0xFF7A7A7A), // L09 rail discs in the overview, their first rows
        onGlass = Color(0xFF2E2E2E), // L09 glyph of a rail disc in the overview
        glassSelected = Color(0xFF9A9A9A), // derived: the disc a step lighter, the step of chrome's selected cell
        selected = Color(0xFF131313), // derived: the pill of the sidebar on black (L06)
        divider = Color(0xFF2B2C30), // derived: the sidebar's
        accent = Color(0xFF63A2F6), // MB2 activity dot
        onAccent = Color(0xFF000000), // derived: the surface under the accent
        ok = OkOnDark, // derived: the iOS system green of the dark appearance
        warning = WarningOnDark, // derived: the iOS system orange of the dark appearance
        danger = DangerOnDark, // derived: the iOS system red of the dark appearance
        dangerText = DangerTextOnDark, // derived: the sheet's
        onDanger = Color(0xFF000000), // derived: as onAccent
    )

    val dark = VeloColors(
        dark = true,
        backdrop = Backdrop, // derived: the redesign with gutters and overview is shown in Light only (L09, L20)
        sidebar = SurfaceColors(
            surface = Color(0xFF0B0C11), // D12
            text = Color(0xFFF3F4F9), // MB2 row title (D12 reads #E8ECF0 through thin video strokes)
            secondary = Color(0xFF8C8F94), // MB2 group header
            disabled = Color(0xFF5F6064), // MB2 row age
            glass = Color(0xFF24262B), // D12 "Search" capsule, eye and gear capsule
            glassEdge = Color(0xFF383C42), // D12 eye and gear capsule top rim
            onGlass = Color(0xFFF3F4F9), // derived: text (D12 eye glyph reads #E1E6EC)
            glassSelected = Color(0xFF3A3D44), // derived: chrome's selected cell (D12 tab capsule)
            selected = Color(0xFF191A1F), // D12 selected row pill
            divider = Color(0xFF2B2C30), // derived: the list card of MB2, the next step above the surface
            accent = Color(0xFF63A2F6), // MB2 activity dot (D12 reads #5C9EF3)
            onAccent = Color(0xFF0B0C11), // derived: the surface
            ok = OkOnDark, // derived: the iOS system green of the dark appearance
            warning = WarningOnDark, // derived: the iOS system orange of the dark appearance
            danger = DangerOnDark, // derived: the iOS system red of the dark appearance
            dangerText = DangerTextOnDark, // derived: the sheet's
            onDanger = Color(0xFF0B0C11), // derived: the surface
        ),
        chrome = SurfaceColors(
            surface = Color(0xFF0F1015), // D12 pane, key bar and rail
            text = Color(0xFFF5F8FE), // PK pane header title
            secondary = Color(0xFF8C8F94), // derived: MB2 group header; PK's subtitle #85888D is 4.15 on glass
            disabled = Color(0xFF5F6064), // derived: the sidebar's
            glass = Color(0xFF25282F), // D12 command disc, keyboard capsule, tab capsule
            glassEdge = Color(0xFF424348), // D12 command disc top rim
            onGlass = Color(0xFFF5F8FE), // derived: text (PK key bar glyph reads #F0F3F8)
            glassSelected = Color(0xFF3A3D44), // D12 tab capsule, selected cell
            selected = Color(0xFF1D1E23), // derived: the sidebar's pill step (#0B0C11 to #191A1F) on this surface
            divider = Color(0xFF2B2C30), // derived: the sidebar's
            accent = Color(0xFF7FA6F5), // CM layout thumbnail, selected cell
            onAccent = Color(0xFF0B0C11), // derived: the darkest surface
            ok = OkOnDark, // derived: the iOS system green of the dark appearance
            warning = WarningOnDark, // derived: the iOS system orange of the dark appearance
            danger = DangerOnDark, // derived: the iOS system red of the dark appearance
            dangerText = DangerTextOnDark, // derived: the sheet's, on the same fill
            onDanger = Color(0xFF0B0C11), // derived
        ),
        overlay = SurfaceColors(
            surface = Color(0xFF2E323E), // AK sheet
            text = Color(0xFFFCFEFF), // AK row text
            secondary = Color(0xFFC2C3C9), // derived: AK row chevron #A8AAB1, lightened to 4.5 over the lightest the material gets
            disabled = Color(0xFF8B8E98), // derived: halfway from the surface to secondary
            glass = Color(0xFF393D49), // AK field and grouped card
            glassEdge = Color(0xFF727588), // AK close disc top rim. The Overlays board rims a field with glassSelected, #4A4D5C: 1.248 over a bright terminal
            onGlass = Color(0xFFFCFEFF), // derived: text
            glassSelected = Color(0xFF4A4D5C), // AK close disc, the lighter control on the sheet
            selected = Color(0xFF4A4E59), // derived: lighter than the material over any backdrop
            divider = Color(0xFF444854), // AK hairline between rows
            accent = Color(0xFF7FA6F5), // CM, as chrome
            onAccent = Color(0xFF0B0C11), // derived
            ok = OkOnDark, // derived: the iOS system green of the dark appearance
            warning = WarningOnDark, // derived: the iOS system orange of the dark appearance
            danger = Color(0xFFFF9994), // derived: the dark red lightened to 4.5 as text over the lightest the material gets (Tokens board "dangerOnOverlay"); #FF453A is 2.98 as a mark over a bright terminal
            dangerText = Color(0xFFFF9994), // Tokens board "dangerOnOverlay": the Overlays board's destructive menu item
            onDanger = Color(0xFF0B0C11), // derived
        ),
        sheet = SurfaceColors(
            surface = Color(0xFF0F1015), // Tokens board "chrome": the settings' fill, as chrome's
            text = Color(0xFFF3F4F9), // Tokens board "text", as the sidebar's
            secondary = Color(0xFF8C8F94), // Tokens board "secondary"
            disabled = Color(0xFF5F6064), // Tokens board "disabled"
            glass = Color(0xFF24262B), // Tokens board "glass": buttons, fields, the segmented track
            glassEdge = Color(0xFF383C42), // Tokens board "glassEdge"
            onGlass = Color(0xFFF3F4F9), // Controls board: the label of a secondary button and of the selected cell
            glassSelected = Color(0xFF3A3D44), // Tokens board "glassSelected": the selected cell, a switch that is off
            selected = Color(0xFF1D1E23), // Tokens board "selected": the selected row, a disabled button
            divider = Color(0xFF2B2C30), // Tokens board "divider": group borders and hairlines
            accent = Color(0xFF63A2F6), // Tokens board "accent": links, a switch that is on, the primary button
            onAccent = Color(0xFF0B0C11), // Tokens board "onAccent"
            ok = OkOnDark, // Tokens board "ok"
            warning = WarningOnDark, // Tokens board "warning"
            danger = DangerOnDark, // Tokens board "danger": a field's error ring, an outlined button's rim at 50 %, a destructive dialog's confirm
            dangerText = DangerTextOnDark, // Controls board: the outlined destructive button and a field's error line
            onDanger = Color(0xFF0B0C11), // derived: the Overlays board writes white on the red, 3.4; dark text reaches 5.7
        ),
        raised = Color(0xFF15171D), // Tokens board "raised", the one value the redesign adds
        textOnRaised = Color(0xFFC2C3C9), // Tokens board "textOnRaised", the overlay's secondary
        overlayAnchor = Color(0xFF15161B), // AK the screen above the sheet
        overlayLight = 0.10f, // derived: no image shows an overlay over a bright picture; the most, with overlayTint, at which every text keeps 4.5 over any colour
        overlayTint = 0.45f, // derived: as overlayLight
        scrim = Color.Black.copy(alpha = 0.45f), // Tokens and Overlays boards: black at 45 % (AK against PK read 40 %)
        paneFocus = Color(0xB37FA6F5), // derived: chrome's accent at 70 %, the strength the app uses today
    )
}

/** Measured light tokens for dev/tokens/table.py and contrast tests; never loaded by the app. */
internal object ReferencePalettes {
    private val Backdrop = VeloPalettes.dark.backdrop
    private val OkOnDark = Backdrop.ok
    private val WarningOnDark = Backdrop.warning
    private val DangerOnDark = Backdrop.danger

    private val LightChrome = SurfaceColors(
        surface = Color(0xFFF9F9F9), // L06 pane card, L20 key bar strip
        text = Color(0xFF151616), // M01 pane header title
        secondary = Color(0xFF626164), // L01 pane header subtitle
        disabled = Color(0xFFA7A7A7), // derived: the glass rim, the lightest grey the Duo draws on this surface
        glass = Color(0xFFF5F5F5), // L06 rail discs, L01 tab capsule
        glassEdge = Color(0xFFA7A7A7), // M01 back disc rim
        onGlass = Color(0xFF2D2D2F), // L06 rail "…" dots
        glassSelected = Color(0xFFD4D2D3), // L01 tab capsule, selected cell
        selected = Color(0xFFE5E5E5), // derived: halfway from glass to its selected cell
        divider = Color(0xFFC7C7CA), // L06 layout thumbnail, the cell that is not selected
        accent = Color(0xFF336FB1), // L06 layout thumbnail, selected cell
        onAccent = Color(0xFFFFFFFF), // derived
        ok = Color(0xFF248A3D), // derived: the iOS system green of the light appearance, darker variant
        warning = Color(0xFFC93400), // derived: the iOS system orange, darker variant
        danger = Color(0xFFD70015), // derived: the iOS system red, darker variant
        dangerText = Color(0xFFD70015), // derived: as danger
        onDanger = Color(0xFFFFFFFF), // derived
    )

    val light = VeloColors(
        dark = false,
        backdrop = Backdrop,
        sidebar = SurfaceColors(
            surface = Color(0xFF000000), // L06
            text = Color(0xFFD5DDE4), // L06 row title, host label
            secondary = Color(0xFF8C8F94), // derived: Dark's (MB2); L06's group header #808088 is 4.27 on glass
            disabled = Color(0xFF5F6064), // derived: Dark's
            glass = Color(0xFF1D1D1D), // L06 "Search" capsule, eye and gear capsule
            glassEdge = Color(0xFF434343), // L06 "Search" capsule top rim
            onGlass = Color(0xFFD5DDE4), // derived: text (L06 eye glyph reads #D0D8DD)
            glassSelected = Color(0xFF383838), // derived: Dark's step from glass to its selected cell
            selected = Color(0xFF131313), // L06 selected row pill
            divider = Color(0xFF2B2C30), // derived: Dark's
            accent = Color(0xFF72ACFA), // L06 dot on the eye
            onAccent = Color(0xFF000000), // derived: the surface
            ok = OkOnDark, // derived: the iOS system green of the dark appearance
            warning = WarningOnDark, // derived: the iOS system orange of the dark appearance
            danger = DangerOnDark, // derived: the iOS system red of the dark appearance
            dangerText = DangerOnDark, // derived: as danger
            onDanger = Color(0xFF000000), // derived
        ),
        chrome = LightChrome,
        overlay = SurfaceColors(
            surface = Color(0xFFECEDF1), // MB1 sheet
            text = Color(0xFF18191E), // MB1 body text
            secondary = Color(0xFF555556), // derived: M03 link line #727271, darkened to 4.5 over the darkest the material gets
            disabled = Color(0xFF9A9BA0), // derived: halfway from the surface to secondary
            glass = Color(0xFFF2F1F6), // MB1 inner card
            glassEdge = Color(0xFFAEAFB3), // MB1 back disc rim
            onGlass = Color(0xFF191A1F), // MB1 back chevron
            glassSelected = Color(0xFFC2C3C8), // derived: chrome's step from glass to its selected cell
            selected = Color(0xFFCACBCE), // derived: darker than the material over any backdrop
            divider = Color(0xFFC6C6C8), // derived: chrome's divider on this surface
            accent = Color(0xFF336FB1), // L06, as chrome
            onAccent = Color(0xFFFFFFFF), // derived
            ok = Color(0xFF1E7B34), // derived: chrome's green darkened to 3 on a selected row
            warning = Color(0xFFC93400), // derived: chrome's
            danger = Color(0xFFB80012), // derived: chrome's red darkened to 4.5 as text over the darkest the material gets
            dangerText = Color(0xFFB80012), // derived: as danger
            onDanger = Color(0xFFFFFFFF), // derived
        ),
        sheet = LightChrome, // derived: the redesign draws no light settings
        raised = Color(0xFFFFFFFF), // derived: a step lighter than chrome
        textOnRaised = Color(0xFF3C3C40), // derived: between chrome's text and secondary
        overlayAnchor = Color(0xFFBDBEC2), // MB1 backdrop behind the sheet
        overlayLight = 0.10f, // derived: Dark's; here too the most at which every text keeps 4.5 over any colour
        overlayTint = 0.45f, // derived: as overlayLight
        scrim = Color(0x3B000000), // M03 against M01: the pane behind the menu is dimmed by black at 23 %
        paneFocus = Color(0xB363A2F6), // derived: the backdrop's accent at 70 %, as Dark's strength; chrome's #336FB1 reads 2.46 on black
    )
}

/**
 * The tint of each settings category's icon tile, under a white line icon (redesign, Controls
 * board, "분류 아이콘"). The same in both appearances.
 */
object CategoryTints {
    val appearance = Color(0xFF5B4FC4)
    val keyboard = Color(0xFF3E4A5E)
    val notifications = Color(0xFF9A3B62)
    val remotes = Color(0xFF2F6B57)
    val about = Color(0xFF2F5E9E)
}
