package app.notomorrow.feature.workout

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.relation.WorkoutExerciseWithSets
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.StatTile
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant

/**
 * Read-only detail of a finished workout: tiles, then every exercise with its completed sets and
 * record badges (`NoTomorrow/Features/Workout/WorkoutDetailSheet.swift`).
 *
 * The sheet shows the drag indicator and paints itself `ground`, matching
 * `.presentationBackground(NT.Colors.ground)` + `.presentationDragIndicator(.visible)`.
 */
@Composable
fun WorkoutDetailSheet(
    workout: WorkoutWithExercises,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    unit: WeightUnit = WeightUnit.Kg,
) {
    NtSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        showsHandle = true,
        containerColor = NT.Colors.ground,
    ) {
        WorkoutDetailHeader(workout, onDone = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = NT.Spacing.section),
        ) {
            WorkoutDetailTiles(workout)

            if (workout.prCount > 0) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtIcon(
                        icon = NtIcons.TrophyFill,
                        size = sfIconSize(12f),
                        tint = NT.Colors.ember,
                    )
                    TabularText(
                        text = stringResource(S.dashboard_prs, workout.prCount),
                        style = NT.Fonts.footnoteBold,
                        color = NT.Colors.ember,
                    )
                }
            }

            Spacer(Modifier.height(NT.Spacing.section))
            SectionHeader(title = stringResource(S.workout_exercises))
            Spacer(Modifier.height(4.dp))

            if (workout.exercises.isEmpty()) {
                NtText(
                    text = stringResource(S.workout_noSetsLogged),
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink2,
                )
            } else {
                workout.sortedExercises.forEach { item ->
                    WorkoutDetailExercise(item = item, unit = unit)
                    Hairline()
                }
            }
        }
    }
}

/** Workout name + a plain "Done", then the long day and the start time. */
@Composable
private fun WorkoutDetailHeader(workout: WorkoutWithExercises, onDone: () -> Unit) {
    val startedAt = Instant.ofEpochMilli(workout.workout.startedAt)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NT.Spacing.screenH)
            .padding(top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // `HStack(alignment: .firstTextBaseline)` — the title2 name and the body "Done" sit on one
        // baseline, not on one centre line.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NtText(
                text = workout.workout.name,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                style = NT.Fonts.title2,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .alignByBaseline()
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(onClick = onDone),
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.common_done),
                    style = NT.Fonts.body,
                    color = NT.Colors.ink2,
                )
            }
        }
        NtText(
            text = Fmt.longDay(startedAt) + " · " + Fmt.time(startedAt),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

/** Time / Sets / Volume. */
@Composable
private fun WorkoutDetailTiles(workout: WorkoutWithExercises) {
    val strings = rememberNtStrings()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            label = stringResource(S.workout_time),
            value = Fmt.duration(
                workoutDuration(workout.workout.startedAt, workout.workout.endedAt),
                strings,
            ),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(S.workout_sets),
            value = workout.completedSetCount.toString(),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            label = stringResource(S.workout_volume),
            value = Fmt.volume(workout.totalVolumeKg),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Exercise name + one line per completed set with PR / set-record badges. */
@Composable
private fun WorkoutDetailExercise(item: WorkoutExerciseWithSets, unit: WeightUnit) {
    val completed = item.sortedSets.filter { it.isCompleted }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NtText(
            text = item.exercise?.localizedName().orEmpty(),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        if (completed.isEmpty()) {
            NtText(
                text = stringResource(S.workout_noSetsLogged),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        } else {
            completed.forEach { set -> WorkoutDetailSetLine(set, unit) }
        }
    }
}

/** 32 pt line: the numbered chip, "85 × 7", and the record badge. */
@Composable
private fun WorkoutDetailSetLine(set: SetEntryEntity, unit: WeightUnit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(24.dp).background(NT.Colors.surface2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TabularText(
                text = setLabel(set.order, set.kind),
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
            )
        }
        TabularText(
            text = Fmt.set(set.weightKg, set.reps, unit),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
        )
        Spacer(Modifier.weight(1f))
        when {
            set.isPR -> Badge(text = stringResource(S.workout_pr))
            set.isSetRecord -> Badge(text = stringResource(S.workout_setRecord), color = NT.Colors.ink2)
        }
    }
}

/**
 * Set number for normal sets; W / D / F glyphs for warm-up, drop and failure — hardcoded on iOS
 * (`WorkoutDetailSheet.swift:126`), so they stay unlocalized here too.
 */
internal fun setLabel(order: Int, kind: SetKind): String = when (kind) {
    SetKind.Normal -> (order + 1).toString()
    SetKind.Warmup -> "W"
    SetKind.Drop -> "D"
    SetKind.Failure -> "F"
}
