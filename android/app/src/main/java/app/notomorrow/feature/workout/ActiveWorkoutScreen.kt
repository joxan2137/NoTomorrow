package app.notomorrow.feature.workout

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.S
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.di.LocalAppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The workout in progress — 1:1 port of `ActiveWorkoutView.swift`.
 *
 * A layer of the tab shell (`MainTabScaffold`), over whatever tab is showing, not a destination of
 * its own: the shell hosts [model] (one per workout) so the mini bar reads the same state, and
 * slides this screen up and down. The chevron in the header, and back, collapse it into the mini
 * bar ([onMinimize]); `WorkoutDoneScreen` is swapped in **in place** on Finish.
 *
 * @param scroll the set table's scroll position, hoisted by the shell so a collapse keeps it.
 */
@Composable
fun ActiveWorkoutScreen(
    model: ActiveWorkoutViewModel,
    scroll: ScrollState,
    onMinimize: () -> Unit,
) {
    val session = LocalAppContainer.current.workoutSession
    val state by model.state.collectAsStateWithLifecycle()
    val rest by model.restState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // `@FocusState private var focus: SetField?` — one shared focus for the whole set table.
    val focus = remember { SetFieldFocus() }
    val now by rememberSecondTicker()
    val restRunning = rest.isRunning(now)

    var showsFinishDialog by remember { mutableStateOf(false) }
    var showsPicker by remember { mutableStateOf(false) }
    var showsRestSheet by remember { mutableStateOf(false) }
    // `@State private var plateSet: SetEntry?` — the set the plate calculator is open for.
    var plateSetId by remember { mutableStateOf<Long?>(null) }
    val plateTarget = focusedWeightSet(state, focus.focused)

    // History may have been edited (or the unit changed) while the workout sat in the mini bar.
    LaunchedEffect(model) { model.reloadPrevious() }

    // A rest-notification tap asks for the rest sheet; it opens once this screen has slid up.
    val wantsRestSheet by session.wantsRestSheet.collectAsStateWithLifecycle()
    LaunchedEffect(wantsRestSheet) {
        if (!wantsRestSheet) return@LaunchedEffect
        delay(REST_SHEET_DELAY_MS)
        session.consumeRestSheet()
        if (model.restState.value.isRunning() && !model.state.value.showsDone) showsRestSheet = true
    }

    // Hands the workout to the mini bar; everything already typed is written through.
    val minimize = {
        focusManager.clearFocus()
        onMinimize()
    }

    // Back on the summary is "Edit sets" — the summary slid in over the table. Anywhere else it
    // collapses the workout into the mini bar, the screen following the predictive-back gesture.
    BackHandler(enabled = state.showsDone, onBack = model::reopen)
    // The predictive-back preview (0…1), one per presentation: a completed gesture hands where the
    // finger let go to the collapsed presentation ([released]), so the screen slides down from
    // there. Asking the screen up again — even by tapping the mini bar before the slide-down has
    // finished, this layer still composed — starts at full size in that same frame, so the
    // workout never comes back shrunk and shifted. A collapse by the chevron carries nothing.
    val expanded by session.showsActiveWorkout.collectAsStateWithLifecycle()
    val released = remember { floatArrayOf(0f) }
    var backProgress by remember(expanded) {
        if (expanded) released[0] = 0f
        mutableFloatStateOf(released[0])
    }
    PredictiveBackHandler(enabled = !state.showsDone) { events ->
        try {
            events.collect { backProgress = it.progress }
            released[0] = backProgress
            minimize()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    // Android only: the rest-over alert needs exact alarms (and notifications) to be on time.
    RestAlertPermissionPrompt(restRunning = restRunning && !state.showsDone)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - BACK_SCALE * backProgress
                scaleX = scale
                scaleY = scale
                translationY = BACK_TRAVEL.toPx() * backProgress
            }
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
                    // `commitFinish` releases the session, and the shell slides this layer away.
                    onDone = model::commitFinish,
                    onEditSets = model::reopen,
                )
            } else if (!state.loading && !state.missing) {
                WorkoutTable(
                    state = state,
                    restRunning = restRunning,
                    focus = focus,
                    scroll = scroll,
                    onMinimize = minimize,
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
                            model.complete(row.id) { logged ->
                                if (logged) {
                                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                } else {
                                    // Nothing to log yet: the row stays open and the reps cell asks for a number.
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    focus.request(SetField(row.id, isReps = true), keyboard)
                                }
                            }
                        }
                    },
                    onDeleteSet = { setId ->
                        focusManager.clearFocus()
                        model.removeSet(setId)
                    },
                    onRowAppear = model::prefillFromPrevious,
                    onUseSuggestion = model::useSuggestion,
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
                    // Sits above the Plates accessory while a weight cell has the keyboard.
                    .padding(bottom = if (plateTarget != null) 14.dp + NT.Size.control + 8.dp else 14.dp),
                onTap = { showsRestSheet = true },
                onPlus15 = { model.adjustRest(15) },
                onSkip = model::skipRest,
            )
        }

        // `ToolbarItemGroup(placement: .keyboard)`'s Plates: Compose has no keyboard toolbar, so it
        // floats just above the keyboard (the layer's safe-drawing inset includes the IME) while a
        // weight cell with a weight has the focus.
        AnimatedVisibility(
            visible = plateTarget != null && !state.showsDone,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            PlatesAccessory(
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 14.dp),
                onClick = {
                    val id = plateTarget ?: return@PlatesAccessory
                    focusManager.clearFocus()
                    plateSetId = id
                },
            )
        }
    }

    plateSetId?.let { setId ->
        val row = state.exercises.firstNotNullOfOrNull { ex -> ex.sets.firstOrNull { it.id == setId } }
        if (row == null) {
            plateSetId = null
        } else {
            PlateCalculatorSheet(
                weightKg = row.weightKg,
                unit = state.unit,
                onUse = { kg -> model.setWeight(setId, kg) },
                onDismiss = { plateSetId = null },
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
            // Same as Done: `discard` ends the session and the shell slides this layer away.
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

/**
 * `focusedWeightSet(in:)` — the set whose weight cell has the keyboard, when it has a weight to
 * load (the Plates accessory); `null` otherwise.
 */
internal fun focusedWeightSet(state: ActiveWorkoutUiState, focused: SetField?): Long? {
    if (focused == null || focused.isReps) return null
    val row = state.exercises.firstNotNullOfOrNull { ex -> ex.sets.firstOrNull { it.id == focused.setId } }
    return row?.takeIf { it.weightKg > 0 }?.id
}

/** The "Plates" capsule over the keyboard (`Button("plates.title", systemImage: "circle.grid.2x1")`). */
@Composable
private fun PlatesAccessory(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(NT.Size.control)
            .pressScale(onClick = onClick)
            .background(NT.Colors.surface2, CircleShape)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Dumbbell, size = sfIconSize(14f), tint = NT.Colors.ink)
        NtText(
            text = stringResource(S.plates_title),
            style = NT.Fonts.subheadlineBold,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

/** How long a notification's rest-sheet request waits for the screen to finish sliding up. */
private const val REST_SHEET_DELAY_MS = 350L

/** The predictive-back preview: the screen sinks and shrinks a little as the gesture goes. */
private val BACK_TRAVEL = 64.dp
private const val BACK_SCALE = 0.06f

/** `spring(response: 0.35, dampingFraction: 0.85)` (`ActiveWorkoutView.swift:76`) — the pill in and out. */
private val PILL_SPRING_FLOAT = NT.Anim.spring085
private val PILL_SPRING_OFFSET =
    spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.85f, stiffness = 322.3f)
