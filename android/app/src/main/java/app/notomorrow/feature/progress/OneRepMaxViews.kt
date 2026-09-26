package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntMediumDetent
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.tabular
import app.notomorrow.feature.workout.SetInput
import app.notomorrow.feature.workout.WarmupPlan
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * `PercentageTable` (`OneRepMaxViews.swift`): "% · weight · reps" rows for an e1RM already in
 * [unit] ([OneRepMax]) — the lift page's Percentages and the 1RM calculator.
 */
@Composable
fun PercentageTable(e1RM: Double, unit: WeightUnit, modifier: Modifier = Modifier) {
    val repsLabel = stringResource(S.workout_reps)
    Column(modifier.fillMaxWidth()) {
        Row(
            // Each row below names its own columns for TalkBack.
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clearAndSetSemantics {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColumnHeader(stringResource(S.onerm_percent), Modifier.width(PERCENT_COLUMN), TextAlign.Start)
            ColumnHeader(stringResource(S.onerm_weight), Modifier.weight(1f), TextAlign.Start)
            ColumnHeader(stringResource(S.workout_reps), Modifier.width(PERCENT_COLUMN), TextAlign.End)
        }
        OneRepMax.rows(e1RM, WarmupPlan.increment(unit)).forEachIndexed { index, row ->
            if (index > 0) Hairline()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clearAndSetSemantics {
                    contentDescription = Fmt.percent(row.percent / 100.0) + ", " + Fmt.plate(row.weight, unit)
                    stateDescription = "$repsLabel ${row.reps}"
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabularText(
                    text = Fmt.percent(row.percent / 100.0),
                    modifier = Modifier.width(PERCENT_COLUMN),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                )
                TabularText(
                    text = Fmt.plate(row.weight, unit),
                    modifier = Modifier.weight(1f),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink,
                )
                NtText(
                    text = row.reps.toString(),
                    modifier = Modifier.width(PERCENT_COLUMN),
                    style = NT.Fonts.subheadline.tabular(),
                    color = NT.Colors.ink2,
                    maxLines = 1,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The lift page's Percentages (`percentages(_:)` in `ExerciseProgressView.swift`): 100 … 50 % of
 * the best e1RM, rounded to plates, with the reps each allows; "1RM calculator" beside the title.
 */
@Composable
fun PercentagesSection(state: ExerciseProgressUiState, modifier: Modifier = Modifier) {
    var showsCalculator by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NtText(
                text = stringResource(S.onerm_percentages),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(onClick = { showsCalculator = true }),
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.onerm_calculator),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
        PercentageTable(
            e1RM = SetInput.display(state.current, state.unit),
            unit = state.unit,
            modifier = Modifier.padding(top = 4.dp),
        )
        NtText(
            text = stringResource(S.onerm_footnote),
            modifier = Modifier.padding(top = 8.dp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink3,
        )
    }
    if (showsCalculator) {
        OneRepMaxCalculatorSheet(unit = state.unit, onDismiss = { showsCalculator = false })
    }
}

/**
 * `OneRepMaxCalculatorSheet`: a set's weight × reps → the Epley e1RM and its percentage table.
 * Reached from a lift's Percentages and from the plate calculator. Weights are typed and shown in
 * the user's unit; [initialWeight] (in [unit]) prefills the weight.
 */
@Composable
fun OneRepMaxCalculatorSheet(
    unit: WeightUnit,
    onDismiss: () -> Unit,
    initialWeight: Double? = null,
) {
    var weightText by rememberSaveable {
        mutableStateOf(initialWeight?.takeIf { it > 0 }?.let { SetInput.text(SetInput.kg(it, unit), unit) }.orEmpty())
    }
    var repsText by rememberSaveable { mutableStateOf("") }
    val weight = minOf(SetInput.number(weightText), 10_000.0)
    val reps = SetInput.reps(repsText)
    val e1RM = OneRepMax.estimate(weight, reps)
    val weightFocus = remember { FocusRequester() }
    val repsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { if (weightText.isEmpty()) weightFocus.requestFocus() else repsFocus.requestFocus() }
    }

    NtSheet(
        onDismiss = onDismiss,
        showsHandle = true,
        containerColor = NT.Colors.ground,
        minHeight = ntMediumDetent(),
        skipPartiallyExpanded = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(stringResource(S.onerm_calculator), style = NT.Fonts.title2, color = NT.Colors.ink, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                NtText(stringResource(S.common_done), style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = NT.Spacing.section),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                NumberField(
                    label = stringResource(S.onerm_weight),
                    value = weightText,
                    onValueChange = { weightText = it },
                    suffix = unit.raw,
                    keyboardType = KeyboardType.Decimal,
                    focus = weightFocus,
                    modifier = Modifier.weight(1f),
                )
                NtText(
                    text = Fmt.TIMES,
                    modifier = Modifier.padding(bottom = 14.dp),
                    style = NT.Fonts.title2,
                    color = NT.Colors.ink2,
                )
                NumberField(
                    label = stringResource(S.workout_reps),
                    value = repsText,
                    onValueChange = { repsText = it },
                    suffix = null,
                    keyboardType = KeyboardType.Number,
                    focus = repsFocus,
                    modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(stringResource(S.onerm_estimated))
                if (e1RM > 0) {
                    TabularText(
                        text = Fmt.plate(OneRepMax.round(e1RM, 0.1), unit),
                        style = NT.Fonts.display(44),
                        color = NT.Colors.ink,
                    )
                } else {
                    NtText(stringResource(S.onerm_hint), style = NT.Fonts.subheadline, color = NT.Colors.ink2)
                }
            }
            if (e1RM > 0) {
                PercentageTable(e1RM = e1RM, unit = unit, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String?,
    keyboardType: KeyboardType,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label, modifier = Modifier.clearAndSetSemantics {})
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(NT.Colors.surface, NtShapes.field)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    NtText("0", style = NT.Fonts.title2.tabular(), color = NT.Colors.ink3, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = label },
                    textStyle = NT.Fonts.title2.tabular().copy(color = NT.Colors.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(NT.Colors.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                )
            }
            if (suffix != null) {
                NtText(suffix, style = NT.Fonts.subheadline, color = NT.Colors.ink2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColumnHeader(text: String, modifier: Modifier, align: TextAlign) {
    NtText(
        text = text,
        modifier = modifier,
        style = NT.Fonts.caption,
        color = NT.Colors.ink2,
        maxLines = 1,
        textAlign = align,
    )
}

/** `.frame(width: 64)` — the % and Reps columns. */
private val PERCENT_COLUMN: Dp = 64.dp
