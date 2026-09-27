package app.notomorrow.service

import app.notomorrow.data.entity.ExerciseEntity
import app.notomorrow.model.SetKind
import app.notomorrow.model.WeightUnit
import app.notomorrow.util.Fmt
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Importing Strong, Hevy and No Tomorrow CSV exports (`WorkoutImportTests.swift`): format detection,
 * grouping, units, set kinds, name matching, and that importing twice adds nothing.
 */
class WorkoutImportTest {

    private val zone = ZoneOffset.UTC

    private val strong = """
        Workout #;Date;Workout Name;Duration (sec);Exercise Name;Set Order;Weight (kg);Reps;RPE;Distance (meters);Seconds;Notes;Workout Notes
        1;2026-09-20 18:00:00;"Push; heavy";3600;Bench Press (Barbell);W;40;10;;;;;
        1;2026-09-20 18:00:00;"Push; heavy";3600;Bench Press (Barbell);1;80;8;8,5;;;Wide grip;
        1;2026-09-20 18:00:00;"Push; heavy";3600;Bench Press (Barbell);Rest Timer;;;;;90;;
        1;2026-09-20 18:00:00;"Push; heavy";3600;Cable Crossover Deluxe;1;20;12;;;;;
        2;2026-09-22 07:30:00;Legs;2700;Squat (Barbell);1;100;5;;;;;
    """.trimIndent()

    @Test
    fun `strong semicolon export`() {
        val parsed = WorkoutImport.parse(strong, WeightUnit.Kg, zone)
        assertEquals(WorkoutImport.Format.Strong, parsed.format)
        assertEquals(listOf("Push; heavy", "Legs"), parsed.workouts.map { it.name })
        val push = parsed.workouts[0]
        assertEquals(3_600_000L, push.endedAt - push.startedAt)
        assertEquals(listOf("Bench Press (Barbell)", "Cable Crossover Deluxe"), push.exercises.map { it.name })
        assertEquals(listOf(SetKind.Warmup, SetKind.Normal), push.exercises[0].sets.map { it.kind })
        assertEquals(8.5, push.exercises[0].sets[1].rpe)
        assertEquals("Wide grip", push.exercises[0].notes)
        assertEquals(Instant.parse("2026-09-20T18:00:00Z").toEpochMilli(), push.startedAt)
    }

    @Test
    fun `hevy export in pounds`() {
        val hevy = """
            "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_lbs","reps","distance_miles","duration_seconds","rpe"
            "Upper","26 Sep 2026, 18:04","26 Sep 2026, 19:10","","Bench Press (Barbell)","","","0","warmup","95","10","","",""
            "Upper","26 Sep 2026, 18:04","26 Sep 2026, 19:10","","Bench Press (Barbell)","","","1","normal","225","5","","","9"
            "Upper","26 Sep 2026, 18:04","26 Sep 2026, 19:10","","Plank","","","0","normal","","","","60",""
        """.trimIndent()
        val parsed = WorkoutImport.parse(hevy, WeightUnit.Kg, zone)
        assertEquals(WorkoutImport.Format.Hevy, parsed.format)
        assertEquals(1, parsed.workouts.size)
        assertEquals(2, parsed.workouts[0].setCount)
        assertEquals(1, parsed.skippedRows, "the timed plank has no reps")
        assertEquals(225 / Fmt.LB_PER_KG, parsed.workouts[0].exercises[0].sets[1].weightKg, 0.001)
        assertEquals(66 * 60_000L, parsed.workouts[0].endedAt - parsed.workouts[0].startedAt)
    }

    @Test
    fun `own export round trips and unknown files fail`() {
        val own = """
            workout_id,workout_name,started_at,ended_at,exercise,set,kind,weight_kg,reps,completed_at,pr,set_record
            A,Push A,2026-09-20T16:00:00Z,2026-09-20T17:00:00Z,Barbell Bench Press - Medium Grip,1,normal,80,8,2026-09-20T16:10:00Z,1,0
        """.trimIndent()
        val parsed = WorkoutImport.parse(own, WeightUnit.Kg, zone)
        assertEquals(WorkoutImport.Format.NoTomorrow, parsed.format)
        assertEquals(Instant.parse("2026-09-20T16:00:00Z").toEpochMilli(), parsed.workouts.first().startedAt)
        assertFailsWith<WorkoutImport.Failure> { WorkoutImport.parse("a,b\n1,2", WeightUnit.Kg) }
        assertFailsWith<WorkoutImport.Failure> { WorkoutImport.parse("", WeightUnit.Kg) }
    }

    @Test
    fun `match keys move equipment to the front`() {
        assertTrue("barbell bench press" in WorkoutImport.matchKeys("Bench Press (Barbell)"))
        assertEquals("pull ups", WorkoutImport.matchKeys("Pull-Ups").first())
    }

    @Test
    fun `csv reader handles quotes, CRLF and a byte-order mark`() {
        val rows = WorkoutImport.csvRows("﻿a,b\r\n\"x, \"\"y\"\"\",\"line\nbreak\"\r\n\r\n")
        assertEquals(listOf(listOf("a", "b"), listOf("x, \"y\"", "line\nbreak")), rows)
    }

    @Test
    fun `import matches library, creates custom and skips duplicates`() = runBlocking {
        val workouts = FakeWorkoutDao()
        val exercises = FakeExerciseDao(
            listOf(
                ExerciseEntity(id = "Barbell_Bench_Press", name = "Barbell Bench Press", primaryMuscles = listOf("chest")),
                ExerciseEntity(id = "Barbell_Squat", name = "Barbell Squat", primaryMuscles = listOf("quadriceps")),
            ),
        )
        val importer = WorkoutImporter(workouts, exercises, RecordService(workouts))
        val parsed = WorkoutImport.parse(strong, WeightUnit.Kg, zone)

        val first = importer.importWorkouts(parsed.workouts)
        assertEquals(2, first.workouts)
        assertEquals(4, first.sets)
        assertEquals(listOf("Cable Crossover Deluxe"), first.newExercises)
        val push = workouts.workouts.value.first { it.name == "Push; heavy" }
        val graph = workouts.workoutWithExercises(push.id)!!
        assertEquals("Barbell_Bench_Press", graph.sortedExercises.first().workoutExercise.exerciseId)
        assertTrue(graph.exercises.flatMap { it.sets }.all { it.isCompleted })
        assertTrue(exercises.rows.value.any { it.isCustom && it.name == "Cable Crossover Deluxe" })
        assertEquals(push.startedAt, exercises.byId("Barbell_Bench_Press")!!.lastUsedAt)
        assertTrue(graph.exercises.flatMap { it.sets }.any { it.isPR }, "records are rebuilt")

        val second = importer.importWorkouts(parsed.workouts)
        assertEquals(0, second.workouts)
        assertEquals(2, second.duplicates)
        assertEquals(2, workouts.workouts.value.size)
    }
}
