package app.notomorrow.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * `POST_NOTIFICATIONS` is a runtime permission from API 33
 * (`docs/android-research.md` §7.3 row 5). iOS asks for notification
 * authorization in the onboarding Schedule step, so Android asks there too,
 * beside the two reminder toggles; Settings → Notifications reflects the state
 * afterwards.
 */
object PushPermission {

    /** The permission string, for callers that launch their own contract. */
    const val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    /** API 33+. Below that, notifications are allowed by default. */
    val isRuntimePermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Permission-level check. Always true below API 33. */
    fun isGranted(context: Context): Boolean =
        !isRuntimePermission ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * What the user actually sees: the permission **and** the app-level toggle
     * in system settings, which can be off at any API level.
     */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * True when the system will show the dialog again. False after a permanent
     * denial — the only path left is system settings.
     */
    fun shouldShowRationale(activity: Activity): Boolean =
        isRuntimePermission && ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISSION)

    /** Nearest [Activity] behind a composition [Context], or `null` in a preview. */
    fun activityOf(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}

/**
 * Observable notification-permission state for a screen.
 *
 * Re-reads on every `ON_RESUME`, so a trip to system settings is reflected when
 * the user comes back (`docs/android-architecture.md`, Settings → Notifications).
 */
@Stable
class PushPermissionState internal constructor(
    private val context: Context,
    private val launch: () -> Unit,
) {
    internal var grantedState by mutableStateOf(PushPermission.isGranted(context))
    internal var enabledState by mutableStateOf(PushPermission.areNotificationsEnabled(context))

    /** The runtime permission is granted (always true below API 33). */
    val isGranted: Boolean get() = grantedState

    /** The permission is granted *and* the user has not switched notifications off. */
    val areNotificationsEnabled: Boolean get() = enabledState

    /** False once the user has permanently denied: only system settings can undo that. */
    val canRequest: Boolean
        get() = !grantedState &&
            PushPermission.activityOf(context)?.let { PushPermission.shouldShowRationale(it) } ?: true

    /** Shows the system dialog. A no-op below API 33 and when already granted. */
    fun request() {
        if (grantedState) return
        launch()
    }

    /** Re-reads the system state; called automatically on resume. */
    fun refresh() {
        grantedState = PushPermission.isGranted(context)
        enabledState = PushPermission.areNotificationsEnabled(context)
    }
}

/**
 * Remembers a [PushPermissionState] wired to the `RequestPermission` contract.
 *
 * ```kotlin
 * val notifications = rememberPushPermissionState()
 * NtToggleRow(..., checked = reminders, onCheckedChange = {
 *     reminders = it
 *     if (it) notifications.request()
 * })
 * ```
 */
@Composable
fun rememberPushPermissionState(onResult: (Boolean) -> Unit = {}): PushPermissionState {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    // Plain holder, not snapshot state: the launcher callback needs the state
    // object that is created after it, and this must not drive recomposition.
    val holder = remember { arrayOfNulls<PushPermissionState>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        holder[0]?.refresh()
        currentOnResult(granted)
    }
    val resolved = remember(context) {
        PushPermissionState(context) {
            if (PushPermission.isRuntimePermission) launcher.launch(PushPermission.PERMISSION)
        }
    }
    SideEffect { holder[0] = resolved }

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
