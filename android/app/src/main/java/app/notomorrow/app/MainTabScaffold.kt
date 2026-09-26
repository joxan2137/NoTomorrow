package app.notomorrow.app

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import app.notomorrow.di.ntViewModel
import app.notomorrow.feature.auth.SignInSheet
import app.notomorrow.feature.bro.BroScreen
import app.notomorrow.feature.dashboard.DashboardScreen
import app.notomorrow.feature.fuel.FuelHomeScreen
import app.notomorrow.feature.progress.ExerciseProgressScreen
import app.notomorrow.feature.progress.ProgressHomeScreen
import app.notomorrow.feature.progress.RecordsScreen
import app.notomorrow.feature.settings.SettingsSheetHost
import app.notomorrow.feature.workout.ActiveWorkoutScreen
import app.notomorrow.feature.workout.ActiveWorkoutViewModel
import app.notomorrow.feature.workout.RestExpiryWatcher
import app.notomorrow.feature.workout.TrainScreen
import app.notomorrow.feature.workout.WorkoutMiniBar
import app.notomorrow.feature.workout.WorkoutMiniBarTokens
import app.notomorrow.model.AppTab
import app.notomorrow.model.WeightUnit
import app.notomorrow.nav.NtRoute
import app.notomorrow.rest.RestTimerState
import app.notomorrow.service.WorkoutSessionController
import app.notomorrow.util.NtStrings

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
 * stack (`progress` → `progress/records` / `progress/exercise/{id}`), so it carries its own small `NavHost`; back
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
 *
 * **The workout in progress** is a layer of this shell, not a destination (`ActiveWorkoutView`
 * is iOS's `fullScreenCover` on `MainTabView`). The shell hosts its `ActiveWorkoutViewModel` — one
 * per workout, keyed by id — which both the full screen and the [WorkoutMiniBar] read, so
 * collapsing keeps the open exercise, "up next", the summary and the scroll position:
 *  - **expanded**, the full screen slides up *inside* the recording (so the glass menus and
 *    dialogs over it are real glass, as they were when it was a root destination) and the chrome —
 *    tab bar and mini bar — fades down; once it covers the page the tabs are no longer placed and
 *    their back handlers are off;
 *  - **collapsed**, the mini bar sits 8 dp above the tab bar on every tab, and
 *    [LocalTabBarHeight] grows by its height so every screen — and Fuel's add bar — clears it.
 *
 * It also runs the session's launch pass and the shell-level rest-expiry watcher, so a rest that
 * runs out while the workout is collapsed still ends in-app.
 */
@Composable
fun MainTabScaffold() {
    val container = LocalAppContainer.current
    val appState = container.appState
    val session = container.workoutSession
    val restTimer = container.restTimer
    val selected by appState.selectedTab.collectAsStateWithLifecycle()
    val activeWorkoutId by session.activeWorkoutId.collectAsStateWithLifecycle()
    val expanded by session.showsActiveWorkout.collectAsStateWithLifecycle()
    val showsSummary by session.showsSummary.collectAsStateWithLifecycle()
    val rest by restTimer.state.collectAsStateWithLifecycle()

    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // One function, so the padding every screen applies and the bar that sits on top of it cannot
    // disagree — they did, by up to 21 dp, while this was `NT.Size.tabBar + navInset`.
    val tabBarHeight = NtTabBarTokens.barHeight(navigationBar)

    WorkoutSessionLaunch()
    // Closes a rest that runs out while the workout is collapsed (haptic, notification, mini bar).
    RestExpiryWatcher(state = rest, onElapsed = restTimer::finishIfElapsed)

    // The workout the layer shows. It outlives the session by one presentation, so the full
    // screen keeps rendering while it slides away after Done / Discard (the session let go first).
    val hostedWorkoutId = rememberLastNonNull(activeWorkoutId)
    val workoutScroll = remember(hostedWorkoutId) { ScrollState(0) }
    val workoutVisibility = remember { MutableTransitionState(false) }
    workoutVisibility.targetState = expanded && activeWorkoutId != null
    // Settled full screen: nothing underneath is placed, drawn, captured or answers back.
    val covering = workoutVisibility.currentState && workoutVisibility.isIdle
    val chromeHidden by animateFloatAsState(
        targetValue = if (workoutVisibility.targetState) 1f else 0f,
        animationSpec = tween(WORKOUT_SLIDE_MS, easing = NT.Ease.out),
        label = "shellChrome",
    )

    // iOS drops the bar while the keyboard is up (it would ride on top of it). A keyboard over the
    // full screen is the workout's own business, so it changes nothing underneath.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val showsMiniBar = WorkoutSessionController.isInProgress(activeWorkoutId, showsSummary) &&
        !(imeVisible && !covering)
    val chromeHeight = if (showsMiniBar) tabBarHeight + WorkoutMiniBarTokens.reserved else tabBarHeight

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

    CompositionLocalProvider(LocalTabBarHeight provides chromeHeight) {
        Box(Modifier.fillMaxSize().background(NT.Colors.ground)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.ntBackdropSource(backdrop) else Modifier),
            ) {
                for (tab in AppTab.entries) {
                    if (tab !in live) continue
                    key(tab) {
                        TabPage(visible = tab == selected && !covering) {
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

                // `fullScreenCover`: slides up on expand, down on collapse, Done and Discard.
                if (hostedWorkoutId != null) {
                    AnimatedVisibility(
                        visibleState = workoutVisibility,
                        enter = slideInVertically(tween(WORKOUT_SLIDE_MS, easing = NT.Ease.out)) { it },
                        exit = slideOutVertically(tween(WORKOUT_SLIDE_MS, easing = NT.Ease.out)) { it },
                    ) {
                        key(hostedWorkoutId) {
                            ActiveWorkoutScreen(
                                model = activeWorkoutModel(hostedWorkoutId),
                                scroll = workoutScroll,
                                onMinimize = session::collapse,
                            )
                        }
                    }
                }
            }

            // The chrome: out of the recording (glass samples the page), faded down while the
            // workout rises over the page, and not placed at all once it covers it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .placedUnless(covering)
                    .graphicsLayer {
                        alpha = 1f - chromeHidden
                        translationY = CHROME_DROP.toPx() * chromeHidden
                    },
            ) {
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

                val barWorkoutId = activeWorkoutId
                AnimatedVisibility(
                    visible = showsMiniBar && barWorkoutId != null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = NT.Spacing.screenH)
                        .padding(bottom = tabBarHeight + WorkoutMiniBarTokens.gap),
                    enter = slideInVertically(MINI_BAR_OFFSET) { it } + fadeIn(NT.Anim.spring085),
                    exit = slideOutVertically(MINI_BAR_OFFSET) { it } + fadeOut(NT.Anim.spring085),
                ) {
                    val id = barWorkoutId ?: hostedWorkoutId
                    if (id != null) {
                        key(id) {
                            WorkoutMiniBarHost(
                                model = activeWorkoutModel(id),
                                rest = rest,
                                onExpand = { session.expand() },
                                onSkipRest = restTimer::skip,
                            )
                        }
                    }
                }
            }

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
 * The workout's view model — one per workout, in this shell's store (the `main` entry), shared by
 * the full screen and the mini bar. A finished workout's model stays in the store, idle, until the
 * shell goes (an onboarding reset); it stopped reading Room when the session let go of it.
 */
@Composable
private fun activeWorkoutModel(workoutId: String): ActiveWorkoutViewModel =
    ntViewModel(key = "activeWorkout/$workoutId") { c ->
        ActiveWorkoutViewModel(
            workoutId = workoutId,
            workoutDao = c.db.workoutDao(),
            recordService = c.recordService,
            attendanceService = c.attendanceService,
            restTimer = c.restTimer,
            session = c.workoutSession,
            appPrefs = c.appPrefs,
            strings = NtStrings.from(c.app),
            units = { c.db.profileDao().profile()?.units ?: WeightUnit.Kg },
            reportAttendance = c.attendanceReporter,
            routines = { c.db.routineDao().routinesWithItems() },
        )
    }

/** The mini bar fed from the workout's model; nothing until its first Room read has landed. */
@Composable
private fun WorkoutMiniBarHost(
    model: ActiveWorkoutViewModel,
    rest: RestTimerState,
    onExpand: () -> Unit,
    onSkipRest: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    if (state.loading || state.missing) return
    WorkoutMiniBar(
        name = state.name,
        startedAt = state.startedAt,
        currentExerciseName = state.currentExercise?.name,
        rest = rest,
        onExpand = onExpand,
        onSkipRest = onSkipRest,
    )
}

/**
 * The session's launch pass (`MainTabView.task { session.restore }`): finish an interrupted
 * discard, close orphans, adopt the workout in progress. A cold start shows the mini bar only.
 *
 * Android only: a process the system killed while the workout was full screen comes back full
 * screen — the saved instance state remembers it, and a fresh launch has none.
 */
@Composable
private fun WorkoutSessionLaunch() {
    val session = LocalAppContainer.current.workoutSession
    val expanded by session.showsActiveWorkout.collectAsStateWithLifecycle()
    var savedExpanded by rememberSaveable { mutableStateOf(false) }
    val restoredExpanded = remember { savedExpanded }
    LaunchedEffect(Unit) {
        session.restore()
        // Not over a notification tap that already expanded it (and may have asked for the rest sheet).
        if (restoredExpanded && !session.showsActiveWorkout.value && session.isWorkoutInProgressNow()) {
            session.expand()
        }
    }
    LaunchedEffect(expanded) { savedExpanded = expanded }
}

/** The last non-null [value] seen — what a layer keeps showing while it animates out. */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    @Suppress("UNCHECKED_CAST")
    return last[0] as T?
}

/** Measured as usual, but not placed while [hidden]: not drawn, not hit-tested (see [TabPage]). */
private fun Modifier.placedUnless(hidden: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (!hidden) placeable.place(0, 0)
    }
}

/** `NtNavHost`'s cover slide, kept for the workout layer. */
private const val WORKOUT_SLIDE_MS = 300

/** How far the chrome sinks as it fades under the rising workout. */
private val CHROME_DROP = 40.dp

/** `spring(response: 0.35, dampingFraction: 0.85)` — the mini bar in and out, like the rest pill. */
private val MINI_BAR_OFFSET = spring<IntOffset>(dampingRatio = 0.85f, stiffness = 322.3f)

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
        CompositionLocalProvider(
            LocalOnBackPressedDispatcherOwner provides backScope,
            LocalTabPageVisible provides visible,
        ) {
            content()
        }
    }
}

