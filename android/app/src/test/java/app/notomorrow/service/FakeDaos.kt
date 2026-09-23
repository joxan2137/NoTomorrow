package app.notomorrow.service

import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.relation.RoutineItemWithExercise
import app.notomorrow.data.relation.RoutineWithItems
import app.notomorrow.model.Participant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the DAOs the services write through. Only the abstract members
 * are implemented — the `@Transaction` helpers with bodies (`AttendanceDao.upsert`,
 * `RoutineDao.insertRoutineWithItems`, `ScheduleDao.save`) are inherited, so the tests
 * exercise the real ones.
 */
class FakeAttendanceDao(initial: List<AttendanceRecordEntity> = emptyList()) : AttendanceDao {

    val rows = MutableStateFlow(initial)

    override fun observeAllDesc(): Flow<List<AttendanceRecordEntity>> =
        rows.map { list -> list.sortedByDescending { it.day } }

    override suspend fun allDesc(): List<AttendanceRecordEntity> =
        rows.value.sortedByDescending { it.day }

    override fun observeRange(from: Long, to: Long): Flow<List<AttendanceRecordEntity>> =
        rows.map { list -> list.filter { it.day in from until to }.sortedBy { it.day } }

    override suspend fun range(from: Long, to: Long): List<AttendanceRecordEntity> =
        rows.value.filter { it.day in from until to }.sortedBy { it.day }

    override suspend fun since(from: Long): List<AttendanceRecordEntity> =
        rows.value.filter { it.day >= from }.sortedBy { it.day }

    override suspend fun forDay(day: Long): List<AttendanceRecordEntity> =
        rows.value.filter { it.day == day }

    override suspend fun forDay(day: Long, participant: Participant): AttendanceRecordEntity? =
        rows.value.firstOrNull { it.day == day && it.participant == participant }

    override suspend fun pastResolved(participant: Participant, before: Long): List<AttendanceRecordEntity> =
        rows.value
            .filter { it.participant == participant && it.day < before }
            .filter { it.status.raw != "planned" && it.status.raw != "confirmed" }
            .sortedByDescending { it.day }

    override suspend fun insert(record: AttendanceRecordEntity) {
        check(rows.value.none { it.day == record.day && it.participant == record.participant }) {
            "unique (day, participant) violated"
        }
        rows.value = rows.value + record
    }

    override suspend fun update(record: AttendanceRecordEntity) {
        rows.value = rows.value.map { if (it.id == record.id) record else it }
    }

