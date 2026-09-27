package app.notomorrow.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import app.notomorrow.BuildConfig
import app.notomorrow.R
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtActionSheet
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.TabularText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.designsystem.tabular
import app.notomorrow.model.AIProvider
import app.notomorrow.nav.NtRoute
import app.notomorrow.service.Days
import app.notomorrow.util.Fmt
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S

/**
 * The Settings root — grouped rows per `design/Settings.dc.html`, each pushing a small editor
 * (`SettingsView.swift:74-146`).
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    model: SettingsViewModel,
    onDone: () -> Unit,
    onRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
    signIn: (@Composable (onDone: () -> Unit) -> Unit)? = null,
) {
    var showsSignOut by remember { mutableStateOf(false) }
    var showsSignIn by remember { mutableStateOf(false) }
    var showsDelete by remember { mutableStateOf(false) }

    StRootScaffold(title = stringResource(S.settings_title), onDone = onDone, modifier = modifier) {
        YouGroup(state, onRoute)
        TrainingGroup(state, onRoute)
        AppGroup(state, onRoute)
        BroGroup(state, onRoute)
        AccountGroup(
            state = state,
            onSignIn = { showsSignIn = true },
            onSignOut = { showsSignOut = true },
            onDelete = { showsDelete = true },
        )
        VersionFooter()
    }

    if (showsSignIn && signIn != null) {
        signIn {
            showsSignIn = false
            model.loadBro()
        }
    }

    if (showsSignOut) {
        NtActionSheet(
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.settings_signOut),
                    role = NtAlertRole.Destructive,
                    onClick = model::signOut,
                ),
            ),
            cancel = stringResource(S.common_cancel),
            onDismiss = { showsSignOut = false },
            title = stringResource(S.settings_signOut_confirm),
            message = stringResource(S.settings_signOut_message),
        )
    }

    if (showsDelete) {
        NtAlert(
            title = stringResource(S.settings_delete_confirm),
            message = stringResource(S.settings_delete_message),
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.common_delete),
                    role = NtAlertRole.Destructive,
                    onClick = { model.deleteAccount(onDone) },
                ),
                NtAlertAction(title = stringResource(S.common_cancel), role = NtAlertRole.Cancel),
            ),
            onDismiss = { showsDelete = false },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Groups
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun YouGroup(state: SettingsUiState, onRoute: (String) -> Unit) {
    // `profiles.first ?? SettingsModel.profile(in:)` is fetch-or-create, so iOS's row always has
    // a name — before the first Room emission ours falls back to the same default.
    val name = state.profile?.name?.takeIf { it.isNotEmpty() } ?: stringResource(S.bro_you)
    val weight = state.profile?.bodyWeightKg?.let { Fmt.weight(it, state.units) } ?: EM_DASH
    val target = stringResource(
        S.settings_dailyTarget_value_s_s,
        Fmt.kcal((state.profile?.calorieGoal ?: 0).toDouble()),
        Fmt.grams((state.profile?.proteinGoalG ?: 0).toDouble()),
    )
    StGroup(title = stringResource(S.settings_you)) {
        row {
            StLinkRow(
                label = stringResource(S.common_name),
                value = name,
                onClick = { onRoute(NtRoute.SettingsName.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.common_bodyWeight),
                value = weight,
                onClick = { onRoute(NtRoute.SettingsBodyWeight.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_dailyTarget),
                value = target,
                onClick = { onRoute(NtRoute.SettingsDailyTarget.route) },
            )
        }
    }
}

@Composable
private fun TrainingGroup(state: SettingsUiState, onRoute: (String) -> Unit) {
    val days = SettingsFormat.gymDays(
        weekdays = state.weekdays,
        minuteOfDay = state.minuteOfDay,
        shortName = shortWeekdayNames(),
    )
    val rest = SettingsFormat.restTimer(
        seconds = state.restSeconds,
        autoStart = state.restAutoStart,
        autoStartLabel = stringResource(S.settings_autoStart),
    )
    StGroup(title = stringResource(S.settings_training)) {
        row {
            StLinkRow(
                label = stringResource(S.settings_gymDays),
                value = days,
                onClick = { onRoute(NtRoute.SettingsSchedule.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_restTimer),
                value = rest,
                onClick = { onRoute(NtRoute.SettingsRestTimer.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_units),
                value = state.units.raw,
                onClick = { onRoute(NtRoute.SettingsUnits.route) },
            )
        }
    }
}

@Composable
private fun AppGroup(state: SettingsUiState, onRoute: (String) -> Unit) {
    val language = stringResource(
        when (SettingsFormat.language(state.languageOverride)) {
            LanguageOption.English -> S.settings_english
            LanguageOption.Polish -> S.settings_polish
            LanguageOption.System -> S.settings_system
        }
    )
    val notifications = stringResource(
        S.settings_notifications_on_n,
        SettingsFormat.notificationCount(state.remindHourBefore, state.askIfSkippedAt21),
    )
    StGroup(title = stringResource(S.settings_app)) {
        row {
            StLinkRow(
                label = stringResource(S.settings_language),
                value = language,
                onClick = { onRoute(NtRoute.SettingsLanguage.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_notifications),
                value = notifications,
                onClick = { onRoute(NtRoute.SettingsNotifications.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_health),
                value = healthValue(state.health),
                onClick = { onRoute(NtRoute.SettingsHealth.route) },
                dot = if (state.health.isAuthorized) NT.Colors.good else null,
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_export),
                value = stringResource(R.string.settings_export_format),
                onClick = { onRoute(NtRoute.SettingsExport.route) },
            )
        }
        row {
            // `Text(verbatim:)` on iOS: the two app names are not localized.
            StLinkRow(
                label = stringResource(S.import_title),
                value = "Strong · Hevy",
                onClick = { onRoute(NtRoute.SettingsImport.route) },
            )
        }
        row {
            StLinkRow(
                label = stringResource(S.settings_aiProvider),
                value = stringResource(
                    when (state.aiProvider) {
                        AIProvider.Standard -> S.settings_ai_standardShort
                        AIProvider.ClaudeBYOK -> S.settings_ai_claudeShort
                        AIProvider.GeminiBYOK -> S.settings_ai_geminiShort
                    }
                ),
                onClick = { onRoute(NtRoute.SettingsAi.route) },
            )
        }
    }
}

@Composable
private fun BroGroup(state: SettingsUiState, onRoute: (String) -> Unit) {
    val partner = state.partnerName ?: state.pairing?.partnerName
    val pairedAt = state.pairedAt ?: state.pairing?.pairedAt
    StGroup(title = stringResource(S.settings_gymBro)) {
        if (partner != null) {
            row {
                StLinkRow(
                    label = partner,
                    value = stringResource(
                        S.settings_paired,
                        Fmt.dayMonth(Days.date(pairedAt ?: System.currentTimeMillis())),
                    ),
                    onClick = { onRoute(NtRoute.SettingsPartner.route) },
                )
            }
        } else {
            row {
                StInfoRow(stringResource(S.settings_bro_none), stringResource(S.settings_bro_pairInTab))
            }
        }
        row { SettingsCodeRow(state.myCode ?: state.pairing?.myCode) }
    }
}

@Composable
private fun AccountGroup(
    state: SettingsUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    StGroup(
        title = stringResource(S.settings_account),
        footnote = stringResource(S.settings_delete_footnote),
    ) {
        if (state.isSignedIn) {
            row {
                StInfoRow(
                    label = stringResource(S.settings_account_user),
                    value = state.accountName ?: stringResource(S.settings_signedIn),
                    dot = NT.Colors.good,
                )
            }
            row {
                StActionRow(
                    label = stringResource(S.settings_signOut),
                    onClick = onSignOut,
                    isBusy = state.isSigningOut,
                )
            }
        } else {
            row {
                StActionRow(
                    label = stringResource(S.settings_signIn),
                    onClick = onSignIn,
                    value = stringResource(S.settings_notSignedIn),
                )
            }
            // iOS shows the Apple/Google row here inert at 0.4; sign-in itself lives in the sheet.
            row {
                StInfoRow(
                    label = stringResource(S.auth_google),
                    value = "",
                    modifier = Modifier.alphaLayer(0.4f),
                )
            }
        }
        row {
            StActionRow(
                label = stringResource(S.settings_deleteAccount),
                onClick = onDelete,
                color = NT.Colors.bad,
                isBusy = state.isDeleting,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Version
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `SettingsView.versionFooter`: "Version 0.2.1 (57)" under the account group, so "which build is
 * installed" has an answer. Centred footnote in `ink3`, selectable like iOS's `.textSelection`.
 */
