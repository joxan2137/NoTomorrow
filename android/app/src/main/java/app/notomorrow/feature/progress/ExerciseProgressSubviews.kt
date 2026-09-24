package app.notomorrow.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.WeeklyVolumeChart
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The three lower blocks of `ExerciseProgressView`: the delta chip, the all-lifts weekly
 * volume section, and the records list.
 */

// MARK: - Delta chip

/**
 * `ProgressDeltaChip` (`ProgressSupport.swift`): "↑ 14 kg in 3 months" — ember on `emberTint` when
 * positive, `ink2` on [neutral] otherwise; `surface` on the ground, `surface2` inside a card.
 */
@Composable
fun DeltaChip(
    delta: Double,
    unit: WeightUnit,
    range: ProgressRange,
    modifier: Modifier = Modifier,
    neutral: Color = NT.Colors.surface,
) {
    val positive = delta > 0
    val tint = if (positive) NT.Colors.ember else NT.Colors.ink2
    val icon = when {
        positive -> NtIcons.ArrowUp
        delta < 0 -> NtIcons.ArrowDown
        else -> NtIcons.Minus
    }
    Row(
        modifier = modifier
            .height(30.dp)
            .background(
                color = if (positive) NT.Colors.emberTint else neutral,
                shape = CircleShape,
            )
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(icon, size = sfIconSize(12f), tint = tint)
        NtText(
            text = stringResource(
                range.deltaRes,
                Fmt.signedWeight(delta, unit, withUnit = true),
            ),
            style = NT.Fonts.footnoteBold.tabular(),
            color = tint,
            maxLines = 1,
        )
    }
}

// MARK: - Weekly volume (all lifts)

/** "All lifts · weekly" with this week's total and the week-over-week percentage. */
@Composable
fun WeeklyVolumeSection(state: ExerciseProgressUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(
                text = stringResource(S.progress_weeklyVolume),
                style = NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabularText(
                    text = Fmt.volume(state.allThisWeekVolume, state.unit),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
                val ratio = state.weekOverWeek
                if (ratio != null) {
                    val tint = if (ratio >= 0) NT.Colors.ember else NT.Colors.ink2
                    TabularText(
                        text = " · ",
                        style = NT.Fonts.footnote,
                        color = NT.Colors.ink2,
                    )
                    TabularText(
                        text = Fmt.signedPercent(ratio) + " ",
                        style = NT.Fonts.footnote,
                        color = tint,
                    )
                    TabularText(
                        text = stringResource(S.progress_vsLastWeek),
                        style = NT.Fonts.footnote,
                        color = tint,
                    )
                }
            }
        }
        WeeklyVolumeChart(
            weeks = state.weekly,
            modifier = Modifier.fillMaxWidth().height(86.dp),
        )
    }
}

// MARK: - Records

/** Heaviest set and most reps, each with its weight × reps and the day it happened. */
@Composable
fun RecordsSection(state: ExerciseProgressUiState, modifier: Modifier = Modifier) {
    val heaviest = state.heaviest
    val mostReps = state.mostReps
    Column(modifier.fillMaxWidth()) {
        NtText(
            text = stringResource(S.progress_records),
            modifier = Modifier.padding(bottom = 4.dp),
            style = NT.Fonts.headline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        if (heaviest != null) {
            RecordRow(
                label = stringResource(S.progress_heaviestSet),
                set = heaviest,
                unit = state.unit,
            )
            Hairline()
        }
        if (mostReps != null) {
            RecordRow(
                label = stringResource(S.progress_mostReps),
                set = mostReps,
                unit = state.unit,
            )
        }
    }
}

@Composable
private fun RecordRow(label: String, set: RecordSet, unit: WeightUnit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `.frame(width: 18)` around a 16 pt symbol: the glyph keeps its size and is
        // centred in an 18 dp slot, so both record rows share one text origin.
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            NtIcon(NtIcons.Trophy, size = sfIconSize(16f), tint = NT.Colors.ember)
        }
        NtText(
            text = label,
            modifier = Modifier.weight(1f),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
            maxLines = 1,
        )
        TabularText(
            text = Fmt.weight(set.weightKg, unit) + " " + Fmt.TIMES + " " +
                Fmt.count(set.reps),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink,
        )
        NtText(
            text = Fmt.dayMonth(set.day),
            modifier = Modifier.width(52.dp),
            style = NT.Fonts.footnote.tabular(),
            color = NT.Colors.ink2,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
    }
}
