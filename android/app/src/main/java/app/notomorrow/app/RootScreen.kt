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
 * Two responsibilities, and nothing else:
 *  1. onboarding versus the tab shell, decided by `appState.hasOnboarded` — or, when the database
 *     could not be opened, neither: [StoreErrorScreen] (`StoreLoader`);
 *  2. `appState.pendingRoute` — a tapped notification or a deep link (`MainTabView`'s route
 *     consumer on iOS). The workout opens over whatever tab is showing; no route switches tabs to
 *     get to it.
 *
 * The active workout is not here any more: it is a layer of the tab shell ([MainTabScaffold]),
 * which also shows it collapsed as the mini bar on every tab.
 *
 * The library import and routine seeding that `RootView.task` performs run in
 * `NoTomorrowApp`'s start-up coroutine instead, so they are not tied to a composition.
 *
 * It is also where the app's **one** Liquid Glass backdrop, its tier and the single
 * [NtOverlayHost] are created (`docs/android-glass.md` §3.3: *"There is exactly one `NtBackdrop`
 * per app, created in `RootScreen`"*). Creating them here rather than in [MainTabScaffold] is what
 * gives the root-level destinations — `fuel/camera` and the whole onboarding graph — real glass
 * instead of a flat fill. The **recording** stays with whichever screen is mounted (the tab shell,
 * whose recording also holds the workout layer, so the set-kind menu and the Finish dialog over it
 * are glass as on iOS); the two are never nested.
 */
@Composable
fun RootScreen() {
    val container = LocalAppContainer.current
    val appState = container.appState

    val isLoaded by appState.isLoaded.collectAsStateWithLifecycle()
    val hasOnboarded by appState.hasOnboarded.collectAsStateWithLifecycle()
    val pendingRoute by appState.pendingRoute.collectAsStateWithLifecycle()
    // The database opens before `isLoaded` flips (`AppContainer.load`). Nothing below that reads it
    // — the graph, the workout session, the route consumer — is composed until it is open.
    val storeState by container.store.state.collectAsStateWithLifecycle()
    val storeOpen = storeState == StoreLoader.State.Open

    val navController = rememberNavController()

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
                when (val store = storeState) {
                    StoreLoader.State.Open -> NtNavHost(
                        navController = navController,
                        startDestination =
                            if (hasOnboarded) NtRoute.Main.route else NtRoute.Onboarding.route,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Never the tabs or onboarding over a store that did not open (`RootView`).
                    is StoreLoader.State.Failed -> StoreErrorScreen(store.message, Modifier.fillMaxSize())
                    StoreLoader.State.Loading -> Unit
                }
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
    LaunchedEffect(isLoaded, hasOnboarded, storeOpen) {
        if (!isLoaded || !storeOpen) return@LaunchedEffect
        val target = if (hasOnboarded) NtRoute.Main.route else NtRoute.Onboarding.route
        val root = navController.currentBackStackEntry?.destination?.route
        if (root != null && root != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // A tap that arrives while the store error screen is up waits for the store to open.
    if (isLoaded && storeOpen) PendingRouteConsumer(pendingRoute)
}

/** `appState.pendingRoute` — a tapped notification or a deep link. Composed only over an open store. */
@Composable
private fun PendingRouteConsumer(pendingRoute: AppState.Route?) {
    val container = LocalAppContainer.current
    val appState = container.appState
    val session = container.workoutSession

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        when (route) {
            // The rest notifications: the workout expands over whatever tab is showing — plus the
            // rest sheet when the running-rest one was tapped and the rest is still going. After
            // a cold start the persisted id is read back (and, if need be, adopted) first.
            AppState.Route.RestTimer, AppState.Route.ActiveWorkout -> {
                if (session.activeWorkout() != null) {
                    container.restTimer.awaitRestored()
                    val restRunning = container.restTimer.state.value.isRunning()
                    session.expand(restSheet = route == AppState.Route.RestTimer && restRunning)
                }
            }

            AppState.Route.Bro -> appState.select(AppTab.Bro)

            // The widgets (`docs/widgets.md`): Fuel always opens on today.
            AppState.Route.Fuel -> appState.openFuelToday()

            AppState.Route.Today -> appState.select(AppTab.Today)

            // Settings is a sheet, not a destination; `feature/settings` observes the flag.
            AppState.Route.Settings -> {
                appState.select(AppTab.Today)
                appState.showsSettings.value = true
            }
        }
        appState.consumeRoute()
    }
}
