package app.notomorrow.feature.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.service.HealthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The three states `HealthConnectClient.getSdkStatus()` reports, named — Settings → Health app
 * renders one row per state (`docs/android-architecture.md`, "Health app has three states").
 *
 * iOS has only two (`HKHealthStore.isHealthDataAvailable()`), because Health is part of the OS;
 * [ProviderUpdateRequired] is the Android-only middle case and deep-links to Play.
 */
enum class HealthAvailability {
    /** No Health Connect on this device (or below API 28). Every service call no-ops. */
    Unavailable,

    /** Health Connect is installed but too old to bind: send the user to Play. */
    ProviderUpdateRequired,

    /** Ready — permissions can be requested. */
    Available,
}

/** `sdkStatus()` → [HealthAvailability]. Pure; unit-testable. */
fun healthAvailability(sdkStatus: Int): HealthAvailability = when (sdkStatus) {
    HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.Available
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.ProviderUpdateRequired
    else -> HealthAvailability.Unavailable
}

/**
 * The observable handle Settings → Health app drives: what Health Connect can do right now,
 * whether this app may write body weight (iOS's `HealthKitService.isAuthorized`, which mirrors
 * body-mass *sharing* status), and the two actions.
 *
 * Created by [rememberHealthPermissionLauncher]; never constructed by a feature directly.
 */
@Stable
class HealthPermissionLauncher internal constructor(
    private val service: HealthService,
    private val scope: CoroutineScope,
    private val launch: () -> Unit,
) {

    var availability by mutableStateOf(HealthAvailability.Unavailable)
        private set

    /**
     * `true` once the write-body-weight grant is in place — the flag Settings shows as "Connected"
     * and the one `HealthKitService.refreshAuthorization()` computes on iOS.
     */
    var isGranted by mutableStateOf(false)
        private set

    /** Every permission granted, i.e. weight in **and** meals/workouts/calories out. */
    var hasAllPermissions by mutableStateOf(false)
        private set

    /**
     * `false` until the first [reload] has come back. [availability] starts at
     * [HealthAvailability.Unavailable] only because a `mutableStateOf` needs a value — during the
     * first frames it means "not asked yet", not "no Health Connect".
     */
    var loaded by mutableStateOf(false)
        private set

    val isAvailable: Boolean get() = availability == HealthAvailability.Available

    /**
     * Opens Health Connect's permission sheet. `HealthKitService.requestAuthorization()` always
     * calls through, so a tap in the frames before the first [reload] answers must not be a silent
     * no-op: refuse only once a *completed* reload has reported the SDK unusable. The caller then
     * shows [HealthAvailability.ProviderUpdateRequired] with [openHealthConnectProviderUpdate]
     * instead of a dialog that cannot appear.
     */
    fun request() {
        if (loaded && !isAvailable) return
        launch()
    }

    /** Re-reads availability and the grants; called automatically on resume. */
    fun refresh() {
        scope.launch { reload() }
    }

    internal suspend fun reload() {
        availability = healthAvailability(service.sdkStatus())
        isGranted = service.refreshAuthorization()
        hasAllPermissions = service.hasAllPermissions()
        loaded = true
    }
}

/**
 * Remembers a [HealthPermissionLauncher] wired to Health Connect's own permission contract
 * (`HealthService.permissionsContract()`), refreshed on every resume so a grant made inside the
 * Health Connect app shows up when the user comes back.
 *
 * ```kotlin
 * val health = rememberHealthPermissionLauncher()
 * NtLinkRow(
 *     label = stringResource(S.settings_health),
 *     value = when {
 *         !health.isAvailable -> stringResource(S.settings_health_unavailable)
 *         health.isGranted -> stringResource(S.common_on)
 *         else -> stringResource(S.settings_health_connect)
 *     },
 *     onClick = { if (health.isAvailable) health.request() else openHealthConnectProviderUpdate(context) },
 * )
 * ```
 *
 * @param onResult invoked after the sheet closes with `true` when every permission is held.
 */
@Composable
fun rememberHealthPermissionLauncher(
    service: HealthService = LocalAppContainer.current.healthService,
    onResult: (Boolean) -> Unit = {},
): HealthPermissionLauncher {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val permissions = remember(service) { service.permissions }
    val contract = remember { HealthService.permissionsContract() }

    // Plain holder, not snapshot state: the launcher callback needs the object that is created
    // after it, and this must not drive recomposition (the same shape as `rememberPushPermissionState`).
    val holder = remember { arrayOfNulls<HealthPermissionLauncher>(1) }
    val activityLauncher = rememberLauncherForActivityResult(contract) { granted ->
        scope.launch {
            holder[0]?.reload()
            currentOnResult(granted.containsAll(permissions))
        }
    }
    val resolved = remember(service) {
        HealthPermissionLauncher(service, scope) { activityLauncher.launch(permissions) }
    }
    SideEffect { holder[0] = resolved }

    LaunchedEffect(resolved) { resolved.reload() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, resolved) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resolved.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return resolved
}

/**
 * Play deep link for [HealthAvailability.ProviderUpdateRequired] — the documented onboarding
 * overlay, which installs or updates Health Connect without leaving the app when possible.
 *
 * A device without the Play *app* can still have a browser, so a missing `market://` handler falls
 * back to the store's web listing rather than doing nothing.
 */
fun openHealthConnectProviderUpdate(context: Context) {
    val uri = Uri.parse(
        "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        putExtra("overlay", true)
        putExtra("callerId", context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val web = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE"),
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivitySafely(intent, fallback = web)
}

/** Health Connect's own screen, where this app's data and grants can be reviewed or withdrawn. */
fun openHealthConnectSettings(context: Context) {
    val intent = runCatching { HealthConnectClient.getHealthConnectManageDataIntent(context) }
        .getOrElse { return }
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivitySafely(intent)
}

/**
 * `HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME` is `internal` in connect-client 1.1.0, so the
 * Play deep link spells the provider out — it is a fixed, documented package name.
 */
private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/**
 * A device can lack both Play and Health Connect; a missing Activity is a state, never a crash.
 */
private fun Context.startActivitySafely(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        if (fallback == null) return
        try {
            startActivity(fallback)
        } catch (_: ActivityNotFoundException) {
            // Nothing to open — the caller already renders the "unavailable" state.
        }
    }
}
