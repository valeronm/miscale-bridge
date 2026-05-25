package com.miscalebridge.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.compose.DerivedComposition
import com.miscalebridge.app.history.MeasurementHistory
import java.time.Instant

/**
 * Flat row schema for one persisted weigh-in. Mirrors [S400Measurement] +
 * [DerivedComposition], plus the Health-Connect write count. We don't store
 * `isLowFrequency` — by the time a row is written, the low-freq pulse (if any)
 * has already been merged into `impedanceLowOhm`.
 *
 * Primary key matches the dedup key used elsewhere: `(profileId, timestamp)`.
 * A second frame for the same weigh-in upserts the existing row instead of
 * appending — the same behaviour the old in-memory ring had.
 */
@Entity(tableName = "weigh_ins", primaryKeys = ["profile_id", "timestamp_epoch_sec"])
data class WeighInEntity(
    @ColumnInfo(name = "profile_id") val profileId: Int,
    @ColumnInfo(name = "timestamp_epoch_sec") val timestampEpochSec: Long,

    @ColumnInfo(name = "weight_kg") val weightKg: Double,
    @ColumnInfo(name = "heart_rate_bpm") val heartRateBpm: Int?,
    @ColumnInfo(name = "impedance_ohm") val impedanceOhm: Double?,
    @ColumnInfo(name = "impedance_low_ohm") val impedanceLowOhm: Double?,
    @ColumnInfo(name = "raw_hex") val rawHex: String,

    @ColumnInfo(name = "bmi") val bmi: Double,
    @ColumnInfo(name = "lean_body_mass_kg") val leanBodyMassKg: Double?,
    @ColumnInfo(name = "body_fat_percent") val bodyFatPercent: Double?,
    @ColumnInfo(name = "water_percent") val waterPercent: Double?,
    @ColumnInfo(name = "body_water_kg") val bodyWaterKg: Double?,
    @ColumnInfo(name = "bone_mass_kg") val boneMassKg: Double?,
    @ColumnInfo(name = "skeletal_muscle_mass_kg") val skeletalMuscleMassKg: Double?,
    @ColumnInfo(name = "protein_percent") val proteinPercent: Double?,
    @ColumnInfo(name = "visceral_fat_rating") val visceralFatRating: Double?,
    @ColumnInfo(name = "bmr_kcal") val bmrKcal: Double?,
    @ColumnInfo(name = "ecw_kg") val ecwKg: Double?,
    @ColumnInfo(name = "icw_kg") val icwKg: Double?,
    @ColumnInfo(name = "bcm_kg") val bcmKg: Double?,
    @ColumnInfo(name = "ecw_tbw_ratio_pct") val ecwTbwRatioPct: Double?,

    @ColumnInfo(name = "written_record_count") val writtenRecordCount: Int,

    /** Source app's package, e.g. "com.miscalebridge.app" for scale-decoded
     *  rows or "com.xiaomi.hm.health" for an HC import from Mi Fitness.
     *  Nullable for legacy rows from a pre-v2 schema that didn't track it. */
    @ColumnInfo(name = "data_origin_package_name") val dataOriginPackageName: String? = null,
    /** Human-readable label snapshot from PackageManager at write/import time.
     *  Lets the History tab show "Mi Fitness" even after that app is
     *  uninstalled. Nullable for rows we wrote ourselves (UI says "this app"). */
    @ColumnInfo(name = "data_origin_app_label") val dataOriginAppLabel: String? = null,
)

fun WeighInEntity.toEntry(): MeasurementHistory.Entry {
    val m = S400Measurement(
        weightKg = weightKg,
        heartRateBpm = heartRateBpm,
        impedanceOhm = impedanceOhm,
        impedanceLowOhm = impedanceLowOhm,
        isLowFrequency = false,
        timestamp = Instant.ofEpochSecond(timestampEpochSec),
        profileId = profileId,
        rawHex = rawHex,
    )
    val d = DerivedComposition(
        bmi = bmi,
        leanBodyMassKg = leanBodyMassKg,
        bodyFatPercent = bodyFatPercent,
        waterPercent = waterPercent,
        bodyWaterKg = bodyWaterKg,
        boneMassKg = boneMassKg,
        skeletalMuscleMassKg = skeletalMuscleMassKg,
        proteinPercent = proteinPercent,
        visceralFatRating = visceralFatRating,
        bmrKcal = bmrKcal,
        ecwKg = ecwKg,
        icwKg = icwKg,
        bcmKg = bcmKg,
        ecwTbwRatioPct = ecwTbwRatioPct,
    )
    return MeasurementHistory.Entry(
        measurement = m,
        derived = d,
        writtenRecordCount = writtenRecordCount,
        dataOriginPackageName = dataOriginPackageName,
        dataOriginAppLabel = dataOriginAppLabel,
    )
}

fun MeasurementHistory.Entry.toEntity(): WeighInEntity = WeighInEntity(
    profileId = measurement.profileId,
    timestampEpochSec = measurement.timestamp.epochSecond,
    weightKg = measurement.weightKg,
    heartRateBpm = measurement.heartRateBpm,
    impedanceOhm = measurement.impedanceOhm,
    impedanceLowOhm = measurement.impedanceLowOhm,
    rawHex = measurement.rawHex,
    bmi = derived.bmi,
    leanBodyMassKg = derived.leanBodyMassKg,
    bodyFatPercent = derived.bodyFatPercent,
    waterPercent = derived.waterPercent,
    bodyWaterKg = derived.bodyWaterKg,
    boneMassKg = derived.boneMassKg,
    skeletalMuscleMassKg = derived.skeletalMuscleMassKg,
    proteinPercent = derived.proteinPercent,
    visceralFatRating = derived.visceralFatRating,
    bmrKcal = derived.bmrKcal,
    ecwKg = derived.ecwKg,
    icwKg = derived.icwKg,
    bcmKg = derived.bcmKg,
    ecwTbwRatioPct = derived.ecwTbwRatioPct,
    writtenRecordCount = writtenRecordCount,
    dataOriginPackageName = dataOriginPackageName,
    dataOriginAppLabel = dataOriginAppLabel,
)
