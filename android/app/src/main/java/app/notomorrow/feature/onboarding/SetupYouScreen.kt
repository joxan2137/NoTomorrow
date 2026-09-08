package app.notomorrow.feature.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtSegmentedLarge
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.TrainingGoal
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.TargetCalculator
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/** Step "You": name, body weight + unit, goal chips, suggested daily target. */
@Composable
fun SetupYouScreen(
    state: OnboardingUiState,
    onName: (String) -> Unit,
    onWeight: (String) -> Unit,
    onUnit: (WeightUnit) -> Unit,
    onGoal: (TrainingGoal) -> Unit,
    onEditTarget: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val weightFocus = remember { FocusRequester() }
    // `NtSegmented`'s label is a plain lambda, so the two unit names are resolved up here.
    val kgLabel = stringResource(S.unit_kg)
    val lbLabel = stringResource(S.unit_lb)

    ObStepScaffold(
        index = state.stepIndex ?: 0,
        count = state.stepCount,
        onBack = onBack,
        modifier = modifier,
        footer = {
            PrimaryButton(
                title = stringResource(S.common_continue),
                enabled = state.canContinueFromYou,
            ) {
                focus.clearFocus()
                onContinue()
            }
        },
    ) {
        ObStepTitle(
            title = stringResource(S.onboarding_you_title),
            subtitle = stringResource(S.onboarding_you_subtitle),
        )

        ObLabeled(
            label = stringResource(S.common_name),
            modifier = Modifier.padding(horizontal = NT.Spacing.screenH).padding(top = 28.dp),
        ) {
            ObTextField(
                value = state.name,
                onValueChange = onName,
                placeholder = stringResource(S.onboarding_you_namePlaceholder),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { weightFocus.requestFocus() }),
            )
        }

        ObLabeled(
            label = stringResource(S.common_bodyWeight),
            modifier = Modifier.padding(horizontal = NT.Spacing.screenH).padding(top = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ObTextField(
                    value = state.weightText,
                    onValueChange = onWeight,
                    placeholder = Fmt.weight(82.4, WeightUnit.Kg, withUnit = false),
                    modifier = Modifier.weight(1f).focusRequester(weightFocus),
                    textStyle = NT.Fonts.body.tabular(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    trailing = {
                        NtText(
                            text = if (state.unit == WeightUnit.Kg) kgLabel else lbLabel,
                            style = NT.Fonts.subheadline,
                            color = NT.Colors.ink2,
                            maxLines = 1,
                        )
                    },
                )
                NtSegmentedLarge(
                    options = ObWeightUnits,
                    selected = state.unit,
                    onSelect = onUnit,
                    width = 120.dp,
                    label = { unit -> if (unit == WeightUnit.Kg) kgLabel else lbLabel },
                )
            }
        }

        Column(
            modifier = Modifier.padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Eyebrow(
                text = stringResource(S.common_goal),
                modifier = Modifier.padding(horizontal = NT.Spacing.screenH),
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrainingGoal.entries.forEach { goal ->
                    Chip(
                        title = stringResource(NtKeys.goal(goal)),
                        selected = state.goal == goal,
                        onClick = { onGoal(goal) },
                    )
                }
            }
        }

        ObTargetCard(
            state = state,
            onEdit = onEditTarget,
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = NT.Spacing.section),
        )
    }
}

