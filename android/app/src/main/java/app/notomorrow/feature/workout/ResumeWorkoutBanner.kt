package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Eyebrow
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTCard
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings

/**
 * The one card on the Train screen: an unfinished workout the user can jump back into
 * (`TrainView.swift:104`).
 *
 * The elapsed line is iOS's `TimelineView(.periodic(by: 60))` — a 60 s wall-clock ticker, so the
 * minute flips exactly when the minute does.
 */
@Composable
fun ResumeWorkoutBanner(
    workout: ActiveWorkoutSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberNtStrings()
    val now by rememberSecondTicker(periodMs = 60_000L)
    val elapsed = ((now - workout.startedAt).coerceAtLeast(0L)) / 1000.0

    NTCard(modifier = modifier.pressScale(onClick = onClick)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(NT.Colors.ember, CircleShape))
                    Eyebrow(stringResource(S.workout_inProgress), color = NT.Colors.ember)
                }
                NtText(
                    text = stringResource(S.dashboard_resumeWorkout),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                )
                TabularText(
                    text = workout.name + " · " + Fmt.duration(elapsed, strings),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            NtIcon(
                icon = NtIcons.ChevronRight,
                size = sfIconSize(15f),
                tint = NT.Colors.ink3,
            )
        }
    }
}
