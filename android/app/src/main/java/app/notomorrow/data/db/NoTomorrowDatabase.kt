package app.notomorrow.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.BodyWeightDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.BodyWeightEntryEntity
import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.HeadsUpEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity

/**
 * The 14 entities of `NoTomorrowSchema.models`, in the same order.
 *
 * Room turns foreign keys on (`PRAGMA foreign_keys=ON`), so a child written before its
 * parent throws: seed exercises before routines. `@Relation` reads never cascade —
 * deletion relies on the declared keys.
 */
@Database(
    entities = [
        UserProfileEntity::class,
        GymScheduleEntity::class,
        ExerciseEntity::class,
        RoutineEntity::class,
        RoutineItemEntity::class,
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        SetEntryEntity::class,
        FoodItemEntity::class,
        MealEntryEntity::class,
        BodyWeightEntryEntity::class,
        BroPairingEntity::class,
        AttendanceRecordEntity::class,
        HeadsUpEntity::class,
    ],
    version = NoTomorrowDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NoTomorrowDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun foodDao(): FoodDao
    abstract fun mealDao(): MealDao
    abstract fun bodyWeightDao(): BodyWeightDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun headsUpDao(): HeadsUpDao
    abstract fun broPairingDao(): BroPairingDao

    companion object {
        const val VERSION = 1
        const val NAME = "NoTomorrow"
    }
}

/**
 * "Delete account and data" (`SettingsModel.deleteAccount`): every user-owned table,
 * in child-before-parent order, **except non-custom `exercise` rows** — the bundled
 * library is not the user's data.
 *
 * The secure store, `nt.rest.autoStart` and `hasOnboarded` are the caller's job.
 */
suspend fun NoTomorrowDatabase.wipeUserData() = withTransaction {
    headsUpDao().deleteAll()
    attendanceDao().deleteAll()
    broPairingDao().deleteAll()
    bodyWeightDao().deleteAll()
    mealDao().deleteAll()
    foodDao().deleteAll()
    workoutDao().deleteAllSets()
    workoutDao().deleteAllWorkoutExercises()
    workoutDao().deleteAllWorkouts()
    routineDao().deleteAllItems()
    routineDao().deleteAllRoutines()
    exerciseDao().deleteCustom()
    scheduleDao().deleteAll()
    profileDao().deleteAll()
}
