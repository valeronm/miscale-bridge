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

    /** Insert only if the (profileId, timestamp) row doesn't already exist.
     *  Used by the HC importer so re-imports don't blat user-edited or
     *  scale-decoded rows that happen to share a timestamp.
     *  Returns the new row id, or -1L if a row already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: WeighInEntity): Long

    /** Existence/contents probe so the importer can decide whether the
     *  matching row is scale-decoded (preserve) or a prior import (replace). */
    @Query("SELECT * FROM weigh_ins WHERE profile_id = :profileId AND timestamp_epoch_sec = :timestampEpochSec LIMIT 1")
    suspend fun findByKey(profileId: Int, timestampEpochSec: Long): WeighInEntity?

    @Query("DELETE FROM weigh_ins")
    suspend fun clear()
}
