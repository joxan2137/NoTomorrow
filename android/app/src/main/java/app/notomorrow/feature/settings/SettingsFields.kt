package app.notomorrow.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntClickable

// The Settings inputs: `STFieldChrome`, `stLabeled`, the text field, the busy spinner and the
// rest-length stepper button (`Features/Settings/SettingsComponents.swift`).

/** `STFieldChrome` — 52 pt tall, 14 pt continuous corners. */
private val StFieldRadius: Dp = 14.dp
private val StFieldHeight: Dp = 52.dp

// ─────────────────────────────────────────────────────────────────────────────
// Field chrome
// ─────────────────────────────────────────────────────────────────────────────

/** 52 pt surface field with a hairline border that turns ink when focused. */
@Composable
fun Modifier.stField(focused: Boolean): Modifier {
    val color by animateColorAsState(
        targetValue = if (focused) NT.Colors.ink else NT.Colors.hairline,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "stFieldBorderColor",
    )
    val width by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "stFieldBorderWidth",
    )
    return this
        .height(StFieldHeight)
        .background(NT.Colors.surface, NtShapes.rounded(StFieldRadius))
        .border(width, color, NtShapes.rounded(StFieldRadius))
        .padding(horizontal = 16.dp)
}

/** Eyebrow label above a field (`View.stLabeled`), with the optional footnote below it. */
@Composable
fun StLabeled(
    label: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(label)
        content()
        if (footnote != null) {
            NtText(
                text = footnote,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

/**
 * The Settings text field: `BasicTextField` (never `OutlinedTextField`) inside [stField], with
 * an ink3 placeholder, the app's cursor and an optional leading label / trailing unit.
 */
@Composable
fun StTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = NT.Fonts.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().stField(focused),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                textStyle = textStyle.copy(color = NT.Colors.ink, textAlign = textStyle.textAlign),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
            )
            if (value.isEmpty()) {
                NtText(placeholder, style = textStyle, color = NT.Colors.ink3, maxLines = 1)
            }
        }
        trailing?.invoke(this)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Spinner
// ─────────────────────────────────────────────────────────────────────────────

/** `ProgressView().tint(ink2)` — the design system's [NtSpinner] at Settings' 16 dp. */
@Composable
fun StSpinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = NT.Colors.ink2) =
    NtSpinner(modifier = modifier, color = color, size = size)

/**
 * The −/+ glyph box.
 *
 * iOS draws the symbol with `.font(.system(size: 15, weight: .bold))`, which puts a 12.7 pt bar
 * inside the 36 pt circle; `sfIconSize(15f)` (18.75 dp) renders `ic_minus`, whose stroke spans
 * 0.71 of its 24 dp viewport, at 13.3 dp — 5 % over. 17.8 dp lands it back on iOS's 12.7 pt.
 */
private val StStepGlyph: Dp = 17.8.dp

/** A circular −/+ stepper button: 36 dp `surface2` circle inside a 44 dp hit target. */
@Composable
fun StStepButton(
    icon: NtIcons,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(NT.Size.control)
            .alphaLayer(if (enabled) 1f else 0.4f)
            .ntClickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(NT.Colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(icon, size = StStepGlyph, tint = NT.Colors.ink, contentDescription = contentDescription)
        }
    }
}

/** `.opacity(_:)`, spelled once so the call sites read like the Swift. */
internal fun Modifier.alphaLayer(value: Float): Modifier = this.alpha(value)
