package app.notomorrow.data.db

import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration

/**
 * Every schema step since version 1, handed to the builder by [DatabaseModule]. The user's training
 * history lives in this file on their phone; nothing may ever drop it on an upgrade.
 *
 * **Changing an entity** (a column, a table, an index):
 *  1. bump [NoTomorrowDatabase.VERSION] to `n`;
 *  2. build once and commit `android/app/schemas/app.notomorrow.data.db.NoTomorrowDatabase/n.json`
 *     (Room exports it; never edit or delete an older one);
 *  3. add the step `n - 1 → n`, either
 *     - `AutoMigration(from = n - 1, to = n)` in `@Database(autoMigrations = [...])` — enough for an
 *       added table or an added column **with** `@ColumnInfo(defaultValue = ...)` (or nullable);
 *       a rename or a delete needs a spec, whose instance goes in [AUTO_MIGRATION_SPECS]; or
 *     - a hand-written `Migration(n - 1, n)` in [ALL] for anything else (a new NOT NULL column
 *       computed from others, a table split, a type change);
 *  4. pin the new schema's identity hash in `DatabaseMigrationsTest.PINNED_SCHEMAS` (the failing
 *     test prints it).
 *
 * `DatabaseMigrationsTest` fails when an entity changed without a version bump, when a version has
 * no exported schema, or when any step from 1 up to [NoTomorrowDatabase.VERSION] has no migration.
 * Without a path Room would refuse to open the file (the store error screen), never wipe it: the
 * builder falls back to a destructive rebuild only on a downgrade.
 */
object NoTomorrowMigrations {

    /** Hand-written steps, oldest first. Empty so far: 1 → 2 (the superset columns) is an `AutoMigration`. */
    val ALL: Array<Migration> = emptyArray()

    /** Instances of the specs the `@Database` auto-migrations name (renames and deletes). */
    val AUTO_MIGRATION_SPECS: List<AutoMigrationSpec> = emptyList()
}
