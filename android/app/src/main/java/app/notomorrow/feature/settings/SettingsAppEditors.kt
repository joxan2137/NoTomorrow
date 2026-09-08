package app.notomorrow.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.notomorrow.R
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.PrimaryButton
import app.notomorrow.model.AIProvider
import app.notomorrow.push.rememberPushPermissionState
import app.notomorrow.service.HealthService
import app.notomorrow.util.S

// The "App" editors — `Features/Settings/SettingsAppEditors.swift`.

/** System / English / Polski. Applying the override recreates the Activity, so no relaunch hint. */
@Composable
fun LanguageEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = SettingsFormat.language(state.languageOverride)
    StEditorScaffold(stringResource(S.settings_language), onBack, modifier) {
        StGroup(footnote = stringResource(R.string.settings_language_applies)) {
            LanguageOption.entries.forEach { option ->
                row {
                    StCheckRow(
                        title = stringResource(
                            when (option) {
                                LanguageOption.System -> S.settings_system
                                LanguageOption.English -> S.settings_english
                                LanguageOption.Polish -> S.settings_polish
                            }
                        ),
                        selected = current == option,
                        onClick = { model.setLanguage(option.code) },
                    )
                }
            }
        }
    }
}

/**
 * The two reminder toggles plus the real permission states: notifications, and — Android only —
 * the exact-alarm grant the rest countdown depends on.
 */
@Composable
fun NotificationsEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notifications = rememberPushPermissionState()
    val exactAlarms = rememberExactAlarmState()

    val permissionValue = stringResource(
        when {
            notifications.areNotificationsEnabled -> S.settings_notifications_allowed
            !notifications.canRequest -> S.settings_notifications_denied
            else -> S.settings_notifications_notAsked
        }
    )

    StEditorScaffold(stringResource(S.settings_notifications), onBack, modifier) {
        StGroup(footnote = stringResource(S.settings_notifications_footnote)) {
            row {
                StToggleRow(
                    title = stringResource(S.onboarding_schedule_remindHourBefore),
                    checked = state.remindHourBefore,
                    onCheckedChange = {
                        model.setRemindHourBefore(it)
                        if (it) notifications.request()
                    },
                )
            }
            row {
                StToggleRow(
                    title = stringResource(S.onboarding_schedule_askIfSkipped),
                    detail = stringResource(S.onboarding_schedule_askIfSkipped_detail),
                    checked = state.askIfSkippedAt21,
                    onCheckedChange = {
                        model.setAskIfSkipped(it)
                        if (it) notifications.request()
                    },
                )
            }
        }

        StGroup(title = stringResource(S.settings_notifications_permission)) {
            row {
                StInfoRow(
                    label = stringResource(S.settings_notifications_permission),
                    value = permissionValue,
                    dot = if (notifications.areNotificationsEnabled) NT.Colors.good else null,
                )
            }
            row {
                // iOS only swaps to the settings deep link on `.denied`; every other state keeps
                // offering "Request permission" (`SettingsAppEditors.swift:45-51`).
                val isDenied = !notifications.areNotificationsEnabled && !notifications.canRequest
                if (isDenied) {
                    StActionRow(
                        label = stringResource(S.settings_notifications_openSettings),
                        onClick = { openNotificationSettings(context) },
                    )
                } else {
                    StActionRow(
                        label = stringResource(S.settings_notifications_request),
                        onClick = notifications::request,
                    )
                }
            }
            // The rest countdown needs an exact alarm; the grant is a settings page, not a dialog.
            if (exactAlarmsAreRequestable) {
                row {
                    StInfoRow(
                        label = stringResource(R.string.settings_alarms_exact),
                        value = stringResource(
                            if (exactAlarms) S.settings_notifications_allowed
                            else S.settings_notifications_denied
                        ),
                        dot = if (exactAlarms) NT.Colors.good else null,
                    )
                }
                if (!exactAlarms) {
                    row {
                        StActionRow(
                            label = stringResource(R.string.settings_alarms_request),
                            onClick = { openExactAlarmSettings(context) },
                        )
                    }
                }
            }
        }
    }
}

/** Health Connect: unavailable / needs a provider update / connectable. */
@Composable
fun HealthEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissions = remember(model) { model.healthPermissions }
    val launcher = rememberLauncherForActivityResult(HealthService.permissionsContract()) {
        model.refreshHealth()
    }
    LaunchedEffect(Unit) { model.refreshHealth() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshHealth() }

    val health = state.health

    StEditorScaffold(stringResource(S.settings_health), onBack, modifier) {
        NtText(
            text = stringResource(S.settings_health_description),
            style = NT.Fonts.subheadline,
            color = NT.Colors.ink2,
        )

        StGroup {
            row {
                StInfoRow(
                    label = stringResource(S.settings_health_status),
                    value = healthValue(health),
                    dot = if (health.isAuthorized) NT.Colors.good else null,
                )
            }
        }

        when {
            health.needsProviderUpdate -> {
                PrimaryButton(title = stringResource(R.string.settings_health_updateProvider)) {
                    openHealthConnectUpdate(context)
                }
            }

            health.isAvailable -> {
                PrimaryButton(title = stringResource(S.settings_health_connect)) {
                    launcher.launch(permissions)
                }
            }

            else -> {
                NtText(
                    text = stringResource(S.health_error_unavailable),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = NT.Fonts.footnote,
                    color = NT.Colors.ink2,
                )
            }
        }

        if (health.error != null) {
            NtText(
                text = health.error,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = NT.Fonts.footnote,
                color = NT.Colors.bad,
            )
        }
    }
}

