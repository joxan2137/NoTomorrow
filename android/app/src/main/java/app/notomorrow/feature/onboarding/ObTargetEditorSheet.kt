package app.notomorrow.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntMediumDetent
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.tabular
import app.notomorrow.service.TargetCalculator
import app.notomorrow.util.S

/**
 * Edit the suggested daily target by hand (kcal + macros). "Use the suggestion" goes back to the
 * calculator. `.presentationDetents([.medium])`, own grabber, `ground` background.
 */
@Composable
fun ObTargetEditorSheet(
    state: OnboardingUiState,
    onUseSuggestion: () -> Unit,
    onSave: (kcal: String, protein: String, carbs: String, fat: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var kcal by remember { mutableStateOf(state.targets.kcal.toString()) }
    var protein by remember { mutableStateOf(state.targets.proteinG.toString()) }
    var carbs by remember { mutableStateOf(state.targets.carbsG.toString()) }
    var fat by remember { mutableStateOf(state.targets.fatG.toString()) }

    fun load(targets: TargetCalculator.Targets) {
        kcal = targets.kcal.toString()
        protein = targets.proteinG.toString()
        carbs = targets.carbsG.toString()
        fat = targets.fatG.toString()
    }

    NtSheet(
        onDismiss = onDismiss,
        containerColor = NT.Colors.ground,
        // `.presentationDetents([.medium])`: a single fixed detent, so the card sits at exactly
        // half the screen and the weighted spacer below cannot stretch it to full height.
        height = ntMediumDetent(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Grabber() }

            NtText(
                text = stringResource(S.target_daily),
                modifier = Modifier.padding(top = 18.dp),
                style = NT.Fonts.title2,
                color = NT.Colors.ink,
            )

            Column(
                modifier = Modifier.padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ObNumberField(
                    label = stringResource(S.unit_kcal),
                    value = kcal,
                    onValueChange = { kcal = it },
                    unit = stringResource(S.unit_kcal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ObNumberField(
                        label = stringResource(S.macro_protein),
                        value = protein,
                        onValueChange = { protein = it },
                        unit = "g",
                        modifier = Modifier.weight(1f),
                    )
                    ObNumberField(
                        label = stringResource(S.macro_carbs),
                        value = carbs,
                        onValueChange = { carbs = it },
                        unit = "g",
                        modifier = Modifier.weight(1f),
                    )
                    ObNumberField(
                        label = stringResource(S.macro_fat),
                        value = fat,
                        onValueChange = { fat = it },
                        unit = "g",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.heightIn(min = 16.dp).weight(1f))

            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GhostButton(title = stringResource(S.onboarding_you_suggested)) {
                    onUseSuggestion()
                    load(state.suggestedTargets)
                }
                PrimaryButton(title = stringResource(S.common_save)) {
                    onSave(kcal, protein, carbs, fat)
                    onDismiss()
                }
            }
        }
    }
}

/** One labelled numeric field with a trailing unit. */
@Composable
private fun ObNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
) {
    ObLabeled(label = label, modifier = modifier) {
        ObTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "0",
            modifier = Modifier.fillMaxWidth(),
            textStyle = NT.Fonts.body.tabular(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            // `OBTargetEditorSheet.swift:58` — `HStack(spacing: 8)`, not the body-weight row's 12.
            trailingSpacing = 8.dp,
            trailing = {
                NtText(text = unit, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
            },
        )
    }
}
