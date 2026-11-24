package io.github.yhzion.velo.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The corner radii, sizes and text styles of the app, the same in both appearances. As in
// Tokens.kt, the id after a value names the image it was read from (docs/design-tokens.md).

/** Corner radii. A capsule or a disc has no radius here: its shape is [Capsule]. */
@Immutable
class VeloRadii(
    /** A pane card (L06 41.0, L20 40.3). */
    val pane: Dp = 40.dp,
    /** A pane card on the folded screen. derived: the app's compact pane style; the folded Duo shows one pane edge to edge. */
    val paneCompact: Dp = 12.dp,
    /** A menu, dialog, popover or the palette (AK 38.7, MB1 38.1, M03 37.2). */
    val overlay: Dp = 38.dp,
    /** A group or card inside an overlay (AK 25.8). */
    val card: Dp = 26.dp,
    /** The key bar strip (L20 26.8). */
    val bar: Dp = 27.dp,
    /** The program tile of a row (D12 8.1). */
    val tile: Dp = 8.dp,
)

@Immutable
class VeloSizes(
    /** A round glass control and the least touch target (D12 rail disc 47.3, L06 49.6). */
    val control: Dp = 48.dp,
    /** The thickness of a capsule that holds several icons (D12 51.2, L06 54.3). */
    val capsule: Dp = 52.dp,
    /** One icon's share of that capsule's length (L06 55.5). */
    val capsuleCell: Dp = 56.dp,
    /** How far a selected cell stays inside its capsule. derived: D12's tab capsule keeps 1.9 in a capsule of 27.8; the same share of this one. */
    val cellInset: Dp = 4.dp,
    /** An icon in a control (D12 ink 22.0 to 27.8; a Material icon inks five sixths of its box). */
    val icon: Dp = 28.dp,
    /** An icon beside a header or inside a row (L06 "+" ink 17.3). */
    val iconSmall: Dp = 20.dp,
    /** A list row and its selected pill (D12 59.6, L06 59.1). */
    val row: Dp = 60.dp,
    /** A section header of the list (L06: 59.7 from a header's centre to its row's; D12: a header and a row take 116.6). */
    val sectionHeader: Dp = 60.dp,
    /** The selected pill's distance from the list's edge (D12 18.1, L06 17.9). */
    val rowInset: Dp = 18.dp,
    /** From the pill's edge to the row's tile (L06 19.7). */
    val rowPadding: Dp = 20.dp,
    /** From the tile to the title (D12 15.5; L06 puts the title 69.3 from the pill's edge, which is padding, tile and this). */
    val rowGap: Dp = 14.dp,
    /** The program tile of a row (D12 36.9 wide). */
    val tile: Dp = 36.dp,
    /** A state dot (D12 7.8, MB2 7.3). */
    val mark: Dp = 8.dp,
    /** The search field and a field in a dialog (D12 45.3, L06 50.2). */
    val field: Dp = 48.dp,
    /** The sidebar (D12 365.9). */
    val sidebar: Dp = 366.dp,
    /** The rail. derived: a control and a gap on each side; the Duo's rail (109) is built around its camera. */
    val rail: Dp = 68.dp,
    /** The key bar strip (L20 62.7). */
    val keyBar: Dp = 63.dp,
    /** The least width of a key (D12 label pitch 50.5). */
    val key: Dp = 50.dp,
    /** A key that has its own capsule (D12 46.6, 47.3). */
    val keyHeight: Dp = 47.dp,
    /** Between panes, and between panes and chrome (L20 10.2). */
    val gap: Dp = 10.dp,
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
    /** A dialog (MB1 sheet 385.8). */
    val dialogWidth: Dp = 386.dp,
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
    /** The glass edge and a divider (D12: one image pixel, 0.65). */
    val edge: Dp = 0.65.dp,
    /** The edge of the pane that takes the keys. derived: the app's width today. */
    val paneFocus: Dp = 2.dp,
    /** A switch's track. derived: the iOS system switch, 51 by 31 pt; no image shows a switch. */
    val switchWidth: Dp = 51.dp,
    val switchHeight: Dp = 31.dp,
    /** From a switch's track to its knob. derived: the iOS system switch, 2 pt. */
    val switchInset: Dp = 2.dp,
    /** A slider's track. derived: the iOS system slider, 4 pt; no image shows a slider. */
    val sliderTrack: Dp = 4.dp,
    /** A slider's knob. derived: the iOS system slider, 28 pt. */
    val sliderKnob: Dp = 28.dp,
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
    /** The rail's battery gauge across its ring's line. derived: the owner asked for the size of the rail's round buttons ([control]). */
    val gauge: Dp = 48.dp,
    /** The line of that ring. derived: the mockup the owner chose (v4), 3.4 on a ring of 38, kept in proportion. */
    val gaugeStroke: Dp = 4.3.dp,
)

/**
 * Text styles. Sizes are cap heights and x-heights read from the images, divided by the share of
 * the font size they are in the iOS system font (0.705 and 0.526; Roboto's are 0.711 and 0.528).
 * The family is the device's own; line heights are derived (the iOS text styles' leading).
 */
@Immutable
class VeloType(
    /** Row titles, the search field, menu items, dialog text (D12 19.3 to 19.7, L06 19.3 to 19.5, M03 19.5). */
    val body: TextStyle = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    /** The same, for the row that names a unit: a remote, This Device. derived: body, at the weight of the host label. */
    val bodyStrong: TextStyle = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    /** Section headers of the list (D12 18.5, L06 18.2). */
    val header: TextStyle = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    /** The pane header's title (L01 16.9, M01 16.8). */
    val title: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    /** Second lines and notes (L01 13.6, MB2 13.0, M01 12.3, PK 12.2). */
    val caption: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    /** The title of a dialog or sheet (AK 20.6 to 21.1). */
    val overlayTitle: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    /** Key labels (D12 17.1, monospace). */
    val key: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Monospace),
    /** The rail's clock (D12 18.4, L06 18.6, M01 18.9; the iPhone stills 18.8). */
    val clock: TextStyle = TextStyle(fontSize = 19.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    /** The glyph inside a row's tile. derived: the tile's height less its padding. */
    val tileGlyph: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
    /** The title of a full screen. derived: the app's size today; no image shows a full screen's title. */
    val screenTitle: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    /** The wordmark of the start screen. derived: the app's size today. */
    val wordmark: TextStyle = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold),
    /** A cell of a segmented capsule. derived: the pane title's size at the body's weight; no image shows a segmented control. */
    val control: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
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
        capsule = base.capsule.at(MinTouch),
        capsuleCell = base.capsuleCell.at(MinTouch),
        cellInset = base.cellInset.s(),
        icon = base.icon.s(),
        iconSmall = base.iconSmall.s(),
        row = base.row.at(MinTouch),
        sectionHeader = base.sectionHeader.at(MinTouch),
        rowInset = base.rowInset.s(),
        rowPadding = base.rowPadding.s(),
        rowGap = base.rowGap.s(),
        tile = base.tile.s(),
        mark = base.mark.at(6.dp),
        field = base.field.at(MinTouch),
        sidebar = base.sidebar.s(),
        rail = maxOf(base.rail.s(), control + gap * 2),
        keyBar = maxOf(base.keyBar.s(), keyHeight + (base.keyBar - base.keyHeight) * f),
        key = base.key.at(MinKey),
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
        gauge = control,
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
        wordmark = base.wordmark.scaled(f),
        control = base.control.scaled(f),
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
    )
}
