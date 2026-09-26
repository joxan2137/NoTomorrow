package app.notomorrow.feature.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtSegmented
import app.notomorrow.designsystem.SectionHeader
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.WeeklyVolumeChart
import app.notomorrow.feature.workout.workoutCount
import app.notomorrow.feature.workout.workoutSetCount
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings

/**
 * Progress > Lifts: the last eight weeks of training as small bars, switchable between workouts,
 * volume, time and sets, with this week's value against last week's — port of
 * `WeeklyStatsCard.swift`. [weeks] is [WeeklyStats.weeks], oldest first.
 */
@Composable
fun WeeklyStatsCard(
    weeks: List<WeeklyStats.Week>,
    unit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    var metric by rememberSaveable { mutableStateOf(WeeklyStats.Metric.Workouts) }
    val titles = WeeklyStats.Metric.entries.associateWith { stringResource(it.titleRes) }
    val strings = rememberNtStrings()
    val workoutsLabel = weeks.associateWith { workoutCount(it.workouts) }
    val setsLabel = weeks.associateWith { workoutSetCount(it.sets) }
    val format: (WeeklyStats.Week?) -> String = { week ->
        when {
            week == null -> "—"
            metric == WeeklyStats.Metric.Workouts -> workoutsLabel.getValue(week)
            metric == WeeklyStats.Metric.Volume -> Fmt.volume(week.volumeKg, unit)
            metric == WeeklyStats.Metric.Duration -> Fmt.duration(week.durationSeconds, strings)
            else -> setsLabel.getValue(week)
        }
    }
    val thisWeek = weeks.lastOrNull()
    val lastWeek = if (weeks.size >= 2) weeks[weeks.size - 2] else null
    val ratio = WeeklyStats.change(weeks, metric)
    val chartDescription = titles.getValue(metric) + ": " + weeks.joinToString(", ") { format(it) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = stringResource(S.stats_title))
        NTCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NtSegmented(
                    options = WeeklyStats.Metric.entries,
                    selected = metric,
                    onSelect = { metric = it },
                    label = { titles.getValue(it) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Eyebrow(stringResource(S.progress_thisWeek))
                        TabularText(format(thisWeek), style = NT.Fonts.title2, color = NT.Colors.ink)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Eyebrow(stringResource(S.workout_history_lastWeek))
                        TabularText(format(lastWeek), style = NT.Fonts.subheadline, color = NT.Colors.ink2)
                    }
                }
                if (ratio != null) {
                    TabularText(
                        text = Fmt.signedPercent(ratio) + " " + stringResource(S.progress_vsLastWeek),
                        style = NT.Fonts.footnote,
                        color = if (ratio >= 0) NT.Colors.ember else NT.Colors.ink2,
                    )
                }
                // The volume chart's bars, fed the selected metric: its axis only labels the weeks.
                WeeklyVolumeChart(
                    weeks = weeks.map { it.toBar(metric) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp)
                        .clearAndSetSemantics { contentDescription = chartDescription },
                )
            }
        }
    }
}
