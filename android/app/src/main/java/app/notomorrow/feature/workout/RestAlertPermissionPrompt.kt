package app.notomorrow.feature.workout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.notomorrow.designsystem.NtAlert
import app.notomorrow.designsystem.NtAlertAction
import app.notomorrow.designsystem.NtAlertRole
import app.notomorrow.di.LocalAppContainer
import app.notomorrow.feature.settings.openExactAlarmSettings
import app.notomorrow.feature.settings.openNotificationSettings
import app.notomorrow.push.PushPermission
import app.notomorrow.push.rememberPushPermissionState
import app.notomorrow.rest.RestAlarms
import app.notomorrow.util.S

/**
 * Android only — iOS has no counterpart because it needs none: its rest-over notification is a
 * `UNTimeIntervalNotificationTrigger`, on time with no permission.
 *
 * Android's end-of-rest alarm is exact only with `SCHEDULE_EXACT_ALARM`, which a fresh install on
 * Android 14+ does not have; without it the alarm falls back to `setAndAllowWhileIdle` and, with
 * the phone locked, can land a minute or more late. Without `POST_NOTIFICATIONS` it shows nothing
 * at all. The one place that could grant either used to be buried in Settings > Notifications, so
 * this asks **once**, the first time a rest starts in a workout — the moment the alert matters.
 * Whatever the answer, it never asks again (`nt.rest.permissionAsked`); Settings keeps the switch.
 *
 * Allow asks for notifications first (a runtime dialog) and then opens the system's "Alarms &
 * reminders" page; `ExactAlarmStateReceiver` re-arms the running rest as soon as that is granted.
 * The runtime dialog is always tried first while the permission is missing — Android cannot tell
 * "never asked" from "denied for good" before a request (the rationale flag is false for both),
 * so only a denial that leaves no rationale sends the user to the app's notification page, and
 * coming back from there with notifications on continues to the exact-alarm page.
 */
@Composable
internal fun RestAlertPermissionPrompt(restRunning: Boolean) {
    val context = LocalContext.current
    val prefs = LocalAppContainer.current.appPrefs
    val alarms = remember(context) { RestAlarms(context) }
    var showing by remember { mutableStateOf(false) }
    // The user is in the app's notification settings on this prompt's behalf.
    var inNotificationSettings by remember { mutableStateOf(false) }
    val openStep: (RestAlertPrompt.Step?) -> Unit = { step ->
        when (step) {
            RestAlertPrompt.Step.NotificationSettings -> {
                inNotificationSettings = true
                openNotificationSettings(context)
            }
            RestAlertPrompt.Step.ExactAlarmSettings -> openExactAlarmSettings(context)
            RestAlertPrompt.Step.RequestNotifications, null -> Unit
        }
    }
    val notifications = rememberPushPermissionState { granted ->
        val activity = PushPermission.activityOf(context)
        openStep(
            RestAlertPrompt.afterRequest(
                granted = granted,
                canAskAgain = activity?.let(PushPermission::shouldShowRationale) ?: false,
                exactAlarms = alarms.canScheduleExact(),
            ),
        )
    }

    // Back from the notification page: on to the exact-alarm page once notifications are on.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && inNotificationSettings) {
                inNotificationSettings = false
                notifications.refresh()
                openStep(
                    RestAlertPrompt.afterNotificationSettings(
                        notificationsEnabled = notifications.areNotificationsEnabled,
                        exactAlarms = alarms.canScheduleExact(),
                    ),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(restRunning) {
        if (!restRunning) return@LaunchedEffect
        notifications.refresh()
        val ask = RestAlertPrompt.shouldAsk(
            exactAlarms = alarms.canScheduleExact(),
            notifications = notifications.areNotificationsEnabled,
            alreadyAsked = runCatching { prefs.restPermissionAskedOnce() }.getOrDefault(true),
        )
        if (ask) {
            runCatching { prefs.setRestPermissionAsked(true) }
            showing = true
        }
    }

    if (!showing) return
    NtAlert(
        title = stringResource(S.timer_exactAlarm_title),
        message = stringResource(S.timer_exactAlarm_message),
        actions = listOf(
            NtAlertAction(
                title = stringResource(S.timer_exactAlarm_notNow),
                role = NtAlertRole.Cancel,
            ),
            NtAlertAction(title = stringResource(S.timer_exactAlarm_allow)) {
                notifications.refresh()
                when (
                    val step = RestAlertPrompt.nextStep(
                        notificationsEnabled = notifications.areNotificationsEnabled,
                        permissionGranted = notifications.isGranted,
                    )
                ) {
                    RestAlertPrompt.Step.RequestNotifications -> notifications.request()
                    else -> openStep(step)
                }
            },
        ),
        onDismiss = { showing = false },
    )
}

/** The prompt's rules, pure so they are testable. */
internal object RestAlertPrompt {

    /** What Allow does first. */
    enum class Step { RequestNotifications, NotificationSettings, ExactAlarmSettings }

    /** Ask once, and only when something the on-time alert needs is missing. */
    fun shouldAsk(exactAlarms: Boolean, notifications: Boolean, alreadyAsked: Boolean): Boolean =
        !alreadyAsked && (!exactAlarms || !notifications)

    /**
     * Notifications come first — without them the alert is not shown at all. The runtime dialog
     * whenever the permission is missing (a fresh install included: [afterRequest] handles a
     * permission denied for good, which answers at once with no dialog), the app's notification
     * page when the permission is granted but notifications are switched off, and the exact-alarm
     * page once notifications are on.
     */
    fun nextStep(notificationsEnabled: Boolean, permissionGranted: Boolean): Step = when {
        notificationsEnabled -> Step.ExactAlarmSettings
        !permissionGranted -> Step.RequestNotifications
        else -> Step.NotificationSettings
    }

    /**
     * The runtime dialog answered. Granted: on to the exact-alarm page when that is still missing.
     * Denied with the dialog still available ([canAskAgain], the rationale flag): nothing — the user
     * said no. Denied with no dialog left: the app's notification page, the only way back.
     */
    fun afterRequest(granted: Boolean, canAskAgain: Boolean, exactAlarms: Boolean): Step? = when {
        granted -> if (exactAlarms) null else Step.ExactAlarmSettings
        canAskAgain -> null
        else -> Step.NotificationSettings
    }

    /** Back from the app's notification page: the exact-alarm page next, if notifications are on now. */
    fun afterNotificationSettings(notificationsEnabled: Boolean, exactAlarms: Boolean): Step? =
        if (notificationsEnabled && !exactAlarms) Step.ExactAlarmSettings else null
}
