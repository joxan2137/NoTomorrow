package app.notomorrow.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import app.notomorrow.data.dao.AttendanceDao
import app.notomorrow.data.dao.BodyMeasurementDao
import app.notomorrow.data.dao.BodyWeightDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.FoodDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.data.dao.MealDao
import app.notomorrow.data.dao.ProfileDao
import app.notomorrow.data.dao.ProgressPhotoDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.dao.ScheduleDao
import app.notomorrow.data.dao.WorkoutDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.BodyMeasurementEntity
import app.notomorrow.data.entity.BodyWeightEntryEntity
import app.notomorrow.data.entity.BroPairingEntity
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.HeadsUpEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.entity.ProgressPhotoEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.SetEntryEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.data.entity.WorkoutEntity
import app.notomorrow.data.entity.WorkoutExerciseEntity
import app.notomorrow.data.files.ProgressPhotoFiles
import java.io.File

/**
 * The 16 entities of `NoTomorrowSchema.models`, in the same order.
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
        BodyMeasurementEntity::class,
        ProgressPhotoEntity::class,
        BroPairingEntity::class,
        AttendanceRecordEntity::class,
        HeadsUpEntity::class,
    ],
    version = NoTomorrowDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [
        // 2: `workout_exercise.supersetGroup` and `routine_item.supersetGroup` (nullable).
        AutoMigration(from = 1, to = 2),
        // 3: the `body_measurement` table.
        AutoMigration(from = 2, to = 3),
        // 4: the `progress_photo` table.
        AutoMigration(from = 3, to = 4),
    ],
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
    abstract fun bodyMeasurementDao(): BodyMeasurementDao
    abstract fun progressPhotoDao(): ProgressPhotoDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun headsUpDao(): HeadsUpDao
    abstract fun broPairingDao(): BroPairingDao

    companion object {
        const val VERSION = 4
        const val NAME = "NoTomorrow"
    }
}

/**
 * "Delete account and data" (`SettingsModel.deleteAccount`): every user-owned table, in
 * child-before-parent order (Room enforces the foreign keys), in **one transaction** — all of it
 * goes or none of it does, never a store with the meals gone and the workouts left. The bundled
 * exercise library stays (it is not the user's data), without the user's trace on it: custom
 * exercises are deleted and the library's "last used" stamps cleared.
 *
 * The workout session, the rest timer, the secure store, `nt.rest.autoStart` and `hasOnboarded`
 * are the caller's job (`AccountDataReset`, `SettingsViewModel.deleteAccount`), and so are the
 * progress photo files ([UserDataWipe.deleteFiles], once the rows are gone).
 */
suspend fun NoTomorrowDatabase.wipeUserData() = withTransaction {
    for (step in UserDataWipe.steps) step.run(this)
}

/**
 * The steps of [wipeUserData], one per table in the order they run, so a test can hold them
 * against the exported schema: every table covered, every child before its parent.
 */
internal object UserDataWipe {

    /**
     * The files that belong to wiped rows: every progress photo under [filesDir]. Run after the
     * transaction commits, so a rolled-back wipe keeps its photos.
     */
    fun deleteFiles(filesDir: File) {
        ProgressPhotoFiles.inFilesDir(filesDir).deleteAll()
    }

    class Step(val table: String, val run: suspend (NoTomorrowDatabase) -> Unit)

    val steps: List<Step> = listOf(
        Step("heads_up") { it.headsUpDao().deleteAll() },
        Step("attendance_record") { it.attendanceDao().deleteAll() },
        Step("bro_pairing") { it.broPairingDao().deleteAll() },
        Step("body_weight_entry") { it.bodyWeightDao().deleteAll() },
        Step("body_measurement") { it.bodyMeasurementDao().deleteAll() },
        Step("progress_photo") { it.progressPhotoDao().deleteAll() },
        Step("meal_entry") { it.mealDao().deleteAll() },
        Step("food_item") { it.foodDao().deleteAll() },
        Step("set_entry") { it.workoutDao().deleteAllSets() },
        Step("workout_exercise") { it.workoutDao().deleteAllWorkoutExercises() },
        Step("workout") { it.workoutDao().deleteAllWorkouts() },
        Step("routine_item") { it.routineDao().deleteAllItems() },
        Step("routine") { it.routineDao().deleteAllRoutines() },
        Step("exercise") {
            it.exerciseDao().deleteCustom()
            it.exerciseDao().clearLastUsed()
        },
        Step("gym_schedule") { it.scheduleDao().deleteAll() },
        Step("user_profile") { it.profileDao().deleteAll() },
    )
}