    override suspend fun delete(record: AttendanceRecordEntity) {
        rows.value = rows.value.filterNot { it.id == record.id }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

class FakeScheduleDao(initial: GymScheduleEntity? = null) : ScheduleDao {

    val row = MutableStateFlow(initial)

    override fun observeSchedule(): Flow<GymScheduleEntity?> = row

    override suspend fun schedule(): GymScheduleEntity? = row.value

    override suspend fun upsertRaw(schedule: GymScheduleEntity) {
        row.value = schedule
    }

    override suspend fun deleteAll() {
        row.value = null
    }
}

class FakeProfileDao(initial: UserProfileEntity? = null) : ProfileDao {

    val row = MutableStateFlow(initial)

    override fun observeProfile(): Flow<UserProfileEntity?> = row

    override suspend fun profile(): UserProfileEntity? = row.value

    override suspend fun createdAt(): Long? = row.value?.createdAt

    override suspend fun upsert(profile: UserProfileEntity) {
        row.value = profile
    }

    override suspend fun deleteAll() {
        row.value = null
    }
}

class FakeExerciseDao(initial: List<ExerciseEntity> = emptyList()) : ExerciseDao {

    val rows = MutableStateFlow(initial)

    override fun observeAllByName(): Flow<List<ExerciseEntity>> =
        rows.map { list -> list.sortedBy { it.name.lowercase() } }

    override suspend fun allByName(): List<ExerciseEntity> = rows.value.sortedBy { it.name.lowercase() }

    override suspend fun count(): Int = rows.value.size

    override suspend fun libraryCount(): Int = rows.value.count { !it.isCustom }

    override suspend fun byId(id: String): ExerciseEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun byIds(ids: List<String>): List<ExerciseEntity> = rows.value.filter { it.id in ids }

    override suspend fun missingPolishNames(limit: Int): List<ExerciseEntity> =
        rows.value.filter { it.namePL == null && !it.isCustom }.take(limit)

    override suspend fun updatePolishName(id: String, namePL: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(namePL = namePL) else it }
    }

    override suspend fun markUsed(id: String, at: Long) {
        rows.value = rows.value.map { if (it.id == id) it.copy(lastUsedAt = at) else it }
    }

    override suspend fun insertAllIgnoring(exercises: List<ExerciseEntity>) {
        val known = rows.value.map { it.id }.toSet()
        rows.value = rows.value + exercises.filter { it.id !in known }
    }

    override suspend fun upsert(exercise: ExerciseEntity) {
        rows.value = rows.value.filterNot { it.id == exercise.id } + exercise
    }

    override suspend fun update(exercise: ExerciseEntity) = upsert(exercise)

    override suspend fun deleteCustom() {
        rows.value = rows.value.filterNot { it.isCustom }
    }

    override suspend fun clearLastUsed() {
        rows.value = rows.value.map { it.copy(lastUsedAt = null) }
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}

/** [exercise] resolves an item's exercise for the `@Relation` reads (none by default). */
class FakeRoutineDao(
    private val exercise: (String) -> ExerciseEntity? = { null },
) : RoutineDao {

    val routines = MutableStateFlow<List<RoutineEntity>>(emptyList())
    val items = MutableStateFlow<List<RoutineItemEntity>>(emptyList())
    private var nextItemId = 1L

    private fun withItems(): List<RoutineWithItems> =
        routines.value.sortedBy { it.order }.map { routine ->
            RoutineWithItems(
                routine,
                items.value
                    .filter { it.routineId == routine.id }
                    .map { RoutineItemWithExercise(it, exercise = it.exerciseId?.let(exercise)) },
            )
        }

    override fun observeRoutinesWithItems(): Flow<List<RoutineWithItems>> = routines.map { withItems() }

    override suspend fun routinesWithItems(): List<RoutineWithItems> = withItems()

    override suspend fun routineWithItems(id: String): RoutineWithItems? = withItems().firstOrNull { it.routine.id == id }

    override fun observeRoutineWithItems(id: String): Flow<RoutineWithItems?> =
        routines.map { withItems().firstOrNull { row -> row.routine.id == id } }

    override fun observeRoutines(): Flow<List<RoutineEntity>> = routines.map { list -> list.sortedBy { it.order } }

    override suspend fun routineCount(): Int = routines.value.size

    override suspend fun items(routineId: String): List<RoutineItemEntity> =
        items.value.filter { it.routineId == routineId }.sortedBy { it.order }

    override suspend fun insertRoutine(routine: RoutineEntity) {
        routines.value = routines.value + routine
    }

    override suspend fun insertRoutines(routines: List<RoutineEntity>) {
        this.routines.value = this.routines.value + routines
    }

    override suspend fun insertItems(items: List<RoutineItemEntity>): List<Long> = items.map { insertItem(it) }

    override suspend fun insertItem(item: RoutineItemEntity): Long {
        val id = nextItemId++
        items.value = items.value + item.copy(id = id)
        return id
    }

    override suspend fun upsertRoutine(routine: RoutineEntity) {
        routines.value = routines.value.filterNot { it.id == routine.id } + routine
    }

    override suspend fun updateItem(item: RoutineItemEntity) {
        items.value = items.value.map { if (it.id == item.id) item else it }
    }

    override suspend fun deleteRoutine(routine: RoutineEntity) {
        routines.value = routines.value.filterNot { it.id == routine.id }
        items.value = items.value.filterNot { it.routineId == routine.id }
    }

    override suspend fun deleteItem(item: RoutineItemEntity) {
        items.value = items.value.filterNot { it.id == item.id }
    }

    override suspend fun deleteAllItems() {
        items.value = emptyList()
    }

    override suspend fun deleteAllRoutines() {
        routines.value = emptyList()
    }
}
