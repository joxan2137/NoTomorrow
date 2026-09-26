package app.notomorrow.rest

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.notomorrow.MainActivity
import app.notomorrow.NtChannels
import app.notomorrow.R
import app.notomorrow.app.AppState
import app.notomorrow.designsystem.NT
import app.notomorrow.restChimeUri
import app.notomorrow.push.NtPushIntents
import java.util.Locale

/**
 * The Android stand-in for the rest-timer Live Activity
 * (`NoTomorrowWidgets/RestTimerLiveActivity.swift`): one **ongoing** notification
 * whose countdown is rendered by the system from an absolute `when`, plus the
 * end-of-rest alert that replaces iOS's `interruptionLevel = .timeSensitive`.
 *
 * Posted once per state change (start, ±15, skip, end) — never per second.
 *
 * Tapping either one opens the workout in progress, expanded (`AppState.Route` on the launch
 * intent, as the Live Activity's `notomorrow://workout/rest` and the notification delegate do on
 * iOS): the running countdown asks for the rest sheet too, the "Rest is over" alert for the
 * workout alone.
 *
 * On API 36+ the ongoing notification asks to be promoted to a Live Update
 * (status-bar chip / Now Bar). That is decoration: the timer is complete and
 * correct without it.
 */
class RestTimerNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /** Posts (or refreshes) the ongoing countdown. */
    fun showRunning(
        endAt: Long,
        totalSeconds: Int,
        exerciseName: String,
        nextSetLabel: String,
    ) {
        val now = System.currentTimeMillis()
        val title = exerciseName.ifEmpty { appContext.getString(R.string.timer_rest) }
        val builder = NotificationCompat.Builder(appContext, NtChannels.REST)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(nextSetLabel)
            .setContentIntent(contentIntent(RUNNING_ROUTE, REQUEST_CONTENT_RUNNING))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(NT.Colors.ember.toArgb())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(endAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // Gone at 0 rather than counting negative until a late (inexact) alarm clears it.
            .setTimeoutAfter((endAt - now).coerceAtLeast(1L))
            .addAction(
                0,
                appContext.getString(R.string.rest_action_plus15),
                broadcast(RestTimerReceiver.ACTION_PLUS15, REQUEST_PLUS15),
            )
            .addAction(
                0,
                appContext.getString(R.string.common_skip),
                broadcast(RestTimerReceiver.ACTION_SKIP, REQUEST_SKIP),
            )
        applyLiveUpdate(builder, endAt = endAt, totalSeconds = totalSeconds, now = now)
        post(ID_RUNNING, builder)
    }

    fun cancelRunning() {
        manager.cancel(ID_RUNNING)
    }

    /** Clears a delivered "Rest is over" — a new rest, or a Skip, makes it stale. */
    fun cancelDone() {
        manager.cancel(ID_DONE)
    }

    /**
     * The end-of-rest alert. Title and body are the iOS content verbatim:
     * `timer.notification.title` and `"exercise · nextSetLabel"` (exercise alone
     * when there is no next-set label).
     */
    fun notifyDone(exerciseName: String, nextSetLabel: String) {
        val body = when {
            exerciseName.isEmpty() -> nextSetLabel
            nextSetLabel.isEmpty() -> exerciseName
            else -> "$exerciseName · $nextSetLabel"
        }
        val builder = NotificationCompat.Builder(appContext, NtChannels.REST_DONE)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(appContext.getString(R.string.timer_notification_title))
            .setContentText(body)
            .setContentIntent(contentIntent(DONE_ROUTE, REQUEST_CONTENT_DONE))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(NT.Colors.ember.toArgb())
            .setAutoCancel(true)
            // The rest chime, on STREAM_NOTIFICATION (USAGE_NOTIFICATION) rather than the alarm
            // or media stream, so playing music ducks instead of pausing. API 26+ takes the
            // sound from the channel (`NtChannels.REST_DONE`), which plays the same file.
            .setSound(restChimeUri(appContext), AudioManager.STREAM_NOTIFICATION)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        post(ID_DONE, builder)
    }

    // MARK: Live Update promotion (API 36+, progressive enhancement)

    private fun applyLiveUpdate(
        builder: NotificationCompat.Builder,
        endAt: Long,
        totalSeconds: Int,
        now: Long,
    ) {
        if (Build.VERSION.SDK_INT < API_LIVE_UPDATES) return
        if (!manager.canPostPromotedNotifications()) return
        val total = totalSeconds.coerceAtLeast(1)
        val elapsed = (total - ((endAt - now) / 1000L)).coerceIn(0L, total.toLong()).toInt()
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(elapsed)
            .setProgressSegments(
                listOf(
                    NotificationCompat.ProgressStyle.Segment(total)
                        .setColor(NT.Colors.ember.toArgb())
                )
            )
        builder
            .setStyle(style)
            .setShortCriticalText(shortCriticalText())
            .setRequestPromotedOngoing(true)
    }

    private fun shortCriticalText(): String {
        val locale: Locale = appContext.resources.configuration.locales[0] ?: Locale.getDefault()
        return appContext.getString(R.string.timer_rest).uppercase(locale)
    }

    // MARK: Plumbing

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked mid-flight — the in-app ticker still works.
        }
    }

    /**
     * Opens the app on [route]. Each notification has its own request code: `FLAG_UPDATE_CURRENT`
     * with a shared one would overwrite the other's route extra.
     */
    private fun contentIntent(route: AppState.Route, requestCode: Int): PendingIntent? {
        val intent = Intent(appContext, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(NtPushIntents.EXTRA_ROUTE, route.wire)
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Actions go straight to a `BroadcastReceiver`; never trampoline through an
     * Activity (blocked since Android 12).
     */
    private fun broadcast(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(appContext, RestTimerReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Ongoing countdown. */
        const val ID_RUNNING: Int = 2601

        /** End-of-rest alert. */
        const val ID_DONE: Int = 2602

        /** `Build.VERSION_CODES.BAKLAVA`; spelled out so this compiles on any compileSdk. */
        private const val API_LIVE_UPDATES = 36

        /** The running countdown opens the workout and its rest sheet. */
        val RUNNING_ROUTE = AppState.Route.RestTimer

        /** "Rest is over" opens the workout. */
        val DONE_ROUTE = AppState.Route.ActiveWorkout

        private const val REQUEST_PLUS15 = 0x2E52
        private const val REQUEST_SKIP = 0x2E53
        private const val REQUEST_CONTENT_RUNNING = 0x2E54
        private const val REQUEST_CONTENT_DONE = 0x2E55

        private val SMALL_ICON = R.drawable.ic_stat_rest
    }
}
