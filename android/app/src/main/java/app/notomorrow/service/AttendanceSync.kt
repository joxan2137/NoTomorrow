package app.notomorrow.service

import app.notomorrow.data.prefs.AppPrefs
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.net.BackendError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Hands my attendance writes to the backend, paired or not — `AttendanceSync.swift`. The server's
 * reminder and 21:00 "did you skip?" jobs read my status for the day, and a partner sees it (a
 * finished workout is `attended`, not a stale `confirmed`). Room stays the source of truth; this is
 * fire and forget, with [AttendanceOutbox] for retries.
 *
 * The app's reporter (`AppContainer.attendanceReporter`) launches [BroService.reportAttendance] on
 * the process scope, so a report outlives the screen that made it. Tests pass [None] or a spy, so
 * they never reach a backend.
 */
fun interface AttendanceReporter {

    fun report(day: LocalDate, status: AttendanceStatus)

    /** Reports the local writes a workout edit or delete made (see [AttendanceService.wireStatus]). */
    fun report(changes: List<AttendanceService.WorkoutDayChange>, isGymDay: (LocalDate) -> Boolean) {
        for (change in changes) {
            val (day, status) = AttendanceService.wireStatus(change, isGymDay) ?: continue
            report(day, status)
        }
    }

    companion object {
        /** Reports nothing: previews, tests, and the onboarding flow's own services. */
        val None = AttendanceReporter { _, _ -> }
    }
}

/**
 * My attendance writes the backend has not confirmed yet, one per day and account (the latest
 * status wins) — `AttendanceOutbox` in `AttendanceSync.swift`, kept as a JSON array under
 * `nt.attendance.outbox`:
 * `[{"day": "yyyy-MM-dd", "status": "cancelled", "reason": "sick", "note": "…", "makeUpDay": "yyyy-MM-dd",
 * "backend": "remote", "account": "<user id>"}]`. Every field after `status` is optional.
 *
 * [BroService] puts a write here before sending it and removes it once the server took it, so a
 * write made offline (or cut off by the process dying) goes out with a later refresh. Each entry
 * carries the backend and account it was made on ([Target]), and only the matching ones are sent:
 * a write queued before a sign-out or a flip of the demo switch waits for its own account instead
 * of reaching another one.
 */
class AttendanceOutbox(private val store: Store = InMemoryStore()) {

    /** Where the JSON lives. [update] must be atomic (one DataStore edit in the app). */
    interface Store {
        suspend fun read(): String?

        /** Replaces the stored JSON with `transform(current)`; `null` removes it. */
        suspend fun update(transform: (String?) -> String?)
    }

    /** Tests, and a [BroService] that never reports (the onboarding flow's own). */
    class InMemoryStore(initial: String? = null) : Store {
        private val lock = Mutex()

        var value: String? = initial
            private set

        override suspend fun read(): String? = lock.withLock { value }

        override suspend fun update(transform: (String?) -> String?) = lock.withLock { value = transform(value) }
    }

    /**
     * The backend (the demo one or the real one) and the account a write was made on. The demo
     * backend has no account.
     */
    data class Target(val backend: String, val account: String?) {
        companion object {
            const val DEMO = "demo"
            const val REMOTE = "remote"

            val Demo = Target(DEMO, null)

            fun remote(userId: String) = Target(REMOTE, userId)
        }
    }

    /**
     * One queued write: my [status] for [day]; a cancellation also carries its [reason], [note]
     * and [makeUpDay]. [target] is `null` only for an entry queued before entries were tagged:
     * whoever is signed in sends it.
     */
    data class Entry(
        val day: LocalDate,
        val status: AttendanceStatus,
        val reason: String? = null,
        val note: String? = null,
        val makeUpDay: LocalDate? = null,
        val target: Target? = null,
    ) {
        fun belongsTo(target: Target): Boolean = this.target == null || this.target == target
    }

    /** Oldest day first. */
    suspend fun entries(): List<Entry> = decode(store.read())

    /** The entries [target] sends, oldest day first. */
    suspend fun entries(target: Target): List<Entry> = entries().filter { it.belongsTo(target) }

    /** Queues [entry], replacing anything queued for that day on the same backend and account. */
    suspend fun put(entry: Entry) {
        store.update { json ->
            val kept = decode(json).filterNot { it.day == entry.day && (it.target == entry.target || it.target == null) }
            encode(kept + entry)
        }
    }

    /** Queues [status] for [day] on [target]. */
    suspend fun put(day: LocalDate, status: AttendanceStatus, target: Target? = null) =
        put(Entry(day, status, target = target))

    /** Drops [entry] once sent, unless a newer status for the same day replaced it in the meantime. */
    suspend fun remove(entry: Entry) {
        store.update { json ->
            val list = decode(json)
            if (entry in list) encode(list - entry) else json
        }
    }

    /** Forgets everything queued for [target], or everything when it is `null`. */
    suspend fun clear(target: Target? = null) {
        if (target == null) {
            store.update { null }
        } else {
            store.update { json -> encode(decode(json).filterNot { it.target == target }) }
        }
    }

    @Serializable
    private data class Wire(
        val day: String,
        val status: String,
        val reason: String? = null,
        val note: String? = null,
        val makeUpDay: String? = null,
        val backend: String? = null,
        val account: String? = null,
    )

    companion object {

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Wire.serializer())

        /** `nt.attendance.outbox` in DataStore. */
        fun prefsStore(prefs: AppPrefs): Store = object : Store {
            override suspend fun read(): String? = prefs.attendanceOutboxOnce()
            override suspend fun update(transform: (String?) -> String?) = prefs.updateAttendanceOutbox(transform)
        }

        /**
         * A reply that retrying cannot fix (the server rejected the write itself), as opposed to no
         * connection, a lapsed session, rate limiting or server trouble, which keep the write queued.
         */
        fun isRejected(error: Throwable): Boolean {
            val wrapped = BackendError.wrap(error)
            val status = (wrapped as? BackendError.Http)?.status ?: return false
            return status in 400..499 && status !in RETRYABLE_4XX
        }

        private val RETRYABLE_4XX = setOf(401, 408, 429)

        /** Unreadable or unknown entries are dropped; the next write rewrites the list without them. */
        internal fun decode(raw: String?): List<Entry> {
            if (raw.isNullOrEmpty()) return emptyList()
            val wire = runCatching { json.decodeFromString(serializer, raw) }.getOrElse { return emptyList() }
            return wire.mapNotNull { item ->
                val day = parseDay(item.day) ?: return@mapNotNull null
                val status = AttendanceStatus.from(item.status) ?: return@mapNotNull null
                Entry(
                    day = day,
                    status = status,
                    reason = item.reason,
                    note = item.note,
                    makeUpDay = item.makeUpDay?.let(::parseDay),
                    target = item.backend?.let { Target(it, item.account) },
                )
            }.sortedBy { it.day }
        }

        /** Sorted by day; `null` for an empty list, so the key goes away. Empty fields are left out. */
        internal fun encode(list: List<Entry>): String? {
            if (list.isEmpty()) return null
            return json.encodeToString(
                serializer,
                list.sortedBy { it.day }.map {
                    Wire(
                        day = it.day.toString(),
                        status = it.status.raw,
                        reason = it.reason,
                        note = it.note,
                        makeUpDay = it.makeUpDay?.toString(),
                        backend = it.target?.backend,
                        account = it.target?.account,
                    )
                },
            )
        }

        private fun parseDay(raw: String): LocalDate? = try {
            LocalDate.parse(raw)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
