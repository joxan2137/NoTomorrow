package app.notomorrow.feature.workout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import app.notomorrow.designsystem.rememberSecondTicker
import app.notomorrow.rest.RestTimerState

/**
 * Zero-size observer that closes out the rest the second it elapses — 1:1 port of
 * `RestTimerExpiryWatcher` (`RestPillView.swift:57`).
 *
 * The alarm and the notification are the delivery path when the app is away; this is the
 * in-app one, so `isRunning` flips (and the pill disappears) without waiting for a
 * broadcast to come back through the receiver.
 */
@Composable
fun RestExpiryWatcher(state: RestTimerState, onElapsed: () -> Unit) {
    val now by rememberSecondTicker()
    val endAt = state.endAt
    LaunchedEffect(endAt, now) {
        if (endAt != null && endAt <= now) onElapsed()
    }
}
