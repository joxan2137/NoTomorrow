package app.notomorrow.feature.fuel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NTPressScale
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtMenu
import app.notomorrow.designsystem.NtMenuItem
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.model.MealSlot
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * `LogToMealButton` (`AIScanResultView.swift:132`) — the white pill that logs on tap and opens
 * the slot menu on long press, the Android reading of SwiftUI's `Menu { … } primaryAction:`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogToMealButton(
    meal: MealSlot,
    onMeal: (MealSlot) -> Unit,
    onLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val items = MealSlotOrdered.map { slot ->
        NtMenuItem(
            title = stringResource(NtKeys.meal(slot)),
            onClick = { onMeal(slot) },
            icon = if (slot == meal) NtIcons.Checkmark else null,
        )
    }

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(NT.Size.primaryButton)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = NTPressScale,
                    role = Role.Button,
                    onClick = onLog,
                    onLongClick = { expanded = true },
                )
                .background(NT.Colors.ink, CircleShape)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NtText(
                text = stringResource(S.fuel_logTo, stringResource(NtKeys.meal(meal))),
                style = NT.Fonts.headline,
                color = NT.Colors.onPrimary,
                maxLines = 1,
            )
            NtIcon(NtIcons.ChevronDown, size = sfIconSize(14f), tint = NT.Colors.onPrimary)
        }
        NtMenu(expanded = expanded, onDismiss = { expanded = false }, items = items)
    }
}
