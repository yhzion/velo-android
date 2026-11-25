package io.github.yhzion.velo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The corner radii, sizes and text styles of the app, the same in both appearances. As in
// Tokens.kt, the id after a value names the image it was read from (docs/design-tokens.md).
// The redesign canvas gives its sizes at the Medium size: such a value is written as medium(n),
// which is the Large size that Medium scales to n. Its source names the board.

/** The Large length that the Medium size draws [value] dp long: what the redesign gives at Medium. */
private fun medium(value: Float): Dp = (value / UiSize.Medium.factor).dp

/** The Large text size that the Medium size draws [value] sp large. */
private fun mediumSp(value: Float) = (value / UiSize.Medium.factor).sp

/**
 * The line of a style [size] sp at Medium: Roboto's ascent and descent, 2400 of its 2048 units.
 * The boards set no line height on a line of text, so the browser gives it this one; a label
 * of the app is then as tall as the board's, and a paragraph sets its own (1.45 em, 1.5 em).
 */
private fun robotoLine(size: Float) = mediumSp(size * 2400f / 2048f)

/** [value], given at the Medium size, as [size] draws it: what a screen uses for a board's number that is no token. */
fun mediumAt(size: UiSize, value: Dp): Dp = value * (size.factor / UiSize.Medium.factor)

/** Corner radii. A capsule or a disc has no radius here: its shape is [Capsule]. */
@Immutable
class VeloRadii(
    /** A pane card: Tokens board "card", the terminal pane (Pane board 16). */
    val pane: Dp = medium(16f),
    /** A pane card on the folded screen. derived: the app's compact pane style; the folded Duo shows one pane edge to edge. */
    val paneCompact: Dp = 12.dp,
    /** A dialog, popover or the palette: Tokens board "dialog". */
    val overlay: Dp = medium(32f),
    /** A card: on the sheet, the raised card; inside a dialog, a glass card (Tokens board "card"). */
    val card: Dp = medium(16f),
    /** The key bar strip: a capsule (KeyBar board, 56 high, 28). */
    val bar: Dp = medium(28f),
    /** A tab tile of 30 (Tokens board "tile": the tile's size times 0.27). */
    val tile: Dp = medium(8f),
    /** A small badge, such as a license name (Tokens board "badge"). */
    val badge: Dp = medium(11f),
    /** A group of setting rows (Tokens board "group"). */
    val group: Dp = medium(12f),
    /** The settings sheet on the unfolded screen (Tokens board "sheet"). */
    val sheet: Dp = medium(20f),
    /** A button, a field, a segmented track, a menu (Tokens board "pill"). */
    val pill: Dp = medium(24f),
    /** The category list beside the details on the unfolded screen (Tokens board "nav"). */
    val nav: Dp = medium(30f),
)

/**
 * The spacing steps of the redesign (Tokens board: "간격은 4, 8, 12, 16, 20, 24 단계만 씁니다"),
 * at Medium, scaled with the Size setting.
 */
@Immutable
class VeloSpace(
    val s4: Dp = medium(4f),
    val s8: Dp = medium(8f),
    val s12: Dp = medium(12f),
    val s16: Dp = medium(16f),
    val s20: Dp = medium(20f),
    val s24: Dp = medium(24f),
)

