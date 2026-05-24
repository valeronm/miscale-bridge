package com.miscalebridge.app.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM round-trip: entity → domain Entry → entity must equal the original.
 * Catches mapper drift without spinning up Room.
 */
class WeighInEntityMapperTest {

    @Test fun entityRoundTripPreservesAllFields() {
        val original = sampleEntity()
        assertEquals(original, original.toEntry().toEntity())
    }

    @Test fun nullableFieldsSurviveRoundTrip() {
        val nullAlmostEverything = sampleEntity().copy(
            heartRateBpm = null,
            impedanceOhm = null,
            impedanceLowOhm = null,
            leanBodyMassKg = null,
            bodyFatPercent = null,
            waterPercent = null,
            bodyWaterKg = null,
            boneMassKg = null,
            skeletalMuscleMassKg = null,
            proteinPercent = null,
            visceralFatRating = null,
            bmrKcal = null,
            ecwKg = null,
            icwKg = null,
            bcmKg = null,
            ecwTbwRatioPct = null,
        )
        assertEquals(nullAlmostEverything, nullAlmostEverything.toEntry().toEntity())
    }
}

private fun sampleEntity(
    profileId: Int = 1,
    ts: Long = 1_700_000_000L,
): WeighInEntity = WeighInEntity(
    profileId = profileId,
    timestampEpochSec = ts,
    weightKg = 70.5,
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
    writtenRecordCount = 6,
)
