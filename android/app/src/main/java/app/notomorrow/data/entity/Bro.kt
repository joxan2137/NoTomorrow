package app.notomorrow.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.notomorrow.model.AttendanceStatus
import app.notomorrow.model.HeadsUpKind
import app.notomorrow.model.Participant

/**
 * `BroPairing` (`Models.swift:348`) — a **singleton row**: the app pairs with at most one
 * partner. `null` means "not paired".
 */
@Entity(tableName = "bro_pairing")
data class BroPairingEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val partnerId: String,
    val partnerName: String,
    val myCode: String,
    val pairedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SINGLETON_ID: Int = 0
    }
}

/**
 * `AttendanceRecord` (`Models.swift:363`) — one row per (day, participant): what was
 * planned and what happened. iOS leaves the uniqueness unenforced; Android enforces it
 * with a unique index, so every write goes through `AttendanceDao.upsert`.
 */
@Entity(
    tableName = "attendance_record",
    indices = [Index(value = ["day", "participant"], unique = true)],
)
data class AttendanceRecordEntity(
    @PrimaryKey val id: String,
    /** Local midnight in the device zone. */
    val day: Long,
    val participant: Participant,
    val scheduledMinuteOfDay: Int,
    val status: AttendanceStatus = AttendanceStatus.Planned,
    val reason: String? = null,
    val note: String? = null,
    val makeUpDay: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** `HeadsUp` (`Models.swift:387`) — a short message tied to one session day. */
@Entity(
    tableName = "heads_up",
    indices = [Index("sentAt")],
)
data class HeadsUpEntity(
    @PrimaryKey val id: String,
    val fromMe: Boolean,
    val kind: HeadsUpKind,
    val text: String,
    /** Local midnight in the device zone. */
    val sessionDay: Long,
    val sentAt: Long = System.currentTimeMillis(),
    val readAt: Long? = null,
)
