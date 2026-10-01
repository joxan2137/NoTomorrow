package app.notomorrow.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.model.MealSlot
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * The description screen — step 1 of an estimate with no photo ("Describe" on the Fuel tab): the
 * user writes what they ate and the same AI estimate runs on the text alone. Laid out like
 * [AIScanSourceView]: slot eyebrow, title and subtitle, then a multi-line field (focused on open,
 * capped at [AIScanCorrections.MAX_NOTES_LENGTH]) and "Estimate calories", disabled while the
 * text is blank.
 */
@Composable
fun AIScanDescribeView(
    meal: MealSlot,
    notes: String,
    onNotes: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(stringResource(NtKeys.meal(meal)))
            NtText(
                text = stringResource(S.fuel_ai_describe_title),
                style = NT.Fonts.title3,
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(S.fuel_ai_describe_subtitle),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NT.Colors.surface2, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            BasicTextField(
                value = notes,
                onValueChange = { onNotes(AIScanCorrections.truncated(it, AIScanCorrections.MAX_NOTES_LENGTH)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                textStyle = NT.Fonts.body.copy(color = NT.Colors.ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 5,
                maxLines = 10,
                cursorBrush = SolidColor(NT.Colors.ink),
            )
            if (notes.isEmpty()) {
                NtText(
                    text = stringResource(S.fuel_ai_describe_placeholder),
                    style = NT.Fonts.body,
                    color = NT.Colors.ink3,
                )
            }
        }

        PrimaryButton(
            title = stringResource(S.fuel_ai_describe_estimate),
            icon = NtIcons.Sparkles,
            enabled = notes.isNotBlank(),
        ) {
            focusManager.clearFocus()
            onSubmit()
        }
    }
}
