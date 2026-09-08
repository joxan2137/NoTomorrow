package app.notomorrow.feature.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.Grabber
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.designsystem.ntMediumDetent
import app.notomorrow.model.MealSlot
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * `AIScanAddMissedSheet` (`AIScanResultView.swift:246-269`) — "add something it missed".
 *
 * iOS ships this as a plain placeholder ("Until `FoodSearchView` lands…"): a `.medium` detent
 * with the slot title, the "not found" line and a Done button. It writes **nothing** — neither a
 * `MealEntry` nor a food on the estimate — so the port must not hand off to `FoodSearchSheet`,
 * which would log the food to Room immediately, even if the scan is then abandoned.
 */
@Composable
fun AIScanAddMissedSheet(
    meal: MealSlot,
    onDismiss: () -> Unit,
) {
    NtSheet(
        onDismiss = onDismiss,
        containerColor = NT.Colors.ground,
        // `.presentationDetents([.medium])`: a single fixed detent, so the weighted spacer above
        // the Done button cannot stretch the card to full height.
        height = ntMediumDetent(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(NT.Spacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Grabber() }

            NtText(
                text = stringResource(S.fuel_addTo, stringResource(NtKeys.meal(meal))),
                style = NT.Fonts.title2,
                color = NT.Colors.ink,
            )
            NtText(
                text = stringResource(S.fuel_notFound),
                style = NT.Fonts.subheadline,
                color = NT.Colors.ink2,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            SecondaryButton(title = stringResource(S.common_done), onClick = onDismiss)
        }
    }
}
