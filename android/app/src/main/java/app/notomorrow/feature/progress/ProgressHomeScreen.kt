package app.notomorrow.feature.progress

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSegmented
import app.notomorrow.designsystem.NtText
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.S

/**
 * "Progress" — the port of `ProgressHomeView`
 * (`Features/Progress/ProgressHomeView.swift`).
 *
 * No visible top bar: the eyebrow (last PR date), the title and the Lifts / Body
 * segmented control are the screen's own header. The Lifts tab shows the body-weight
 * card **above** the e1RM list; the Body tab replaces both with the full body view.
 */
@Composable
fun ProgressHomeScreen(onExercise: (String) -> Unit) {
    val model = ntViewModel { container ->
        ProgressHomeViewModel(
            profileDao = container.db.profileDao(),
            workoutDao = container.db.workoutDao(),
            exerciseDao = container.db.exerciseDao(),
            bodyWeightDao = container.db.bodyWeightDao(),
            health = container.healthService,
            appState = container.appState,
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()

    // iOS re-derives the whole model from `Date.now` in `.onAppear`, so returning to the tab
    // always refreshes the relative phrasing ("PR today", the 4-week baseline, the ISO week).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.refresh() }

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        // Nothing is drawn before the first store emission: iOS never shows this screen with an
        // empty model, and the empty state must mean "no lifts", not "not read yet".
        if (state.loaded) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 32.dp + LocalTabBarHeight.current),
                horizontalAlignment = Alignment.Start,
            ) {
                ProgressHeader(state = state, onSelect = model::select)

                Crossfade(
                    targetState = state.tab,
                    // `.easeOut(duration: 0.15)` — the spec the segmented control animates on.
                    animationSpec = NT.Anim.easeOut15,
                    label = "progressTab",
                ) { tab ->
                    when (tab) {
                        ProgressTab.Lifts -> Column(Modifier.fillMaxWidth()) {
                            BodyWeightCard(
                                stats = state.body,
                                unit = state.unit,
                                onLog = model::showLogWeight,
                                modifier = Modifier.padding(top = 18.dp),
                            )
                            LiftsSection(
                                state = state,
                                onExercise = onExercise,
                                onStartWorkout = model::selectTrainTab,
                                modifier = Modifier.padding(top = NT.Spacing.section),
                            )
                        }

                        ProgressTab.Body -> BodyTab(
                            stats = state.body,
                            unit = state.unit,
                            onLog = model::showLogWeight,
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                }
            }
        }
    }

    if (state.showsLogWeight) {
        LogWeightSheet(
            unit = state.unit,
            suggestedKg = state.body.latest?.kg,
            onSave = model::logWeight,
            onDismiss = model::dismissLogWeight,
        )
    }
}

// MARK: - Header

/** Eyebrow ("LAST PR · WEDNESDAY"), `progress.title`, and the 150 dp Lifts / Body switch. */
@Composable
private fun ProgressHeader(
    state: ProgressHomeUiState,
    onSelect: (ProgressTab) -> Unit,
) {
    val lastPR = stringResource(S.progress_lastPR)
    val eyebrow = state.lastPRDate?.let { "$lastPR · ${it.asText()}" }
        ?: stringResource(S.progress_noPRYet)
    // `NtSegmented.label` is a plain lambda, so the titles are resolved up here.
    val titles = ProgressTab.entries.associateWith { stringResource(it.titleRes) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow(eyebrow)
            NtText(
                text = stringResource(S.progress_title),
                style = NT.Fonts.largeTitle,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        NtSegmented(
            options = ProgressTab.entries,
            selected = state.tab,
            onSelect = onSelect,
            modifier = Modifier.padding(bottom = 4.dp),
            segmentHeight = 34.dp,
            inset = 3.dp,
            radius = 11.dp,
            style = NT.Fonts.subheadline,
            width = 150.dp,
            label = { titles.getValue(it) },
        )
    }
}

// MARK: - Lifts

/** "Estimated 1RM · 3 months" over the lift rows, or the designed empty state. */
@Composable
private fun LiftsSection(
    state: ProgressHomeUiState,
    onExercise: (String) -> Unit,
    onStartWorkout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(
                text = stringResource(S.progress_e1rm),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            NtText(
                text = stringResource(S.progress_range3Months),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }

        if (state.hasCompletedSets) {
            state.lifts.forEachIndexed { index, lift ->
                ProgressLiftRow(
                    lift = lift,
                    unit = state.unit,
                    showsHairline = index < state.lifts.size - 1,
                    onClick = { onExercise(lift.exerciseId) },
                )
            }
        } else {
            ProgressEmptyState(onStartWorkout = onStartWorkout)
        }
    }
}

/** "Log a workout and your lifts show up here." + Start workout. */
@Composable
private fun ProgressEmptyState(onStartWorkout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        NtText(
            text = stringResource(S.progress_empty),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )
        GhostButton(title = stringResource(S.workout_start), onClick = onStartWorkout)
    }
}
