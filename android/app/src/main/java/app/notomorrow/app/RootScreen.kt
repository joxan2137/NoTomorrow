package app.notomorrow.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.notomorrow.BuildConfig
import app.notomorrow.designsystem.GlassDebug
import app.notomorrow.designsystem.GlassProbeScreen
import app.notomorrow.designsystem.GlassTier
import app.notomorrow.designsystem.LocalGlassTier
import app.notomorrow.designsystem.LocalNtBackdrop
import app.notomorrow.designsystem.LocalNtOverlayHost
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.NtOverlayHost
import app.notomorrow.designsystem.rememberGlassTier
import app.notomorrow.designsystem.rememberNtBackdrop
import app.notomorrow.designsystem.rememberNtOverlayState
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.model.AppTab
import app.notomorrow.nav.NtNavHost
import app.notomorrow.nav.NtRoute

/**
 * The app's root — the port of `NoTomorrow/App/RootView.swift`.
 *
 * Three responsibilities, and nothing else:
 *  1. onboarding versus the tab shell, decided by `appState.hasOnboarded`;
 *  2. the active-workout full-screen cover, which iOS mounts on the tab shell "so starting a
 *     workout from any tab opens it reliably" and Android hosts on the **root** `NavHost`;
 *  3. `appState.pendingRoute` — a tapped notification or a deep link. iOS declares the type with
 *     no readers; Android implements it.
 *
 * The library import and routine seeding that `RootView.task` performs run in
 * `NoTomorrowApp`'s start-up coroutine instead, so they are not tied to a composition.
 *
 * It is also where the app's **one** Liquid Glass backdrop, its tier and the single
 * [NtOverlayHost] are created (`docs/android-glass.md` §3.3: *"There is exactly one `NtBackdrop`
 * per app, created in `RootScreen`"*). Creating them here rather than in [MainTabScaffold] is what
 * gives the root-level destinations — `workout/active`, `fuel/camera` and the whole onboarding
 * graph — real glass instead of a flat fill: on iOS the set-kind `Menu`, the finish
 * `confirmationDialog` and the keyboard-accessory `Done` in `ActiveWorkoutView` all render as glass
 * over the workout list. The **recording** stays with whichever screen is mounted (the tab
 * `NavHost` in [MainTabScaffold]); the two are never nested.
 */
@Composable
fun RootScreen() {
    val container = LocalAppContainer.current
    val appState = container.appState
    val session = container.workoutSession

    val isLoaded by appState.isLoaded.collectAsStateWithLifecycle()
    val hasOnboarded by appState.hasOnboarded.collectAsStateWithLifecycle()
    val showsActiveWorkout by session.showsActiveWorkout.collectAsStateWithLifecycle()
    val pendingRoute by appState.pendingRoute.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    val backdrop = rememberNtBackdrop()
    val glassTier by rememberGlassTier()
    val overlay = rememberNtOverlayState()

    // A device that cannot record the backdrop at all (see `ntBackdropSource`) keeps its screen and
    // loses the material: the flat tier is the one that needs no capture, and over this app's single
    // `ground` backdrop it lands within 1/255 of the real transfer function anyway.
    // `GlassDebug.forcedTier` is the probe screen and the debug intent (`MainActivity`); null otherwise.
    val effectiveTier =
        if (backdrop.captureFailed) GlassTier.Tint else GlassDebug.forcedTier ?: glassTier

    CompositionLocalProvider(
        LocalNtBackdrop provides backdrop,
        LocalGlassTier provides effectiveTier,
        LocalNtOverlayHost provides overlay,
    ) {
        Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
            // Until `nt.hasOnboarded` has been read back there is nothing safe to show: rendering
            // the default (false) would flash the Welcome screen at a returning user. The splash
            // screen is still up here — `MainActivity` keeps it on the same condition.
            if (isLoaded) {
                NtNavHost(
                    navController = navController,
                    startDestination =
                        if (hasOnboarded) NtRoute.Main.route else NtRoute.Onboarding.route,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Menus, alerts and action sheets are composed here — after the recorded subtree, so
            // their glass may sample it, and in this window, so the screen underneath stays live.
            NtOverlayHost(overlay)

            if (BuildConfig.DEBUG && GlassDebug.showProbe) {
                GlassProbeScreen(onClose = { GlassDebug.showProbe = false })
            }
        }
    }

    // Onboarding finishing (or a delete-account reset) swaps the whole graph, clearing the stack so
    // back cannot walk into the flow that no longer applies.
    LaunchedEffect(isLoaded, hasOnboarded) {
        if (!isLoaded) return@LaunchedEffect
        val target = if (hasOnboarded) NtRoute.Main.route else NtRoute.Onboarding.route
        val root = navController.currentBackStackEntry?.destination?.route
        if (root != null && root != target && root != NtRoute.ActiveWorkout.route) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // `showsActiveWorkout` → the cover. Presenting is a navigate; dismissing is a pop.
    LaunchedEffect(showsActiveWorkout, isLoaded) {
        if (!isLoaded) return@LaunchedEffect
        val onScreen = navController.currentBackStackEntry?.destination?.route ==
            NtRoute.ActiveWorkout.route
        when {
            showsActiveWorkout && !onScreen ->
                navController.navigate(NtRoute.ActiveWorkout.route) { launchSingleTop = true }

            !showsActiveWorkout && onScreen -> navController.popBackStack()
        }
    }

    // …and back, so predictive back (or the screen popping itself) clears the flag.
    LaunchedEffect(currentRoute) {
        if (currentRoute != NtRoute.ActiveWorkout.route && session.showsActiveWorkout.value) {
            session.hide()
        }
    }

    LaunchedEffect(pendingRoute, isLoaded) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (!isLoaded) return@LaunchedEffect
        when (route) {
            // The rest timer lives inside the active workout; `feature/workout` observes the flag.
            AppState.Route.RestTimer -> {
                appState.select(AppTab.Train)
                if (session.activeWorkoutId.value != null) session.show()
                appState.showsRestTimer.value = true
            }

            AppState.Route.ActiveWorkout -> {
                appState.select(AppTab.Train)
                if (session.activeWorkoutId.value != null) session.show()
            }

            AppState.Route.Bro -> appState.select(AppTab.Bro)

            // Settings is a sheet, not a destination; `feature/settings` observes the flag.
            AppState.Route.Settings -> {
                appState.select(AppTab.Today)
                appState.showsSettings.value = true
            }
        }
        appState.consumeRoute()
    }
}