/** Standard (Gemini through the backend), or Claude / Gemini with the user's own key (secure store). */
@Composable
fun AIProviderEditor(
    state: SettingsUiState,
    model: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var keyDraft by remember { mutableStateOf("") }
    var geminiKeyDraft by remember { mutableStateOf("") }

    StEditorScaffold(stringResource(S.settings_aiProvider), onBack, modifier) {
        StGroup {
            row {
                StCheckRow(
                    title = stringResource(S.settings_ai_standard),
                    detail = stringResource(S.settings_ai_standard_detail),
                    selected = state.aiProvider == AIProvider.Standard,
                    onClick = { model.setAiProvider(AIProvider.Standard) },
                )
            }
            row {
                StCheckRow(
                    title = stringResource(S.settings_ai_claude),
                    detail = stringResource(S.settings_ai_claude_detail),
                    selected = state.aiProvider == AIProvider.ClaudeBYOK,
                    onClick = { model.setAiProvider(AIProvider.ClaudeBYOK) },
                )
            }
            row {
                StCheckRow(
                    title = stringResource(S.settings_ai_gemini),
                    detail = stringResource(S.settings_ai_gemini_detail),
                    selected = state.aiProvider == AIProvider.GeminiBYOK,
                    onClick = { model.setAiProvider(AIProvider.GeminiBYOK) },
                )
            }
        }

        // `.animation(.easeOut(0.2), value: model.aiProvider)` — the key section fades in.
        AnimatedVisibility(
            visible = state.aiProvider == AIProvider.ClaudeBYOK,
            enter = fadeIn(tween(200, easing = NT.Ease.out)),
            exit = fadeOut(tween(200, easing = NT.Ease.out)),
        ) {
            AIKeySection(
                masked = state.maskedAnthropicKey,
                labelRes = S.settings_ai_keyLabel,
                placeholderRes = S.settings_ai_keyPlaceholder,
                footnoteRes = S.settings_ai_footnote,
                draft = keyDraft,
                onDraft = { keyDraft = it },
                onSave = {
                    model.saveAnthropicKey(keyDraft)
                    keyDraft = ""
                },
                onRemove = {
                    model.removeAnthropicKey()
                    keyDraft = ""
                },
            )
        }

        AnimatedVisibility(
            visible = state.aiProvider == AIProvider.GeminiBYOK,
            enter = fadeIn(tween(200, easing = NT.Ease.out)),
            exit = fadeOut(tween(200, easing = NT.Ease.out)),
        ) {
            AIKeySection(
                masked = state.maskedGeminiKey,
                labelRes = S.settings_ai_geminiKeyLabel,
                placeholderRes = S.settings_ai_geminiKeyPlaceholder,
                footnoteRes = S.settings_ai_geminiFootnote,
                draft = geminiKeyDraft,
                onDraft = { geminiKeyDraft = it },
                onSave = {
                    model.saveGeminiKey(geminiKeyDraft)
                    geminiKeyDraft = ""
                },
                onRemove = {
                    model.removeGeminiKey()
                    geminiKeyDraft = ""
                },
            )
        }

        StGroup(title = stringResource(S.settings_developer)) {
            row {
                StToggleRow(
                    title = stringResource(S.settings_demoData),
                    detail = stringResource(S.settings_demoData_detail),
                    checked = state.useDemoData,
                    onCheckedChange = model::setUseDemoData,
                )
            }
        }
    }
}

/** `AIProviderEditor.keySection` — the stored key with a Remove row, or the secure field and Save. */
@Composable
private fun AIKeySection(
    masked: String?,
    @StringRes labelRes: Int,
    @StringRes placeholderRes: Int,
    @StringRes footnoteRes: Int,
    draft: String,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (masked != null) {
            StGroup {
                row {
                    StInfoRow(
                        label = stringResource(labelRes),
                        value = masked,
                        dot = NT.Colors.good,
                    )
                }
                row {
                    StActionRow(
                        label = stringResource(S.settings_ai_removeKey),
                        onClick = onRemove,
                        color = NT.Colors.bad,
                    )
                }
            }
        } else {
            StLabeled(stringResource(labelRes)) {
                StTextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = stringResource(placeholderRes),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            PrimaryButton(
                title = stringResource(S.settings_ai_saveKey),
                height = NT.Size.cardButton,
                enabled = draft.trim().isNotEmpty(),
                onClick = onSave,
            )
        }

        NtText(
            text = stringResource(footnoteRes),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = NT.Fonts.footnote,
            color = NT.Colors.ink2,
        )
    }
}
