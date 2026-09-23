package app.notomorrow.feature.bro

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.HeadsUpEntity
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.service.AttendanceService
import app.notomorrow.service.BroService
import app.notomorrow.service.Days
import app.notomorrow.util.Localizer
import app.notomorrow.util.NtKeys
import app.notomorrow.util.S
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** `CantMakeItSheet.CantReason` — the five `cant.reason.*` chips, in iOS order. */
enum class CantReason(val raw: String) {
    Sick("sick"),
    Work("work"),
    Tired("tired"),
    Family("family"),
    None("none");

    @get:StringRes
    val labelRes: Int
        get() = NtKeys.cantReason(raw) ?: S.cant_reason_none
}

/** `CantMakeItSheet.MakeUpChoice`. */
sealed interface MakeUpChoice {
    data class Day(val value: LocalDate) : MakeUpChoice
    data object Skip : MakeUpChoice

    /** `MakeUpChoice.date` — the proposed day, or `null` for "skip this one". */
    val date: LocalDate? get() = (this as? Day)?.value
}

/** Everything the "Can't make it today" sheet renders. */
data class CantMakeItUiState(
    val sessionDay: LocalDate = LocalDate.now(),
    val minuteOfDay: Int = AttendanceService.DEFAULT_MINUTE_OF_DAY,
    val routineName: String? = null,
    val partnerName: String? = null,
    val makeUpDays: List<LocalDate> = emptyList(),
    val reason: CantReason? = null,
    val note: String = "",
    val makeUp: MakeUpChoice? = null,
    val isSending: Boolean = false,
) {
    val isPaired: Boolean get() = partnerName != null
}

/**
 * The write side of `CantMakeItSheet` (`Features/Bro/CantMakeItSheet.swift`).
 *
 * iOS takes the session day, minute and routine name as parameters; here the sheet is opened
 * from two places with no arguments, so it derives exactly what `BroView` would have passed —
 * `BroDerived.sessionLine`, falling back to today and the schedule's default minute.
 */
class CantMakeItViewModel(
    private val scheduleDao: ScheduleDao,
    private val attendanceDao: AttendanceDao,
    private val routineDao: RoutineDao,
    private val workoutDao: WorkoutDao,
    private val pairingDao: BroPairingDao,
    private val headsUpDao: HeadsUpDao,
    private val attendance: AttendanceService,
    private val bro: BroService,
    private val strings: Localizer,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val reason = MutableStateFlow<CantReason?>(null)
    private val note = MutableStateFlow("")
    private val makeUp = MutableStateFlow<MakeUpChoice?>(null)
    private val isSending = MutableStateFlow(false)

    private val session: Flow<Derived> = combine(
        scheduleDao.observeSchedule(),
        attendanceDao.observeAllDesc(),
        routineDao.observeRoutines(),
        workoutDao.observeFinishedWorkouts(),
        combine(bro.partner, pairingDao.observePairing()) { partner, pairing ->
            partner?.name ?: pairing?.partnerName
        },
    ) { schedule, records, routines, workouts, partnerName ->
        val instant = now()
        val line = BroDerived.sessionLine(schedule, records, routines, workouts, instant, zone)
        val day = line?.day ?: Days.date(instant, zone)
        Derived(
            sessionDay = day,
            minuteOfDay = line?.minuteOfDay
                ?: schedule?.defaultMinuteOfDay
                ?: AttendanceService.DEFAULT_MINUTE_OF_DAY,
            routineName = line?.routineName,
            partnerName = partnerName,
            makeUpDays = BroDerived.nonGymDays(day, schedule),
        )
    }

    private val form: Flow<Form> = combine(reason, note, makeUp, isSending) { r, n, m, sending ->
        Form(r, n, m, sending)
    }

    val uiState: StateFlow<CantMakeItUiState> =
        combine(session, form) { derived, form ->
            CantMakeItUiState(
                sessionDay = derived.sessionDay,
                minuteOfDay = derived.minuteOfDay,
                routineName = derived.routineName,
                partnerName = derived.partnerName,
                makeUpDays = derived.makeUpDays,
                reason = form.reason,
                note = form.note,
                makeUp = form.makeUp,
                isSending = form.isSending,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CantMakeItUiState())

    // MARK: - Form

    /** Tapping the selected chip clears it, exactly as iOS does. */
    fun toggleReason(value: CantReason) {
        reason.value = if (reason.value == value) null else value
    }

    fun setNote(raw: String) {
        note.value = if (raw.length > MAX_NOTE_LENGTH) raw.take(MAX_NOTE_LENGTH) else raw
    }

    fun toggleMakeUp(value: MakeUpChoice) {
        makeUp.value = if (makeUp.value == value) null else value
    }

    /** The sheet is presented fresh every time on iOS; the view model is not. */
    fun reset() {
        reason.value = null
        note.value = ""
        makeUp.value = null
        isSending.value = false
    }

    // MARK: - Send

    /**
     * `CantMakeItSheet.send()` — my `AttendanceRecord(.cancelled)`, the make-up day as planned
     * (`AttendanceService.markPlanned`, the server's rule) and a local `HeadsUp(.cantMakeIt)`
     * first, then the backend is told whenever I am signed in, paired or not — its 21:00 skip check
     * reads the cancellation too; the heads-up itself goes out only when paired (`BroService`).
     * [onDone] runs as soon as the local writes have landed, so the sheet closes without waiting
     * for the network.
     */
    fun send(onDone: () -> Unit) {
        if (isSending.value) return
        isSending.value = true
        val state = uiState.value
        val day = state.sessionDay
        val reasonRaw = state.reason?.raw
        val trimmed = state.note.trim()
        val noteValue = trimmed.ifEmpty { null }
        val makeUpDay = state.makeUp?.date

        viewModelScope.launch {
            val record = attendance.markMissed(
                day = day,
                reason = reasonRaw,
                note = noteValue,
                makeUp = makeUpDay,
            )
            // A day I already trained stays attended: nothing is cancelled, told to the partner or
            // sent to the server.
            if (record.status == AttendanceStatus.Attended) {
                onDone()
                isSending.value = false
                return@launch
            }
            makeUpDay?.let { attendance.markPlanned(it) }
            val text = noteValue
                ?: state.reason?.let { strings.string(it.labelRes) }
                ?: ""
            headsUpDao.insert(
                HeadsUpEntity(
                    id = UUID.randomUUID().toString(),
                    fromMe = true,
                    kind = HeadsUpKind.CantMakeIt,
                    text = text,
                    sessionDay = Days.millis(day, zone),
                    sentAt = now().toEpochMilli(),
                )
            )
            onDone()
            if (bro.canSync) bro.cantMakeIt(reasonRaw, noteValue, makeUpDay, day)
            isSending.value = false
        }
    }

    private data class Derived(
        val sessionDay: LocalDate,
        val minuteOfDay: Int,
        val routineName: String?,
        val partnerName: String?,
        val makeUpDays: List<LocalDate>,
    )

    private data class Form(
        val reason: CantReason?,
        val note: String,
        val makeUp: MakeUpChoice?,
        val isSending: Boolean,
    )

    companion object {
        /** `CantMakeItSheet.maxNoteLength`. */
        const val MAX_NOTE_LENGTH: Int = 80
    }
}
