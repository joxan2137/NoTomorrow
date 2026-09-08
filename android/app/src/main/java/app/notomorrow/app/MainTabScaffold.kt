package app.notomorrow.app

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.notomorrow.designsystem.LocalNtBackdrop
import app.notomorrow.designsystem.NT
import app.notomorrow.designsystem.ntBackdropSource
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.feature.auth.SignInSheet
import app.notomorrow.feature.bro.BroScreen
import app.notomorrow.feature.dashboard.DashboardScreen
import app.notomorrow.feature.fuel.FuelHomeScreen
import app.notomorrow.feature.progress.ExerciseProgressScreen
import app.notomorrow.feature.progress.ProgressHomeScreen
import app.notomorrow.feature.settings.SettingsSheetHost
import app.notomorrow.feature.workout.TrainScreen
import app.notomorrow.model.AppTab
import app.notomorrow.nav.NtRoute

/**
 * The tab shell — the port of `MainTabView` in `NoTomorrow/App/RootView.swift`.
 *
 * `TabView` keeps every tab's `NavigationStack` alive and swaps between them instantly. This does
 * the same: all five tab roots are **composed once and stay composed**; only the selected one is
 * placed — laid out on screen, drawn, hit-tested, captured for the glass. The others keep their
 * scroll positions, view models and pending work, and cost nothing to draw. A tab switch is then a
 * placement change plus the pill spring, not a fresh composition, first layout and first draw of
 * the target screen — which is what a `NavHost` with `saveState`/`restoreState` did on every
 * switch, and what the S22 showed as a ~50 ms UI-thread stall per tap (`docs/android-status.md`
 * §7).
 *
 * The roots are warmed one per frame after the shell's first frame, so start-up shows the selected
 * tab immediately and the rest are ready within a few frames. Progress is the one tab with a
 * stack (`progress` → `progress/exercise/{id}`), so it carries its own small `NavHost`; back
 * presses reach it only while it is the visible tab ([TabPage]).
 *
 * The bar itself is [NtTabBar]: content scrolls **under** it, so screens add [LocalTabBarHeight] as
 * bottom `contentPadding` rather than being inset by a `Scaffold`.
 *
 * It is also where the app's one Liquid Glass backdrop is **recorded** (`docs/android-glass.md`
 * §3.3): the page container wears `ntBackdropSource`, and `NtTabBar` — its sibling in the same
 * `Box`, drawn after it and therefore outside the capture — samples it. The backdrop itself is
 * *created* in [RootScreen], so root-level destinations get one too; there is never a second
 * `NtBackdrop`, never two recordings mounted at once, and nothing inside the pages may wear
 * `Modifier.liquidGlass`: glass cannot sample glass. The recording is a *reference*, not a
 * per-frame copy: the samplers' effect layers draw the backdrop `RenderNode`, so the page is
 * re-recorded only when the page itself repaints — `NtTabBar` sits in its own graphics layer so
 * the pill spring can never be the reason.
 */
@Composable
fun MainTabScaffold() {
    val container = LocalAppContainer.current
    val appState = container.appState
    val selected by appState.selectedTab.collectAsStateWithLifecycle()

    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // One function, so the padding every screen applies and the bar that sits on top of it cannot
    // disagree — they did, by up to 21 dp, while this was `NT.Size.tabBar + navInset`.
    val tabBarHeight = NtTabBarTokens.barHeight(navigationBar)

    // iOS never asks for notification authorisation here: `MainTabView` presents nothing at appear,
    // and the prompt comes from the onboarding reminder toggles (`SetupScheduleScreen`) and from
    // `settings/notifications` (`SettingsAppEditors`). Both are ported, so the shell only observes
    // the grant — it must not throw a POST_NOTIFICATIONS dialog at the first frame of the Dashboard.

    val backdrop = LocalNtBackdrop.current
    val progressNav = rememberNavController()

    // Warm the hidden tabs one per frame once the selected one is up: none of them ever shares a
    // frame with the first paint of the shell, and a tab picked before its turn is composed on the
    // spot (`live` below), never skipped.
    var warmed by remember { mutableStateOf(setOf(selected)) }
    LaunchedEffect(Unit) {
        for (tab in AppTab.entries) {
            if (tab in warmed) continue
            withFrameNanos { }
            warmed = warmed + tab
        }
    }
    val live = if (selected in warmed) warmed else warmed + selected

    CompositionLocalProvider(LocalTabBarHeight provides tabBarHeight) {
        Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.ntBackdropSource(backdrop) else Modifier),
            ) {
                for (tab in AppTab.entries) {
                    if (tab !in live) continue
                    key(tab) {
                        TabPage(visible = tab == selected) {
                            when (tab) {
                                AppTab.Today -> DashboardScreen()
                                AppTab.Train -> TrainScreen()
                                AppTab.Fuel -> FuelHomeScreen()
                                AppTab.Progress -> ProgressTab(progressNav)
                                AppTab.Bro -> BroScreen()
                            }
                        }
                    }
                }
            }

            NtTabBar(
                selected = selected,
                onSelect = { tab ->
                    if (tab == selected) {
                        // Re-tapping the active tab pops its stack to the root, as UIKit does.
                        // Progress is the only tab that has one.
                        if (tab == AppTab.Progress) {
                            progressNav.popBackStack(NtRoute.Progress.route, inclusive = false)
                        }
                    } else {
                        appState.select(tab)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // iOS presents `SettingsView()` as a `.sheet` from the Dashboard avatar, but the
            // `settings` push route has to open it from any tab — so the flag lives on
            // `AppState` and the host is mounted once here, beside the bar. Sign-in is the
            // slot `feature/settings` asks for, filled with `feature/auth`'s sheet so Settings
            // never depends on the auth package.
            SettingsSheetHost(
                signIn = { onDone -> SignInSheet(onDismiss = onDone, onSignedIn = onDone) },
            )
        }
    }
}

/**
 * One tab root, kept composed while hidden.
 *
 * Hidden means **not placed**: the page still measures, so it is ready the instant it is selected,
 * but it is not drawn, not hit-tested, not part of the backdrop capture, and reports no global
 * position. Back presses are scoped the same way — see [TabBackScope].
 */
@Composable
private fun TabPage(visible: Boolean, content: @Composable () -> Unit) {
    val backScope = rememberTabBackScope(visible)
    Box(
        Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (visible) placeable.place(0, 0)
                }
            },
    ) {
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides backScope) {
            content()
        }
    }
}

/** The Progress tab's own stack: its root and the per-exercise page it pushes. */
@Composable
private fun ProgressTab(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = NtRoute.Progress.route,
        modifier = Modifier.fillMaxSize(),
        // The former shell hosted this route with no transition; unchanged here.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(NtRoute.Progress.route) {
            ProgressHomeScreen(
                onExercise = { id -> navController.navigate(NtRoute.ExerciseProgress.of(id)) },
            )
        }
        composable(
            route = NtRoute.ExerciseProgress.route,
            arguments = listOf(navArgument(NtRoute.ARG_EXERCISE_ID) { type = NavType.StringType }),
        ) { entry ->
            ExerciseProgressScreen(
                exerciseId = entry.arguments?.getString(NtRoute.ARG_EXERCISE_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Height of the tab bar **including** the navigation-bar inset, from
 * [NtTabBarTokens.barHeight] — the same expression `NtTabBar` sizes itself with. Screens inside the
 * shell add it as bottom `contentPadding` so their content scrolls under the bar instead of being
 * clipped by it.
 *
 * The default is the bare bar height, for previews and for screens composed outside the shell.
 */
val LocalTabBarHeight = compositionLocalOf { NT.Size.tabBar }
