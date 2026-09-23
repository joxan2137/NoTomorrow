package app.notomorrow.data.db

import androidx.room.RoomDatabase
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import java.io.File
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Keeps the next schema change from wiping (or locking out) the user's history — android-parity-1.
 * `DatabaseModule` no longer rebuilds the tables on an upgrade, so every version needs an exported
 * schema and a migration path; this fails the build when one is missing, on the JVM, with no
 * device:
 *
 *  - an entity changed but [NoTomorrowDatabase.VERSION] was not bumped (the compiled identity hash
 *    no longer matches the one pinned for that version) — on a phone Room would refuse to open the
 *    existing file;
 *  - a version has no `schemas/…/<n>.json` committed (AutoMigration and a future
 *    `MigrationTestHelper` test both read it);
 *  - some step from 1 up to [NoTomorrowDatabase.VERSION] has neither an `AutoMigration` nor a
 *    `Migration` in [NoTomorrowMigrations.ALL];
 *  - an auto-migration names a spec that [NoTomorrowMigrations.AUTO_MIGRATION_SPECS] does not supply.
 */
class DatabaseMigrationsTest {

    /** The generated `NoTomorrowDatabase_Impl` — nothing is opened, so no Android runtime is needed. */
    private val compiled: RoomDatabase =
        Class.forName("${NoTomorrowDatabase::class.java.name}_Impl")
            .getDeclaredConstructor()
            .newInstance() as RoomDatabase

    private val openDelegate: RoomOpenDelegate by lazy {
        val method = RoomDatabase::class.java.getDeclaredMethod("createOpenDelegate")
        method.isAccessible = true
        method.invoke(compiled) as RoomOpenDelegate
    }

    private val schemaDir: File by lazy {
        val name = NoTomorrowDatabase::class.java.name
        listOf("schemas/$name", "app/schemas/$name", "android/app/schemas/$name")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?: fail("No exported Room schemas found (android/app/schemas/$name); run from the app module")
    }

    private fun schema(version: Int): File = File(schemaDir, "$version.json")

    private val json = Json { ignoreUnknownKeys = true }

    private fun exported(version: Int): Pair<Int, String> {
        val database = json.parseToJsonElement(schema(version).readText()).jsonObject["database"]!!.jsonObject
        return database["version"]!!.jsonPrimitive.int to database["identityHash"]!!.jsonPrimitive.content
    }

    @Test
    fun `the compiled schema is the version it claims to be`() {
        assertEquals(NoTomorrowDatabase.VERSION, openDelegate.version)
        val pinned = PINNED_SCHEMAS[NoTomorrowDatabase.VERSION]
            ?: fail(
                "Schema version ${NoTomorrowDatabase.VERSION} is not pinned. Add " +
                    "${NoTomorrowDatabase.VERSION} to \"${openDelegate.identityHash}\" to PINNED_SCHEMAS " +
                    "once its migration is in place (see Migrations.kt).",
            )
        assertEquals(
            pinned,
            openDelegate.identityHash,
            "An entity changed but NoTomorrowDatabase.VERSION is still ${NoTomorrowDatabase.VERSION}. " +
                "Bump it, commit the new schemas/<n>.json, add the migration and pin the new hash " +
                "(Migrations.kt) — shipped as is, Room would refuse to open every existing database.",
        )
    }

    @Test
    fun `every version up to the current one has its exported schema committed`() {
        for (version in 1..NoTomorrowDatabase.VERSION) {
            assertTrue(schema(version).isFile, "Missing ${schema(version)}: commit Room's export for version $version")
            val (declared, hash) = exported(version)
            assertEquals(version, declared, "${schema(version).name} declares version $declared")
            assertEquals(PINNED_SCHEMAS[version], hash, "${schema(version).name} differs from the schema pinned for version $version")
        }
    }

    @Test
    fun `every step from version 1 up to the current one has a migration`() {
        val specs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec> =
            NoTomorrowMigrations.AUTO_MIGRATION_SPECS.associateBy { it::class }
        val missingSpecs = compiled.getRequiredAutoMigrationSpecClasses() - specs.keys
        assertTrue(missingSpecs.isEmpty(), "Auto-migration specs not in NoTomorrowMigrations.AUTO_MIGRATION_SPECS: $missingSpecs")

        val steps = (NoTomorrowMigrations.ALL.toList() + compiled.createAutoMigrations(specs))
            .map { it.startVersion to it.endVersion }
        for ((from, to) in steps) {
            assertTrue(from in 1 until to && to <= NoTomorrowDatabase.VERSION, "Migration $from → $to is out of range")
        }
        assertEquals(steps.size, steps.toSet().size, "Two migrations for the same step: $steps")

        for (start in 1 until NoTomorrowDatabase.VERSION) {
            assertTrue(
                reaches(start, NoTomorrowDatabase.VERSION, steps),
                "No migration path from version $start to ${NoTomorrowDatabase.VERSION}. Add an AutoMigration " +
                    "or a Migration to NoTomorrowMigrations.ALL — without one the app cannot open that database.",
            )
        }
    }

    /** Room walks up from [from] one migration at a time; any chain of steps that lands on [to] will do. */
    private fun reaches(from: Int, to: Int, steps: List<Pair<Int, Int>>): Boolean {
        if (from == to) return true
        return steps.filter { it.first == from }.any { reaches(it.second, to, steps) }
    }

    private companion object {
        /**
         * `identityHash` of every shipped schema version, from `schemas/…/<n>.json`. Never change an
         * entry; add one per version bump (the failing test prints the new hash).
         */
        val PINNED_SCHEMAS: Map<Int, String> = mapOf(
            1 to "8c349171cad10bb34e08e456c3ab2fd3",
        )
    }
}
