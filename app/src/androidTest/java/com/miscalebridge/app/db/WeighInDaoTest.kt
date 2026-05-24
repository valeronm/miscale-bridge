package com.miscalebridge.app.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory Room tests for the persisted weigh-in store. Verifies the four
 * behaviours the rest of the app relies on:
 *   1. Round-trip: every nullable field survives insert+read.
 *   2. Upsert: a second row with the same `(profileId, timestamp)` PK replaces
 *      the first — this is how the low-freq merge avoids duplicate rows.
 *   3. Order: `observeAll` returns newest first, matching what the UI expects.
 *   4. Clear: wipes the table.
 */
@RunWith(AndroidJUnit4::class)
class WeighInDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WeighInDao

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()    // tests block on the suspend queries
            .build()
        dao = db.weighInDao()
    }

    @After fun teardown() = db.close()

    @Test fun roundTripPreservesAllFields() = runTest {
        val entity = sampleEntity()
        dao.upsert(entity)
        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        assertEquals(entity, rows[0])
    }

    @Test fun upsertReplacesOnSamePrimaryKey() = runTest {
        val v1 = sampleEntity(weightKg = 70.0, writtenRecordCount = 0)
        val v2 = v1.copy(weightKg = 71.4, writtenRecordCount = 6)
        dao.upsert(v1)
        dao.upsert(v2)
        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        assertEquals(71.4, rows[0].weightKg, 0.0)
        assertEquals(6, rows[0].writtenRecordCount)
    }

    @Test fun observeAllReturnsNewestFirst() = runTest {
        dao.upsert(sampleEntity(ts = 1_000L))
        dao.upsert(sampleEntity(ts = 3_000L))
        dao.upsert(sampleEntity(ts = 2_000L))
        val rows = dao.observeAll().first()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), rows.map { it.timestampEpochSec })
    }

    @Test fun clearEmptiesTable() = runTest {
        dao.upsert(sampleEntity())
        dao.upsert(sampleEntity(ts = 2_000L))
        dao.clear()
        assertTrue(dao.observeAll().first().isEmpty())
    }
}

internal fun sampleEntity(
    profileId: Int = 1,
    ts: Long = 1_700_000_000L,
    weightKg: Double = 70.5,
    writtenRecordCount: Int = 6,
): WeighInEntity = WeighInEntity(
    profileId = profileId,
    timestampEpochSec = ts,
    weightKg = weightKg,
    heartRateBpm = 72,
    impedanceOhm = 540.0,
    impedanceLowOhm = 500.0,
    rawHex = "deadbeef",
    bmi = 22.3,
    leanBodyMassKg = 55.0,
    bodyFatPercent = 22.0,
    waterPercent = 55.0,
    bodyWaterKg = 38.5,
    boneMassKg = 2.8,
    skeletalMuscleMassKg = 30.5,
    proteinPercent = 16.0,
    visceralFatRating = 5.0,
    bmrKcal = 1500.0,
    ecwKg = 16.0,
    icwKg = 22.5,
    bcmKg = 28.0,
    ecwTbwRatioPct = 41.0,
    writtenRecordCount = writtenRecordCount,
)

