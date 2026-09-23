package app.notomorrow

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.notomorrow.app.AppVisibility
import app.notomorrow.app.RootScreen
import app.notomorrow.designsystem.NTTheme
import app.notomorrow.di.AppContainer
import app.notomorrow.designsystem.GlassDebug
import app.notomorrow.designsystem.GlassTier
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.push.NtPushIntents

/**
 * The single Activity. `AppCompatActivity` is mandatory, not decorative:
 * `AppCompatDelegate.setApplicationLocales` silently does nothing without it (research §6.9), and
 * the language override is a shipped feature.
 *
 * It owns three things and delegates everything else to `RootScreen`: edge-to-edge window setup,
 * holding the splash screen until the container has read its preferences back, and turning a
 * tapped notification into `appState.pendingRoute`.
 *
 * Back is handled by Compose Navigation and `BackHandler` throughout — never `onBackPressed`;
 * `android:enableOnBackInvokedCallback="true"` in the manifest puts the app on predictive back.
 */
class MainActivity : AppCompatActivity() {

    private val container: AppContainer get() = (application as NoTomorrowApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Explicit `dark(...)`: the app is dark irrespective of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val container = this.container
        // `nt.hasOnboarded` lives in DataStore, so it is read on a coroutine; showing the tab shell
        // (or worse, Welcome) before it lands would flash the wrong screen at a returning user.
        splash.setKeepOnScreenCondition { !container.appState.isLoaded.value }

        // A recreated Activity (process death, a config change) carries the intent it was first
        // started with; a task relaunched from Recents carries the last notification's. Neither
        // is a new tap, so neither may replay its route (`onNewIntent` delivers the real ones).
        if (savedInstanceState == null && !isLaunchedFromHistory(intent)) handlePushIntent(intent)
        handleDebugIntent(intent)

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                NTTheme {
                    RootScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    /** `singleTask`: a running app gets the notification tap here, not through a fresh `onCreate`. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePushIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * The extras `push/PushNotifier` puts on its `PendingIntent`: `EXTRA_ROUTE`
     * (`restTimer | activeWorkout | bro | settings`), `EXTRA_KIND` and `EXTRA_SESSION_DAY`.
     * `RootScreen` consumes the resulting `pendingRoute`.
     */
    private fun handlePushIntent(intent: Intent?) {
        val route = intent?.getStringExtra(NtPushIntents.EXTRA_ROUTE) ?: return
        container.appState.requestRoute(route)
        // Consumed: the intent stays attached to the Activity and must not route twice.
        intent.removeExtra(NtPushIntents.EXTRA_ROUTE)
    }

    private fun isLaunchedFromHistory(intent: Intent?): Boolean =
        intent != null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

    /**
     * Debug builds only: force the Liquid Glass tier from adb, so the material's cost and look can
     * be A/B'd on a device without a rebuild or a hand on the screen —
     * `am start -n app.notomorrow.android/.MainActivity --es app.notomorrow.debug.glassTier tint`
     * (`full` | `blur` | `tint` | `auto`). Read by `RootScreen` through `GlassDebug.forcedTier`.
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra(EXTRA_DEBUG_GLASS_TIER)?.let { tier ->
            GlassDebug.forcedTier = when (tier.lowercase()) {
                "full" -> GlassTier.Full
                "blur" -> GlassTier.Blur
                "tint" -> GlassTier.Tint
                else -> null
            }
        }
        // `--es app.notomorrow.debug.pillLens off`: the moving lens alone, everything else as is.
        intent.getStringExtra(EXTRA_DEBUG_PILL_LENS)?.let { GlassDebug.pillLens = it != "off" }
        // `--es app.notomorrow.debug.shader passthrough|noagg|noblur|full` (comma-separable).
        intent.getStringExtra(EXTRA_DEBUG_SHADER)?.let { modes ->
            val set = modes.split(',').map { it.trim().lowercase() }.toSet()
            GlassDebug.shaderPassthrough = "passthrough" in set
            GlassDebug.noAggregate = "noagg" in set
            GlassDebug.noBlur = "noblur" in set
        }
    }

    private companion object {
        const val EXTRA_DEBUG_GLASS_TIER = "app.notomorrow.debug.glassTier"
        const val EXTRA_DEBUG_PILL_LENS = "app.notomorrow.debug.pillLens"
        const val EXTRA_DEBUG_SHADER = "app.notomorrow.debug.shader"
    }
}