@Immutable
class VeloSizes(
    /** A round glass control and the least touch target (D12 rail disc 47.3, L06 49.6). */
    val control: Dp = 48.dp,
    /** The thickness of a capsule that holds several icons: its round controls and 2 around them (Sidebar board, sidebar-actions: 52). */
    val capsule: Dp = 48.dp + medium(2f) * 2,
    /** One icon's share of that capsule's length (L06 55.5). */
    val capsuleCell: Dp = 56.dp,
    /** How far a selected cell stays inside its capsule (Controls board: the segmented track's 3 around its cells). */
    val cellInset: Dp = medium(3f),
    /** An icon in a control (D12 ink 22.0 to 27.8; a Material icon inks five sixths of its box). */
    val icon: Dp = 28.dp,
    /** An icon beside a header or inside a row (L06 "+" ink 17.3). */
    val iconSmall: Dp = 20.dp,
    /** A row of settings and a list row (Tokens board: "설정 행은 최소 60 높이"). */
    val row: Dp = medium(60f),
    /** A tab's row in the sidebar (Controls board, sample-tab-row: 56). */
    val tabRow: Dp = medium(56f),
    /** A section header of the list (Sidebar board, session header: 44; kept at the least touch target). */
    val sectionHeader: Dp = medium(44f),
    /** The selected pill's distance from the list's edge (D12 18.1, L06 17.9). */
    val rowInset: Dp = 18.dp,
    /** From the pill's edge to the row's tile (L06 19.7). */
    val rowPadding: Dp = 20.dp,
    /** From the tile to the title (D12 15.5; L06 puts the title 69.3 from the pill's edge, which is padding, tile and this). */
    val rowGap: Dp = 14.dp,
    /** The program tile of a row (D12 36.9 wide). */
    val tile: Dp = 36.dp,
    /** A state dot (Controls board, sample-status-dot: 8). */
    val mark: Dp = medium(8f),
    /** The update dot and a tab tile's state dot, each in a ring of the surface (Controls board: 9). */
    val dot: Dp = medium(9f),
    /** A tab tile's face in a sidebar row (Controls board, sample-tab-row: 30). */
    val tabTile: Dp = medium(30f),
    /** The search field and a field in a dialog (D12 45.3, L06 50.2). */
    val field: Dp = 48.dp,
    /** The sidebar beside the panes (WsOpenDocked board: 320; D12 365.9). */
    val sidebar: Dp = medium(320f),
    /** The rail (Rail board: 60, a control and 6 on each side); the Duo's rail (109) is built around its camera. */
    val rail: Dp = medium(60f),
    /** The key bar strip (KeyBar board: 56). */
    val keyBar: Dp = medium(56f),
    /** The widest the key bar grows: in the middle of a wide pane column (WsOpenPanes board: 560). */
    val keyBarMax: Dp = medium(560f),
    /** The least width of a key's cap (KeyBar board: 32). Its touch target is the room the bar gives it ([io.github.yhzion.velo.ui.keyCells]). */
    val key: Dp = medium(32f),
    /** A key's height in the strip (KeyBar board: 48). */
    val keyHeight: Dp = medium(48f),
    /** Between panes, and between panes and the screen's edge (Tokens board: 8). */
    val gap: Dp = medium(8f),
    /** Between pane cards and adjacent chrome in Compact style. */
    val gapCompact: Dp = 4.dp,
    /** Physical pixels inside the system safe area in Compact style (#6). */
    val compactOuterPx: Int = 4,
    val compactWideSidePx: Int = 2,
    /** Inside an overlay, from its edge to its content (AK 15.4, MB1 17.1). */
    val overlayPadding: Dp = 16.dp,
    /** A menu item. derived: M03 45.7 and AK 44.8, raised to Android's least touch target. */
    val menuItem: Dp = 48.dp,
    /** The widest a menu grows (M03 284.8). */
    val menuWidth: Dp = 285.dp,
    /** Above the first and under the last item of a menu, and around its divider. derived: half the overlay padding. */
    val menuPadding: Dp = 8.dp,
    /** The narrowest a menu is. derived: wide enough for an icon and two short words at the body size. */
    val menuMinWidth: Dp = 200.dp,
    /** The widest a dialog grows (Overlays board, DlgAddRemote: 386). */
    val dialogWidth: Dp = medium(386f),
    /** The command palette. derived: the app's width today; no image shows the palette. */
    val paletteWidth: Dp = 420.dp,
    /** The tallest the command palette grows. derived: the app's height today. */
    val paletteHeight: Dp = 520.dp,
    /** An overlay's distance from the window's edge and from its anchor (MB1 sheet inset 8.1). */
    val overlayMargin: Dp = 8.dp,
    /** How far the picture behind an overlay is blurred. derived: far enough that no letter of a terminal can be read through it. */
    val blur: Dp = 24.dp,
    /** The shadow under an overlay, as an elevation. derived: M03 shows a soft shadow of about 6 px around the menu. */
    val overlayShadow: Dp = 12.dp,
    /** The glass edge, a group's border and a divider (Controls board: 1). */
    val edge: Dp = 1.dp,
    /** The edge of the pane that takes the keys. derived: the app's width today. */
    val paneFocus: Dp = 2.dp,
    /** A switch's track (Controls board: 51 by 31). */
    val switchWidth: Dp = medium(51f),
    val switchHeight: Dp = medium(31f),
    /** From a switch's track to its knob (Controls board: 2). */
    val switchInset: Dp = medium(2f),
    /** A slider's track (Controls board: 4). */
    val sliderTrack: Dp = medium(4f),
    /** A slider's knob (Controls board: 28). */
    val sliderKnob: Dp = medium(28f),
    /** The widest the settings sheet grows on the unfolded screen. derived: the mockup the owner chose (A), 820 of 1088. */
    val sheetWidth: Dp = 820.dp,
    /** The list of categories beside the details in the settings sheet. derived: as [sheetWidth]. */
    val sheetList: Dp = 236.dp,
    /** A tab's state icon, and the height of its working dots. derived: the app's size today. */
    val statusIcon: Dp = 16.dp,
    /** A program's icon beside a tab's caption. derived: the app's size today. */
    val markIcon: Dp = 14.dp,
    /** A program's icon in a row's tile. derived: the app's size today. */
    val markTile: Dp = 20.dp,
    /** The rail's battery gauge across its ring's line (Rail board: 46, a little inside the rail's round buttons). */
    val gauge: Dp = medium(46f),
    /** The line of that ring. derived: the mockup the owner chose (v4), 3.4 on a ring of 38, kept in proportion. */
    val gaugeStroke: Dp = 4.3.dp,
)

