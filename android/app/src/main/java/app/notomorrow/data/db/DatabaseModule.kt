package app.notomorrow.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Builds the one [NoTomorrowDatabase] the process uses.
 *
 * Upgrades only ever **migrate**: every step from version 1 up to [NoTomorrowDatabase.VERSION] is
 * in [NoTomorrowMigrations] (hand-written) or in `@Database(autoMigrations = …)`, and
 * `DatabaseMigrationsTest` fails the build when one is missing. Only a downgrade — an older build
 * installed over a newer file — drops and recreates the tables, since there is no way down.
 *
 * [build] opens the file eagerly and **throws** when it cannot (unreadable or corrupt, a schema
 * without a migration path, an identity hash that does not match). There is no in-memory fallback,
 * and a corrupt file is not deleted either ([KeepCorruptFileOpenHelperFactory]): the caller
 * (`StoreLoader`) shows the store error screen, and the file stays untouched on disk.
 */
object DatabaseModule {

    fun build(context: Context, name: String = NoTomorrowDatabase.NAME): NoTomorrowDatabase {
        val db = configure(
            Room.databaseBuilder(context.applicationContext, NoTomorrowDatabase::class.java, name),
        )
            .openHelperFactory(KeepCorruptFileOpenHelperFactory())
            .build()
        try {
            db.openHelper.writableDatabase
        } catch (e: Throwable) {
            db.close()
            throw e
        }
        return db
    }

    /** The migration policy, shared by the app and the in-memory builder. */
    fun <T : RoomDatabase> configure(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> {
        NoTomorrowMigrations.AUTO_MIGRATION_SPECS.forEach(builder::addAutoMigrationSpec)
        return builder
            .addMigrations(*NoTomorrowMigrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
    }

    /** Tests and previews: a throwaway database with the same migration policy. */
    fun inMemory(context: Context): NoTomorrowDatabase =
        configure(Room.inMemoryDatabaseBuilder(context.applicationContext, NoTomorrowDatabase::class.java))
            .build()
}

/**
 * The framework's open helper, except that a corrupt file is **kept**. SQLite's default reaction
 * (`SupportSQLiteOpenHelper.Callback.onCorruption`, which Room does not override) deletes the
 * database, and Room then creates an empty one that the app would show as if it were the user's
 * data — the Android form of iOS's silent in-memory store. Here the open fails instead, and
 * `StoreLoader` shows the store error screen with the file untouched on disk. Recovery never
 * deletes either (`allowDataLossOnRecovery` is off).
 */
internal class KeepCorruptFileOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(KeepCorruptFile(configuration.callback))
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build(),
        )

    /** Room's callback, untouched, but for [onCorruption]. */
    private class KeepCorruptFile(
        private val wrapped: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(wrapped.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = wrapped.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = wrapped.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            wrapped.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            wrapped.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = wrapped.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            throw SQLiteDatabaseCorruptException("The database is corrupt; it was kept on disk (${db.path})")
        }
    }
}
