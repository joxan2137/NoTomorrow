package app.notomorrow.feature.progress

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.E1RMChart
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSegmented
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.StatTile
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.di.ntViewModel
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * One lift over time — the port of `ExerciseProgressView`
 * (`Features/Progress/ExerciseProgressView.swift`): hero e1RM + delta chip, the line/area
 * chart with PR marks, three tiles, the weekly volume of every lift, and the records.
 *
 * The navigation bar is hidden on iOS, so the screen draws its own back chevron in a
 * 44 dp hit target.
 */
@Composable
fun ExerciseProgressScreen(exerciseId: String, onBack: () -> Unit) {
    val model = ntViewModel(key = exerciseId) { container ->
        ExerciseProgressViewModel(
            exerciseId = exerciseId,
            profileDao = container.db.profileDao(),
            workoutDao = container.db.workoutDao(),
            exerciseDao = container.db.exerciseDao(),
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()

    // `.onAppear { model.reload(modelContext) }` — re-read "now" so "PR today" and the ISO
    // week never go stale in a session left open across midnight.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.refresh() }

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        // iOS reloads synchronously in `.onAppear`, so the title is never blank and
        // `progress.empty` never flashes for a lift that has history; nothing is drawn until
        // the query has actually answered.
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
                ExerciseProgressHeader(
                    name = state.name,
                    range = state.range,
                    onBack = onBack,
                    onSelect = model::select,
                )

                if (state.hasLift) {
                    ExerciseHero(state = state, modifier = Modifier.padding(top = 18.dp))
                    E1RMSection(state = state, modifier = Modifier.padding(top = 12.dp))
                    ExerciseTiles(state = state, modifier = Modifier.padding(top = 14.dp))
                    WeeklyVolumeSection(
                        state = state,
                        modifier = Modifier.padding(top = NT.Spacing.section),
                    )
                    RecordsSection(state = state, modifier = Modifier.padding(top = 18.dp))
                } else {
                    NtText(
                        text = stringResource(S.progress_empty),
                        modifier = Modifier.padding(top = 40.dp),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                }
            }
        }
    }
}

// MARK: - Header (back · name · 1M 3M 1Y All)

@Composable
private fun ExerciseProgressHeader(
    name: String,
    range: ProgressRange,
    onBack: () -> Unit,
    onSelect: (ProgressRange) -> Unit,
) {
    // `NtSegmented.label` is a plain lambda, so the titles are resolved up here.
    val titles = ProgressRange.entries.associateWith { stringResource(it.titleRes) }
    Row(
        modifier = Modifier.fillMaxWidth().height(NT.Size.control),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS pulls the 44 dp button 10 pt into the screen margin (`.padding(.leading, -10)`),
        // so it occupies 34 pt of layout and overhangs to the left.
        Box(Modifier.width(NT.Size.control - 10.dp).height(NT.Size.control)) {
            Box(
                modifier = Modifier
                    .offset(x = (-10).dp)
                    .size(NT.Size.control)
                    // `.buttonStyle(.plain)` on the iOS back button: no press scale.
                    .ntPlainClickable(onClick = onBack, role = Role.Button),
                contentAlignment = Alignment.Center,
            ) {
                NtIcon(
                    NtIcons.ArrowLeft,
                    size = sfIconSize(20f),
                    tint = NT.Colors.ink,
                    contentDescription = stringResource(S.common_back),
                )
            }
        }

        BasicText(
            text = name,
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2.copy(color = NT.Colors.ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 17.6.sp,
                maxFontSize = 22.sp,
                stepSize = 0.5.sp,
            ),
        )

        NtSegmented(
            options = ProgressRange.entries,
            selected = range,
            onSelect = onSelect,
            width = 168.dp,
            label = { titles.getValue(it) },
        )
    }
}

// MARK: - Hero

/** `display(64)` e1RM with the unit in `title2`, and the range delta chip on the right. */
@Composable
private fun ExerciseHero(state: ExerciseProgressUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(stringResource(S.progress_e1rm))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NtText(
                    text = Fmt.weight(state.current, state.unit, withUnit = false),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.display(64).tabular(),
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                NtText(
                    text = state.unit.raw,
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.title2,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        DeltaChip(
            delta = state.delta,
            unit = state.unit,
            range = state.range,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

// MARK: - Chart

/** The 172 dp e1RM chart, or the one-line hint that replaces it under two points. */
@Composable
private fun E1RMSection(state: ExerciseProgressUiState, modifier: Modifier = Modifier) {
    if (state.points.size >= 2) {
        E1RMChart(
            points = state.points,
            modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
            unit = state.unit,
        )
    } else {
        Box(
            modifier = modifier.fillMaxWidth().height(CHART_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(
                    if (state.points.size == 1) S.progress_firstSessionHint
                    else S.progress_noSessionsInRange,
                ),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

private val CHART_HEIGHT = 172.dp

// MARK: - Tiles

/**
 * Last PR · This week · Sessions. The first and third are hand-rolled with [StatTile]'s
 * geometry because their label and value need more than one text run.
 */
@Composable
private fun ExerciseTiles(state: ExerciseProgressUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tile(modifier = Modifier.weight(1f), label = stringResource(S.progress_lastPR)) {
            ScalingHeadline(
                text = state.lastPR?.asText() ?: stringResource(S.progress_noPRYet),
            )
        }

        StatTile(
            label = stringResource(S.progress_thisWeek),
            value = Fmt.volume(state.thisWeekVolume, state.unit),
            modifier = Modifier.weight(1f),
        )

        val sessions = stringResource(S.progress_sessions)
        val rangeTitle = stringResource(state.range.titleRes)
        Tile(modifier = Modifier.weight(1f), label = "$sessions · $rangeTitle") {
            NtText(
                text = Fmt.count(state.sessions),
                style = NT.Fonts.headline.tabular(),
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
    }
}

/** `StatTile` with a caller-supplied value run. */
@Composable
private fun Tile(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NT.Colors.surface, NtShapes.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Eyebrow(label)
        value()
    }
}

/** `headline` with iOS's `minimumScaleFactor(0.8)` — 17 sp shrinking to 13.6 sp. */
@Composable
private fun ScalingHeadline(text: String) {
    BasicText(
        text = text,
        style = NT.Fonts.headline.tabular().copy(color = NT.Colors.ink),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 13.6.sp,
            maxFontSize = 17.sp,
            stepSize = 0.25.sp,
        ),
    )
}
