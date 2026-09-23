package app.notomorrow.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar day and the zone it was read in. The system resets the process's default zone
 * before it delivers `ACTION_TIMEZONE_CHANGED`, so [now] after that broadcast reads the new one.
 */
@Immutable
data class CurrentDay(val date: LocalDate, val zone: ZoneId) {
    companion object {
        fun now(): CurrentDay {
            val zone = ZoneId.systemDefault()
            return CurrentDay(LocalDate.now(zone), zone)
        }
    }
}

/**
 * The calendar day, re-read on `ACTION_DATE_CHANGED` (and its time/time-zone siblings) and
 * on every resume — the replacement for iOS's `.NSCalendarDayChanged` + `.id(day)`.
 *
 * The Dashboard re-keys its day queries on it.
 */
@Composable
fun rememberCurrentDay(): LocalDate = rememberCurrentDayAndZone().date

/**
 * [rememberCurrentDay] plus the zone: it also changes when only the zone does (a flight a few
 * hours east or west on the same date), so the Fuel tab can hand every change to
 * `FuelViewModel.syncToday`, which re-keys its day queries on the new zone.
 */
@Composable
fun rememberCurrentDayAndZone(): CurrentDay {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var day by remember { mutableStateOf(CurrentDay.now()) }

    DisposableEffect(context, owner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                day = CurrentDay.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) day = CurrentDay.now()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            owner.lifecycle.removeObserver(observer)
        }
    }
    return day
}
