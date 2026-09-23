package app.notomorrow.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.CapsuleShape
import app.notomorrow.designsystem.GlassStyle
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ProgressRing
import app.notomorrow.designsystem.liquidGlass
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.pressScale
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.rest.RestTimerState
import app.notomorrow.util.Fmt
import app.notomorrow.util.S
import app.notomorrow.util.rememberNtStrings

/**
 * What the mini bar says at one moment — 1:1 port of `WorkoutMiniBarState`
 * (`WorkoutMiniBar.swift`). Pure, so the phrasing rules are testable.
 *
 * @property elapsed "42:10" / "1:02:03" since the workout started.
 * @property restRemaining seconds of rest left, `null` when not resting.
 * @property restFraction share of the rest still to go (1 → 0), for the ring.
 * @property exerciseName resting: the exercise up next; otherwise the one the user is on. Empty
 *   when there is none.
 */
data class WorkoutMiniBarState(
    val elapsed: String,
    val restRemaining: Double?,
    val restFraction: Double,
    val exerciseName: String,
) {
    val isResting: Boolean get() = restRemaining != null

    /**
     * TalkBack's state for the bar: the exercise, or "Rest, Squat" while resting. The countdown
     * stays out on purpose: a focused node whose state changes is read out again, and a
     * second-by-second countdown would make TalkBack repeat the bar every tick. What changes at
     * most once a minute (the elapsed minutes in the label) is coarse enough to follow.
     */
    fun accessibilityValue(restLabel: String): String {
        if (!isResting) return exerciseName
        return if (exerciseName.isEmpty()) restLabel else "$restLabel, $exerciseName"
    }

    companion object {
        fun of(
            startedAt: Long,
            now: Long,
            restEnd: Long?,
            restTotal: Int,
            upNextName: String,
            currentName: String?,
        ): WorkoutMiniBarState {
            val elapsed = Fmt.elapsed((now - startedAt) / 1000.0)
            if (restEnd != null && restEnd > now) {
                val remaining = (restEnd - now) / 1000.0
                return WorkoutMiniBarState(
                    elapsed = elapsed,
                    restRemaining = remaining,
                    restFraction = if (restTotal > 0) minOf(1.0, remaining / restTotal) else 0.0,
                    exerciseName = upNextName.ifEmpty { currentName.orEmpty() },
                )
            }
            return WorkoutMiniBarState(
                elapsed = elapsed,
                restRemaining = null,
                restFraction = 0.0,
                exerciseName = currentName.orEmpty(),
            )
        }
    }
}

/**
 * The workout in progress, pinned above the tab bar on every tab — 1:1 port of `WorkoutMiniBar`
 * (`WorkoutMiniBar.swift`): name, elapsed time and current exercise; while resting, the
 * countdown, what is up next and a Skip button. Tapping anywhere else expands the workout.
 *
 * Stateless: everything comes from the workout's view model and the rest timer, so collapsing and
 * tab switches lose nothing. A Liquid Glass capsule like the tab bar, and like it drawn **outside**
 * the recorded page (`MainTabScaffold`); its own graphics layer keeps the one-second tick from
 * repainting anything else.
 */
@Composable
fun WorkoutMiniBar(
    name: String,
    startedAt: Long,
    currentExerciseName: String?,
    rest: RestTimerState,
    onExpand: () -> Unit,
    onSkipRest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Not `rest.isRunning()` at composition: the countdown and the resting look flip on the tick.
    val now by rememberSecondTicker()
    val strings = rememberNtStrings()
    val state = WorkoutMiniBarState.of(
        startedAt = startedAt,
        now = now,
        restEnd = rest.endAt,
        restTotal = rest.totalSeconds,
        upNextName = rest.exerciseName,
        currentName = currentExerciseName,
    )
    val restLabel = stringResource(S.timer_rest)
    val label = stringResource(
        S.workout_miniBar_label_s_s,
        name,
        Fmt.duration(((now - startedAt).coerceAtLeast(0L)) / 1000.0, strings),
    )
    val value = state.accessibilityValue(restLabel)
    // TalkBack reads "Double-tap to <action>", so this is the action phrase ("otworzyć trening"),
    // not the iOS hint sentence ("Otwiera trening").
    val action = stringResource(S.workout_miniBar_action)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WorkoutMiniBarTokens.height)
            .graphicsLayer()
            .liquidGlass(CapsuleShape, GlassStyle.Regular)
            .padding(start = 12.dp, end = if (state.isResting) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .ntPlainClickable(role = Role.Button, onClickLabel = action, onClick = onExpand)
                .clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = value
                    role = Role.Button
                    onClick(label = action) {
                        onExpand()
                        true
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Leading(state)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                NtText(text = name, style = NT.Fonts.subheadlineBold, color = NT.Colors.ink, maxLines = 1)
                Detail(state, restLabel)
            }
            if (!state.isResting) {
                // `chevron.up`: the set has only the down chevron, turned over.
                NtIcon(
                    icon = NtIcons.ChevronDown,
                    modifier = Modifier.size(24.dp).rotate(180f),
                    size = sfIconSize(13f),
                    tint = NT.Colors.ink2,
                )
            }
        }
        if (state.isResting) SkipButton(onClick = onSkipRest)
    }
}

/** An 8 dp ember dot, or while resting a 24 dp ring draining with the rest. */
@Composable
private fun Leading(state: WorkoutMiniBarState) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (state.isResting) {
            ProgressRing(
                progress = state.restFraction,
                modifier = Modifier.size(24.dp),
                lineWidth = 3.dp,
            )
        } else {
            Box(Modifier.size(8.dp).background(NT.Colors.ember, CircleShape))
        }
    }
}

/** "42:10 · Bench press", or while resting "Rest 1:12 · Squat" with the countdown in ember. */
@Composable
private fun Detail(state: WorkoutMiniBarState, restLabel: String) {
    val style = NT.Fonts.footnote.tabular()
    Row(verticalAlignment = Alignment.CenterVertically) {
        val remaining = state.restRemaining
        if (remaining != null) {
            NtText(text = "$restLabel ", style = style, color = NT.Colors.ink2, maxLines = 1)
            NtText(text = Fmt.clock(remaining), style = style, color = NT.Colors.ember, maxLines = 1)
        } else {
            NtText(text = state.elapsed, style = style, color = NT.Colors.ink2, maxLines = 1)
        }
        if (state.exerciseName.isNotEmpty()) {
            NtText(
                text = " · " + state.exerciseName,
                modifier = Modifier.weight(1f, fill = false),
                style = style,
                color = NT.Colors.ink2,
                maxLines = 1,
            )
        }
    }
}

/** `common.skip` on an ink capsule, 32 dp tall in a 44 dp hit area. */
@Composable
private fun SkipButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier.heightIn(min = NT.Size.control).pressScale(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .background(NT.Colors.ink, CapsuleShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(S.common_skip),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.onPrimary,
                maxLines = 1,
            )
        }
    }
}

/** The iOS bar's geometry (`WorkoutMiniBar.height`, its inset padding), in one place. */
object WorkoutMiniBarTokens {
    /** `NT.Size.cardButton`. */
    val height = NT.Size.cardButton

    /** Clear space between the bar and the tab bar under it. */
    val gap = 8.dp

    /** Everything the bar adds to the bottom chrome while it shows. */
    val reserved = height + gap
}
