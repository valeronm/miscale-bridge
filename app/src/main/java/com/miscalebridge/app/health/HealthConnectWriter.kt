package com.miscalebridge.app.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.compose.computeDerived
import com.miscalebridge.app.profile.UserProfile
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class HealthConnectWriter(private val context: Context) {

    /** Keyed by (profileId, scaleTimestamp). Prevents the main + low-freq
     *  follow-up frames of one weigh-in from inserting twice, and survives
     *  repeated taps for the same measurement. */
    private val written: MutableSet<Pair<Int, Instant>> = ConcurrentHashMap.newKeySet()

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    /** Returns the number of records inserted, or 0 if this measurement was
     *  already written (or has no insertable fields). */
    suspend fun write(m: S400Measurement, profile: UserProfile): Result<Int> {
        val key = profile.profileId to m.timestamp
        if (!written.add(key)) {
            Log.i(TAG, "skip duplicate write for profile=${profile.profileId} ts=${m.timestamp}")
            return Result.success(0)
        }
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val records = buildRecords(m, profile)
            if (records.isEmpty()) {
                Log.i(TAG, "no insertable records for $m")
                return@runCatching 0
            }
            Log.i(TAG, "writing ${records.size} record(s) at ${m.timestamp}: " +
                records.joinToString { it::class.simpleName ?: "?" })
            client.insertRecords(records)
            records.size
        }.onFailure {
            written.remove(key)   // allow retry next time
            Log.w(TAG, "insertRecords failed", it)
        }
    }

    private fun buildRecords(m: S400Measurement, profile: UserProfile): List<Record> {
        val zone = ZoneOffset.systemDefault().rules.getOffset(m.timestamp)
        val device = Device(
            manufacturer = "Xiaomi",
            model = "MJTZC01YM",
            type = Device.TYPE_SCALE,
        )
        val meta = Metadata.autoRecorded(device = device)
        val derived = computeDerived(m, profile)

        return buildList {
            add(WeightRecord(
                weight = Mass.kilograms(m.weightKg),
                time = m.timestamp, zoneOffset = zone, metadata = meta,
            ))
            derived.bodyFatPercent?.let {
                add(BodyFatRecord(
                    time = m.timestamp, zoneOffset = zone,
                    percentage = Percentage(it), metadata = meta,
                ))
            }
            derived.leanBodyMassKg?.let {
                add(LeanBodyMassRecord(
                    time = m.timestamp, zoneOffset = zone,
                    mass = Mass.kilograms(it), metadata = meta,
                ))
            }
            derived.bodyWaterKg?.let {
                add(BodyWaterMassRecord(
                    time = m.timestamp, zoneOffset = zone,
                    mass = Mass.kilograms(it), metadata = meta,
                ))
            }
            derived.boneMassKg?.let {
                add(BoneMassRecord(
                    time = m.timestamp, zoneOffset = zone,
                    mass = Mass.kilograms(it), metadata = meta,
                ))
            }
            derived.bmrKcal?.let { kcalPerDay ->
                // BasalMetabolicRateRecord stores Power; kcal/day → watts.
                val watts = kcalPerDay * 4.184 * 1000.0 / 86400.0
                add(BasalMetabolicRateRecord(
                    time = m.timestamp, zoneOffset = zone,
                    basalMetabolicRate = Power.watts(watts), metadata = meta,
                ))
            }
        }
    }

    private companion object { const val TAG = "HealthConnectWriter" }
}
