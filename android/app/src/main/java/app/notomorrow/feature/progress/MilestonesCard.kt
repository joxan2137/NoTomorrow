package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings

/**
 * Progress > Lifts: the latest milestone reached and the next few with how far along they are —
 * port of `MilestonesCard.swift`. The whole card opens [MilestonesScreen]; the evaluation lives
 * in [Milestones].
 */
@Composable
fun MilestonesCard(
    milestones: List<Milestones.Milestone>,
    unit: WeightUnit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = Milestones.latest(milestones)
    val upcoming = Milestones.next(milestones).take(3)
    val achievedCount = milestones.count { it.isAchieved }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = stringResource(S.milestones_title),
            trailing = "$achievedCount / ${milestones.size}",
        )
        NTCard(modifier = Modifier.ntPlainClickable(role = Role.Button, onClick = onOpen)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (latest != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(stringResource(S.milestones_latest))
                        MilestoneAchievedRow(latest, unit)
                    }
                } else {
                    NtText(
                        text = stringResource(S.milestones_empty),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink2,
                    )
                }
                if (upcoming.isNotEmpty()) {
                    Hairline()
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Eyebrow(stringResource(S.milestones_upNext))
                        upcoming.forEach { MilestoneProgressRow(it, unit) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtText(
                        text = stringResource(S.milestones_seeAll),
                        modifier = Modifier.weight(1f),
                        style = NT.Fonts.subheadline,
                        color = NT.Colors.ink,
                        maxLines = 1,
                    )
                    NtIcon(NtIcons.ChevronRight, size = sfIconSize(13f), tint = NT.Colors.ink3)
                }
            }
        }
    }
}

/** Trophy, title and the day it was reached. */
@Composable
fun MilestoneAchievedRow(milestone: Milestones.Milestone, unit: WeightUnit, modifier: Modifier = Modifier) {
    val strings = rememberNtStrings()
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).background(NT.Colors.ember.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            NtIcon(NtIcons.Trophy, size = sfIconSize(15f), tint = NT.Colors.ember)
        }
        TabularText(
            text = Milestones.title(milestone, unit, strings),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
            maxLines = 2,
        )
        milestone.achieved?.let { achieved ->
            TabularText(
                text = Fmt.mediumDate(achieved.day),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
    }
}

/** Title and "32 / 50" over a thin ember bar. */
@Composable
fun MilestoneProgressRow(milestone: Milestones.Milestone, unit: WeightUnit, modifier: Modifier = Modifier) {
    val strings = rememberNtStrings()
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabularText(
                text = Milestones.title(milestone, unit, strings),
                modifier = Modifier.weight(1f),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink,
            )
            TabularText(
                text = Milestones.progressText(milestone, unit),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(NT.Colors.surface2, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(milestone.progress.toFloat())
                    .fillMaxHeight()
                    .background(NT.Colors.ember, CircleShape),
            )
        }
    }
}