/**
 * Text styles. The redesign's type scale (Tokens board) is given at Medium in Roboto, the
 * device's own family; the styles it does not name keep the sizes read from the images (cap
 * heights and x-heights over the share of the font size they are in the iOS system font).
 * A line of interface text is as tall as Roboto's own line ([robotoLine]), as the boards set it;
 * the other line heights are derived.
 */
@Immutable
class VeloType(
    /** Row titles, buttons, menu items, a field's text (Tokens board "body"). */
    val body: TextStyle = TextStyle(fontSize = mediumSp(16f), lineHeight = robotoLine(16f), fontWeight = FontWeight.Normal),
    /** The same, for a label that stands out: a remote's name, a selected row, a button. derived: body at the board's button weight. */
    val bodyStrong: TextStyle = TextStyle(fontSize = mediumSp(16f), lineHeight = robotoLine(16f), fontWeight = FontWeight.Medium),
    /** Section headers of the sidebar's list (Sidebar board, session header: 15, 500). */
    val header: TextStyle = TextStyle(fontSize = mediumSp(15f), lineHeight = robotoLine(15f), fontWeight = FontWeight.Medium),
    /** The title of a header: a settings page's, a pane's (Tokens board "header-title"). */
    val title: TextStyle = TextStyle(fontSize = mediumSp(18f), lineHeight = robotoLine(18f), fontWeight = FontWeight.SemiBold),
    /** Second lines, notes and the heading of a settings group (Tokens board "caption"). */
    val caption: TextStyle = TextStyle(fontSize = mediumSp(13f), lineHeight = robotoLine(13f), fontWeight = FontWeight.Normal),
    /** The title of a dialog or a card (Tokens board "dialog-title"). */
    val overlayTitle: TextStyle = TextStyle(fontSize = mediumSp(20f), lineHeight = robotoLine(20f), fontWeight = FontWeight.SemiBold),
    /** Key labels (Controls and KeyBar boards: 13, 500, monospace). */
    val key: TextStyle = TextStyle(fontSize = mediumSp(13f), lineHeight = mediumSp(18f), fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),
    /** The rail's clock (Rail board: 17, 600). */
    val clock: TextStyle = TextStyle(fontSize = mediumSp(17f), lineHeight = mediumSp(22f), fontWeight = FontWeight.SemiBold),
    /** The glyph inside a row's tile. derived: the tile's height less its padding. */
    val tileGlyph: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
    /** The title of a full screen: Settings (Tokens board "screen-title"). */
    val screenTitle: TextStyle = TextStyle(fontSize = mediumSp(26f), lineHeight = robotoLine(26f), fontWeight = FontWeight.SemiBold),
    /** The title of a page beside the categories on the unfolded screen (Tokens board "page-title"). */
    val pageTitle: TextStyle = TextStyle(fontSize = mediumSp(22f), lineHeight = robotoLine(22f), fontWeight = FontWeight.SemiBold),
    /** The wordmark of the start screen (StartKit board: 38, 600; 44 on the unfolded screen). */
    val wordmark: TextStyle = TextStyle(fontSize = mediumSp(38f), lineHeight = mediumSp(44f), fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    /** A cell of a segmented control; the selected cell is Medium (Controls board: 15). */
    val control: TextStyle = TextStyle(fontSize = mediumSp(15f), lineHeight = robotoLine(15f), fontWeight = FontWeight.Normal),
    /** A row's value and a menu button's choice (Tokens board "value"). */
    val value: TextStyle = TextStyle(fontSize = mediumSp(15f), lineHeight = robotoLine(15f), fontWeight = FontWeight.Normal),
    /** A small heading in capitals, such as NEW VERSION (Tokens board "eyebrow"). */
    val eyebrow: TextStyle = TextStyle(fontSize = mediumSp(12f), lineHeight = robotoLine(12f), fontWeight = FontWeight.Bold, letterSpacing = 0.06.em),
    /** A program's glyph beside a tab's caption. derived: the app's size today. */
    val markGlyph: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 20.sp),
    /** A program's glyph in a row's tile. derived: the app's size today. */
    val markGlyphTile: TextStyle = TextStyle(fontSize = 26.sp, lineHeight = 30.sp),
)

