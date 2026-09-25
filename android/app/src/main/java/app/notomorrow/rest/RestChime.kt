package app.notomorrow.rest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import app.notomorrow.R

/**
 * The end-of-rest chime played by the app itself — the port of `NoTomorrow/Services/RestChime.swift`.
 *
 * Only over the full-screen workout ([RestEndAlert.Haptic]): everywhere else the "Rest is over"
 * notification plays the same sound through its channel (`NtChannels.REST_DONE`). The sound is
 * `res/raw/rest_over.ogg`, rendered by `scripts/sounds/rest_over.py` (`docs/widgets.md`).
 *
 * `USAGE_NOTIFICATION_EVENT` / `CONTENT_TYPE_SONIFICATION`, so music ducks rather than stops, and —
 * like iOS's `.ambient` session, which the ringer switch silences — nothing plays unless the
 * ringer is in normal mode.
 */
class RestChime(context: Context) {

    private val appContext = context.applicationContext

    fun play() {
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try {
            val player = MediaPlayer.create(appContext, R.raw.rest_over, ATTRIBUTES, audio.generateAudioSessionId())
                ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                true
            }
            player.start()
        } catch (e: Exception) {
            // A missing chime is cosmetic: the haptic has already said it.
            Log.w(TAG, "Could not play the rest chime", e)
        }
    }

    private companion object {
        const val TAG = "RestChime"

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
