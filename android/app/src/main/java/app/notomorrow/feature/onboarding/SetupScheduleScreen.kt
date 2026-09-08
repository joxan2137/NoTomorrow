package app.notomorrow.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Hairline
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.push.rememberPushPermissionState
import app.notomorrow.util.P
import app.notomorrow.util.S

/** Step "Schedule": 7 day toggles (Mon-first), usual time wheel, two reminder toggles. */
@Composable
fun SetupScheduleScreen(
    state: OnboardingUiState,
    onToggleDay: (Int) -> Unit,
    onHoldDay: (Int) -> Unit,
    onTime: (hour: Int, minute: Int) -> Unit,
    onRemindHourBefore: (Boolean) -> Unit,
    onAskIfSkipped: (Boolean) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // iOS asks for notification authorization from Settings; on Android the permission has to be
    // requested from a screen, and the contract puts it here, beside the two reminder toggles.
    // Only ever in response to a tap: both toggles default to on, so prompting on appear would
    // put the system dialog in front of the user the instant the step arrives — iOS never does.
    val notifications = rememberPushPermissionState()

    ObStepScaffold(
        index = state.stepIndex ?: 1,
        count = state.stepCount,
        onBack = onBack,
        modifier = modifier,
        footer = {
            PrimaryButton(
                title = stringResource(S.common_continue),
                enabled = state.weekdays.isNotEmpty(),
                onClick = onContinue,
            )
        },
    ) {
        ObStepTitle(
            title = stringResource(S.onboarding_schedule_title),
            subtitle = stringResource(S.onboarding_schedule_subtitle),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            (1..7).forEach { day ->
                ObDayToggle(
                    day = day,
                    isOn = state.weekdays.contains(day),
                    overrideMinute = state.overrides[day],
                    modifier = Modifier.weight(1f),
                    onTap = { onToggleDay(day) },
                    onHold = { onHoldDay(day) },
                )
            }
        }

        ObSplitLine(
            days = state.weekdays.size,
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 12.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                NtText(
                    text = stringResource(S.onboarding_schedule_usualTime),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.headline,
                    color = NT.Colors.ink,
                )
                Spacer(Modifier.widthIn(min = 12.dp).weight(1f))
                NtText(
                    text = stringResource(S.onboarding_schedule_holdHint),
                    modifier = Modifier.alignByBaseline(),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                    textAlign = TextAlign.End,
                )
            }
            ObTimeWheel(
                hour = state.usualHour,
                minute = state.usualMinute,
                onChange = onTime,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(top = 24.dp),
        ) {
            ObToggleRow(
                title = stringResource(S.onboarding_schedule_remindHourBefore),
                checked = state.remindHourBefore,
                onCheckedChange = {
                    onRemindHourBefore(it)
                    if (it) notifications.request()
                },
            )
            Hairline()
            ObToggleRow(
                title = stringResource(S.onboarding_schedule_askIfSkipped),
                detail = stringResource(S.onboarding_schedule_askIfSkipped_detail),
                checked = state.askIfSkippedAt21,
                onCheckedChange = {
                    onAskIfSkipped(it)
                    if (it) notifications.request()
                },
            )
        }
    }
}

/** "3 days a week · Use the Push / Pull split ›", or the "pick a day" nudge when nothing is on. */
@Composable
private fun ObSplitLine(days: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (days == 0) {
            NtText(
                text = stringResource(S.onboarding_schedule_pickDays),
                style = NT.Fonts.footnote,
                color = NT.Colors.ink2,
            )
        } else {
            val split = when (OnboardingUiState.splitKind(days)) {
                SplitKind.FullBody -> S.split_fullBody
                SplitKind.PushPull -> S.split_pushPull
                SplitKind.PushPullLegs -> S.split_pushPullLegs
            }
            TabularText(
                text = pluralStringResource(P.onboarding_schedule_daysPerWeek_n, days, days) +
                    " · " +
                    stringResource(S.onboarding_schedule_useSplit_s, stringResource(split)),
                modifier = Modifier.weight(1f, fill = false),
                style = NT.Fonts.footnoteBold,
                color = NT.Colors.ember,
                maxLines = 2,
            )
            NtIcon(NtIcons.ChevronRight, size = sfIconSize(11f), tint = NT.Colors.ember)
        }
    }
}
