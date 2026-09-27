package app.notomorrow.feature.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** `WorkoutStrings.displayName` on iOS: a default name from any language shows in the current one. */
class WorkoutNamesTest {
    @Test fun defaultNamesFollowTheCurrentLanguage() {
        assertEquals("Trening", WorkoutNames.display("Workout", "Trening"))
        assertEquals("Workout", WorkoutNames.display("Trening", "Workout"))
        assertEquals("Push A", WorkoutNames.display("Push A", "Trening"))
    }

    @Test fun defaultsCoverEveryBundledTranslation() {
        val values = File("src/main/res").listFiles()!!.filter { it.name.startsWith("values") }.mapNotNull { dir ->
            File(dir, "strings.xml").takeIf { it.exists() }?.readText()
                ?.let { Regex("<string name=\"workout_defaultName\">([^<]*)</string>").find(it)?.groupValues?.get(1) }
        }.toSet()
        assertEquals(values, WorkoutNames.DEFAULTS)
    }
}
