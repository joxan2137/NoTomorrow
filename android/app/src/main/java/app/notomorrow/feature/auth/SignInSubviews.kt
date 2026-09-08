package app.notomorrow.feature.auth

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtSpinner
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntPlainClickable

/** `STFieldChrome`: 52 pt `surface` field, 14 pt continuous corners. */
private val AuthFieldHeight: Dp = 52.dp
private val AuthFieldRadius: Dp = 14.dp

/**
 * The sign-in sheet's text field — `TextField`/`SecureField` under `.stField(isFocused:)`
 * (`Features/Settings/SettingsComponents.swift:196`): 16 pt horizontal padding, 52 pt tall,
 * `surface` at a 14 pt continuous radius, and a hairline border that turns `ink` at 1.5 pt when
 * focused, `easeOut` 0.15 s.
 *
 * @param contentType SwiftUI's `.textContentType(_:)` — what a password manager may fill or offer
 *   to save. The sheet is the app's only credential entry point, so this is behaviour, not polish.
 */
@Composable
internal fun AuthField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    secure: Boolean = false,
    contentType: ContentType? = null,
) {
    val fallback = remember { FocusRequester() }
    val requester = focusRequester ?: fallback
    var focused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (focused) NT.Colors.ink else NT.Colors.hairline,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "authFieldBorderColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "authFieldBorderWidth",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AuthFieldHeight)
            .background(NT.Colors.surface, NtShapes.rounded(AuthFieldRadius))
            .border(borderWidth, borderColor, NtShapes.rounded(AuthFieldRadius))
            .ntPlainClickable { runCatching { requester.requestFocus() } }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            NtText(
                text = placeholder,
                style = NT.Fonts.body,
                color = NT.Colors.ink3,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (contentType != null) {
                        Modifier.semantics { this.contentType = contentType }
                    } else {
                        Modifier
                    },
                ),
            textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
            singleLine = true,
            cursorBrush = SolidColor(NT.Colors.ink),
            visualTransformation = if (secure) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
    }
}

/**
 * SwiftUI's indeterminate `ProgressView()`, drawn rather than imported — the app ships no Material
 * chrome. Same geometry as the other hand-drawn spinners in the port.
 */
@Composable
internal fun AuthSpinner(
    modifier: Modifier = Modifier,
    color: Color = NT.Colors.ink,
    size: Dp = 18.dp,
    strokeWidth: Dp = 2.dp,
) = NtSpinner(modifier = modifier, color = color, size = size, strokeWidth = strokeWidth)