/**
 * The Size setting: how large the app's own controls and text are, the terminal's text aside.
 * [Large] is the size read from the Duo's images; the others scale it by [factor], down to the
 * floors of [veloSizes] and [veloType].
 */
enum class UiSize(
    /** The value kept in the settings. */
    val saved: String,
    /** The share of the Large size. derived: chosen on the Fold8 (#138). */
    val factor: Float,
) {
    Small("small", 0.72f), Medium("medium", 0.85f), Large("large", 1f);

    companion object {
        /** The choice a saved value stands for; a missing or unknown one is [Medium]. */
        fun parse(saved: String?): UiSize = entries.firstOrNull { it.saved == saved } ?: Medium
    }
}

/** The least a control that takes a tap is, in either direction: Android's least touch target. */
val MinTouch: Dp = 48.dp

/** The least a key of the key bar is: the iOS least touch target, so that a row of keys still fits the bar. */
val MinKey: Dp = 44.dp

/** The smallest text: a size below this is kept at it, unless the Large size is smaller already. */
const val MinTextSp = 11f

/**
 * The sizes at [size]. A size that sets how large a tap target is stays at [MinTouch] or
 * [MinKey]; a line, a focus edge, a blur and a shadow do not scale. The rail stays a control
 * and a gap on each side, and the key bar stays as much taller than its keys as it is at Large.
 */
