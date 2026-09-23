package app.notomorrow.service

import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.net.BackendClient
import app.notomorrow.net.BackendError
import app.notomorrow.net.dto.Me
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `AttendanceSyncTests.swift` on Android: the outbox (one entry per day, last write wins, a newer
 * status survives the removal of the one that was sent), which failures keep a write queued, and
 * `BroService` sending my attendance whether or not I am paired, retrying on the next refresh,
 * dropping what the server rejects and doing nothing while signed out.
 */
class AttendanceSyncTest {

    private val zone = ZoneOffset.UTC
    private val monday = LocalDate.of(2026, 9, 21)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)

    // MARK: Outbox

    @Test
    fun `one entry per day, the latest status wins, oldest day first`() = runBlocking {
        val store = AttendanceOutbox.InMemoryStore()
        val outbox = AttendanceOutbox(store)

        outbox.put(wednesday, AttendanceStatus.Attended)
        outbox.put(monday, AttendanceStatus.Missed)
        outbox.put(wednesday, AttendanceStatus.Planned)

        assertEquals(
            listOf(
                AttendanceOutbox.Entry(monday, AttendanceStatus.Missed),
                AttendanceOutbox.Entry(wednesday, AttendanceStatus.Planned),
            ),
            outbox.entries(),
        )
        assertEquals(
            """[{"day":"2026-09-21","status":"missed"},{"day":"2026-09-23","status":"planned"}]""",
            store.value,
            "the same JSON iOS keeps in nt.attendance.outbox",
        )
    }

    @Test
    fun `removing a sent entry keeps a newer status for the same day`() = runBlocking {
        val outbox = AttendanceOutbox()
        outbox.put(monday, AttendanceStatus.Attended)
        val sent = outbox.entries().single()
        outbox.put(monday, AttendanceStatus.Missed) // an edit while the first write was in flight

        outbox.remove(sent)
        assertEquals(listOf(AttendanceOutbox.Entry(monday, AttendanceStatus.Missed)), outbox.entries())

        outbox.remove(outbox.entries().single())
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `clear forgets everything, and an unreadable outbox reads as empty`() = runBlocking {
        val store = AttendanceOutbox.InMemoryStore()
        val outbox = AttendanceOutbox(store)
        outbox.put(monday, AttendanceStatus.Attended)
        outbox.clear()
        assertNull(store.value, "the key goes away")

        val garbage = AttendanceOutbox(AttendanceOutbox.InMemoryStore("not json"))
        assertTrue(garbage.entries().isEmpty())
        val partly = AttendanceOutbox(
            AttendanceOutbox.InMemoryStore(
                """[{"day":"2026-09-21","status":"attended"},{"day":"soon","status":"attended"},{"day":"2026-09-22","status":"gone"}]""",
            ),
        )
        assertEquals(listOf(AttendanceOutbox.Entry(monday, AttendanceStatus.Attended)), partly.entries())
    }

    @Test
    fun `only a write the server rejects outright is dropped`() {
        assertTrue(AttendanceOutbox.isRejected(BackendError.Http(400, "bad_request", "")))
        assertTrue(AttendanceOutbox.isRejected(BackendError.Http(404, "not_found", "")))
        assertTrue(AttendanceOutbox.isRejected(BackendError.Http(422, "invalid", "")))
        assertFalse(AttendanceOutbox.isRejected(BackendError.Http(401, "unauthorized", "")))
        assertFalse(AttendanceOutbox.isRejected(BackendError.Http(408, "timeout", "")))
        assertFalse(AttendanceOutbox.isRejected(BackendError.Http(429, "rate_limited", "")))
        assertFalse(AttendanceOutbox.isRejected(BackendError.Http(503, "unavailable", "")))
        assertFalse(AttendanceOutbox.isRejected(BackendError.Unauthorized))
        assertFalse(AttendanceOutbox.isRejected(BackendError.Network))
        assertFalse(AttendanceOutbox.isRejected(IOException("offline")))
    }

    // MARK: Reporter

    @Test
    fun `an edit reports what the server should hold`() {
        val sent = mutableListOf<Pair<LocalDate, AttendanceStatus>>()
        val reporter = AttendanceReporter { day, status -> sent += day to status }
        val gymDays = setOf(monday, tuesday)

        reporter.report(
            listOf(
                AttendanceService.WorkoutDayChange.MarkAttended(wednesday),
                AttendanceService.WorkoutDayChange.MarkMissed(monday),
                AttendanceService.WorkoutDayChange.Clear(tuesday),
                AttendanceService.WorkoutDayChange.Clear(monday.plusDays(5)),
            ),
        ) { it in gymDays }

        assertEquals(
            listOf(
                wednesday to AttendanceStatus.Attended,
                monday to AttendanceStatus.Missed,
                tuesday to AttendanceStatus.Planned, // a gym day given back
            ),
            sent,
            "a cleared rest day has no server equivalent",
        )
    }

    // MARK: BroService

    /** A backend that records every `setAttendance`, failing with [failure] while it is set. */
    private class SpyBackend {
        val sent = mutableListOf<Pair<LocalDate, AttendanceStatus>>()

        /** Every `setAttendance` that went through, with its reason, note and make-up day. */
        val calls = mutableListOf<AttendanceOutbox.Entry>()
        var failure: (LocalDate) -> Throwable? = { null }
        var meCalls = 0

        val client: BackendClient = mockk(relaxed = true) {
            coEvery { setAttendance(any(), any(), any(), any(), any()) } answers {
                val day = firstArg<LocalDate>()
                failure(day)?.let { throw it }
                sent += day to secondArg<AttendanceStatus>()
                calls += AttendanceOutbox.Entry(day, secondArg(), thirdArg(), arg(3), arg(4))
            }
            coEvery { me() } answers {
                meCalls++
                Me(id = "u1", username = "mark", displayName = "Mark")
            }
        }
    }

    /** The signed-in account most tests run as. */
    private val mark = AttendanceOutbox.Target.remote("u1")

    private fun bro(
        backend: SpyBackend,
        outbox: AttendanceOutbox = AttendanceOutbox(),
        attendanceDao: FakeAttendanceDao = FakeAttendanceDao(),
        target: () -> AttendanceOutbox.Target? = { mark },
    ) = BroService(
        clientProvider = { backend.client },
        scheduleDao = FakeScheduleDao(),
        attendanceDao = attendanceDao,
        headsUpDao = mockk<HeadsUpDao>(relaxed = true),
        pairingDao = mockk<BroPairingDao>(relaxed = true),
        zone = zone,
        attendanceOutbox = outbox,
        syncTargetProvider = target,
    )

    @Test
    fun `attendance is sent whether or not I am paired`() = runBlocking {
        val backend = SpyBackend()
        val outbox = AttendanceOutbox()
        val service = bro(backend, outbox)
        assertFalse(service.isPaired)

        service.reportAttendance(monday, AttendanceStatus.Attended)

        assertEquals(listOf(monday to AttendanceStatus.Attended), backend.sent)
        assertTrue(outbox.entries().isEmpty(), "the server took it")
        assertNull(service.lastError.value)
    }

    @Test
    fun `a write made offline goes out with the next refresh`() = runBlocking {
        val backend = SpyBackend().apply { failure = { IOException("offline") } }
        val outbox = AttendanceOutbox()
        val service = bro(backend, outbox)

        service.reportAttendance(monday, AttendanceStatus.Attended)
        service.reportAttendance(tuesday, AttendanceStatus.Missed)
        assertTrue(backend.sent.isEmpty())
        assertEquals(2, outbox.entries().size, "both wait")
        assertNull(service.lastError.value, "background sync never shows an error")

        backend.failure = { null }
        service.refresh()

        assertEquals(listOf(monday to AttendanceStatus.Attended, tuesday to AttendanceStatus.Missed), backend.sent)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `a rejected write is dropped while the rest still go out`() = runBlocking {
        val backend = SpyBackend().apply {
            failure = { day -> if (day == monday) BackendError.Http(400, "bad_request", "") else null }
        }
        val outbox = AttendanceOutbox()
        outbox.put(monday, AttendanceStatus.Attended)
        outbox.put(wednesday, AttendanceStatus.Attended)
        val service = bro(backend, outbox)

        service.flushAttendanceOutbox()

        assertEquals(listOf(wednesday to AttendanceStatus.Attended), backend.sent)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `a failure that may clear up stops the flush and keeps the queue`() = runBlocking {
        val backend = SpyBackend().apply {
            failure = { day -> if (day == monday) BackendError.Http(503, "unavailable", "") else null }
        }
        val outbox = AttendanceOutbox()
        outbox.put(monday, AttendanceStatus.Attended)
        outbox.put(wednesday, AttendanceStatus.Attended)
        val service = bro(backend, outbox)

        service.flushAttendanceOutbox()

        assertTrue(backend.sent.isEmpty(), "oldest first, and nothing after a failure that may clear up")
        assertEquals(2, outbox.entries().size)
    }

    @Test
    fun `signed out sends and queues nothing`() = runBlocking {
        val backend = SpyBackend()
        val outbox = AttendanceOutbox()
        val service = bro(backend, outbox, target = { null })

        service.reportAttendance(monday, AttendanceStatus.Attended)

        assertTrue(backend.sent.isEmpty())
        assertTrue(outbox.entries().isEmpty())
        assertFalse(service.canSync)
    }

    @Test
    fun `delete account forgets the queue`() = runBlocking {
        val outbox = AttendanceOutbox()
        outbox.put(monday, AttendanceStatus.Attended)
        val service = bro(SpyBackend(), outbox)

        service.clearAttendanceOutbox()

        assertTrue(outbox.entries().isEmpty())
    }

    // MARK: Backend and account

    @Test
    fun `queued writes stay with the backend and account they were made on`() = runBlocking {
        val backend = SpyBackend().apply { failure = { IOException("offline") } }
        val outbox = AttendanceOutbox()
        var target: AttendanceOutbox.Target? = mark
        val service = bro(backend, outbox, target = { target })

        service.reportAttendance(monday, AttendanceStatus.Attended) // offline: queued for Mark
        backend.failure = { null }

        target = null // signed out
        service.refresh()
        service.reportAttendance(tuesday, AttendanceStatus.Attended)
        assertTrue(backend.sent.isEmpty(), "signed out: nothing sent, nothing queued")

        target = AttendanceOutbox.Target.Demo // the demo switch
        service.refresh()
        service.reportAttendance(wednesday, AttendanceStatus.Missed)
        assertEquals(listOf(wednesday to AttendanceStatus.Missed), backend.sent, "Mark's Monday never reaches the demo backend")

        target = AttendanceOutbox.Target.remote("u2") // another account
        service.refresh()
        assertEquals(1, backend.sent.size, "nor another account")
        assertEquals(listOf(AttendanceOutbox.Entry(monday, AttendanceStatus.Attended, target = mark)), outbox.entries())

        target = mark // Mark signs back in
        service.refresh()
        assertEquals(monday to AttendanceStatus.Attended, backend.sent.last())
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `one entry per day and account, and clearing one account keeps the others`() = runBlocking {
        val store = AttendanceOutbox.InMemoryStore()
        val outbox = AttendanceOutbox(store)
        val other = AttendanceOutbox.Target.remote("u2")

        outbox.put(monday, AttendanceStatus.Attended, mark)
        outbox.put(monday, AttendanceStatus.Missed, other)
        outbox.put(monday, AttendanceStatus.Planned, mark)

        assertEquals(
            listOf(AttendanceStatus.Missed, AttendanceStatus.Planned),
            outbox.entries().map { it.status }.sortedBy { it.raw },
        )
        assertEquals(listOf(AttendanceStatus.Planned), outbox.entries(mark).map { it.status })
        assertTrue(store.value!!.contains(""""backend":"remote","account":"u1""""), store.value)

        outbox.clear(mark)
        assertEquals(listOf(AttendanceOutbox.Entry(monday, AttendanceStatus.Missed, target = other)), outbox.entries())
    }

    @Test
    fun `an entry queued before entries were tagged goes out with whoever is signed in`() = runBlocking {
        val outbox = AttendanceOutbox(AttendanceOutbox.InMemoryStore("""[{"day":"2026-09-21","status":"attended"}]"""))
        val backend = SpyBackend()

        bro(backend, outbox).flushAttendanceOutbox()

        assertEquals(listOf(monday to AttendanceStatus.Attended), backend.sent)
        assertTrue(outbox.entries().isEmpty())
    }

    // MARK: Can't make it

    @Test
    fun `can't make it goes through the outbox with its reason, note and make-up day`() = runBlocking {
        val backend = SpyBackend().apply { failure = { IOException("offline") } }
        val store = AttendanceOutbox.InMemoryStore()
        val outbox = AttendanceOutbox(store)
        val service = bro(backend, outbox)
        service.reportAttendance(monday, AttendanceStatus.Planned) // a gym day given back, still queued

        service.cantMakeIt(reason = "sick", note = "flu", makeUpDay = wednesday, sessionDay = monday)

        val cancellation = AttendanceOutbox.Entry(monday, AttendanceStatus.Cancelled, "sick", "flu", wednesday, mark)
        assertEquals(listOf(cancellation), outbox.entries(), "it replaces what was queued for the day")
        assertTrue(store.value!!.contains(""""reason":"sick","note":"flu","makeUpDay":"2026-09-23""""), store.value)
        assertNull(service.lastError.value, "background sync never shows an error")

        backend.failure = { null }
        service.refresh()

        assertEquals(listOf(cancellation.copy(target = null)), backend.calls, "sent in full once back online")
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun `can't make it signed out stays local`() = runBlocking {
        val backend = SpyBackend()
        val outbox = AttendanceOutbox()
        val attendance = FakeAttendanceDao()
        val service = bro(backend, outbox, attendance, target = { null })

        service.cantMakeIt(reason = "work", note = null, makeUpDay = null, sessionDay = monday)

        assertEquals(AttendanceStatus.Cancelled, attendance.rows.value.single().status)
        assertTrue(backend.calls.isEmpty())
        assertTrue(outbox.entries().isEmpty())
    }

    // MARK: Concurrent flushes

    /** Reads hand back what was stored when they began, and the one after [gateAfter] reads waits for [gate]. */
    private class GatedStore(private val gateAfter: Int) : AttendanceOutbox.Store {
        private val inner = AttendanceOutbox.InMemoryStore()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        private var reads = 0

        override suspend fun read(): String? {
            val value = inner.read()
            if (reads++ == gateAfter) {
                reached.complete(Unit)
                gate.await()
            }
            return value
        }

        override suspend fun update(transform: (String?) -> String?) = inner.update(transform)
    }

    @Test
    fun `a write queued while a flush is finishing is sent by that flush`() = runBlocking {
        val backend = SpyBackend()
        val store = GatedStore(gateAfter = 0)
        val outbox = AttendanceOutbox(store)
        val service = bro(backend, outbox)

        // A refresh's flush has read an empty queue and is about to finish…
        val first = launch { service.flushAttendanceOutbox() }
        store.reached.await()
        // …when Finish reports the day: its own flush finds the first one running.
        service.reportAttendance(monday, AttendanceStatus.Attended)
        assertTrue(backend.sent.isEmpty())

        store.gate.complete(Unit)
        first.join()

        assertEquals(listOf(monday to AttendanceStatus.Attended), backend.sent, "not left for the next refresh")
        assertTrue(outbox.entries().isEmpty())
    }
}
