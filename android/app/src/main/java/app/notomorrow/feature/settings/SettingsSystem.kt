package app.notomorrow.feature.settings

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The system state Settings has to reflect but does not own: exact-alarm permission, the app's
 * own system-settings pages, and the Health Connect provider on Play.
 *
 * iOS gets all of this for free (`UIApplication.openSettingsURLString` and a single notification
 * authorization), so none of it exists in the Swift source; `docs/android-architecture.md`
 * (Settings → Notifications / Health app) asks for it explicitly.
 */

/** `true` when the rest timer can still fire to the second (`RestAlarms.canScheduleExact`). */
fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService<AlarmManager>()?.canScheduleExactAlarms() ?: false
}

/** Whether the exact-alarm row is worth showing at all — below API 31 it is always granted. */
val exactAlarmsAreRequestable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Re-read on every `ON_RESUME`, so a trip to the system's "Alarms & reminders" page is reflected
 * when the user comes back — the same contract as `rememberPushPermissionState`.
 */
@Composable
fun rememberExactAlarmState(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = canScheduleExactAlarms(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

/** `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` — the only way to ask; there is no runtime dialog. */
fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The app's notification settings page (API 26+), the Android stand-in for `openSettingsURLString`. */
fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(fallback) } }
}

/** Sends the user to Health Connect on Play with the onboarding deep link Google documents. */
fun openHealthConnectUpdate(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setData("market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri())
        .putExtra("overlay", true)
        .putExtra("callerId", context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW)
        .setData("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(web) } }
}

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

private fun String.toUri(): Uri = Uri.parse(this)
