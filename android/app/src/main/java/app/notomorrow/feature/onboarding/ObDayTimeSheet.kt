package app.notomorrow.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.GhostButton
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntMediumDetent
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * "Hold a day for a different time": the per-weekday override of the usual time.
 * `.presentationDetents([.medium])`, own grabber, `ground` background.
 */
@Composable
fun ObDayTimeSheet(
    day: Int,
    minute: Int?,
    usualMinute: Int,
    onSave: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var minuteOfDay by remember(day) { mutableIntStateOf(minute ?: usualMinute) }

    NtSheet(
        onDismiss = onDismiss,
        containerColor = NT.Colors.ground,
        // `.presentationDetents([.medium])`: a single fixed detent, so the card sits at exactly
        // half the screen and the weighted spacer below cannot stretch it to full height.
        height = ntMediumDetent(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NT.Spacing.screenH),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Grabber() }

            NtText(
                text = stringResource(
                    S.onboarding_schedule_dayTime_title_s,
                    stringResource(NtKeys.weekdayShort(day)),
                ),
                modifier = Modifier.padding(top = 18.dp),
                style = NT.Fonts.title2,
                color = NT.Colors.ink,
            )

            ObTimeWheel(
                hour = minuteOfDay / 60,
                minute = minuteOfDay % 60,
                onChange = { hour, m -> minuteOfDay = hour * 60 + m },
                modifier = Modifier.padding(top = 18.dp),
            )

            Spacer(Modifier.heightIn(min = 16.dp).weight(1f))

            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GhostButton(title = stringResource(S.onboarding_schedule_dayTime_useUsual)) {
                    onSave(null)
                    onDismiss()
                }
                PrimaryButton(title = stringResource(S.common_save)) {
                    // Setting a day back to the usual time clears the override, exactly as on iOS.
                    onSave(if (minuteOfDay == usualMinute) null else minuteOfDay)
                    onDismiss()
                }
            }
        }
    }
}
