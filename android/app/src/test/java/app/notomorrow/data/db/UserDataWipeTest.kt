package app.notomorrow.data.db

import app.notomorrow.data.dao.BodyMeasurementDao
import app.notomorrow.data.dao.BodyWeightDao
import app.notomorrow.data.dao.BroPairingDao
import app.notomorrow.data.dao.HeadsUpDao
import app.notomorrow.data.entity.AttendanceRecordEntity
import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.data.entity.FoodItemEntity
import app.notomorrow.data.entity.GymScheduleEntity
import app.notomorrow.data.entity.MealEntryEntity
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import app.notomorrow.data.entity.UserProfileEntity
import app.notomorrow.feature.fuelhome.FakeFoodDao
import app.notomorrow.feature.fuelhome.FakeMealDao
import app.notomorrow.model.FoodSource
import app.notomorrow.model.MealSlot
import app.notomorrow.model.Participant
import app.notomorrow.service.FakeAttendanceDao
import app.notomorrow.service.FakeExerciseDao
import app.notomorrow.service.FakeProfileDao
import app.notomorrow.service.FakeRoutineDao
import app.notomorrow.service.FakeScheduleDao
import app.notomorrow.service.FakeWorkoutDao
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * "Delete account and data" must leave no row of the old account behind — on iOS the workouts,
 * their exercises and sets survived it, and the unfinished one came back as the running workout.
 * [wipeUserData] runs [UserDataWipe.steps] in one transaction; this holds the steps against the
 * exported schema (every table, children first) and runs them over in-memory tables.
 */
class UserDataWipeTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** `schemas/…/<version>.json`: table name → the tables its foreign keys point at. */
    private val schemaTables: Map<String, List<String>> by lazy {
        val name = NoTomorrowDatabase::class.java.name
        val dir = listOf("schemas/$name", "app/schemas/$name", "android/app/schemas/$name")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: fail("No exported Room schemas found (android/app/schemas/$name); run from the app module")
        val file = File(dir, "${NoTomorrowDatabase.VERSION}.json")
        val entities = json.parseToJsonElement(file.readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        entities.associate { entity ->
            val obj = entity.jsonObject
            obj["tableName"]!!.jsonPrimitive.content to
                (obj["foreignKeys"]?.jsonArray.orEmpty()).map { it.jsonObject["table"]!!.jsonPrimitive.content }
        }
    }

    private val order: List<String> = UserDataWipe.steps.map { it.table }

    @Test
    fun `every table of the schema is wiped, once`() {
        assertEquals(order.size, order.toSet().size, "a table is wiped twice: $order")
        assertEquals(schemaTables.keys, order.toSet(), "wipe steps and schema tables differ")
    }

    @Test
    fun `every child table is emptied before its parent`() {
        for ((child, parents) in schemaTables) {
            for (parent in parents) {
                assertTrue(
                    order.indexOf(child) < order.indexOf(parent),
                    "$child references $parent, so it has to go first: $order",
                )
            }
        }
    }

    @Test
    fun `the steps leave nothing of the account, only the bundled library without its stamps`() = runTest {
        val workouts = FakeWorkoutDao()
        // A finished workout and one still running (collapsed, a set ticked).
        workouts.seed("done", startedAt = 1_000, endedAt = 9_000, completed = 3)
        workouts.seed("running", startedAt = 10_000, completed = 1)
        val exercises = FakeExerciseDao(
            listOf(
                ExerciseEntity(id = "Barbell_Squat", name = "Squat", lastUsedAt = 5_000),
                ExerciseEntity(id = "custom-1", name = "Mine", isCustom = true, lastUsedAt = 6_000),
            )
        )
        val routines = FakeRoutineDao().apply {
            routines.value = listOf(RoutineEntity(id = "r", name = "Push A"))
            items.value = listOf(RoutineItemEntity(id = 1, routineId = "r", exerciseId = "Barbell_Squat", order = 0))
        }
        val foods = FakeFoodDao(
            listOf(
                FoodItemEntity(
                    id = "f", name = "Oats", source = FoodSource.entries.first(),
                    kcalPer100 = 380.0, proteinPer100 = 13.0, carbsPer100 = 60.0, fatPer100 = 7.0,
                )
            )
        )
        val meals = FakeMealDao(
            foods,
            listOf(
                MealEntryEntity(
                    id = "m", day = 0, slot = MealSlot.Breakfast, foodId = "f",
                    grams = 80.0, kcal = 304.0, proteinG = 10.0, carbsG = 48.0, fatG = 6.0,
                )
            ),
        )
        val attendance = FakeAttendanceDao(
            listOf(AttendanceRecordEntity(id = "a", day = 0, participant = Participant.Me, scheduledMinuteOfDay = 1080))
        )
        val schedule = FakeScheduleDao(GymScheduleEntity(weekdays = listOf(1, 3, 5)))
        val profile = FakeProfileDao(UserProfileEntity(name = "Jan"))
        val headsUps = mockk<HeadsUpDao>(relaxed = true)
        val pairing = mockk<BroPairingDao>(relaxed = true)
        val bodyWeight = mockk<BodyWeightDao>(relaxed = true)
        val measurements = mockk<BodyMeasurementDao>(relaxed = true)

        val db = mockk<NoTomorrowDatabase> {
            every { workoutDao() } returns workouts
            every { exerciseDao() } returns exercises
            every { routineDao() } returns routines
            every { foodDao() } returns foods
            every { mealDao() } returns meals
            every { attendanceDao() } returns attendance
            every { scheduleDao() } returns schedule
            every { profileDao() } returns profile
            every { headsUpDao() } returns headsUps
            every { broPairingDao() } returns pairing
            every { bodyWeightDao() } returns bodyWeight
            every { bodyMeasurementDao() } returns measurements
        }

        for (step in UserDataWipe.steps) step.run(db)

        assertTrue(workouts.workouts.value.isEmpty(), "workouts left: ${workouts.workouts.value}")
        assertTrue(workouts.exercises.value.isEmpty())
        assertTrue(workouts.sets.value.isEmpty())
        assertTrue(workouts.activeWorkouts().isEmpty(), "an unfinished workout would be adopted again")
        assertTrue(routines.routines.value.isEmpty())
        assertTrue(routines.items.value.isEmpty())
        assertTrue(meals.rows.value.isEmpty())
        assertTrue(foods.rows.value.isEmpty())
        assertTrue(attendance.rows.value.isEmpty())
        assertNull(schedule.row.value)
        assertNull(profile.row.value)
        assertEquals(listOf("Barbell_Squat"), exercises.rows.value.map { it.id }, "the library stays, custom goes")
        assertNull(exercises.rows.value.single().lastUsedAt, "the old account's usage order goes")
        coVerify(exactly = 1) { headsUps.deleteAll() }
        coVerify(exactly = 1) { pairing.deleteAll() }
        coVerify(exactly = 1) { bodyWeight.deleteAll() }
        coVerify(exactly = 1) { measurements.deleteAll() }
    }
}
