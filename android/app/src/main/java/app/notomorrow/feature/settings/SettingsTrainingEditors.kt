package app.notomorrow.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtSegmentedLarge
import app.notomorrow.designsystem.NtShapes
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.NtTimeWheel
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

// The "Training" editors — `Features/Settings/SettingsTrainingEditors.swift` and
// `Features/Settings/ScheduleEditor.swift`.

/** Default rest length in 15 s steps + the auto-start flag (`nt.rest.autoStart`). */
@Composable
fun RestTimerEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seconds = state.restSeconds

    StEditorScaffold(stringResource(S.settings_restTimer), onBack, modifier) {
        StGroup(footnote = stringResource(S.settings_restTimer_footnote)) {
            row {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // `.frame(minHeight: 56).padding(.vertical, 4)` = a 64 pt floor: the
                        // padding node has to stay OUTSIDE the min-size one to match.
                        .padding(vertical = 4.dp)
                        .defaultMinSize(minHeight = 56.dp),
                    horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NtText(
                        text = stringResource(S.settings_restTimer_length),
                        style = NT.Fonts.body,
                        color = NT.Colors.ink,
                    )
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    RestStepper(seconds = seconds, onChange = model::setRestSeconds)
                }
            }
            row {
                StToggleRow(
                    title = stringResource(S.settings_restTimer_autoStart),
                    detail = stringResource(S.settings_restTimer_autoStart_detail),
                    checked = state.restAutoStart,
                    onCheckedChange = model::setRestAutoStart,
                )
            }
        }
    }
}

@Composable
private fun RestStepper(seconds: Int, onChange: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StStepButton(
            icon = NtIcons.Minus,
            enabled = SettingsFormat.canStepRest(seconds, -SettingsFormat.REST_STEP),
            onClick = { onChange(SettingsFormat.steppedRest(seconds, -SettingsFormat.REST_STEP)) },
        )
        // `.frame(minWidth: 52)` centres its child, so the clock stays optically between the
        // two circles whether it reads "1:30" or "10:00".
        Box(
            modifier = Modifier.widthIn(min = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            // `.contentTransition(.numericText())` + `.easeOut(0.15)`: the digits roll from the
            // old reading straight to the new one — no in-between seconds are ever shown.
            AnimatedContent(
                targetState = seconds,
                transitionSpec = {
                    val up = targetState > initialState
                    val spec = tween<IntOffset>(150, easing = NT.Ease.out)
                    (slideInVertically(spec) { if (up) it else -it } + fadeIn(NT.Anim.easeOut15))
                        .togetherWith(
                            slideOutVertically(spec) { if (up) -it else it } +
                                fadeOut(NT.Anim.easeOut15)
                        )
                },
                label = "stRestClock",
            ) { value ->
                TabularText(
                    text = Fmt.clock(value),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                )
            }
        }
        StStepButton(
            icon = NtIcons.Plus,
            enabled = SettingsFormat.canStepRest(seconds, SettingsFormat.REST_STEP),
            onClick = { onChange(SettingsFormat.steppedRest(seconds, SettingsFormat.REST_STEP)) },
        )
    }
}

/** kg / lb — the 44 pt segmented control. */
@Composable
fun UnitsEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kg = stringResource(S.unit_kg)
    val lb = stringResource(S.unit_lb)
    StEditorScaffold(stringResource(S.settings_units), onBack, modifier) {
        StLabeled(
            label = stringResource(S.settings_units),
            footnote = stringResource(S.settings_units_footnote),
        ) {
            NtSegmentedLarge(
                options = WeightUnit.entries,
                selected = state.units,
                onSelect = model::setUnits,
                label = { if (it == WeightUnit.Kg) kg else lb },
            )
        }
    }
}

/**
 * Gym days · time: seven Monday-first day toggles and a wheel time picker, written straight to
 * `GymSchedule` and pushed to the bro when paired.
 */
@Composable
fun ScheduleEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = state.schedule
    var selected by remember(schedule?.weekdays) { mutableStateOf(state.weekdays.toSet()) }
    var minuteOfDay by remember(schedule?.defaultMinuteOfDay) { mutableStateOf(state.minuteOfDay) }
    var dirty by remember { mutableStateOf(false) }
    val isDirty by rememberUpdatedState(dirty)

    // `.onDisappear { if dirty { push() } }`
    DisposableEffect(Unit) {
        onDispose { if (isDirty) model.pushSchedule() }
    }

    fun apply(days: Set<Int>, minute: Int) {
        dirty = true
        model.saveSchedule(days, minute)
    }

    StEditorScaffold(stringResource(S.settings_gymDays), onBack, modifier) {
        StLabeled(
            label = stringResource(S.settings_gymDays),
            footnote = stringResource(
                if (selected.isEmpty()) S.onboarding_schedule_pickDays else S.settings_schedule_footnote
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..7).forEach { day ->
                    StDayToggle(
                        day = day,
                        isOn = selected.contains(day),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selected = if (selected.contains(day)) selected - day else selected + day
                            apply(selected, minuteOfDay)
                        },
                    )
                }
            }
        }

        StLabeled(stringResource(S.onboarding_schedule_usualTime)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    // `NtTimeWheel` is intrinsically `NtWheelRowHeight * NtWheelVisibleRows`
                    // = 32 × 7 = 224 dp, and `ScheduleEditor` shows it in a 180 dp frame — the
                    // same crop UIKit applies to `UIDatePicker`'s 216 pt intrinsic height. Without
                    // the clip the outermost rows paint outside the tile.
                    .clip(NtShapes.tile)
                    .background(NT.Colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                NtTimeWheel(
                    hour = minuteOfDay / 60,
                    minute = minuteOfDay % 60,
                    onChange = { hour, minute ->
                        minuteOfDay = hour * 60 + minute
                        apply(selected, minuteOfDay)
                    },
                )
            }
        }
    }
}

/** One day tile: the weekday letter on `ink` when on, `surface` when off, 44 pt tall, r 12. */
@Composable
private fun RowScope.StDayToggle(
    day: Int,
    isOn: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val label = stringResource(NtKeys.weekday(day))
    val accessibility = stringResource(NtKeys.weekdayShort(day))
    // `withAnimation(.easeOut(duration: 0.15))` on the selection change.
    val fill by animateColorAsState(
        targetValue = if (isOn) NT.Colors.ink else NT.Colors.surface,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "stDayToggleFill",
    )
    val content by animateColorAsState(
        targetValue = if (isOn) NT.Colors.onPrimary else NT.Colors.ink,
        animationSpec = tween(150, easing = NT.Ease.out),
        label = "stDayToggleContent",
    )
    Box(
        modifier = modifier
            .height(NT.Size.control)
            .background(color = fill, shape = NtShapes.rounded(NT.Radius.field))
            .ntPlainClickable(onClickLabel = accessibility, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NtText(
            text = label,
            style = NT.Fonts.subheadlineBold,
            color = content,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
