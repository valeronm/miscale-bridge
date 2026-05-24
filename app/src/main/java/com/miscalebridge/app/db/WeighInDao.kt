package com.miscalebridge.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeighInDao {
    /** Newest first — matches what the History tab expects to render. */
    @Query("SELECT * FROM weigh_ins ORDER BY timestamp_epoch_sec DESC")
    fun observeAll(): Flow<List<WeighInEntity>>

    /** Insert-or-replace by primary key so the low-freq merge of the same
     *  weigh-in overwrites the prior row instead of duplicating it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeighInEntity)

    @Query("DELETE FROM weigh_ins")
    suspend fun clear()
}
