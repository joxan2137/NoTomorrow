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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.app.LocalTabBarHeight
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * Progress > Milestones — the port of `MilestonesView` (`Features/Progress/MilestonesView.swift`):
 * every tier of every track, reached ones with their day, the rest with how far along they are.
 * The strength track needs a body weight; without one it says how to unlock it.
 */
@Composable
fun MilestonesScreen(onBack: () -> Unit) {
    val model = ntViewModel { container ->
        MilestonesViewModel(
            profileDao = container.db.profileDao(),
            workoutDao = container.db.workoutDao(),
            bodyWeightDao = container.db.bodyWeightDao(),
        )
    }
    val state by model.uiState.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        if (state.loaded) {
            val byKind = state.milestones.groupBy { it.kind }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(bottom = 32.dp + LocalTabBarHeight.current),
                horizontalAlignment = Alignment.Start,
            ) {
                MilestonesHeader(onBack = onBack)
                MilestonesSection(
                    title = stringResource(S.milestones_section_workouts),
                    milestones = byKind[Milestones.Kind.Workouts].orEmpty(),
                    unit = state.unit,
                    modifier = Modifier.padding(top = 18.dp),
                )
                MilestonesSection(
                    title = stringResource(S.milestones_section_volume),
                    milestones = byKind[Milestones.Kind.Volume].orEmpty(),
                    unit = state.unit,
                    modifier = Modifier.padding(top = NT.Spacing.section),
                )
                MilestonesSection(
                    title = stringResource(S.milestones_section_weekStreak),
                    milestones = byKind[Milestones.Kind.WeekStreak].orEmpty(),
                    unit = state.unit,
                    modifier = Modifier.padding(top = NT.Spacing.section),
                )
                StrengthSection(
                    milestones = state.milestones.filter { it.kind.isLift },
                    bodyWeightKg = state.bodyWeightKg,
                    unit = state.unit,
                    modifier = Modifier.padding(top = NT.Spacing.section),
                )
            }
        }
    }
}

/** Back arrow (44 dp, pulled 10 dp into the margin as on the records page) and the title. */
@Composable
private fun MilestonesHeader(onBack: () -> Unit) {
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
            text = stringResource(S.milestones_title),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

@Composable
private fun MilestonesSection(
    title: String,
    milestones: List<Milestones.Milestone>,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = title)
        NTCard { MilestoneRows(milestones, unit) }
    }
}

@Composable
private fun StrengthSection(
    milestones: List<Milestones.Milestone>,
    bodyWeightKg: Double?,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = stringResource(S.milestones_section_strength))
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bodyWeightKg != null && milestones.isNotEmpty()) {
                    MilestoneRows(milestones, unit)
                    NtText(
                        text = stringResource(S.milestones_strengthNote_s, Fmt.weight(bodyWeightKg, unit)),
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                } else {
                    NtText(
                        text = stringResource(S.milestones_noBodyWeight),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                }
            }
        }
    }
}

@Composable
private fun MilestoneRows(milestones: List<Milestones.Milestone>, unit: WeightUnit) {
    Column(Modifier.fillMaxWidth()) {
        milestones.forEachIndexed { index, milestone ->
            val rowModifier = Modifier.padding(vertical = 10.dp)
            if (milestone.isAchieved) {
                MilestoneAchievedRow(milestone, unit, rowModifier)
            } else {
                MilestoneProgressRow(milestone, unit, rowModifier)
            }
            if (index < milestones.size - 1) Hairline()
        }
    }
}
