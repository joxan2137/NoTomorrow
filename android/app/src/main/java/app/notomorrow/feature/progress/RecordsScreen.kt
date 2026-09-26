package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import java.time.LocalDate

/**
 * Progress > Records — the port of `RecordsView` (`Features/Progress/RecordsView.swift`): every
 * lift with a PR, most recent PR first, with its best e1RM, heaviest weight and best volume set and
 * when each was done. A row opens the lift's [ExerciseProgressScreen].
 */
@Composable
fun RecordsScreen(onExercise: (String) -> Unit, onBack: () -> Unit) {
    val model = ntViewModel { container ->
        RecordsViewModel(
            profileDao = container.db.profileDao(),
            workoutDao = container.db.workoutDao(),
            exerciseDao = container.db.exerciseDao(),
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.refresh() }

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
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
                RecordsHeader(onBack = onBack)
                if (state.rows.isEmpty()) {
                    NtText(
                        text = stringResource(S.records_empty),
                        modifier = Modifier.padding(top = 40.dp),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                } else {
                    Column(Modifier.padding(top = 8.dp)) {
                        state.rows.forEachIndexed { index, row ->
                            RecordsRow(
                                row = row,
                                unit = state.unit,
                                showsHairline = index < state.rows.size - 1,
                                onClick = { onExercise(row.exerciseId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Back arrow (44 dp, pulled 10 dp into the margin as on the lift page) and the title. */
@Composable
private fun RecordsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(NT.Size.control),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(NT.Size.control - 10.dp).height(NT.Size.control)) {
            Box(
                modifier = Modifier
                    .offset(x = (-10).dp)
                    .size(NT.Size.control)
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
        NtText(
            text = stringResource(S.records_title),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

/** One lift: name and last-PR phrase over three record cells. */
@Composable
private fun RecordsRow(
    row: RecordsRowState,
    unit: WeightUnit,
    showsHairline: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().ntPlainClickable(role = Role.Button, onClick = onClick)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NtText(
                    text = row.name,
                    modifier = Modifier.weight(1f),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                    maxLines = 1,
                )
                NtText(
                    text = row.lastPR.asText(),
                    style = NT.Fonts.footnote,
                    color = if (row.isHot) NT.Colors.ember else NT.Colors.ink2,
                    maxLines = 1,
                )
                NtIcon(NtIcons.ChevronRight, size = sfIconSize(13f), tint = NT.Colors.ink3)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RecordCell(
                    label = stringResource(S.records_bestE1RM),
                    value = Fmt.weight(row.bestE1RMKg, unit),
                    day = row.bestE1RMDay,
                    modifier = Modifier.weight(1f),
                )
                RecordCell(
                    label = stringResource(S.records_heaviest),
                    value = Fmt.weight(row.heaviest.weightKg, unit),
                    day = row.heaviest.day,
                    modifier = Modifier.weight(1f),
                )
                RecordCell(
                    label = stringResource(S.records_bestVolumeSet),
                    value = Fmt.set(row.bestVolume.weightKg, row.bestVolume.reps, unit),
                    day = row.bestVolume.day,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showsHairline) Hairline()
    }
}

@Composable
private fun RecordCell(label: String, value: String, day: LocalDate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        TabularText(value, style = NT.Fonts.subheadline, color = NT.Colors.ink)
        TabularText(Fmt.dayMonth(day), style = NT.Fonts.footnote, color = NT.Colors.ink2)
    }
}
