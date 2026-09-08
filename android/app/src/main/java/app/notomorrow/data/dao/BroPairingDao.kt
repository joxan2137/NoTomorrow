package app.notomorrow.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.notomorrow.data.entity.BroPairingEntity
import kotlinx.coroutines.flow.Flow

/**
 * The singleton `bro_pairing` row. `null` means "not paired" — `BroService.refresh`
 * clears it when the server says the pairing is gone.
 */
@Dao
interface BroPairingDao {

    @Query("SELECT * FROM bro_pairing LIMIT 1")
    fun observePairing(): Flow<BroPairingEntity?>

    @Query("SELECT * FROM bro_pairing LIMIT 1")
    suspend fun pairing(): BroPairingEntity?

    @Upsert
    suspend fun upsert(pairing: BroPairingEntity)

    @Query("DELETE FROM bro_pairing")
    suspend fun deleteAll()
}
