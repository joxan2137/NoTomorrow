package app.notomorrow.feature.workout

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.NtStrings

/**
 * The workout in progress — 1:1 port of `ActiveWorkoutView.swift`.
 *
 * Hosted by the **root** NavHost (`workout/active`) so it opens from any tab, exactly as iOS
 * mounts the `fullScreenCover` on the tab shell. It pops itself ([onClose]) when there is no
 * active workout, and swaps `WorkoutDoneScreen` in **in place** rather than pushing a
 * destination.
 */
@Composable
fun ActiveWorkoutScreen(onClose: () -> Unit) {
    val container = LocalAppContainer.current
    val appState = container.appState
    val model = ntViewModel { c ->
        ActiveWorkoutViewModel(
            workoutDao = c.db.workoutDao(),
            recordService = c.recordService,
            attendanceService = c.attendanceService,
            restTimer = c.restTimer,
            session = c.workoutSession,
            appPrefs = c.appPrefs,
            strings = NtStrings.from(c.app),
        )
    }
    val state by model.state.collectAsStateWithLifecycle()
    val rest by model.restState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current

    // `@FocusState private var focus: SetField?` — one shared focus for the whole set table.
    val focus = remember { SetFieldFocus() }
    val now by rememberSecondTicker()
    val restRunning = rest.isRunning(now)

    var showsFinishDialog by remember { mutableStateOf(false) }
    var showsPicker by remember { mutableStateOf(false) }
    var showsRestSheet by remember { mutableStateOf(false) }

    // No workout is running: close the cover rather than render an empty table.
    LaunchedEffect(state.missing) { if (state.missing) onClose() }

    // A tapped rest-timer notification (`AppState.Route.RestTimer`) opens the sheet here.
    val pendingRest by appState.showsRestTimer.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRest) {
        if (pendingRest) {
            showsRestSheet = true
            appState.showsRestTimer.value = false
        }
    }

    RestExpiryWatcher(state = rest, onElapsed = model::finishRestIfElapsed)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NT.Colors.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
    ) {
        AnimatedContent(
            targetState = state.showsDone,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(300, easing = NT.Ease.inOut)) { it } +
                        fadeIn(tween(300, easing = NT.Ease.inOut))) togetherWith
                        fadeOut(tween(300, easing = NT.Ease.inOut))
                } else {
                    // `.move(edge: .trailing)` is symmetric: the summary slides right on the way
                    // out while the table fades back in.
                    fadeIn(tween(250, easing = NT.Ease.inOut)) togetherWith
                        (slideOutHorizontally(tween(250, easing = NT.Ease.inOut)) { it } +
                            fadeOut(tween(250, easing = NT.Ease.inOut)))
                }
            },
            label = "ActiveWorkoutDone",
        ) { showsDone ->
            if (showsDone) {
                WorkoutDoneScreen(
                    workoutId = state.workoutId,
                    // `commitFinish` releases the session, and `RootScreen` pops the destination
                    // off the back of that flag — popping here as well would take `main` with it.
                    onDone = model::commitFinish,
                    endedAt = state.finishedAt,
                    onEditSets = model::reopen,
                )
            } else if (!state.loading) {
                WorkoutTable(
                    state = state,
                    restRunning = restRunning,
                    focus = focus,
                    onFinish = {
                        focusManager.clearFocus()
                        showsFinishDialog = true
                    },
                    onAddExercise = { showsPicker = true },
                    onToggleExpanded = model::toggleExpanded,
                    onRemove = model::removeExercise,
                    onAddSet = model::addSet,
                    onKind = model::setKind,
                    onWeight = model::setWeight,
                    onReps = model::setReps,
                    onToggleSet = { row ->
                        if (row.isCompleted) {
                            model.uncomplete(row.id)
                        } else {
                            focusManager.clearFocus()
                            model.complete(row.id)
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    },
                )
            }
        }

        // The rest pill floats over the table, never over the summary.
        AnimatedVisibility(
            visible = restRunning && !state.showsDone,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(PILL_SPRING_OFFSET) { it } + fadeIn(PILL_SPRING_FLOAT),
            exit = slideOutVertically(PILL_SPRING_OFFSET) { it } + fadeOut(PILL_SPRING_FLOAT),
        ) {
            RestPill(
                state = rest,
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 14.dp),
                onTap = { showsRestSheet = true },
                onPlus15 = { model.adjustRest(15) },
                onSkip = model::skipRest,
            )
        }
    }

    if (showsFinishDialog) {
        FinishDialog(
            canDiscard = state.completedSetCount == 0,
            onDismiss = { showsFinishDialog = false },
            onFinish = {
                focusManager.clearFocus()
                model.finish()
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            // Same as Done: `discard` ends the session and `RootScreen` does the popping.
            onDiscard = model::discard,
        )
    }

    if (showsPicker) {
        // The picker appends the chosen exercises to the workout itself (`WorkoutStarter.append`,
        // exactly as `ExercisePickerView` does on iOS); this screen only reloads the ghosts.
        ExercisePickerSheet(
            onDismiss = {
                showsPicker = false
                model.reloadPrevious()
            },
            workoutId = state.workoutId,
            onAdd = { model.reloadPrevious() },
        )
    }

    if (showsRestSheet) {
        RestTimerSheet(
            state = rest,
            workoutName = state.name.ifEmpty { rest.workoutName },
            startedAt = state.startedAt.takeIf { it > 0 },
            endedAt = state.endedAt,
            upNext = state.upNext,
            onAdjust = model::adjustRest,
            onSkip = {
                model.skipRest()
                showsRestSheet = false
            },
            onElapsed = model::finishRestIfElapsed,
            onDismiss = { showsRestSheet = false },
        )
    }
}

/** `spring(response: 0.35, dampingFraction: 0.85)` (`ActiveWorkoutView.swift:76`) — the pill in and out. */
private val PILL_SPRING_FLOAT = NT.Anim.spring085
private val PILL_SPRING_OFFSET =
    spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = 322.3f)
