package app.meetingrecorder.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * The three button kinds of the app with the modest corner radius (FR-007). Material's own buttons are fully round
 * and ignore the shape scale (research R5), so these wrappers set the shape and nothing else. Screens use them and
 * never the raw Material buttons (ThemeSourceRulesTest). They take exactly the parameters the screens pass.
 */

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.medium, content = content)
}

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    // Material draws the outline in the faint divider color; the edge of a control needs the stronger outline color
    // (3:1 against the page, FR-011). A disabled button keeps a faint edge, which is exempt from the contrast rule.
    val outline = MaterialTheme.colorScheme.outline
    val border = BorderStroke(1.dp, if (enabled) outline else outline.copy(alpha = 0.38f))
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = border,
        content = content,
    )
}

@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = MaterialTheme.shapes.medium, content = content)
}
