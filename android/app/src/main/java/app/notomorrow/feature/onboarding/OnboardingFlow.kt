package app.notomorrow.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.notomorrow.designsystem.NT
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.di.ntViewModel
import app.notomorrow.service.BroService
import app.notomorrow.util.S
import app.notomorrow.app.AppLocale
import java.util.Locale

/**
 * Welcome → You → Schedule → Pair (or Pair first via "Pair now") — the port of
 * `OnboardingFlow.swift`.
 *
 * A **state machine, not a back stack**: the step order is mutable and one `AnimatedContent`
 * renders whichever step is current. The new step slides in from the side it was reached from;
 * the old one only fades out (`.asymmetric`, research §5.2 row 27).
 *
 * Nothing is written to Room until the last Continue / Not now.
 */
@Composable
fun OnboardingFlow() {
    val container = LocalAppContainer.current
    val appState = container.appState
    val model = ntViewModel { c ->
        OnboardingViewModel(
            profileDao = c.db.profileDao(),
            scheduleDao = c.db.scheduleDao(),
            bodyWeightDao = c.db.bodyWeightDao(),
            routineSeeder = c.routineSeeder,
            // iOS's `OnboardingModel` owns its own `BroService()`, not the shared one.
            bro = BroService(
                clientProvider = { c.appConfig.makeBackendClient() },
                scheduleDao = c.db.scheduleDao(),
                attendanceDao = c.db.attendanceDao(),
                headsUpDao = c.db.headsUpDao(),
                pairingDao = c.db.broPairingDao(),
            ),
            authStore = c.authStore,
            appState = c.appState,
        )
    }

    val state by model.state.collectAsStateWithLifecycle()
    val languageOverride by appState.languageOverride.collectAsStateWithLifecycle()
    val fallbackName = stringResource(S.bro_you)

    var showsTargetEditor by remember { mutableStateOf(false) }
    var overrideDay by remember { mutableStateOf<Int?>(null) }

    // `SetupPairView.task { await model.loadCode() }`: swap the local code for the backend's the
    // first time the pair step is on screen.
    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.Pair) model.loadCode()
    }

    // The back arrow and the system back gesture do the same thing; on Welcome there is nothing
    // to go back to, so the gesture leaves the app exactly as iOS's first screen does.
    BackHandler(enabled = state.step != OnboardingStep.Welcome) { model.back() }

    Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { onboardingStepTransition(state.movesForward) },
            label = "onboardingStep",
        ) { step ->
            when (step) {
                OnboardingStep.Welcome -> WelcomeScreen(
                    language = languageOverride ?: obSystemLanguage(),
                    onLanguage = appState::setLanguageOverride,
                    onGetStarted = model::startStandard,
                    onPairNow = model::startWithPair,
                )

                OnboardingStep.You -> SetupYouScreen(
                    state = state,
                    onName = model::setName,
                    onWeight = model::setWeightText,
                    onUnit = model::setUnit,
                    onGoal = model::setGoal,
                    onEditTarget = { showsTargetEditor = true },
                    onBack = model::back,
                    onContinue = model::next,
                )

                OnboardingStep.Schedule -> SetupScheduleScreen(
                    state = state,
                    onToggleDay = model::toggleDay,
                    onHoldDay = { overrideDay = it },
                    onTime = model::setUsualTime,
                    onRemindHourBefore = model::setRemindHourBefore,
                    onAskIfSkipped = model::setAskIfSkippedAt21,
                    onBack = model::back,
                    onContinue = model::next,
                )

                OnboardingStep.Pair -> SetupPairScreen(
                    state = state,
                    onCodeEntry = model::setCodeEntry,
                    onPair = model::pair,
                    onBack = model::back,
                    onAdvance = {
                        if (state.isLastStep) model.finish(fallbackName) else model.next()
                    },
                )
            }
        }
    }

    if (showsTargetEditor) {
        ObTargetEditorSheet(
            state = state,
            onUseSuggestion = model::useSuggestedTargets,
            onSave = model::saveTargets,
            onDismiss = { showsTargetEditor = false },
        )
    }

    overrideDay?.let { day ->
        ObDayTimeSheet(
            day = day,
            minute = state.overrides[day],
            usualMinute = state.usualMinuteOfDay,
            onSave = { minute -> model.setOverride(day, minute) },
            onDismiss = { overrideDay = null },
        )
    }
}

/**
 * `.asymmetric(insertion: .move(edge:).combined(with: .opacity), removal: .opacity)` with
 * `.easeInOut(duration: 0.28)`.
 */
private fun onboardingStepTransition(forward: Boolean): ContentTransform = ContentTransform(
    targetContentEnter = slideInHorizontally(
        animationSpec = tween(280, easing = NT.Ease.inOut),
        initialOffsetX = { width -> if (forward) width else -width },
    ) + fadeIn(tween(280, easing = NT.Ease.inOut)),
    initialContentExit = fadeOut(tween(280, easing = NT.Ease.inOut)),
    sizeTransform = SizeTransform(clip = false),
)

/** `OBL10n.systemLanguage` — "pl" when the device prefers Polish, "en" otherwise. */
private fun obSystemLanguage(): String =
    if (AppLocale.systemLanguage().lowercase(Locale.ROOT).startsWith("pl")) "pl" else "en"