@Composable
private fun VersionFooter() {
    val label = SettingsFormat.versionLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    SelectionContainer(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        NtText(
            text = stringResource(S.settings_version_s, label),
            modifier = Modifier.fillMaxWidth(),
            style = NT.Fonts.footnote.tabular(),
            color = NT.Colors.ink3,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Code row
// ─────────────────────────────────────────────────────────────────────────────

/** "Your code · NT-7K4Q" with a share glyph instead of a chevron — the row **is** the share link. */
@Composable
fun SettingsCodeRow(code: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shareText = if (code != null) stringResource(S.bro_shareText, code) else ""
    val placeholder = stringResource(S.bro_codePlaceholder)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = NT.Size.control)
            .alphaLayer(if (code == null) 0.5f else 1f)
            .then(
                if (code == null) {
                    Modifier
                } else {
                    Modifier.ntPlainClickable {
                        // Explicit user action: the system chooser decides where the code goes.
                        ShareCompat.IntentBuilder(context)
                            .setType("text/plain")
                            .setText(shareText)
                            .createChooserIntent()
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .let(context::startActivity)
                    }
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(NT.Spacing.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NtText(stringResource(S.settings_yourCode), style = NT.Fonts.body, color = NT.Colors.ink)
        Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
        TabularText(code ?: placeholder, style = NT.Fonts.body, color = NT.Colors.ink2, maxLines = 1)
        if (code != null) {
            NtIcon(NtIcons.SquareAndArrowUp, size = sfIconSize(15f), tint = NT.Colors.ink2)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared helpers
// ─────────────────────────────────────────────────────────────────────────────

/** `healthValue` — unavailable / needs an update / connected / not connected. */
@Composable
internal fun healthValue(health: HealthState): String = stringResource(
    when {
        health.needsProviderUpdate -> R.string.settings_health_updateRequired
        !health.isAvailable -> S.settings_health_unavailable
        health.isAuthorized -> S.settings_connected
        else -> S.settings_notConnected
    }
)

/** `SettingsModel.shortKey(isoWeekday:)` resolved once for all seven days. */
@Composable
internal fun shortWeekdayNames(): (Int) -> String {
    val names = (1..7).map { stringResource(NtKeys.weekdayShort(it)) }
    return remember(names) { { iso -> names[iso.coerceIn(1, 7) - 1] } }
}

/** iOS prints a literal em dash when there is no body weight yet. */
private const val EM_DASH = "—"
