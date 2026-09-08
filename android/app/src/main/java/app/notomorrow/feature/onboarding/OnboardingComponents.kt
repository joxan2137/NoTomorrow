package app.notomorrow.feature.onboarding

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtToggle
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntDismissKeyboardOnScroll
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.S

// Small building blocks shared by the onboarding screens — the port of
// `Features/Onboarding/OnboardingComponents.swift`. Prefixed `Ob` to stay out of the design
// system's way, exactly like iOS's `OB` prefix.

/** The field / segmented corner radius shared by every onboarding input. */
private val ObFieldRadius = 14.dp

private val ObFieldHeight = 52.dp

// ─────────────────────────────────────────────────────────────────────────────
// Progress header
// ─────────────────────────────────────────────────────────────────────────────

/** Back arrow (44 dp hit area), [count] 4 dp segments, "1 / 3". */
@Composable
fun ObProgressHeader(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .padding(start = NT.Spacing.screenH - 10.dp, end = NT.Spacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(NT.Size.control)
                .pressScale(onClickLabel = stringResource(S.common_back), onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(NtIcons.ArrowLeft, size = sfIconSize(20f), tint = NT.Colors.ink)
        }

        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { i ->
                val fill by animateColorAsState(
                    targetValue = if (i <= index) NT.Colors.ink else NT.Colors.surface2,
                    animationSpec = tween(200, easing = NT.Ease.out),
                    label = "obProgressSegment",
                )
                Box(Modifier.weight(1f).height(4.dp).background(fill, CircleShape))
            }
        }

        TabularText(
            text = stringResource(S.onboarding_step_n_n, index + 1, count),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Field chrome
// ─────────────────────────────────────────────────────────────────────────────

/** 52 dp surface field with a hairline border that turns ink when focused. */
@Composable
fun Modifier.obField(focused: Boolean): Modifier {
    val color by animateColorAsState(
        targetValue = if (focused) NT.Colors.ink else NT.Colors.hairline,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "obFieldBorderColor",
    )
    val width by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "obFieldBorderWidth",
    )
    return this
        .height(ObFieldHeight)
        .background(NT.Colors.surface, NtShapes.rounded(ObFieldRadius))
        .border(width, color, NtShapes.rounded(ObFieldRadius))
        .padding(horizontal = 16.dp)
}

/** Eyebrow label above a field or chip row (`View.obLabeled`). */
@Composable
fun ObLabeled(
    label: String,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        Eyebrow(label)
        content()
    }
}

/**
 * The onboarding text field. `BasicTextField` (never `OutlinedTextField`) with the app's cursor,
 * an ink2 placeholder and an optional trailing unit label.
 */
@Composable
fun ObTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = NT.Fonts.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    /**
     * Gap between the field and its trailing unit label. iOS uses `HStack(spacing: 12)` for the
     * body-weight row (`SetupYouView.swift:63`) and `HStack(spacing: 8)` for the target editor's
     * kcal / macro fields (`OBTargetEditorSheet.swift:58`).
     */
    trailingSpacing: Dp = 12.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.obField(focused),
        horizontalArrangement = Arrangement.spacedBy(if (trailing != null) trailingSpacing else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                textStyle = textStyle.copy(color = NT.Colors.ink),
                singleLine = true,
                cursorBrush = SolidColor(NT.Colors.ink),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
            if (value.isEmpty()) {
                NtText(placeholder, style = textStyle, color = NT.Colors.ink3, maxLines = 1)
            }
        }
        trailing?.invoke(this)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Toggle row
// ─────────────────────────────────────────────────────────────────────────────

/** `OBToggleRow` — title (+ optional detail) and a trailing switch, min height 52. */
@Composable
fun ObToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            NtText(title, style = NT.Fonts.body, color = NT.Colors.ink)
            if (detail != null) {
                NtText(detail, style = NT.Fonts.footnote, color = NT.Colors.ink2)
            }
        }
        Spacer(Modifier.width(8.dp))
        NtToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Step scaffold
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Progress header + scrolling content + pinned CTA above the navigation bar
 * (`.safeAreaInset(edge: .bottom)`).
 */
@Composable
fun ObStepScaffold(
    index: Int,
    count: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NT.Colors.ground)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        ObProgressHeader(index = index, count = count, onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                // `.scrollDismissesKeyboard(.interactively)`.
                .ntDismissKeyboardOnScroll()
                .verticalScroll(rememberScrollState()),
        ) {
            content()
            Spacer(Modifier.height(24.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(NT.Colors.ground)
                // One bottom inset, not two: iOS's `.safeAreaInset(edge: .bottom)` collapses the
                // home-indicator inset once the keyboard covers it, so the footer clears whichever
                // of the two is taller — never their sum.
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 12.dp, bottom = 8.dp),
            content = footer,
        )
    }
}

/** Title + subtitle block at the top of every step. */
@Composable
fun ObStepTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NtText(title, style = NT.Fonts.title1, color = NT.Colors.ink)
        NtText(subtitle, style = NT.Fonts.subheadline, color = NT.Colors.ink2)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Layout helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * SwiftUI's `.padding(.bottom, -n)`: the child still draws at its full height but reports a
 * shorter one, pulling whatever follows up by [amount].
 */
fun Modifier.obNegativeBottomPadding(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height - amount.roundToPx()).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, 0) }
}
