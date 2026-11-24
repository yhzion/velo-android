package io.github.yhzion.velo.ui.theme

import androidx.compose.foundation.clickable
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
 * A one-line text field: a glass capsule on the surface it is on, with an optional [label]
 * above it, an [icon] before the text and a [placeholder] while it is empty. [trailing] sits
 * at its end, inside the capsule. [error] under it turns nothing red but the line itself.
 */
@Composable
fun VeloTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", label: String? = null, error: String? = null, icon: Painter? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
    /** How the text is drawn: a password's field hides it ([androidx.compose.ui.text.input.PasswordVisualTransformation]). */
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    val type = VeloTheme.type
    val description = fieldDescription(label, placeholder)
    Column(modifier) {
        // The input says the label itself ([fieldDescription]): drawn text only, so it is not read twice.
        if (label != null) Text(label, Modifier.padding(start = s.rowPadding, bottom = s.menuPadding / 2).clearAndSetSemantics {}, style = type.caption, color = c.secondary)
        BasicTextField(
            value, onValueChange, Modifier.fillMaxWidth().semantics { description?.let { contentDescription = it } }, singleLine = true,
            textStyle = type.body.copy(color = c.onGlass), cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
        ) { field ->
            Row(Modifier.height(s.field).glass().padding(start = s.rowPadding, end = if (trailing != null) s.cellInset else s.rowPadding),
                verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    // The Duo's magnifier is 24 dp of ink (D12 24.0, L06 23.9): the control's icon size, not the row's.
                    Icon(icon, null, Modifier.size(s.icon), tint = c.secondary)
                    Spacer(Modifier.width(s.rowGap / 2))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    // Without a label the placeholder is the input's name, which the input already says.
                    if (value.isEmpty()) Text(placeholder, if (!labelNamesField(label)) Modifier.clearAndSetSemantics {} else Modifier, style = type.body, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
                if (trailing != null) trailing()
            }
        }
        if (error != null) Text(error, Modifier.padding(start = s.rowPadding, top = s.menuPadding / 2).semantics { liveRegion = LiveRegionMode.Polite },
            style = type.caption, color = c.danger)
    }
}

/**
 * The search field of a list, as on the Duo: a glass capsule with a magnifier and "Search".
 * It floats at the bottom of the list it filters; the list scrolls under it. With text in it,
 * an X at its end clears it.
 */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    val c = VeloTheme.surface
    val s = VeloTheme.sizes
    VeloTextField(
        query, onQueryChange, modifier, placeholder = placeholder, icon = rememberVectorPainter(Icons.Default.Search),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailing = if (query.isEmpty()) null else {
            {
                Box(Modifier.size(s.field - s.cellInset * 2).clip(Capsule).clickable(role = Role.Button) { onQueryChange("") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "Clear search", Modifier.size(s.iconSmall), tint = c.secondary)
                }
            }
        },
    )
}
