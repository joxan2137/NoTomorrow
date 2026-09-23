package app.notomorrow.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.notomorrow.app.MainTabScaffold
import app.notomorrow.designsystem.NT
import app.notomorrow.feature.fuel.CameraCaptureScreen
import app.notomorrow.feature.onboarding.OnboardingFlow

/**
 * The **root** graph: onboarding, the tab shell, and the full-screen camera that must open from
 * any tab. The five tab roots are not destinations at all any more: `MainTabScaffold` keeps them
 * composed and swaps placement, the way iOS's `TabView` keeps every tab alive — and the workout in
 * progress is a layer of that shell too, so it can collapse into the mini bar over every tab.
 *
 * `RootScreen` owns the controller and keeps it in step with `appState.hasOnboarded`; this file
 * only declares the destinations and their transitions. iOS presents the camera as a
 * `fullScreenCover`, so it slides up and back down rather than pushing horizontally.
 */
@Composable
fun NtNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        // Root-level swaps (onboarding → tabs) cross-fade; a horizontal push would be wrong for
        // what is a change of app mode, not a navigation step.
        enterTransition = { fadeIn(FADE) },
        exitTransition = { fadeOut(FADE) },
        popEnterTransition = { fadeIn(FADE) },
        popExitTransition = { fadeOut(FADE) },
    ) {
        composable(NtRoute.Onboarding.route) { OnboardingFlow() }

        composable(NtRoute.Main.route) { MainTabScaffold() }

        composable(
            route = NtRoute.FuelCamera.route,
            enterTransition = { slideUp() },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideDown() },
        ) {
            // iOS's `CameraPicker` always hands the image back to whoever presented it
            // (`AIScanSourceView.swift:105-133`); as a *route* the only channel back to the
            // presenting entry is its `savedStateHandle`, so the JPEG is deposited there under
            // [NtRoute.RESULT_CAMERA_JPEG] before the cover slides down.
            CameraCaptureScreen(
                onCaptured = { jpeg ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(NtRoute.RESULT_CAMERA_JPEG, jpeg)
                    navController.popBackStack()
                },
                onClose = { navController.popBackStack() },
            )
        }
    }
}

// MARK: - Transitions

private val FADE = tween<Float>(200, easing = NT.Ease.out)

private fun AnimatedContentTransitionScope<*>.slideUp(): EnterTransition =
    slideInVertically(tween(300, easing = NT.Ease.out)) { it }

private fun AnimatedContentTransitionScope<*>.slideDown(): ExitTransition =
    slideOutVertically(tween(300, easing = NT.Ease.out)) { it }