/** The one card on the screen: suggested kcal + protein line + formula note. */
@Composable
private fun ObTargetCard(
    state: OnboardingUiState,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets = state.targets
    // `.animation(.easeOut(duration: 0.2), value: t)` on the whole card (`SetupYouView.swift:155`):
    // every number eases to its new value when the goal chip or the body weight changes.
    val ease = tween<Float>(200, easing = NT.Ease.out)
    val kcal by animateIntAsState(
        targetValue = targets.kcal,
        animationSpec = tween(200, easing = NT.Ease.out),
        label = "obTargetKcal",
    )
    val proteinG by animateFloatAsState(
        targetValue = targets.proteinG.toFloat(),
        animationSpec = ease,
        label = "obTargetProtein",
    )
    val perKg by animateFloatAsState(
        targetValue = proteinPerKg(state).toFloat(),
        animationSpec = ease,
        label = "obTargetPerKg",
    )
    val formulaNote = if (state.customTargets != null) {
        stringResource(S.onboarding_you_custom)
    } else {
        stringResource(
            S.onboarding_you_formula_s,
            Fmt.signedInteger(TargetCalculator.kcalAdjustment(state.goal)),
        )
    }
    NTCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Eyebrow(text = stringResource(S.target_daily), color = NT.Colors.ember)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .offset(x = 4.dp)
                        // `.frame(minWidth: 44, minHeight: 44, alignment: .trailing)` on a child of
                        // a 20 pt row: SwiftUI lets the label overflow its parent, so the hit area
                        // really is 44 pt. `requiredSizeIn` is the Compose equivalent —
                        // `defaultMinSize` would be coerced straight back to the row's 20 dp.
                        .requiredSizeIn(minWidth = NT.Size.control, minHeight = NT.Size.control)
                        .ntPlainClickable(onClick = onEdit),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    NtText(
                        text = stringResource(S.common_edit),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                    )
                }
            }

            Row {
                // `.contentTransition(.numericText())` is not reproduced glyph-by-glyph: the
                // display face has no tabular figures (research §5.4) and an `AnimatedContent`
                // wrapper would break the first-text-baseline alignment this row depends on. The
                // card's own `.easeOut(0.2)` is what carries the change, so the value is animated
                // instead of the view.
                TabularText(
                    text = Fmt.kcal(kcal.toDouble(), withUnit = false),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.display(56),
                    color = NT.Colors.ink,
                )
                Spacer(Modifier.width(8.dp))
                NtText(
                    text = stringResource(S.unit_kcal),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.title2,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TabularText(
                    text = stringResource(
                        S.target_proteinPerKg_s_s,
                        Fmt.grams(proteinG.toDouble()),
                        // `perKg.formatted(.number.precision(.fractionLength(1)))` + "g"
                        // (`SetupYouView.swift:126`): the formula's own ratio while the targets
                        // are computed, the edited targets' actual ratio once they are custom.
                        Fmt.fixed(perKg.toDouble(), 1) + Fmt.NBSP + "g",
                    ),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink,
                )
                Spacer(Modifier.widthIn(min = 8.dp).weight(1f))
                AnimatedContent(
                    targetState = formulaNote,
                    modifier = Modifier.alignByBaseline(),
                    transitionSpec = {
                        fadeIn(tween(200, easing = NT.Ease.out)) togetherWith
                            fadeOut(tween(200, easing = NT.Ease.out))
                    },
                    label = "obTargetFormulaNote",
                ) { note ->
                    TabularText(
                        text = note,
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink3,
                    )
                }
            }
        }
    }
}

/**
 * `OBTargetCard.perKgText` (`SetupYouView.swift:158`): the formula's own grams-per-kilogram while
 * the targets are computed, and the edited targets' actual ratio once the user has overridden
 * them. An unset (or zero) body weight falls back to `TargetCalculator.assumedBodyWeightKg`, which
 * also keeps the division safe.
 */
internal fun proteinPerKg(state: OnboardingUiState): Double {
    val weight = state.bodyWeightKg?.takeIf { it > 0 } ?: TargetCalculator.ASSUMED_BODY_WEIGHT_KG
    return if (state.customTargets == null) {
        TargetCalculator.proteinGramsPerKg(state.goal)
    } else {
        state.targets.proteinG / weight
    }
}

/** The two units the segmented control offers. */
internal val ObWeightUnits: List<WeightUnit> = listOf(WeightUnit.Kg, WeightUnit.Lb)
