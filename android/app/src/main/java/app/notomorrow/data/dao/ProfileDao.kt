package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.notomorrow.data.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * The singleton `user_profile` row. `null` means onboarding has not run —
 * `SettingsModel.profile(in:)` is the fetch-or-create on top of this.
 */
@Dao
interface ProfileDao {

    @Query("SELECT * FROM user_profile LIMIT 1")
    fun observeProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun profile(): UserProfileEntity?

    /** `createdAt` of the profile — the floor of `markPastPlannedAsMissed`. */
    @Query("SELECT createdAt FROM user_profile LIMIT 1")
    suspend fun createdAt(): Long?

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
}
