package app.notomorrow.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Chip
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.Parsing
import app.notomorrow.util.S

// The "You" editors — `Features/Settings/SettingsYouEditors.swift`.

/** Name: one field, focused on appear, committed when the editor goes away. */
@Composable
fun NameEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = state.profile?.name.orEmpty()
    var draft by remember(name) { mutableStateOf(name) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val latest by rememberUpdatedState(draft)
    val current by rememberUpdatedState(name)

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // `.onDisappear(perform: commit)` — a blank or unchanged draft is dropped.
    DisposableEffect(Unit) {
        onDispose {
            val trimmed = latest.trim()
            if (trimmed.isNotEmpty() && trimmed != current) model.setName(trimmed)
        }
    }

    StEditorScaffold(stringResource(S.common_name), onBack, modifier) {
        StLabeled(stringResource(S.common_name)) {
            StTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = stringResource(S.onboarding_you_namePlaceholder),
                modifier = Modifier.focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                // `.onSubmit(commit)` — write the name and resign first responder. The user
                // stays on the Name screen; `onDispose` then finds nothing left to commit.
                keyboardActions = KeyboardActions(
                    onDone = {
                        val trimmed = draft.trim()
                        if (trimmed.isNotEmpty() && trimmed != name) model.setName(trimmed)
                        focus.clearFocus()
                    },
                ),
            )
        }
    }
}

/**
 * Body weight in the profile's unit. Saving updates the profile **and** logs today's
 * `BodyWeightEntry`, then pops.
 */
@Composable
fun BodyWeightEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = state.units
    val stored = state.profile?.bodyWeightKg
    var draft by remember(stored, unit) {
        mutableStateOf(stored?.let { Fmt.weight(it, unit, withUnit = false) } ?: "")
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val enteredKg = Parsing.positive(draft)?.let { if (unit == WeightUnit.Kg) it else it / Fmt.LB_PER_KG }

    StEditorScaffold(stringResource(S.common_bodyWeight), onBack, modifier) {
        StLabeled(
            label = stringResource(S.common_bodyWeight),
            footnote = stringResource(S.settings_bodyWeight_footnote),
        ) {
            StTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = stringResource(S.settings_bodyWeight_placeholder),
                modifier = Modifier.focusRequester(focusRequester),
                textStyle = NT.Fonts.body.tabular(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                trailing = {
                    NtText(
                        text = stringResource(if (unit == WeightUnit.Kg) S.unit_kg else S.unit_lb),
                        style = NT.Fonts.body,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                },
            )
        }

        PrimaryButton(title = stringResource(S.common_save), enabled = enteredKg != null) {
            enteredKg?.let { model.saveBodyWeight(it) }
            onBack()
        }
    }
}

/** kcal / protein / carbs / fat fields plus "Suggest" from `TargetCalculator`. */
@Composable
fun DailyTargetEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    var kcal by remember(profile?.calorieGoal) { mutableStateOf((profile?.calorieGoal ?: 0).toString()) }
    var protein by remember(profile?.proteinGoalG) { mutableStateOf((profile?.proteinGoalG ?: 0).toString()) }
    var carbs by remember(profile?.carbsGoalG) { mutableStateOf((profile?.carbsGoalG ?: 0).toString()) }
    var fat by remember(profile?.fatGoalG) { mutableStateOf((profile?.fatGoalG ?: 0).toString()) }
    val focus = LocalFocusManager.current

    val parsed = listOf(kcal, protein, carbs, fat).map(Parsing::int)
    val isValid = parsed.none { it == null }

    val gramsUnit = stringResource(S.unit_g)
    val kcalUnit = stringResource(S.unit_kcal)

    StEditorScaffold(stringResource(S.settings_dailyTarget), onBack, modifier) {
        StLabeled(stringResource(S.settings_target_kcal)) {
            StNumberField(stringResource(S.settings_target_kcal), kcal, kcalUnit) { kcal = it }
        }

        StLabeled(stringResource(S.settings_target_macros)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StNumberField(stringResource(S.macro_protein), protein, gramsUnit) { protein = it }
                StNumberField(stringResource(S.macro_carbs), carbs, gramsUnit) { carbs = it }
                StNumberField(stringResource(S.macro_fat), fat, gramsUnit) { fat = it }
            }
        }

        StLabeled(stringResource(S.common_goal)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrainingGoal.entries.forEach { goal ->
                    Chip(
                        title = stringResource(NtKeys.goal(goal)),
                        selected = state.goal == goal,
                        onClick = { model.setGoal(goal) },
                    )
                }
            }
        }

        // `.stFootnote` only — the Suggest button carries no eyebrow.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                title = stringResource(S.settings_target_suggest),
                height = NT.Size.cardButton,
            ) {
                val t = model.suggestTargets()
                kcal = t.kcal.toString()
                protein = t.proteinG.toString()
                carbs = t.carbsG.toString()
                fat = t.fatG.toString()
                focus.clearFocus()
            }
            NtText(
                text = stringResource(S.settings_target_suggestFootnote),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }

        PrimaryButton(title = stringResource(S.common_save), enabled = isValid) {
            val values = parsed.filterNotNull()
            if (values.size == parsed.size) {
                model.saveTargets(values[0], values[1], values[2], values[3])
                onBack()
            }
        }
    }
}

/** One "label … value unit" field row (`DailyTargetEditor.field`). */
@Composable
private fun StNumberField(
    label: String,
    value: String,
    unit: String,
    onValueChange: (String) -> Unit,
) {
    StTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "",
        textStyle = NT.Fonts.body.tabular().copy(textAlign = TextAlign.End),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        leading = {
            NtText(label, style = NT.Fonts.body, color = NT.Colors.ink, maxLines = 1)
        },
        trailing = {
            NtText(unit, style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
        },
    )
}
