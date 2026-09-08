package app.notomorrow.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.SecondaryButton
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import app.notomorrow.util.S

/**
 * The partner row's destination: who you are paired with, since when, your code, and Unpair —
 * the port of `PartnerEditor` (`Features/Settings/SettingsPartnerEditor.swift`).
 */
@Composable
fun PartnerEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    onUnpaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showsUnpair by remember { mutableStateOf(false) }
    val name = state.partnerName ?: state.pairing?.partnerName.orEmpty()
    val since = state.pairedAt ?: state.pairing?.pairedAt ?: System.currentTimeMillis()

    StEditorScaffold(stringResource(S.settings_gymBro), onBack, modifier) {
        StGroup(footnote = stringResource(S.settings_bro_shareHint)) {
            row {
                StInfoRow(
                    label = stringResource(S.settings_bro_partner),
                    value = name,
                    dot = NT.Colors.good,
                )
            }
            row {
                StInfoRow(
                    label = stringResource(S.settings_bro_since),
                    value = Fmt.dayMonth(Days.date(since)),
                )
            }
            row { SettingsCodeRow(state.myCode ?: state.pairing?.myCode) }
        }

        SecondaryButton(
            title = stringResource(S.settings_unpair),
            modifier = Modifier.alphaLayer(if (state.isUnpairing) 0.5f else 1f),
            height = NT.Size.cardButton,
            tint = NT.Colors.bad,
            enabled = !state.isUnpairing,
        ) {
            showsUnpair = true
        }
    }

    if (showsUnpair) {
        NtActionSheet(
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.settings_unpair),
                    role = NtAlertRole.Destructive,
                    onClick = { model.unpair(onUnpaired) },
                ),
            ),
            cancel = stringResource(S.common_cancel),
            onDismiss = { showsUnpair = false },
            title = stringResource(S.settings_unpair_confirm_s, name),
            message = stringResource(S.settings_unpair_message),
        )
    }
}
