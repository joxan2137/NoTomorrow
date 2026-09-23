package app.notomorrow.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Avatar
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.di.ntViewModel
import app.notomorrow.feature.bro.CantMakeItSheet
import app.notomorrow.feature.workout.WorkoutDetailPresenter
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtStrings
import app.notomorrow.util.S
import app.notomorrow.util.rememberCurrentDay
import java.time.LocalDate

/**
 * "Today" — the port of `DashboardView` / `DashboardScreen`
 * (`Features/Dashboard/DashboardView.swift`).
 *
 * Owns the day boundary: [rememberCurrentDay] re-reads the calendar day on
 * `ACTION_DATE_CHANGED` and on every resume, and hands it to the view model, which
 * re-subscribes its day-scoped queries. A dashboard left open overnight never shows
 * yesterday's data.
 */
@Composable
fun DashboardScreen() {
    val model = ntViewModel { container ->
        DashboardViewModel(
            profileDao = container.db.profileDao(),
            scheduleDao = container.db.scheduleDao(),
            pairingDao = container.db.broPairingDao(),
            routineDao = container.db.routineDao(),
            attendanceDao = container.db.attendanceDao(),
            mealDao = container.db.mealDao(),
            workoutDao = container.db.workoutDao(),
            exerciseDao = container.db.exerciseDao(),
            attendance = container.attendanceService,
            bro = container.broService,
            routineSeeder = container.routineSeeder,
            session = container.workoutSession,
            appState = container.appState,
            needsSignIn = container.authStore.needsSignIn,
            strings = NtStrings.from(container.app),
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()
    val day = rememberCurrentDay()
    // `@State private var selectedWorkout: Workout?` — "Last session" opens it over Today.
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(day) {
        model.setDay(day)
        model.onAppear()
    }
    // The `.task` analogue: refresh the partner now and every 15 s while on screen.
    LaunchedEffect(Unit) { model.runBroRefreshLoop() }

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 8.dp, bottom = 32.dp + LocalTabBarHeight.current),
            horizontalAlignment = Alignment.Start,
        ) {
            Header(
                day = state.day,
                initial = state.avatarInitial,
                onSettings = model::openSettings,
            )
            WeekStrip(
                days = state.week,
                modifier = Modifier.padding(top = NT.Spacing.section),
            )
            NextSessionCard(
                state = state,
                onConfirm = model::confirm,
                onStart = model::startWorkout,
                onCantMakeIt = model::showCantMakeIt,
                modifier = Modifier.padding(top = NT.Spacing.section),
            )
            FuelSummaryRow(
                totals = state.totals,
                goals = state.goals,
                onClick = model::selectFuelTab,
                modifier = Modifier.padding(top = 26.dp),
            )
            Hairline(Modifier.padding(top = NT.Spacing.section))
            LastSessionRow(
                session = state.lastSession,
                units = state.units,
                onClick = {
                    val last = state.lastSession
                    if (last != null) selectedWorkoutId = last.workoutId else model.selectTrainTab()
                },
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }

    WorkoutDetailPresenter(
        workoutId = selectedWorkoutId,
        unit = state.units,
        host = "today",
        onDismiss = { selectedWorkoutId = null },
    )

    if (state.showsCantMakeIt) {
        // iOS hands `CantMakeItSheet` an `onDone` closure that only its `send()` calls;
        // plain "Never mind" / swipe-down dismissal must not hit the partner backend.
        CantMakeItSheet(
            onDismiss = model::dismissCantMakeIt,
            onSent = model::onCantMakeItSent,
        )
    }
}

/** Eyebrow date + "Today" + the avatar that opens Settings. */
@Composable
private fun Header(day: LocalDate, initial: String, onSettings: () -> Unit) {
    val label = stringResource(S.dashboard_settings)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Swift sets no line limit on either line: a long locale form of the date
            // (or of "Today") wraps rather than truncating.
            Eyebrow(Fmt.longDay(day), maxLines = Int.MAX_VALUE)
            NtText(
                text = stringResource(S.tab_today),
                style = NT.Fonts.largeTitle,
                color = NT.Colors.ink,
            )
        }
        Box(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .size(NT.Size.control)
                .pressScale(onClick = onSettings)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Avatar(initial = initial)
        }
    }
}
