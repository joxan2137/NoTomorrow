package app.notomorrow.nav

import app.notomorrow.model.AppTab

/**
 * Every route name in the app, in one place. **The strings are part of the contract**
 * (`docs/android-architecture.md`, "Navigation") — a push payload, a deep link and a test all name
 * them, so do not rename one without changing the spec.
 *
 * Three graphs live under the root:
 *  - [Onboarding] is a **state machine, not a back stack**: the four step routes exist so the flow
 *    can be addressed and logged, but `OnboardingFlow` renders them with one `AnimatedContent`
 *    over `model.step` (the order is mutable — `startWithPair()` puts [OnboardingPair] first).
 *  - [Main] is the tab shell; its own `NavHost` owns the five tab roots plus
 *    [ExerciseProgress].
 *  - [Settings] is modal, hosted by `SettingsSheet`'s own `NavHost`.
 *
 * [ActiveWorkout] and [FuelCamera] are hosted by the **root** host, not by a tab, mirroring iOS
 * mounting the `fullScreenCover` on the tab shell so starting a workout from any tab opens it
 * reliably.
 */
sealed class NtRoute(val route: String) {

    // MARK: - Root

    data object Root : NtRoute("root")

    // MARK: - Onboarding

    data object Onboarding : NtRoute("onboarding")
    data object OnboardingWelcome : NtRoute("onboarding/welcome")
    data object OnboardingYou : NtRoute("onboarding/you")
    data object OnboardingSchedule : NtRoute("onboarding/schedule")
    data object OnboardingPair : NtRoute("onboarding/pair")

    // MARK: - Main tabs

    data object Main : NtRoute("main")
    data object Today : NtRoute("today")
    data object Train : NtRoute("train")
    data object Fuel : NtRoute("fuel")
    data object Progress : NtRoute("progress")
    data object Bro : NtRoute("bro")

    /** `progress/exercise/{exerciseId}` — the only route in the app with an argument. */
    data object ExerciseProgress : NtRoute("progress/exercise/{$ARG_EXERCISE_ID}") {
        fun of(exerciseId: String): String = "progress/exercise/$exerciseId"
    }

    // MARK: - Full-screen destinations (root-hosted)

    data object ActiveWorkout : NtRoute("workout/active")
    data object FuelCamera : NtRoute("fuel/camera")

    // MARK: - Settings (modal graph)

    data object Settings : NtRoute("settings")
    data object SettingsName : NtRoute("settings/name")
    data object SettingsBodyWeight : NtRoute("settings/bodyWeight")
    data object SettingsDailyTarget : NtRoute("settings/dailyTarget")
    data object SettingsSchedule : NtRoute("settings/schedule")
    data object SettingsRestTimer : NtRoute("settings/restTimer")
    data object SettingsUnits : NtRoute("settings/units")
    data object SettingsLanguage : NtRoute("settings/language")
    data object SettingsNotifications : NtRoute("settings/notifications")
    data object SettingsHealth : NtRoute("settings/health")
    data object SettingsExport : NtRoute("settings/export")
    data object SettingsAi : NtRoute("settings/ai")
    data object SettingsPartner : NtRoute("settings/partner")

    companion object {
        /** Nav argument name of [ExerciseProgress]. */
        const val ARG_EXERCISE_ID = "exerciseId"

        /**
         * `savedStateHandle` key [FuelCamera] writes its downscaled JPEG to on the presenting
         * entry, standing in for the closure iOS's `CameraPicker` calls back with the image.
         */
        const val RESULT_CAMERA_JPEG = "fuel.camera.jpeg"

        /** The tab roots, in tab-bar order. [AppTab.route] is the same string. */
        val tabRoutes: List<String> = AppTab.entries.map { it.route }
    }
}