fun veloSizes(size: UiSize): VeloSizes {
    val base = VeloSizes()
    val f = size.factor
    fun Dp.s(): Dp = this * f
    fun Dp.at(least: Dp): Dp = maxOf(this * f, least)
    val control = base.control.at(MinTouch)
    val gap = base.gap.s()
    val keyHeight = base.keyHeight.at(MinKey)
    return VeloSizes(
        control = control,
        capsule = control + medium(2f) * (2 * f),
        capsuleCell = base.capsuleCell.at(MinTouch),
        cellInset = base.cellInset.s(),
        icon = base.icon.s(),
        iconSmall = base.iconSmall.s(),
        row = base.row.at(MinTouch),
        tabRow = base.tabRow.at(MinTouch),
        sectionHeader = base.sectionHeader.at(MinTouch),
        rowInset = base.rowInset.s(),
        rowPadding = base.rowPadding.s(),
        rowGap = base.rowGap.s(),
        tile = base.tile.s(),
        mark = base.mark.at(6.dp),
        dot = base.dot.at(6.dp),
        tabTile = base.tabTile.s(),
        field = base.field.at(MinTouch),
        sidebar = base.sidebar.s(),
        // A control and the Rail board's 6 on each side, whatever the size.
        rail = maxOf(base.rail.s(), control + medium(6f) * (2 * f)),
        keyBar = maxOf(base.keyBar.s(), keyHeight + (base.keyBar - base.keyHeight) * f),
        keyBarMax = base.keyBarMax.s(),
        key = base.key.s(),
        keyHeight = keyHeight,
        gap = gap,
        gapCompact = base.gapCompact.s(),
        compactOuterPx = base.compactOuterPx,
        compactWideSidePx = base.compactWideSidePx,
        overlayPadding = base.overlayPadding.s(),
        menuItem = base.menuItem.at(MinTouch),
        menuWidth = base.menuWidth.s(),
        menuPadding = base.menuPadding.s(),
        menuMinWidth = base.menuMinWidth.s(),
        dialogWidth = base.dialogWidth.s(),
        paletteWidth = base.paletteWidth.s(),
        paletteHeight = base.paletteHeight.s(),
        overlayMargin = base.overlayMargin.s(),
        blur = base.blur,
        overlayShadow = base.overlayShadow,
        edge = base.edge,
        paneFocus = base.paneFocus,
        switchWidth = base.switchWidth.s(),
        switchHeight = base.switchHeight.s(),
        switchInset = base.switchInset.s(),
        sliderTrack = base.sliderTrack.s(),
        sliderKnob = base.sliderKnob.s(),
        sheetWidth = base.sheetWidth.s(),
        sheetList = base.sheetList.s(),
        statusIcon = base.statusIcon.s(),
        markIcon = base.markIcon.s(),
        markTile = base.markTile.s(),
        gauge = base.gauge.s(),
        gaugeStroke = base.gaugeStroke.s(),
    )
}

/** [style] at [f] of its size, kept at [MinTextSp] or above, its line height in the same proportion. */
private fun TextStyle.scaled(f: Float): TextStyle {
    val size = fontSize.value
    val next = maxOf(size * f, minOf(size, MinTextSp))
    return copy(fontSize = next.sp, lineHeight = (lineHeight.value * (next / size)).sp)
}

/** The text styles at [size]. */
fun veloType(size: UiSize): VeloType {
    val base = VeloType()
    val f = size.factor
    return VeloType(
        body = base.body.scaled(f),
        bodyStrong = base.bodyStrong.scaled(f),
        header = base.header.scaled(f),
        title = base.title.scaled(f),
        caption = base.caption.scaled(f),
        overlayTitle = base.overlayTitle.scaled(f),
        key = base.key.scaled(f),
        clock = base.clock.scaled(f),
        tileGlyph = base.tileGlyph.scaled(f),
        screenTitle = base.screenTitle.scaled(f),
        pageTitle = base.pageTitle.scaled(f),
        wordmark = base.wordmark.scaled(f),
        control = base.control.scaled(f),
        value = base.value.scaled(f),
        eyebrow = base.eyebrow.scaled(f),
        markGlyph = base.markGlyph.scaled(f),
        markGlyphTile = base.markGlyphTile.scaled(f),
    )
}

/** The corner radii at [size]: every radius scales with the shapes it rounds. */
fun veloRadii(size: UiSize): VeloRadii {
    val base = VeloRadii()
    val f = size.factor
    return VeloRadii(
        pane = base.pane * f, paneCompact = base.paneCompact * f, overlay = base.overlay * f,
        card = base.card * f, bar = base.bar * f, tile = base.tile * f,
        badge = base.badge * f, group = base.group * f, sheet = base.sheet * f, pill = base.pill * f, nav = base.nav * f,
    )
}

/** The spacing steps at [size]. */
fun veloSpace(size: UiSize): VeloSpace {
    val base = VeloSpace()
    val f = size.factor
    return VeloSpace(s4 = base.s4 * f, s8 = base.s8 * f, s12 = base.s12 * f, s16 = base.s16 * f, s20 = base.s20 * f, s24 = base.s24 * f)
}
