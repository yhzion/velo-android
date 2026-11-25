package io.github.yhzion.velo.ui.theme

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

// Two kinds of glass.
//  - In the window, beside the panes: a fill with a light edge ([glass]). It never overlaps a
//    terminal, so it shows nothing through.
//  - Over the panes: a surface that shows a blur of what is behind it ([OverlaySurface]). The
//    picture comes from Backdrop.kt; the blur is done by the app's own renderer.

/**
 * In-window glass of the surface this is on: its fill ([selected]: the fill of a selected cell)
 * and its edge, clipped to [shape].
 */
@Composable
fun Modifier.glass(shape: Shape = Capsule, selected: Boolean = false): Modifier {
    val c = VeloTheme.surface
    return glass(c, shape, VeloTheme.sizes.edge, selected)
}

/**
 * Glass as the redesign draws a control (Controls board): the fill ([selected]: the selected
 * cell's) and one even rim all round. On an overlay the rim is the overlay's lighter glass
 * (Overlays board, a dialog's field). [ring] replaces the rim with a line twice as wide: a
 * field's focus or its error.
 */
@Composable
fun Modifier.glassControl(shape: Shape = Capsule, selected: Boolean = false, ring: Color? = null): Modifier {
    val c = VeloTheme.surface
    val rim = ring ?: if (c === VeloTheme.colors.overlay) c.glassSelected else c.glassEdge
    val width = if (ring != null) VeloTheme.sizes.edge * 2 else VeloTheme.sizes.edge
    return clip(shape).background(if (selected) c.glassSelected else c.glass).border(width, rim, shape)
}

/** [glass] with the colours and the edge width given, for callers outside a composition's read of the theme. */
fun Modifier.glass(colors: SurfaceColors, shape: Shape, edge: Dp, selected: Boolean = false): Modifier =
    clip(shape).background(if (selected) colors.glassSelected else colors.glass).border(edge, glassEdge(colors), shape)

/**
 * The edge of glass. On dark glass the Duo lights the rim at the top and the bottom and leaves
 * the sides near the fill (D12, PK); on light glass it is one thin line all round (M01, MB1).
 */
private fun glassEdge(colors: SurfaceColors): Brush {
    val edge = colors.glassEdge
    if (colors.glass.luminance() >= 0.5f) return Brush.linearGradient(listOf(edge, edge))
    val side = edge.copy(alpha = 0.35f)
    return Brush.verticalGradient(0f to edge, 0.3f to side, 0.7f to side, 1f to edge)
}

/** Milliseconds over which an overlay's first picture comes in. */
private const val BackdropFadeMillis = 150

/** Whether the content is in a window of its own above the screen's (a dialog); then the screen's window is part of the picture behind it. */
internal val LocalOverlayWindow = staticCompositionLocalOf { false }

internal fun LayoutCoordinates.onScreen(): IntRect {
    val at = positionOnScreen()
    val x = at.x.roundToInt()
    val y = at.y.roundToInt()
    return IntRect(x, y, x + size.width, y + size.height)
}

// Group cards and choice tracks inside Settings are laid out on opaque pane chrome.
internal val LocalSettingsPanel = staticCompositionLocalOf { false }

/**
 * Settings sits above the workspace, but is opaque. On the unfolded screen it is a sheet with the
 * sheet radius and a ring of the divider (the redesign's settings, 20); [fullScreen], on a folded
 * screen or a phone, it fills the window without either. Its content is on the sheet's colours.
 */
@Composable
internal fun SettingsPanelSurface(modifier: Modifier = Modifier, fullScreen: Boolean = false, content: @Composable () -> Unit) {
    val sheet = VeloTheme.colors.sheet
    val sizes = VeloTheme.sizes
    val shape = if (fullScreen) RectangleShape else rounded(VeloTheme.radii.sheet)
    VeloSurface(sheet) {
        Box(
            modifier.then(if (fullScreen) Modifier else Modifier.shadow(sizes.overlayShadow, shape, clip = false)).clip(shape)
                .background(sheet.surface).then(if (fullScreen) Modifier else Modifier.border(sizes.edge, sheet.divider, shape))
                // Empty panel space, unlike the scrim, must not dismiss the sheet.
                .pointerInput(Unit) { detectTapGestures { } },
        ) { CompositionLocalProvider(LocalSettingsPanel provides true, content = content) }
    }
}

