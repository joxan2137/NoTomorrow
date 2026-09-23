package app.notomorrow.feature.fuel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.designsystem.NtIcon
import app.notomorrow.designsystem.NtIcons
import app.notomorrow.designsystem.NtText
import app.notomorrow.designsystem.ntPlainClickable
import app.notomorrow.designsystem.sfIconSize
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.di.ntViewModel
import app.notomorrow.model.MealSlot
import app.notomorrow.util.S
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * `AIScanView` (`Features/Fuel/AIScanView.swift`) — the AI photo estimate for one meal slot:
 * pick a source → analysing → editable result → log.
 *
 * Presented by `FuelHomeScreen` inside an `NtSheet`, so the screen paints its own `ground`
 * and owns nothing above it. It dismisses itself after logging.
 */
@Composable
fun AIScanScreen(
    meal: MealSlot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    day: LocalDate = LocalDate.now(),
    onLogged: () -> Unit = {},
) {
    val model = ntViewModel(key = "aiScan") { container ->
        AIScanViewModel(
            application = container.app,
            prefs = container.appPrefs,
            providers = container.aiEstimateService,
            mealDao = container.db.mealDao(),
            foodDao = container.db.foodDao(),
            foodSearch = container.foodSearchService,
            needsSignIn = container.authStore.needsSignIn,
            initialMeal = meal,
        )
    }
    // `AIScanView.init` makes a new `AIScanModel(meal:)` per presentation; the Android view model
    // is owned by the Fuel back-stack entry, so every entry into this sheet restarts it.
    LaunchedEffect(meal) { model.start(meal) }
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(NT.Colors.ground),
    ) {
        Column(Modifier.fillMaxSize()) {
            AIScanHeader(
                showsRetake = state.showsRetake,
                onBack = onDismiss,
                onRetake = model::retake,
            )
            AIScanContent(
                state = state,
                model = model,
                onLog = {
                    scope.launch {
                        // Nothing written is no success: stay on the result (Log is disabled then).
                        if (model.log(day) == 0) return@launch
                        // `UINotificationFeedbackGenerator().notificationOccurred(.success)`
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        onLogged()
                        onDismiss()
                    }
                },
            )
        }

        AIScanToast(
            toast = state.toast,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (state.showConsent) {
        val provider = stringResource(state.providerNameRes)
        NtAlert(
            title = stringResource(S.fuel_ai_consent_title, provider),
            message = stringResource(S.fuel_ai_consent_body, provider),
            actions = listOf(
                NtAlertAction(
                    title = stringResource(S.fuel_ai_consent_accept),
                    onClick = model::acceptConsent,
                ),
                NtAlertAction(
                    title = stringResource(S.common_cancel),
                    role = NtAlertRole.Cancel,
                    onClick = model::declineConsent,
                ),
            ),
            // Runs before a tapped button's handler as well as on an outside tap / back, so it
            // only hides the alert; the model turns an un-actioned dismissal into the decline.
            onDismiss = model::dismissConsent,
        )
    }
}

/** `AIScanView.header` — 44 dp: back chevron, title, and "Retake" once a photo exists. */
@Composable
private fun AIScanHeader(
    showsRetake: Boolean,
    onBack: () -> Unit,
    onRetake: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(NT.Size.control)
            .padding(horizontal = NT.Spacing.screenH),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(NT.Size.control)
                .ntPlainClickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            // `.accessibilityLabel(Text("common.back"))` names the *control*, not the action.
            NtIcon(
                NtIcons.ArrowLeft,
                size = sfIconSize(20f),
                tint = NT.Colors.ink,
                contentDescription = stringResource(S.common_back),
            )
        }

        NtText(
            text = stringResource(S.fuel_ai_title),
            modifier = Modifier.weight(1f),
            style = NT.Fonts.title2,
            color = NT.Colors.ink,
            maxLines = 1,
        )

        if (showsRetake) {
            Box(
                modifier = Modifier
                    .height(NT.Size.control)
                    .ntPlainClickable(onClick = onRetake),
                contentAlignment = Alignment.Center,
            ) {
                NtText(
                    text = stringResource(S.fuel_ai_retake),
                    style = NT.Fonts.body,
                    color = NT.Colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

/** `AIScanView.content` — the phase switch. */
@Composable
private fun AIScanContent(
    state: AIScanUiState,
    model: AIScanViewModel,
    onLog: () -> Unit,
) {
    when (val phase = state.phase) {
        AIScanPhase.PickSource ->
            if (state.showsSignedOut) {
                AIScanSignedOutView(meal = state.meal)
            } else {
                AIScanSourceView(
                    meal = state.meal,
                    notes = state.notes,
                    onNotes = model::setNotes,
                    onPickedFromLibrary = model::pickedFromLibrary,
                    onCaptured = model::capturedPhoto,
                )
            }

        AIScanPhase.Analyzing -> AIScanAnalyzingView(photo = state.photo)

        AIScanPhase.Result -> AIScanResultView(
            state = state,
            onNotes = model::setNotes,
            onRefine = model::analyze,
            onScale = model::scale,
            onSetGrams = model::setGrams,
            onStepCount = model::stepCount,
            onUpdate = model::update,
            onRemove = model::remove,
            onAppend = model::append,
            onMeal = model::setMeal,
            onSaveRecipe = model::saveAsRecipe,
            onLog = onLog,
        )

        is AIScanPhase.Failed -> AIScanFailedView(
            photo = state.photo,
            messageRes = phase.messageRes,
            onRetake = model::retake,
        )

        // iOS presents `SettingsView()` from the card itself; here the sheet is hoisted on
        // `AppState`, so the button only raises the flag `SettingsSheetHost` observes.
        AIScanPhase.NotAllowed -> {
            val appState = LocalAppContainer.current.appState
            AIScanNotAllowedView(onOpenSettings = { appState.showsSettings.value = true })
        }
    }
}

/**
 * `AIScanView.toastOverlay` — a `surface2` capsule at least 44 dp tall, 84 dp above the bottom
 * edge, moving in from below with the phase's opacity. It grows with its text (a failed refine's
 * message runs to two or three lines at large font sizes) and is a polite live region, so
 * TalkBack reads it out when it appears.
 */
@Composable
private fun AIScanToast(
    toast: Int?,
    modifier: Modifier = Modifier,
) {
    // `.transition(.move(edge: .bottom).combined(with: .opacity))`.
    val slide = remember { tween<IntOffset>(200, easing = NT.Ease.out) }
    AnimatedVisibility(
        visible = toast != null,
        modifier = modifier,
        enter = slideInVertically(slide) { it } + fadeIn(NT.Anim.easeOut20),
        exit = slideOutVertically(slide) { it } + fadeOut(NT.Anim.easeOut20),
    ) {
        // Keeps the last text while the exit transition runs.
        val last = remember { intArrayOf(S.fuel_ai_recipeSoon) }
        if (toast != null) last[0] = toast
        val text = last[0]
        Box(
            modifier = Modifier
                .padding(horizontal = NT.Spacing.screenH)
                .padding(bottom = NT.Size.primaryButton + 28.dp)
                .heightIn(min = NT.Size.control)
                .background(NT.Colors.surface2, RoundedCornerShape(NT.Size.control / 2))
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            NtText(
                text = stringResource(text),
                style = NT.Fonts.subheadlineBold,
                color = NT.Colors.ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}
