package app.notomorrow.feature.fuel

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.service.Days
import app.notomorrow.util.S
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import java.util.UUID

/**
 * `FuelModel.Undo` (`FuelModel.swift`): the last delete or copy to today, undoable from the
 * toast until it expires or the next one replaces it.
 */
@Immutable
data class FuelUndo(
    val kind: Kind,
    val id: String = UUID.randomUUID().toString(),
) {
    sealed interface Kind {
        /** The row as it was stored, plus the name it showed (for a food deleted meanwhile). */
        data class Deleted(val entry: MealEntryEntity, val displayName: String) : Kind

        /** Ids of the entries a "Log again today" / "Copy to today" inserted. */
        data class Added(val ids: List<String>) : Kind
    }

    /** "Entry deleted" / "Added to today". */
    @get:StringRes
    val messageRes: Int
        get() = when (kind) {
            is Kind.Deleted -> S.fuel_entryDeleted
            is Kind.Added -> S.fuel_addedToToday
        }
}

/**
 * The Fuel home's row actions that the toast can take back — `FuelModel.delete`,
 * `logAgainToday`, `copyToToday`, `undo` and `expireUndo`. Framework-free (DAOs and a clock in,
 * no `ViewModel`), so the iOS `FuelUndoCopyTests` cases run on the JVM; [FuelViewModel] launches
 * each call in its scope.
 *
 * Only the last action is undoable: a new delete or copy replaces [pending] (copies in a row add
 * up, see [copyToToday]), and an expiry names the undo it belongs to, so an old toast's timer never
 * ends a newer one.
 */
class FuelEntryActions(
    private val mealDao: MealDao,
    private val foodDao: FoodDao,
    /** Read on every copy, so "today" follows a time-zone change. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** A fixed zone (the tests). */
    constructor(mealDao: MealDao, foodDao: FoodDao, zone: ZoneId) : this(mealDao, foodDao, { zone })

    private val _pending = MutableStateFlow<FuelUndo?>(null)
    val pending: StateFlow<FuelUndo?> = _pending.asStateFlow()

    /** `FuelModel.delete`: removes the row and leaves an undo with every stored column. */
    suspend fun delete(entryId: String) {
        val row = mealDao.byId(entryId) ?: return
        val name = row.foodId?.let { foodDao.byId(it)?.name } ?: row.customName.orEmpty()
        mealDao.deleteById(entryId)
        _pending.value = FuelUndo(FuelUndo.Kind.Deleted(row, name))
    }

    /** "Log again today" on a past day's entry: a copy in the same slot today (new id, logged now). */
    suspend fun logAgainToday(entryId: String, nowMillis: Long) {
        copyToToday(listOf(entryId), nowMillis)
    }

    /**
     * "Copy to today" on a past day's meal slot: [entryIds] (the slot's rows, in their order) into
     * the same slot today. The i-th copy is logged `i` ms after now, so the copies keep the source
     * order and land after whatever today's slot already has. A food-backed copy counts as a use.
     *
     * Copies in a row add up: while the toast still offers to undo earlier copies, the new undo
     * takes those back too (a double tap on "Copy to today" is undone by one Undo). It is a new
     * undo, so the toast's timer restarts and TalkBack announces it again.
     */
    suspend fun copyToToday(entryIds: List<String>, nowMillis: Long) {
        val source = entryIds.mapNotNull { mealDao.byId(it) }
        if (source.isEmpty()) return
        val zone = zone()
        val today = Days.date(nowMillis, zone)
        val copies = source.mapIndexed { index, entry ->
            FuelDerive.copied(
                entry = entry,
                id = UUID.randomUUID().toString(),
                day = today,
                loggedAt = nowMillis + index,
                zone = zone,
            )
        }
        mealDao.insertAll(copies)
        source.forEach { entry -> entry.foodId?.let { foodDao.bumpUsage(it, nowMillis) } }
        val earlier = (_pending.value?.kind as? FuelUndo.Kind.Added)?.ids.orEmpty()
        _pending.value = FuelUndo(FuelUndo.Kind.Added(earlier + copies.map { it.id }))
    }

    /**
     * The toast's Undo: re-inserts a deleted row as it was (same id, day, `loggedAt`, food and AI
     * flags), or removes the copies just added. The usage bump of a copy is not taken back.
     */
    suspend fun undo() {
        val undo = _pending.value ?: return
        _pending.value = null
        when (val kind = undo.kind) {
            is FuelUndo.Kind.Deleted -> {
                val foodExists = kind.entry.foodId?.let { foodDao.byId(it) != null } ?: true
                mealDao.insert(FuelDerive.restored(kind.entry, kind.displayName, foodExists))
            }
            is FuelUndo.Kind.Added -> mealDao.deleteByIds(kind.ids)
        }
    }

    /** The toast timed out: drops the undo unless a newer one replaced it meanwhile. */
    fun expire(undoId: String) {
        if (_pending.value?.id == undoId) _pending.value = null
    }
}