/**
 * A surface that opens over the panes: a menu, a dialog, a popover, the palette. Its fill is
 * made from a blur of what is behind it ([LocalBackdropSource], [VeloColors.overlayMaterial]),
 * with the glass edge and a shadow. Where there is no source, or the device cannot blur
 * (before Android 12), the fill is the overlay's surface colour. [content] is on the overlay
 * surface group.
 */
@Composable
fun OverlaySurface(modifier: Modifier = Modifier, shape: Shape = rounded(VeloTheme.radii.overlay), content: @Composable BoxScope.() -> Unit) {
    val colors = VeloTheme.colors
    val overlay = colors.overlay
    val sizes = VeloTheme.sizes
    val given = LocalBackdropSource.current
    val ownWindow = LocalOverlayWindow.current
    // Inside the screen's own window that window is left out: its copy would hold this surface.
    val source = remember(given, ownWindow) { given?.let { if (ownWindow) it else BackdropSource(it.views, window = null) } }
    var bounds by remember { mutableStateOf<IntRect?>(null) }
    val picture = if (source != null && canBlur) rememberBackdrop(source, colors.backdrop.surface.toArgb()) { bounds } else null
    val blur = with(LocalDensity.current) { sizes.blur.toPx() }

    VeloSurface(overlay) {
        Box(
            modifier
                .shadow(sizes.overlayShadow, shape, clip = false)
                .onGloballyPositioned { bounds = it.onScreen() }
                .clip(shape)
                .background(overlay.surface)
                // One even line of the overlay's divider (Overlays board).
                .border(sizes.edge, overlay.divider, shape)
                // A tap on the surface itself is not a tap on what is behind it.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (picture != null) {
                // The first picture arrives a moment after the surface: it comes in over the plain fill instead of replacing it at once.
                val shown = remember { Animatable(0f) }
                val arrived = picture.value != null
                LaunchedEffect(arrived) { if (arrived) shown.animateTo(1f, tween(BackdropFadeMillis)) }
                Blurred({ picture.value }, colors.overlayMaterial(), blur, Modifier.matchParentSize().graphicsLayer { alpha = shown.value })
            }
            content()
        }
    }
}

/**
 * [picture], one pixel per [BackdropScale] screen pixels, drawn over the whole area, blurred
 * by [blurPx] screen pixels and then turned into the overlay's fill by [material]
 * ([VeloColors.overlayMaterial]). The blur runs on the small picture and the result is scaled
 * up, so its cost does not grow with the surface. Draws nothing until a picture is there.
 */
@Composable
private fun Blurred(picture: () -> ImageBitmap?, material: FloatArray, blurPx: Float, modifier: Modifier) {
    val effect = remember(material, blurPx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31.material(material, blurPx / BackdropScale) else null
    }
    Spacer(
        modifier
            .layout { measurable, constraints ->
                // Read here and in the drawing only, so that a new copy lays out and draws again and composes nothing.
                val now = picture()
                val placeable = measurable.measure(Constraints.fixed(now?.width ?: 0, now?.height ?: 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = BackdropScale.toFloat()
                        scaleY = BackdropScale.toFloat()
                        renderEffect = effect
                    }
                }
            }
            .drawBehind { picture()?.let { drawImage(it) } },
    )
}

@RequiresApi(Build.VERSION_CODES.S)
private object Api31 {
    /** A blur of [radius] pixels, then the colour matrix [m] (three rows of red, green, blue and a constant in 0..1). */
    fun material(m: FloatArray, radius: Float): RenderEffect {
        val matrix = ColorMatrix(floatArrayOf(
            m[0], m[1], m[2], 0f, m[3] * 255f,
            m[4], m[5], m[6], 0f, m[7] * 255f,
            m[8], m[9], m[10], 0f, m[11] * 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val blur = android.graphics.RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), blur).asComposeRenderEffect()
    }
}
