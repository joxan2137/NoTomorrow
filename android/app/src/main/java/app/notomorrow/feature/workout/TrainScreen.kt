package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.WeightUnit
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.LocalDate

/**
 * Train tab: resume banner, routines with Start, empty-workout ghost button, finished-workout
 * history — 1:1 port of `NoTomorrow/Features/Workout/TrainView.swift`.
 *
 * The library import and routine seeding that iOS runs from `.task` here happen once in
 * `NoTomorrowApp`'s start-up coroutine (`AppContainer.seed()`), so this screen only reads.
 *
 * iOS wraps the history in a `LazyVStack` inside the `ScrollView`; the whole screen is a
 * `LazyColumn` here, so an unbounded history composes only what is on screen (the row totals walk
 * the set graph, exactly as `WorkoutHistoryRow` does on iOS). The status-bar inset sits **outside**
 * the scroll viewport — like every other tab — so the eyebrow and the large title never slide under
 * the status bar.
 */
@Composable
fun TrainScreen() {
    val model = ntViewModel { container -> TrainViewModel(container) }
    val state by model.state.collectAsStateWithLifecycle()
    val defaultWorkoutName = stringResource(S.workout_defaultName)

    // `@State private var selectedWorkout: Workout?` — the id survives a rotation, the graph is
    // re-read from the flow.
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = remember(state.history, selectedWorkoutId) {
        state.history.firstOrNull { it.workout.id == selectedWorkoutId }
    }

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            contentPadding = PaddingValues(
                start = NT.Spacing.screenH,
                end = NT.Spacing.screenH,
                top = 8.dp,
                // `.padding(.bottom, NT.Spacing.section)` plus the space the tab bar takes.
                bottom = NT.Spacing.section + LocalTabBarHeight.current,
            ),
        ) {
            item(key = "header") { TrainHeader() }

            state.active?.let { active ->
                item(key = "resume") {
                    ResumeWorkoutBanner(
                        workout = active,
                        onClick = { model.resume(active.id) },
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }

            item(key = "routines") {
                RoutinesSection(
                    routines = state.routines,
                    onStart = model::start,
                    onStartEmpty = { model.startEmpty(defaultWorkoutName) },
                )
            }

            item(key = "historyHeader") { HistoryHeader(count = state.history.size) }

            if (state.history.isEmpty()) {
                item(key = "historyEmpty") {
                    NtText(
                        text = stringResource(S.dashboard_noSessionsYet),
                        modifier = Modifier.padding(vertical = 12.dp),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                }
            } else {
                items(state.history, key = { it.workout.id }) { workout ->
                    HistoryEntry(
                        workout = workout,
                        unit = state.unit,
                        onSelect = { selectedWorkoutId = workout.workout.id },
                    )
                }
            }
        }
    }

    if (selected != null) {
        WorkoutDetailSheet(
            workout = selected,
            unit = state.unit,
            onDismiss = { selectedWorkoutId = null },
        )
    }
}

/** Eyebrow date over the tab title. */
@Composable
private fun TrainHeader() {
    Column {
        Eyebrow(Fmt.longDay(LocalDate.now()))
        Spacer(Modifier.height(4.dp))
        NtText(
            text = stringResource(S.tab_train),
            style = NT.Fonts.largeTitle,
            color = NT.Colors.ink,
        )
    }
}

/** `workout.routines` + one [RoutineRow] per routine, then the ghost "Start empty workout". */
@Composable
private fun RoutinesSection(
    routines: List<RoutineRowItem>,
    onStart: (String) -> Unit,
    onStartEmpty: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(NT.Spacing.section))
        SectionHeader(title = stringResource(S.workout_routines))
        Spacer(Modifier.height(4.dp))

        if (routines.isEmpty()) {
            NtText(
                text = stringResource(S.workout_noRoutines),
                modifier = Modifier.padding(vertical = 12.dp),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        } else {
            routines.forEach { routine ->
                RoutineRow(routine = routine, onStart = { onStart(routine.id) })
                Hairline()
            }
        }

        Spacer(Modifier.height(16.dp))
        GhostButton(title = stringResource(S.workout_startEmpty), onClick = onStartEmpty)
    }
}

/** `workout.history` with the count on the right. */
@Composable
private fun HistoryHeader(count: Int) {
    Column {
        Spacer(Modifier.height(NT.Spacing.section))
        SectionHeader(
            title = stringResource(S.workout_history),
            // `Text(verbatim: "\(history.count)")` — iOS prints this one unlocalized.
            trailing = if (count == 0) null else count.toString(),
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** One finished workout plus the hairline iOS draws after every row. */
@Composable
private fun HistoryEntry(
    workout: WorkoutWithExercises,
    unit: WeightUnit,
    onSelect: () -> Unit,
) {
    Column {
        WorkoutHistoryRow(workout = workout, unit = unit, onClick = onSelect)
        Hairline()
    }
}
