package app.notomorrow.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant

/**
 * Finished workout: name · date · duration · volume, PR count in ember, chevron. Tap opens the
 * detail sheet (`NoTomorrow/Features/Workout/WorkoutHistoryRow.swift`).
 */
@Composable
fun WorkoutHistoryRow(
    workout: WorkoutWithExercises,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unit: WeightUnit = WeightUnit.Kg,
) {
    val strings = rememberNtStrings()
    val row = workout.workout
    val meta = listOf(
        Fmt.dayMonth(Instant.ofEpochMilli(row.startedAt)),
        Fmt.duration(workoutDuration(row.startedAt, row.endedAt), strings),
        Fmt.volume(workout.totalVolumeKg, unit),
    ).joinToString(" · ")
    val prCount = workout.prCount

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(onClick = onClick)
            .heightIn(min = 64.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NtText(
                text = workoutDisplayName(row.name),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            TabularText(
                text = meta,
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        if (prCount > 0) {
            TabularText(
                text = stringResource(S.dashboard_prs, prCount),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.ember,
            )
        }
        NtIcon(icon = NtIcons.ChevronRight, size = sfIconSize(13f), tint = NT.Colors.ink3)
    }
}

/** `Workout.duration` — seconds between the start and the end, or now while it runs. */
internal fun workoutDuration(
    startedAt: Long,
    endedAt: Long?,
    now: Long = System.currentTimeMillis(),
): Double = ((endedAt ?: now) - startedAt).coerceAtLeast(0L) / 1000.0
