package app.notomorrow.rest

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService

/**
 * The end-of-rest wake-up. iOS gets this from `UNTimeIntervalNotificationTrigger`;
 * Android needs an explicit alarm because the ongoing notification is only a view
 * and the process is very likely dead by the time the rest elapses.
 *
 * `setExact()` is **not** a permission-free fallback — it throws `SecurityException`
 * for the same reason `setExactAndAllowWhileIdle()` does. The permission-free
 * degraded path is `setAndAllowWhileIdle()` (research §7.3).
 *
 * The app declares `SCHEDULE_EXACT_ALARM`, never `USE_EXACT_ALARM`: Play restricts
 * the latter to alarm/timer/calendar apps.
 */
class RestAlarms(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService<AlarmManager>()

    /** True when exact alarms are available; false means the chime is only approximately on time. */
    fun canScheduleExact(): Boolean {
        val manager = alarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    /** (Re)arms the end-of-rest alarm for the absolute [endAt] epoch-millis. */
    fun schedule(endAt: Long) {
        val manager = alarmManager ?: return
        val pending = pendingIntent(mutablePending = false) ?: return
        try {
            if (canScheduleExact()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pending)
            } else {
                // Degraded: Doze may batch this by minutes. The in-app ticker still
                // renders the countdown correctly, which covers the common
                // "phone in hand between sets" case.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pending)
            }
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the call.
            try {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pending)
            } catch (_: SecurityException) {
                // Nothing else to do — the UI ticker remains correct.
            }
        }
    }

    fun cancel() {
        val manager = alarmManager ?: return
        val pending = pendingIntent(mutablePending = false) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun pendingIntent(mutablePending: Boolean): PendingIntent? {
        val intent = Intent(appContext, RestTimerReceiver::class.java).setAction(RestTimerReceiver.ACTION_END)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = flags or if (mutablePending) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, REQUEST_END, intent, flags)
    }

    private companion object {
        const val REQUEST_END = 0x2E51
    }
}
