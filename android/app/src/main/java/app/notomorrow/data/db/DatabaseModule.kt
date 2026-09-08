package app.notomorrow.data.db

import android.content.Context
import android.util.Log
import androidx.room.Room

/**
 * Builds the one [NoTomorrowDatabase] the process uses.
 *
 * Version 1 has no migrations: a schema mismatch destroys and rebuilds, which is the
 * same "dev-build safety valve" the iOS container has. If even opening the file fails
 * (`IllegalStateException` — a corrupt or unreadable store), fall back to an in-memory
 * database so the app starts instead of crash-looping.
 */
object DatabaseModule {

    private const val TAG = "NoTomorrowDatabase"

    fun build(context: Context, name: String = NoTomorrowDatabase.NAME): NoTomorrowDatabase {
        val app = context.applicationContext
        return try {
            Room.databaseBuilder(app, NoTomorrowDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { it.openHelper.writableDatabase }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Persistent store unavailable — falling back to in-memory", e)
            inMemory(app)
        }
    }

    /** Tests and the corrupt-store fallback. */
    fun inMemory(context: Context): NoTomorrowDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, NoTomorrowDatabase::class.java)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
}
