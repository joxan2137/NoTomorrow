package app.notomorrow.feature.progress

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Chip
import app.notomorrow.designsystem.E1RMChart
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.FocalCard
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSegmented
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.ntViewModel
import app.notomorrow.feature.bro.bleedHorizontally
import app.notomorrow.feature.workout.MuscleHeatView
import app.notomorrow.feature.workout.workoutMuscleName
import app.notomorrow.feature.workout.workoutSetCount
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * "Progress" — the port of `ProgressHomeView`
 * (`Features/Progress/ProgressHomeView.swift`).
 *
 * No visible top bar: the eyebrow (last PR date), the title and the Lifts / Body
 * segmented control are the screen's own header. The Lifts tab shows a chip per lift over
 * the selected lift's [FocalCard] (e1RM, range delta, chart, range picker), the muscles
 * trained this week, the e1RM list, the training calendar, the weekly stats and the milestones; the Body tab replaces them with the full body view, the measurements and the progress photos.
 */
@Composable
fun ProgressHomeScreen(onExercise: (String) -> Unit, onRecords: () -> Unit, onMilestones: () -> Unit) {
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
                            val focal = state.focal
                            if (state.hasCompletedSets && focal != null) {
                                LiftChips(
                                    lifts = state.lifts,
                                    selectedId = focal.exerciseId,
                                    onSelect = model::selectLift,
                                    modifier = Modifier.padding(top = 18.dp),
                                )
                                LiftFocalCard(
                                    focal = focal,
                                    unit = state.unit,
                                    range = state.range,
                                    onOpen = { onExercise(focal.exerciseId) },
                                    onSelectRange = model::selectRange,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            MusclesSection(
                                muscles = state.muscles,
                                modifier = Modifier.padding(
                                    top = if (state.hasCompletedSets) NT.Spacing.section else 18.dp,
                                ),
                            )
                            LiftsSection(
                                state = state,
                                onExercise = onExercise,
                                onRecords = onRecords,
                                onStartWorkout = model::selectTrainTab,
                                modifier = Modifier.padding(top = NT.Spacing.section),
                            )
                            state.today?.let { today ->
                                TrainingCalendarCard(
                                    days = state.calendarDays,
                                    today = today,
                                    unit = state.unit,
                                    modifier = Modifier.padding(top = NT.Spacing.section),
                                )
                            }
                            WeeklyStatsCard(
                                weeks = state.weeklyStats,
                                unit = state.unit,
                                modifier = Modifier.padding(top = NT.Spacing.section),
                            )
                            MilestonesCard(
                                milestones = state.milestones,
                                unit = state.unit,
                                onOpen = onMilestones,
                                modifier = Modifier.padding(top = NT.Spacing.section),
                            )
                        }

                        ProgressTab.Body -> Column(Modifier.fillMaxWidth()) {
                            BodyTab(
                                stats = state.body,
                                unit = state.unit,
                                onLog = model::showLogWeight,
                                modifier = Modifier.padding(top = 18.dp),
                            )
                            MeasurementsSection(
                                unit = state.unit,
                                modifier = Modifier.padding(top = NT.Spacing.section),
                            )
                            ProgressPhotosSection(modifier = Modifier.padding(top = NT.Spacing.section))
                        }
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

// MARK: - Selected lift

/** Every lift, in the list's order, bleeding to the screen edge (`ProgressHomeView.liftChips`). */
@Composable
private fun LiftChips(
    lifts: List<LiftRowState>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .bleedHorizontally(NT.Spacing.screenH)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NT.Spacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lifts.forEach { lift ->
            Chip(
                title = lift.name,
                selected = lift.exerciseId == selectedId,
                onClick = { onSelect(lift.exerciseId) },
            )
        }
    }
}

/**
 * `ProgressHomeView.focalCard`: the current e1RM in `display(56)` with the unit in `title2`, the
 * range [DeltaChip] (on `surface2`, since the card is `surface`), the 160 dp [E1RMChart] that
 * opens the lift, and the range control.
 */
@Composable
private fun LiftFocalCard(
    focal: FocalLiftState,
    unit: WeightUnit,
    range: ProgressRange,
    onOpen: () -> Unit,
    onSelectRange: (ProgressRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `NtSegmented.label` is a plain lambda, so the titles are resolved up here.
    val titles = ProgressRange.entries.associateWith { stringResource(it.titleRes) }
    FocalCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow(stringResource(S.progress_e1rm))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NtText(
                            text = Fmt.weight(focal.current, unit, withUnit = false),
                            modifier = Modifier.alignByBaseline(),
                            style = NT.Fonts.display(56).tabular(),
                            color = NT.Colors.ink,
                            maxLines = 1,
                        )
                        NtText(
                            text = unit.raw,
                            modifier = Modifier.alignByBaseline(),
                            style = NT.Fonts.title2,
                            color = NT.Colors.ink2,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                DeltaChip(
                    delta = focal.delta,
                    unit = unit,
                    range = range,
                    neutral = NT.Colors.surface2,
                )
            }

            // `NavigationLink(value: lift.id)` with `.buttonStyle(.plain)`, as the list rows.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FOCAL_CHART_HEIGHT)
                    .ntPlainClickable(role = Role.Button, onClick = onOpen),
                contentAlignment = Alignment.Center,
            ) {
                if (focal.points.size >= 2) {
                    E1RMChart(points = focal.points, modifier = Modifier.fillMaxSize(), unit = unit)
                } else {
                    NtText(
                        text = stringResource(
                            if (focal.points.size == 1) S.progress_firstSessionHint
                            else S.progress_noSessionsInRange,
                        ),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                }
            }

            NtSegmented(
                options = ProgressRange.entries,
                selected = range,
                onSelect = onSelectRange,
                label = { titles.getValue(it) },
            )
        }
    }
}

