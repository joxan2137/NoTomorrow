package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The "Records tonight" list of `WorkoutDoneScreen` — `recordsSection` plus `WorkoutRecordRow` from
 * `NoTomorrow/Features/Workout/WorkoutDoneView.swift`.
 */
@Composable
fun WorkoutDoneRecords(
    state: WorkoutDoneUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        NtText(
            text = stringResource(S.workout_done_records),
            modifier = Modifier.padding(bottom = 4.dp),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
        )
        if (state.records.isEmpty()) {
            NtText(
                text = stringResource(S.workout_done_noRecords),
                modifier = Modifier.padding(vertical = 12.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        } else {
            state.records.forEachIndexed { index, record ->
                WorkoutRecordRow(record = record, unit = state.unit)
                if (index < state.records.lastIndex) Hairline()
            }
        }
    }
}

/**
 * 60 pt record row: trophy (PR, ember) or medal (set record, grey), "Exercise · 85 × 7", detail
 * line, trailing tag.
 *
 * Weights are shown in [unit] (`WorkoutRecordRow.unit`).
 */
@Composable
fun WorkoutRecordRow(
    record: WorkoutRecordItem,
    modifier: Modifier = Modifier,
    unit: WeightUnit = WeightUnit.Kg,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (record.isPR) NT.Colors.ember.copy(alpha = 0.14f) else NT.Colors.surface2,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(
                icon = if (record.isPR) NtIcons.Trophy else NtIcons.Medal,
                size = sfIconSize(15f),
                tint = if (record.isPR) NT.Colors.ember else NT.Colors.ink,
            )
        }
        // Weighted, so a long name or detail (Polish) ellipsizes instead of pushing the tag off the row.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TabularText(
                text = record.exercise?.localizedName().orEmpty() +
                    " · " + Fmt.set(record.weightKg, record.reps, unit),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
            )
            TabularText(
                text = recordDetail(record, unit),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
        if (record.isPR) {
            Eyebrow(stringResource(S.workout_pr), color = NT.Colors.ember)
        } else {
            Eyebrow(stringResource(S.workout_set))
        }
    }
}

/** "New best set · e1RM 104 kg, up from 96" or "Set record · most reps at 85 kg". */
@Composable
private fun recordDetail(record: WorkoutRecordItem, unit: WeightUnit): String = if (record.isPR) {
    stringResource(
        S.workout_done_prDetail_s_s,
        Fmt.weight(record.e1RM, unit),
        Fmt.weight(record.bestBeforeE1RM, unit, withUnit = false),
    )
} else {
    stringResource(S.workout_done_setRecordDetail_s, Fmt.weight(record.weightKg, unit))
}
