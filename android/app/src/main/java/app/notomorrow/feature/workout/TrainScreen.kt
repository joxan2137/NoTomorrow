package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.app.LocalTabPageVisible
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.FocalCard
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.data.relation.WorkoutWithExercises
import app.notomorrow.model.WeightUnit
import app.notomorrow.di.ntViewModel
import app.notomorrow.service.Days
import app.notomorrow.service.localizedName
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import java.time.LocalDate

/**
 * Train tab: the "Up next" card (the routine Today suggests, with Start), the other routines, the
 * empty-workout ghost button, and finished-workout history grouped by week — 1:1 port of
 * `NoTomorrow/Features/Workout/TrainView.swift`. A workout in progress lives in the mini bar above
 * the tab bar; Start while one runs asks first.
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
    val blocked by model.blockedStart.collectAsStateWithLifecycle()
    val defaultWorkoutName = stringResource(S.workout_defaultName)

    // `@State private var selectedWorkout: Workout?` — the id survives a rotation; the sheet reads
    // the workout itself, so it follows an edit and closes on a delete.
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }

    // `HistoryWeek.grouped(history, date: \.startedAt, now: .now)`.
    val today = LocalDate.now()
    val historyGroups = remember(state.history, today) {
        HistoryWeek.grouped(state.history, today) { Days.date(it.workout.startedAt) }
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

            state.upNext?.let { routine ->
                item(key = "upNext") {
                    UpNextCard(
                        routine = routine,
                        inProgress = state.isWorkoutInProgress,
                        modifier = Modifier.padding(top = NT.Spacing.section),
                        onStart = { model.start(routine.id) },
                        onResume = model::resumeActive,
                    )
                }
            }

            item(key = "routines") {
                RoutinesSection(
                    routines = state.routines,
                    hasRoutines = state.hasRoutines,
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
                historyGroups.forEach { (week, workouts) ->
                    item(key = "week/${week.name}") { HistoryWeekLabel(week) }
                    items(workouts, key = { it.workout.id }) { workout ->
                        HistoryEntry(
                            workout = workout,
                            unit = state.unit,
                            onSelect = { selectedWorkoutId = workout.workout.id },
                        )
                    }
                }
            }
        }
    }

    WorkoutDetailPresenter(
        workoutId = selectedWorkoutId,
        unit = state.unit,
        host = "train",
        onDismiss = { selectedWorkoutId = null },
    )

    // The dialog lives in the activity's overlay while this tab stays composed on every other tab:
    // leaving Train (or the workout covering it) cancels it, the way a confirmation dialog goes with
    // its view on iOS, instead of following the user to Fuel.
    val pageVisible = LocalTabPageVisible.current
    LaunchedEffect(pageVisible) {
        if (!pageVisible) model.dismissBlockedStart()
    }
    if (pageVisible) {
        blocked?.let { start ->
            AlreadyActiveDialog(
                blocked = start,
                onResume = model::resumeActive,
                // The captured start: the sheet clears `blockedStart` before it runs an action.
                onDiscardAndStart = { model.discardActiveAndStart(start) },
                onDismiss = model::dismissBlockedStart,
            )
        }
    }
}

/**
 * `.confirmationDialog("workout.inProgress")` — a Start while another workout runs: Resume,
 * "Discard it and start new" (only while the running workout has no completed sets, the Finish
 * dialog's rule) and the implicit Cancel.
 */
@Composable
private fun AlreadyActiveDialog(
    blocked: BlockedStart,
    onResume: () -> Unit,
    onDiscardAndStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = buildList {
        add(NtAlertAction(title = stringResource(S.dashboard_resumeWorkout), onClick = onResume))
        if (blocked.canDiscard) {
            add(
                NtAlertAction(
                    title = stringResource(S.workout_alreadyActive_discardAndStart),
                    role = NtAlertRole.Destructive,
                    onClick = onDiscardAndStart,
                ),
            )
        }
    }
    NtActionSheet(
        actions = actions,
        cancel = stringResource(S.common_cancel),
        onDismiss = onDismiss,
        title = stringResource(S.workout_inProgress),
        message = stringResource(S.workout_alreadyActive_message_s, blocked.activeName),
    )
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

/**
 * `TrainView.upNextCard` — the screen's one focal card: the eyebrow and the exercise count, the
 * routine's name, one line per exercise with its target sets × reps (hairlines between), and a
 * full-width Start. While a workout is in progress the eyebrow reads `workout.inProgress` and the
 * button is Resume, which brings that workout back full screen (as the mini bar does).
 */
@Composable
private fun UpNextCard(
    routine: UpNextRoutine,
    inProgress: Boolean,
    modifier: Modifier,
    onStart: () -> Unit,
    onResume: () -> Unit,
) {
    FocalCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(
                text = stringResource(if (inProgress) S.workout_inProgress else S.train_upNext),
                modifier = Modifier.weight(1f),
                color = NT.Colors.ember,
            )
            // `Spacer(minLength: 8)`.
            Spacer(Modifier.width(8.dp))
            TabularText(
                text = stringResource(NtKeys.exerciseCount(routine.items.size), routine.items.size),
                style = NT.Fonts.caption,
                color = NT.Colors.ink2,
            )
        }
        NtText(
            text = routine.name,
            modifier = Modifier.padding(top = 8.dp),
            style = NT.Fonts.title1,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            routine.items.forEachIndexed { index, item ->
                if (index > 0) Hairline()
                UpNextLine(item)
            }
        }
        PrimaryButton(
            title = stringResource(if (inProgress) S.dashboard_resumeWorkout else S.workout_start),
            modifier = Modifier.padding(top = 16.dp),
            height = NT.Size.cardButton,
            onClick = if (inProgress) onResume else onStart,
        )
    }
}

/** `upNextLine(_:)` — "Bench Press ······ 3 × 8". */
@Composable
private fun UpNextLine(item: UpNextItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        // `HStack(spacing: 12)` around a `Spacer(minLength: 8)`.
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(
            text = item.exercise.localizedName(),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        TabularText(
            text = "${item.targetSets} ${Fmt.TIMES} ${item.targetReps}",
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}

/**
 * `workout.routines` + one [RoutineRow] per routine but the up-next one, then the ghost "Start
 * empty workout". `workout.noRoutines` only when there are no routines at all ([hasRoutines]).
 */
@Composable
private fun RoutinesSection(
    routines: List<RoutineRowItem>,
    hasRoutines: Boolean,
    onStart: (String) -> Unit,
    onStartEmpty: () -> Unit,
) {
    Column {
        Spacer(Modifier.height(NT.Spacing.section))
        SectionHeader(title = stringResource(S.workout_routines))
        Spacer(Modifier.height(4.dp))

        if (!hasRoutines) {
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

/** The small eyebrow over one week of history: this week, last week, earlier. */
@Composable
private fun HistoryWeekLabel(week: HistoryWeek) {
    val title = when (week) {
        HistoryWeek.ThisWeek -> S.workout_history_thisWeek
        HistoryWeek.LastWeek -> S.workout_history_lastWeek
        HistoryWeek.Earlier -> S.workout_history_earlier
    }
    Eyebrow(stringResource(title), modifier = Modifier.padding(top = 12.dp))
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