private val FOCAL_CHART_HEIGHT = 160.dp

// MARK: - Muscles this week

/**
 * `ProgressHomeView.musclesSection`: "Muscles this week" with the week's set count, then an
 * [NTCard] with the [MuscleHeatView] beside the five most-trained muscles and, once something
 * was logged, which of the big ones are still at zero.
 */
@Composable
private fun MusclesSection(muscles: MuscleWeek, modifier: Modifier = Modifier) {
    val top = muscles.top
    val notTrained = muscles.notTrainedYet.map { workoutMuscleName(it) }.joinToString(", ")
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(
            title = stringResource(S.progress_musclesThisWeek),
            trailing = workoutSetCount(muscles.totalSets),
        )
        NTCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // Centred: with nothing logged the map stands alone, as in the Swift `HStack`.
                    horizontalArrangement = Arrangement.spacedBy(
                        16.dp,
                        Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuscleHeatView(
                        setsByMuscle = muscles.setsByMuscle,
                        modifier = Modifier.width(120.dp).height(180.dp),
                    )
                    if (top.isNotEmpty()) {
                        Column(Modifier.weight(1f)) {
                            top.forEachIndexed { index, (muscle, sets) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NtText(
                                        text = workoutMuscleName(muscle),
                                        modifier = Modifier.weight(1f),
                                        style = NT.Fonts.subheadline,
                                        color = NT.Colors.ink,
                                        maxLines = 1,
                                    )
                                    TabularText(
                                        text = Fmt.count(sets),
                                        style = NT.Fonts.subheadline,
                                        color = NT.Colors.ink2,
                                    )
                                }
                                if (index < top.size - 1) Hairline()
                            }
                        }
                    }
                }
                if (notTrained.isNotEmpty()) {
                    NtText(
                        text = stringResource(S.progress_notTrainedYet_s, notTrained),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                }
            }
        }
    }
}

// MARK: - Lifts

/** "Estimated 1RM · 3 months" over the lift rows, or the designed empty state. */
@Composable
private fun LiftsSection(
    state: ProgressHomeUiState,
    onExercise: (String) -> Unit,
    onRecords: () -> Unit,
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
            RecordsLink(onClick = onRecords, modifier = Modifier.padding(top = 12.dp))
        } else {
            ProgressEmptyState(onStartWorkout = onStartWorkout)
        }
    }
}

/** "All-time records ›" under the list (`ProgressHomeView.recordsLink`): opens [RecordsScreen]. */
@Composable
private fun RecordsLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(NT.Colors.surface, NtShapes.tile)
            .ntPlainClickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            NtIcon(NtIcons.Trophy, size = sfIconSize(16f), tint = NT.Colors.ember)
        }
        NtText(
            text = stringResource(S.records_title),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        NtIcon(NtIcons.ChevronRight, size = sfIconSize(13f), tint = NT.Colors.ink3)
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
