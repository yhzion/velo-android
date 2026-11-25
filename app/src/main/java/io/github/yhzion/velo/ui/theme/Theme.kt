package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

private val LocalSurface = staticCompositionLocalOf { VeloPalettes.dark.chrome }
private val LocalRadii = staticCompositionLocalOf { VeloRadii() }
private val LocalSizes = staticCompositionLocalOf { VeloSizes() }
private val LocalType = staticCompositionLocalOf { VeloType() }
private val LocalSpace = staticCompositionLocalOf { VeloSpace() }
private val LocalUiSize = staticCompositionLocalOf { UiSize.Large }
/** The colour chosen for the focused pane's edge (Appearance → Active pane), or null for the palette's. Not static: only its readers recompose when it changes. */
private val LocalPaneHighlight = compositionLocalOf<Color?> { null }

/** The tokens, as the screens read them. */
object VeloTheme {
    /** The app palette is dark regardless of the device's appearance. */
    val colors: VeloColors get() = VeloPalettes.dark

    /** The colours of the surface this part of the screen sits on ([VeloSurface]); chrome unless set. */
    val surface: SurfaceColors @Composable @ReadOnlyComposable get() = LocalSurface.current
    val radii: VeloRadii @Composable @ReadOnlyComposable get() = LocalRadii.current
    val sizes: VeloSizes @Composable @ReadOnlyComposable get() = LocalSizes.current
    val type: VeloType @Composable @ReadOnlyComposable get() = LocalType.current
    /** The redesign's spacing steps, 4 to 24 at Medium. */
    val space: VeloSpace @Composable @ReadOnlyComposable get() = LocalSpace.current
    /** The Size setting the tokens are drawn at. */
    val size: UiSize @Composable @ReadOnlyComposable get() = LocalUiSize.current

    /** A length a redesign board gives at Medium, at the current size ([mediumAt]): for a board's number that no token holds. */
    @Composable @ReadOnlyComposable
    fun medium(value: Dp): Dp = mediumAt(LocalUiSize.current, value)

    /** The edge of the pane that takes the keys, and its split handles: the chosen colour at the palette's strength. */
    val paneFocus: Color @Composable get() = LocalPaneHighlight.current?.copy(alpha = colors.paneFocus.alpha) ?: colors.paneFocus

    /** The selected tab's edge in the overview, which the focused pane's edge turns into: the chosen colour, else the backdrop's accent. */
    val selectedCardEdge: Color @Composable get() = LocalPaneHighlight.current ?: colors.backdrop.accent
}

/**
 * Provides the dark tokens at the chosen [size]. Content starts on chrome.
 * Material components inside pick them up through a colour scheme, a typography and
 * shapes made from the tokens. [paneHighlight], when set, replaces the palette's colour of
 * the focused pane's edge ([VeloTheme.paneFocus], [VeloTheme.selectedCardEdge]).
 */
@Composable
fun VeloTheme(size: UiSize = UiSize.Large, paneHighlight: Color? = null, content: @Composable () -> Unit) {
    val sizes = remember(size) { veloSizes(size) }
    val density = LocalDensity.current
    val type = remember(size, density) { veloType(size).onPixels(density) }
    val radii = remember(size) { veloRadii(size) }
    val space = remember(size) { veloSpace(size) }
    CompositionLocalProvider(
        LocalSizes provides sizes, LocalType provides type, LocalRadii provides radii, LocalPaneHighlight provides paneHighlight,
        LocalSpace provides space, LocalUiSize provides size,
    ) {
        VeloSurface(VeloTheme.colors.chrome, content)
    }
}

/**
 * [content] sits on the surface whose colours are [colors]: one of the five groups of
 * [VeloTheme.colors]. Components read their colours from it ([VeloTheme.surface]), and so do
 * Material components.
 */
@Composable
fun VeloSurface(colors: SurfaceColors, content: @Composable () -> Unit) {
    val scrim = VeloTheme.colors.scrim
    val type = VeloTheme.type
    val radii = VeloTheme.radii
    MaterialTheme(
        colorScheme = remember(colors, scrim) { materialColors(colors, scrim) },
        typography = remember(type) { materialTypography(type) },
        shapes = remember(radii) { materialShapes(radii) },
    ) {
        CompositionLocalProvider(
            LocalSurface provides colors,
            LocalContentColor provides colors.text,
            LocalTextStyle provides type.body,
            content = content,
        )
    }
}

/** A rounded rectangle of a radius token. */
fun rounded(radius: Dp): Shape = RoundedCornerShape(radius)

/** A disc, or a rectangle whose short sides are half circles. */
val Capsule: Shape = RoundedCornerShape(percent = 50)

/**
 * The Material 3 colour scheme of a surface group: what a Material component that remains
 * (a switch, a slider, a chip, a button, a snackbar) draws with on that surface. Containers
 * that hold text take the selected-row fill, not glass: text is readable on it in every group,
 * while the backdrop's glass is a mid grey that only carries a glyph.
 */
fun materialColors(s: SurfaceColors, scrim: Color): ColorScheme {
    return darkColorScheme().copy(
        primary = s.accent, onPrimary = s.onAccent,
        primaryContainer = s.glassSelected, onPrimaryContainer = s.onGlass,
        inversePrimary = s.accent,
        secondary = s.accent, onSecondary = s.onAccent,
        secondaryContainer = s.glassSelected, onSecondaryContainer = s.onGlass,
        tertiary = s.accent, onTertiary = s.onAccent,
        tertiaryContainer = s.glassSelected, onTertiaryContainer = s.onGlass,
        background = s.surface, onBackground = s.text,
        surface = s.surface, onSurface = s.text,
        surfaceVariant = s.selected, onSurfaceVariant = s.secondary,
        surfaceTint = s.surface,
        inverseSurface = s.text, inverseOnSurface = s.surface,
        error = s.danger, onError = s.onDanger,
        errorContainer = s.selected, onErrorContainer = s.text,
        outline = s.secondary, outlineVariant = s.divider,
        scrim = scrim.copy(alpha = 1f),
        surfaceBright = s.selected, surfaceDim = s.surface,
        surfaceContainerLowest = s.surface, surfaceContainerLow = s.surface,
        surfaceContainer = s.selected, surfaceContainerHigh = s.selected, surfaceContainerHighest = s.selected,
    )
}

private fun materialTypography(t: VeloType) = Typography(
    displayLarge = t.wordmark, displayMedium = t.wordmark, displaySmall = t.wordmark,
    headlineLarge = t.screenTitle, headlineMedium = t.screenTitle, headlineSmall = t.overlayTitle,
    titleLarge = t.overlayTitle, titleMedium = t.title, titleSmall = t.header,
    bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
    labelLarge = t.bodyStrong, labelMedium = t.caption, labelSmall = t.caption,
)

private fun materialShapes(r: VeloRadii) = Shapes(
    extraSmall = RoundedCornerShape(r.tile), small = RoundedCornerShape(r.tile),
    medium = RoundedCornerShape(r.card), large = RoundedCornerShape(r.card), extraLarge = RoundedCornerShape(r.overlay),
)
