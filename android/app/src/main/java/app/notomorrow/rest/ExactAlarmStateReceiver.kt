package app.notomorrow.rest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-arms a still-pending rest the moment `SCHEDULE_EXACT_ALARM` is granted.
 *
 * `SCHEDULE_EXACT_ALARM` is **not** pre-granted on a fresh install targeting 33+, and it is
 * revoked by a backup/restore onto Android 14 (research §7.3), so a rest started while it is
 * denied lands on the degraded `setAndAllowWhileIdle` path and can be batched by Doze. When the
 * user grants the permission from Settings, the system broadcasts
 * `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` to every app that declared it; putting
 * the pending rest back through [RestTimerController.rescheduleAfterBoot] upgrades it to
 * `setExactAndAllowWhileIdle` without waiting for the next set.
 *
 * The same broadcast fires on **revoke** — the system also stops the app and drops every future
 * exact alarm in that case — so rescheduling is the right response either way: it re-arms on the
 * degraded path, which is better than the alarm the platform just cancelled.
 *
 * `rescheduleAfterBoot` is a no-op when nothing is pending (`endAt == null`), and
 * [RestTimerController.restore] has already discarded a rest whose end is in the past, so this
 * cannot resurrect a finished timer.
 *
 * API 31+ only; on older releases exact alarms need no permission and the broadcast does not
 * exist. `exported="false"` is correct — the sender is the system, which is exempt.
 */
class ExactAlarmStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATE_CHANGED) return
        val controller = RestTimerController.get(context)
        val pending = goAsync()
        scope.launch {
            try {
                controller.rescheduleAfterBoot()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (API 31), spelled
         * out so the receiver compiles against any `minSdk` and so the literal matches the
         * `intent-filter` in `AndroidManifest.xml` verbatim.
         */
        const val ACTION_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
