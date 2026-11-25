package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow

/**
 * What a screen reader calls a text field: its [label], else its [placeholder], null when it has
 * neither. The label drawn above the field and the placeholder drawn in it are separate nodes
 * that vanish (the placeholder, once text is typed) or are not part of the input's node, so the
 * input carries the name itself.
 */
fun fieldDescription(label: String?, placeholder: String): String? =
    if (labelNamesField(label)) label else placeholder.takeIf { it.isNotEmpty() }

/**
 * Whether [label] names the field. An empty label shows no words, only an empty line above the
 * field, so it names nothing: the placeholder then names the field, and the drawn placeholder is
 * left out of the tree ([fieldDescription]) so that it is read once.
 */
fun labelNamesField(label: String?): Boolean = !label.isNullOrEmpty()

/**
 * A one-line text field (Controls and Overlays boards): a glass pill with an even rim on the
 * surface it is on, an optional [label] above it, an [icon] before the text and a [placeholder]
 * while it is empty. [trailing] sits at its end, inside the pill. While it has the keys its rim is
 * the chrome accent, twice as wide. [help] under it says what to type; an [error] takes its place
 * in the red and turns the rim red. [mono] sets the text in the monospace face, for an address or
 * a path. [tagPrefix] names the parts for tests: `<prefix>-field` (the whole), `-input`, `-help`, `-error`.
 */
@Composable
fun VeloTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", label: String? = null, error: String? = null, icon: Painter? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
    /** How the text is drawn: a password's field hides it ([androidx.compose.ui.text.input.PasswordVisualTransformation]). */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    help: String? = null, mono: Boolean = false, tagPrefix: String? = null,
    /** The pill's least height, and the room between the [icon] and the text. */
    minHeight: Dp = VeloTheme.sizes.field, iconGap: Dp = VeloTheme.space.s8,
    /** On the text's own box, between the icon and [trailing]. */
    inputModifier: Modifier = Modifier,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val type = VeloTheme.type
    val description = fieldDescription(label, placeholder)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The chrome accent marks the field that has the keys on every surface (Controls and Overlays boards: #7FA6F5).
    val focusColor = VeloTheme.colors.chrome.accent
    val ring = when {
        error != null -> c.danger
        focused -> focusColor
        else -> null
    }
    val textStyle = if (mono) type.value.copy(fontFamily = FontFamily.Monospace) else type.body
    fun Modifier.part(name: String) = if (tagPrefix != null) testTag("$tagPrefix-$name") else this
    Column(modifier.part("field"), verticalArrangement = Arrangement.spacedBy(VeloTheme.medium(6.dp))) {
        // The input says the label itself ([fieldDescription]): drawn text only, so it is not read twice.
        if (label != null) Text(label, Modifier.padding(start = VeloTheme.space.s4).clearAndSetSemantics {}, style = type.caption, color = c.secondary)
        BasicTextField(
            value, onValueChange, Modifier.fillMaxWidth().part("input").semantics { description?.let { contentDescription = it } }, singleLine = true,
            textStyle = textStyle.copy(color = c.onGlass), cursorBrush = SolidColor(focusColor),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
            interactionSource = interaction,
        ) { field ->
            Row(Modifier.heightIn(min = minHeight).glassControl(Capsule, ring = ring)
                .padding(start = VeloTheme.medium(18.dp), end = if (trailing != null) VeloTheme.medium(6.dp) else VeloTheme.medium(18.dp)),
                verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(VeloTheme.space.s20), tint = c.secondary)
                    Spacer(Modifier.width(iconGap))
                }
                Box(Modifier.weight(1f).then(inputModifier), contentAlignment = Alignment.CenterStart) {
                    // Without a label the placeholder is the input's name, which the input already says.
                    if (value.isEmpty()) Text(placeholder, if (!labelNamesField(label)) Modifier.clearAndSetSemantics {} else Modifier, style = textStyle, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
                if (trailing != null) trailing()
            }
        }
        val note = error ?: help
        if (note != null) Text(
            note,
            Modifier.padding(horizontal = VeloTheme.medium(if (error != null) 6.dp else 4.dp)).part(if (error != null) "error" else "help")
                .then(if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            style = type.caption.paragraph(1.45.em), color = if (error != null) c.dangerText else c.secondary,
        )
    }
}

/**
 * The search field of a list, as on the Duo: a glass capsule with a magnifier and "Search".
 * It floats at the bottom of the list it filters; the list scrolls under it. With text in it,
 * an X at its end clears it.
 */
@Composable
fun SearchField(
    query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search",
    inputModifier: Modifier = Modifier,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    // The Sidebar board's filter: 52 high, the magnifier 12 before the text.
    VeloTextField(
        query, onQueryChange, modifier, placeholder = placeholder, icon = rememberVectorPainter(Icons.Default.Search),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        minHeight = VeloTheme.medium(52.dp).coerceAtLeast(MinTouch), iconGap = VeloTheme.space.s12, inputModifier = inputModifier,
        trailing = if (query.isEmpty()) null else {
            {
                Box(Modifier.size(s.field - s.cellInset * 2).clip(Capsule).clickable(role = Role.Button) { onQueryChange("") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Clear search", Modifier.size(s.iconSmall), tint = c.secondary)
                }
            }
        },
    )
}
