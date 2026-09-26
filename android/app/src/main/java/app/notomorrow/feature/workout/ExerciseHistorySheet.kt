package app.notomorrow.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.data.relation.CompletedSetRow
import app.notomorrow.data.relation.ExerciseNoteRow
import app.notomorrow.designsystem.Badge
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntMediumDetent
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings
import java.time.Instant
import java.time.ZoneId

/**
 * One past session of an exercise in the history sheet (`ExerciseHistorySheet.Session`): the
 * workout it was done in, its completed sets in order and the note left on the exercise that day.
 */
internal data class ExerciseHistorySession(
    /** The `workout_exercise` row. */
    val id: Long,
    val startedAt: Long,
    val workoutName: String,
    val sets: List<CompletedSetRow>,
    val note: String,
) {
    /** The best Epley estimate of the session, warm-ups left out; 0 when none. */
    val bestE1RM: Double
        get() = sets.filter { it.kind != SetKind.Warmup }.maxOfOrNull { it.estimatedOneRepMax } ?: 0.0
}

/**
 * `ExerciseHistorySheet.sessions(of:excluding:)` — the finished sessions of one exercise with at
 * least one completed set, newest first; the workout [excludingWorkoutId] (the one in progress) is
 * left out. [rows] are the exercise's completed sets, [notes] its entries' notes.
 */
internal fun exerciseHistorySessions(
    rows: List<CompletedSetRow>,
    notes: List<ExerciseNoteRow>,
    excludingWorkoutId: String?,
): List<ExerciseHistorySession> {
    val noteByEntry = notes.associate { it.workoutExerciseId to it.notes }
    return rows
        .filter { it.workoutEndedAt != null && it.workoutId != excludingWorkoutId }
        .groupBy { it.workoutExerciseId }
        .map { (entryId, sets) ->
            val first = sets.first()
            ExerciseHistorySession(
                id = entryId,
                startedAt = first.workoutStartedAt,
                workoutName = first.workoutName,
                sets = sets.sortedBy { it.setOrder },
                note = noteByEntry[entryId].orEmpty(),
            )
        }
        .sortedByDescending { it.startedAt }
}

/**
 * Past sessions of one exercise, newest first — port of `ExerciseHistorySheet.swift` (exercise
 * menu › History in the active workout): the day and workout, every completed set with its RPE and
 * PR badge, the session's best e1RM, and the exercise note left that day. [excludingWorkoutId] is
 * the workout in progress, left out of the list.
 */
@Composable
fun ExerciseHistorySheet(
    exerciseId: String,
    exerciseName: String,
    unit: WeightUnit,
    excludingWorkoutId: String?,
    onDismiss: () -> Unit,
) {
    val dao = LocalAppContainer.current.db.workoutDao()
    val sessions by produceState<List<ExerciseHistorySession>?>(null, exerciseId, excludingWorkoutId) {
        value = exerciseHistorySessions(
            rows = dao.completedSetsForExercise(exerciseId),
            notes = dao.exerciseNotes(exerciseId),
            excludingWorkoutId = excludingWorkoutId,
        )
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(stringResource(S.history_title))
                NtText(exerciseName, style = NT.Fonts.title2, color = NT.Colors.ink, maxLines = 1)
            }
            Box(
                modifier = Modifier
                    .heightIn(min = NT.Size.control)
                    .ntPlainClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                NtText(stringResource(S.common_done), style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
            }
        }
        val list = sessions ?: return@NtSheet
        if (list.isEmpty()) {
            NtText(
                text = stringResource(S.history_empty),
                modifier = Modifier.padding(horizontal = NT.Spacing.screenH).padding(top = 12.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = NT.Spacing.screenH,
                    end = NT.Spacing.screenH,
                    bottom = NT.Spacing.section,
                ),
            ) {
                items(list, key = { it.id }) { session ->
                    SessionBlock(session, unit)
                    Hairline()
                }
            }
        }
    }
}

/** "Monday · Push" and the best e1RM, one line per set, then the note. */
@Composable
private fun SessionBlock(session: ExerciseHistorySession, unit: WeightUnit) {
    val strings = rememberNtStrings()
    val day = Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NtText(
                text = Fmt.relativeDay(day, strings) + " · " + session.workoutName,
                modifier = Modifier.weight(1f),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            if (session.bestE1RM > 0) {
                Spacer(Modifier.width(8.dp))
                TabularText(
                    text = "e1RM " + Fmt.weight(session.bestE1RM, unit),
                    style = NT.Fonts.caption,
                    color = NT.Colors.ink2,
                )
            }
        }
        session.sets.forEach { set ->
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtText(
                    text = if (set.kind == SetKind.Normal) "·" else kindLetter(set.kind),
                    modifier = Modifier.width(14.dp),
                    style = NT.Fonts.caption,
                    color = NT.Colors.ink3,
                    textAlign = TextAlign.Center,
                )
                TabularText(
                    text = Fmt.set(set.weightKg, set.reps, unit),
                    style = NT.Fonts.subheadline,
                    color = NT.Colors.ink,
                )
                set.rpe?.let { rpe ->
                    val spoken = Rpe.spoken(rpe)
                    NtText(
                        "@" + Rpe.label(rpe),
                        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                        style = NT.Fonts.caption,
                        color = NT.Colors.ember,
                    )
                }
                if (set.isPR) Badge(text = stringResource(S.workout_pr))
            }
        }
        if (session.note.isNotEmpty()) {
            NtText(session.note, style = NT.Fonts.footnote, color = NT.Colors.ink2)
        }
    }
}
