package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ProgressRing
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.WeightUnit
import app.notomorrow.rest.RestTimerState
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The full rest-timer sheet — 1:1 port of `RestTimerView.swift`: a 280 dp draining ring,
 * the `display(104)` countdown, −15 / Skip rest / +15, the "Up next" card and the
 * lock-screen note.
 *
 * Everything is read from [RestTimerState]; the sheet closes itself when the rest elapses
 * and on appear when nothing is running.
 */
@Composable
fun RestTimerSheet(
    state: RestTimerState,
    workoutName: String,
    startedAt: Long?,
    endedAt: Long?,
    upNext: UpNextTarget?,
    onAdjust: (Int) -> Unit,
    onSkip: () -> Unit,
    onElapsed: () -> Unit,
    onDismiss: () -> Unit,
) {
    NtSheet(onDismiss = onDismiss, containerColor = NT.Colors.ground) {
        val now by rememberSecondTicker()
        val remaining = state.remaining(now)

        // `.onAppear { if !restTimer.isRunning { dismiss() } }` and the ring's own elapse check.
        LaunchedEffect(state.endAt, now) {
            val endAt = state.endAt
            if (endAt == null) {
                onDismiss()
            } else if (endAt <= now) {
                onElapsed()
                onDismiss()
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().background(NT.Colors.ground),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(
                workoutName = workoutName,
                startedAt = startedAt,
                endedAt = endedAt,
                now = now,
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 20.dp),
                onDismiss = onDismiss,
            )
            Ring(
                remaining = remaining,
                totalSeconds = state.totalSeconds,
                fraction = state.fractionRemaining(now),
                modifier = Modifier.padding(top = 48.dp),
            )
            Controls(
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 40.dp),
                onAdjust = onAdjust,
                onSkip = onSkip,
            )
            UpNextCard(
                upNext = upNext,
                fallbackName = state.exerciseName,
                fallbackLine = state.nextSetLabel,
                modifier = Modifier
                    .padding(horizontal = NT.Spacing.screenH)
                    .padding(top = 40.dp),
            )
            Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
            LockNote(Modifier.padding(bottom = 16.dp))
        }
    }
}

// MARK: - Header

@Composable
private fun Header(
    workoutName: String,
    startedAt: Long?,
    endedAt: Long?,
    now: Long,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(NT.Size.control),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Eyebrow(stringResource(S.timer_rest), color = NT.Colors.ember)
            val elapsed = startedAt?.let { ((endedAt ?: now) - it) / 1000.0 }
            NtText(
                text = if (elapsed != null) workoutName + " · " + Fmt.elapsed(elapsed) else workoutName,
                style = if (elapsed != null) NT.Fonts.headline.tabular() else NT.Fonts.headline,
                color = NT.Colors.ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier.size(NT.Size.control).pressScale(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(NT.Colors.surface2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                NtIcon(NtIcons.ChevronDown, size = sfIconSize(15f), tint = NT.Colors.ink)
            }
        }
    }
}

// MARK: - Ring

@Composable
private fun Ring(remaining: Double, totalSeconds: Int, fraction: Double, modifier: Modifier) {
    Box(modifier.size(280.dp), contentAlignment = Alignment.Center) {
        ProgressRing(
            progress = fraction,
            modifier = Modifier.fillMaxSize(),
            lineWidth = 12.dp,
            track = NT.Colors.surface,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TabularText(
                text = Fmt.clock(remaining),
                style = NT.Fonts.display(104),
                color = NT.Colors.ink,
            )
            TabularText(
                text = stringResource(S.timer_of_s, Fmt.clock(totalSeconds)),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
            )
        }
    }
}

// MARK: - Controls

@Composable
private fun Controls(modifier: Modifier, onAdjust: (Int) -> Unit, onSkip: () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdjustButton(label = MINUS_15) { onAdjust(-15) }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(NT.Size.primaryButton)
                .pressScale(onClick = onSkip)
                .background(NT.Colors.ink, CircleShape),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtIcon(NtIcons.ForwardEndFill, size = sfIconSize(15f), tint = NT.Colors.onPrimary)
            NtText(
                text = stringResource(S.timer_skip),
                style = NT.Fonts.headline,
                color = NT.Colors.onPrimary,
                maxLines = 1,
            )
        }
        AdjustButton(label = PLUS_15) { onAdjust(15) }
    }
}

@Composable
private fun AdjustButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = NT.Size.primaryButton)
            .pressScale(onClick = onClick)
            .background(NT.Colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = label,
            style = NT.Fonts.subheadlineBold.tabular(),
            color = NT.Colors.ink,
            maxLines = 1,
        )
    }
}

// MARK: - Up next

@Composable
private fun UpNextCard(
    upNext: UpNextTarget?,
    fallbackName: String,
    fallbackLine: String,
    modifier: Modifier,
) {
    val line = if (upNext == null) {
        fallbackLine
    } else {
        val setLabel = stringResource(S.timer_setOf_n_n, upNext.setIndex, upNext.setCount)
        val bestKg = upNext.bestKg
        val bestReps = upNext.bestReps
        if (bestKg != null && bestReps != null) {
            setLabel + " · " + stringResource(S.workout_best) + ": " + Fmt.set(bestKg, bestReps, upNext.unit)
        } else {
            setLabel
        }
    }
    NTCard(modifier = modifier, padding = 16.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow(stringResource(S.timer_upNext))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    NtText(
                        text = upNext?.exerciseName ?: fallbackName,
                        style = NT.Fonts.headline,
                        color = NT.Colors.ink,
                        maxLines = 1,
                    )
                    NtText(
                        text = line,
                        style = NT.Fonts.footnote.tabular(),
                        color = NT.Colors.ink2,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (upNext != null && (upNext.weightKg > 0 || upNext.reps > 0)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        TabularText(
                            text = Fmt.weight(upNext.weightKg, upNext.unit, withUnit = false),
                            modifier = Modifier.alignByBaseline(),
                            style = NT.Fonts.display(32),
                            color = NT.Colors.ink,
                        )
                        NtText(
                            text = upNext.unit.raw + UNIT_TIMES,
                            modifier = Modifier.alignByBaseline(),
                            style = NT.Fonts.footnote,
                            color = NT.Colors.ink2,
                            maxLines = 1,
                        )
                        TabularText(
                            text = upNext.reps.toString(),
                            modifier = Modifier.alignByBaseline(),
                            style = NT.Fonts.display(32),
                            color = NT.Colors.ink,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Lock note

@Composable
private fun LockNote(modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtIcon(NtIcons.Lock, size = sfIconSize(12f), tint = NT.Colors.ink2)
        NtText(
            text = stringResource(S.timer_lockScreen),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
            maxLines = 1,
        )
    }
}

/** `Text(verbatim: "−15")` — U+2212, the same minus iOS draws. */
private const val MINUS_15 = "−15"
private const val PLUS_15 = "+15"

/** `Text(verbatim: "\(upNext.unit.rawValue) ×")` — after "kg" or "lb". */
private const val UNIT_TIMES = " ×"

// MARK: - Target

/**
 * What the rest timer is counting down towards — `UpNextTarget` in
 * `ActiveWorkoutModel.swift`. `setLabel` is built at the call site because it needs the catalog.
 */
data class UpNextTarget(
    val exerciseName: String,
    val setIndex: Int,
    val setCount: Int,
    val weightKg: Double,
    val reps: Int,
    val bestKg: Double? = null,
    val bestReps: Int? = null,
    /** The user's unit for the rest card (the weights above are kg). */
    val unit: WeightUnit = WeightUnit.Kg,
)
