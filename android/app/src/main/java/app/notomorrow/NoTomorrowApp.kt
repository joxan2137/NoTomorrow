package app.notomorrow

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import app.notomorrow.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process entry point: forces night mode, builds the DI container, creates the notification
 * channels and runs the start-up work `RootView.task` does on iOS.
 *
 * Channels are created here — on the first **foreground** launch — deliberately: a channel first
 * created from a background process (which the FCM SDK will do on message receipt) cannot show
 * notifications (`docs/android-architecture.md`, "Push registration").
 */
class NoTomorrowApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Start-up work that must outlive any composition or Activity. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        // Dark-only, forced, so View-based surfaces (CameraX preview, the system
        // photo picker) do not flip to light with the system theme.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        start()
    }

    /**
     * Everything the container must know before the first meaningful frame, off the main thread:
     * [AppContainer.load] opens the database through `container.store` (a store that cannot be
     * opened shows the store error screen and skips the seeding), and `MainActivity` holds the
     * splash screen up until `appState.isLoaded` flips.
     *
     * Order is load-bearing twice over: `load()` seeds the session before any authenticated call,
     * and the exercise import must precede the routine seeder, which is a deliberate no-op until
     * the fifteen library ids exist.
     */
    private fun start() {
        scope.launch {
            container.load()
            // Re-arms the notification and the alarm from `nt.rest.*` after a cold start.
            container.restTimer.awaitRestored()
            container.seed()
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannels(
            listOf(
                channel(
                    NtChannels.REST,
                    R.string.channel_rest_name,
                    R.string.channel_rest_description,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
                channel(
                    NtChannels.REST_DONE,
                    R.string.channel_rest_done_name,
                    R.string.channel_rest_done_description,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    enableVibration(true)
                    // From API 26 the channel, not the builder, owns the sound. USAGE_NOTIFICATION
                    // makes playing music duck instead of pausing — the behaviour iOS gets from
                    // `.timeSensitive` — and it must match what `RestTimerNotifier.notifyDone`
                    // sets on the pre-O path.
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build(),
                    )
                },
                channel(
                    NtChannels.REMINDERS,
                    R.string.channel_reminders_name,
                    R.string.channel_reminders_description,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                channel(
                    NtChannels.HEADS_UPS,
                    R.string.channel_headsups_name,
                    R.string.channel_headsups_description,
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { enableVibration(true) },
            )
        )
    }

    private fun channel(
        id: String,
        nameRes: Int,
        descriptionRes: Int,
        importance: Int,
    ): NotificationChannel = NotificationChannel(id, getString(nameRes), importance).apply {
        description = getString(descriptionRes)
    }
}

/** Channel ids are part of the contract — renaming one orphans user settings. */
object NtChannels {
    /** Ongoing rest countdown. `IMPORTANCE_LOW`, no sound. */
    const val REST = "nt.rest"

    /** End-of-rest alert — the analogue of iOS's `.timeSensitive`. */
    const val REST_DONE = "nt.rest.done"

    /** Gym-day reminders and the evening check-in. */
    const val REMINDERS = "nt.reminders"

    /**
     * Heads-ups and partner state changes pushed from the backend. The literal `"nt.headsup"` is
     * repeated in `AndroidManifest.xml` as the FCM default channel — manifest meta-data cannot
     * reference a Kotlin const, so the two must be changed together.
     */
    const val HEADS_UPS = "nt.headsup"
}