/**
 * Whether the tab page this is read in is the one on screen — selected, and not covered by the
 * workout. Every tab root stays composed ([TabPage]), so anything a page presents in the activity's
 * overlay (an action sheet, a dialog) must stop when this turns `false`, or it follows the user to
 * the next tab. `true` outside the shell.
 */
val LocalTabPageVisible = compositionLocalOf { true }

/** The Progress tab's own stack: its root, the records list and the per-exercise page they push. */
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
                onRecords = { navController.navigate(NtRoute.ProgressRecords.route) },
            )
        }
        composable(NtRoute.ProgressRecords.route) {
            RecordsScreen(
                onExercise = { id -> navController.navigate(NtRoute.ExerciseProgress.of(id)) },
                onBack = { navController.popBackStack() },
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
 * [NtTabBarTokens.barHeight] — the same expression `NtTabBar` sizes itself with — plus the workout
 * mini bar and its gap while one shows (`WorkoutMiniBarTokens.reserved`), the way iOS's bottom
 * safe-area inset grows. Screens inside the shell add it as bottom `contentPadding` so their
 * content scrolls under the bars instead of being clipped by them, and Fuel's add bar sits above
 * both.
 *
 * The default is the bare bar height, for previews and for screens composed outside the shell.
 */
val LocalTabBarHeight = compositionLocalOf { NT.Size.tabBar }
