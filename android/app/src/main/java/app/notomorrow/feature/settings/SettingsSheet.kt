package app.notomorrow.feature.settings

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtSheet
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.di.ntViewModel
import app.notomorrow.nav.NtRoute

/**
 * The modal Settings sheet — `.large` [NtSheet] hosting its own `NavHost` over the twelve
 * `settings/…` routes (`SettingsView.swift`, `docs/android-architecture.md` → Settings).
 *
 * One [SettingsViewModel] serves the root and every editor, mirroring the single `SettingsModel`
 * every Swift editor binds to. Sign-in is a **slot**: the sheet knows when to ask for it, the
 * shell supplies `feature/auth`'s sheet, so Settings never depends on the auth package.
 */
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    signIn: (@Composable (onDone: () -> Unit) -> Unit)? = null,
) {
    val model = ntViewModel(key = SETTINGS_VM_KEY) { container -> SettingsViewModel.create(container) }
    val state by model.uiState.collectAsStateWithLifecycle()

    // `.task { await model.loadBro(…) }` / `.task { await model.refreshNotificationStatus() }`.
    LaunchedEffect(Unit) {
        model.loadBro()
        model.refreshHealth()
    }

    // Every Settings page is a scroll view, so the navigation-bar inset belongs to its scroll
    // *content* (`StScrollContent`) and not to the sheet's viewport — otherwise the list is cut
    // 24 dp short of the card edge where iOS runs it right to the bottom.
    NtSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        containerColor = NT.Colors.ground,
        clearsNavigationBar = false,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight()) {
            val nav = rememberNavController()
            SettingsNavHost(
                nav = nav,
                state = state,
                model = model,
                onDone = onDismiss,
                signIn = signIn,
            )
        }
    }
}

/**
 * Renders [SettingsSheet] whenever `AppState.showsSettings` is set — by the Dashboard avatar or
 * by a `settings` push route. Mount it once, beside the tab bar.
 */
@Composable
fun SettingsSheetHost(
    modifier: Modifier = Modifier,
    signIn: (@Composable (onDone: () -> Unit) -> Unit)? = null,
) {
    val appState = LocalAppContainer.current.appState
    val shows by appState.showsSettings.collectAsStateWithLifecycle()
    if (shows) {
        SettingsSheet(
            onDismiss = { appState.showsSettings.value = false },
            modifier = modifier,
            signIn = signIn,
        )
    }
}

@Composable
private fun SettingsNavHost(
    nav: NavHostController,
    state: SettingsUiState,
    model: SettingsViewModel,
    onDone: () -> Unit,
    signIn: (@Composable (onDone: () -> Unit) -> Unit)?,
) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        navController = nav,
        startDestination = NtRoute.Settings.route,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable(NtRoute.Settings.route) {
            SettingsScreen(
                state = state,
                model = model,
                onDone = onDone,
                onRoute = { route -> nav.navigate(route) },
                signIn = signIn,
            )
        }
        composable(NtRoute.SettingsName.route) { NameEditor(state, model, back) }
        composable(NtRoute.SettingsBodyWeight.route) { BodyWeightEditor(state, model, back) }
        composable(NtRoute.SettingsDailyTarget.route) { DailyTargetEditor(state, model, back) }
        composable(NtRoute.SettingsSchedule.route) { ScheduleEditor(state, model, back) }
        composable(NtRoute.SettingsRestTimer.route) { RestTimerEditor(state, model, back) }
        composable(NtRoute.SettingsUnits.route) { UnitsEditor(state, model, back) }
        composable(NtRoute.SettingsLanguage.route) { LanguageEditor(state, model, back) }
        composable(NtRoute.SettingsNotifications.route) { NotificationsEditor(state, model, back) }
        composable(NtRoute.SettingsHealth.route) { HealthEditor(state, model, back) }
        composable(NtRoute.SettingsExport.route) { ExportEditor(state, model, back) }
        composable(NtRoute.SettingsImport.route) { ImportEditor(state, back) }
        composable(NtRoute.SettingsAi.route) { AIProviderEditor(state, model, back) }
        composable(NtRoute.SettingsPartner.route) {
            PartnerEditor(
                state = state,
                model = model,
                onBack = back,
                // `PartnerEditor(onUnpaired: { path.removeAll() })` — unpairing pops to the root.
                onUnpaired = { nav.popBackStack(NtRoute.Settings.route, inclusive = false) },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Push transitions — UIKit's navigation push, not Material's shared-axis
// ─────────────────────────────────────────────────────────────────────────────

private const val PUSH_MS = 350

/** The outgoing screen only travels a third of the width, as a UINavigationController push does. */
private const val PARALLAX = 3

private fun AnimatedContentTransitionScope<*>.pushEnter(): EnterTransition =
    slideInHorizontally(tween(PUSH_MS, easing = NT.Ease.inOut)) { it }

private fun AnimatedContentTransitionScope<*>.pushExit(): ExitTransition =
    slideOutHorizontally(tween(PUSH_MS, easing = NT.Ease.inOut)) { -it / PARALLAX } +
        fadeOut(tween(PUSH_MS, easing = NT.Ease.inOut), targetAlpha = 0.85f)

private fun AnimatedContentTransitionScope<*>.popEnter(): EnterTransition =
    slideInHorizontally(tween(PUSH_MS, easing = NT.Ease.inOut)) { -it / PARALLAX } +
        fadeIn(tween(PUSH_MS, easing = NT.Ease.inOut), initialAlpha = 0.85f)

private fun AnimatedContentTransitionScope<*>.popExit(): ExitTransition =
    slideOutHorizontally(tween(PUSH_MS, easing = NT.Ease.inOut)) { it }

/** Keyed so the model survives a sheet close/open, exactly as `@State private var model` does. */
private const val SETTINGS_VM_KEY = "settings"
