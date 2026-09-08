package app.notomorrow.service

import app.notomorrow.data.dao.ExerciseDao
import app.notomorrow.data.dao.RoutineDao
import app.notomorrow.data.entity.RoutineEntity
import app.notomorrow.data.entity.RoutineItemEntity
import java.util.Locale
import java.util.UUID

/**
 * Creates the three starter routines (Push A / Pull A / Legs) once, from exercises that
 * already exist in the store (imported by [ExerciseLibrary]) — 1:1 port of
 * `NoTomorrow/Services/RoutineSeeder.swift`.
 *
 * Ids that are missing are skipped; if **none** of the fifteen are found the seed is
 * postponed, so a later call (after the import) can still do it. The routine names are
 * data, not UI copy: iOS keeps them unlocalised in the store.
 */
class RoutineSeeder(
    private val routineDao: RoutineDao,
    private val exerciseDao: ExerciseDao,
) {

    /** One starter routine. */
    data class Template(val name: String, val exerciseIds: List<String>)

    /** Idempotent: only runs when no routine exists yet. */
    suspend fun seedIfNeeded(now: Long = System.currentTimeMillis()) {
        if (routineDao.routineCount() > 0) return

        val ids = TEMPLATES.flatMap { it.exerciseIds }
        val found = exerciseDao.byIds(ids)
        if (found.isEmpty()) return
        val known = found.map { it.id }.toSet()

        TEMPLATES.forEachIndexed { index, template ->
            val routine = RoutineEntity(
                id = UUID.randomUUID().toString(),
                name = template.name,
                order = index,
                createdAt = now,
            )
            var order = 0
            val items = template.exerciseIds.mapNotNull { id ->
                if (id !in known) return@mapNotNull null
                RoutineItemEntity(
                    routineId = routine.id,
                    exerciseId = id,
                    order = order++,
                    targetSets = DEFAULT_SETS,
                    targetReps = DEFAULT_REPS,
                    restSeconds = restSeconds(id),
                )
            }
            routineDao.insertRoutineWithItems(routine, items)
        }
    }

    companion object {
        const val DEFAULT_SETS = 3
        const val DEFAULT_REPS = 8
        const val DEFAULT_REST_SECONDS = 90
        const val HEAVY_REST_SECONDS = 120

        /** Squat / deadlift / bench variants rest longer. */
        private val HEAVY = listOf("squat", "deadlift", "bench_press")

        val TEMPLATES: List<Template> = listOf(
            Template(
                name = "Push A",
                exerciseIds = listOf(
                    "Barbell_Bench_Press_-_Medium_Grip",
                    "Barbell_Shoulder_Press",
                    "Incline_Dumbbell_Press",
                    "Triceps_Pushdown",
                    "Side_Lateral_Raise",
                ),
            ),
            Template(
                name = "Pull A",
                exerciseIds = listOf(
                    "Barbell_Deadlift",
                    "Bent_Over_Barbell_Row",
                    "Pullups",
                    "Barbell_Curl",
                    "Seated_Cable_Rows",
                ),
            ),
            Template(
                name = "Legs",
                exerciseIds = listOf(
                    "Barbell_Squat",
                    "Romanian_Deadlift",
                    "Leg_Press",
                    "Leg_Extensions",
                    "Standing_Calf_Raises",
                ),
            ),
        )

        fun restSeconds(exerciseId: String): Int {
            val id = exerciseId.lowercase(Locale.ROOT)
            return if (HEAVY.any { id.contains(it) }) HEAVY_REST_SECONDS else DEFAULT_REST_SECONDS
        }
    }
}
