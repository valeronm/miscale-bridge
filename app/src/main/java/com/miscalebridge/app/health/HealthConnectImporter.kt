package com.miscalebridge.app.health

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.miscalebridge.app.db.WeighInDao
import com.miscalebridge.app.db.WeighInEntity
import com.miscalebridge.app.profile.ProfileStore
import com.miscalebridge.app.profile.UserProfile
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Pulls historical weigh-ins out of Health Connect and into the local Room
 * store. Source-agnostic: imports records from this app as well as Mi Fitness,
 * Zepp, manual entries, etc., tagging each with the originating package and
 * (where resolvable) the user-visible app label snapshot.
 *
 * Idempotent: insert-if-missing keyed on (profileId, timestamp). Re-running
 * brings in newly-present HC records without disturbing existing local rows.
 */
class HealthConnectImporter(
    private val context: Context,
    private val profileStore: ProfileStore,
    private val dao: WeighInDao,
) {

    data class Summary(
        val totalReadFromHealthConnect: Int,
        val newlyImported: Int,
        /** Existing imported rows replaced by the fresher HC version. */
        val refreshed: Int,
        /** Scale-decoded rows preserved as-is at the same PK. */
        val preservedScaleDecoded: Int,
    )

    suspend fun import(): Result<Summary> = runCatching {
        val profile = profileStore.config.first()
            ?: error("Profile required before import (need heightCm for BMI back-fill)")
        val client = HealthConnectClient.getOrCreate(context)
        val pm = context.packageManager

        val weights = readAll(client, WeightRecord::class)
        val bodyFats = readAll(client, BodyFatRecord::class)
        val leans = readAll(client, LeanBodyMassRecord::class)
        val waters = readAll(client, BodyWaterMassRecord::class)
        val bones = readAll(client, BoneMassRecord::class)
        val bmrs = readAll(client, BasalMetabolicRateRecord::class)

        Log.i(TAG, "HC reads: weight=${weights.size} bodyFat=${bodyFats.size} " +
            "lean=${leans.size} water=${waters.size} bone=${bones.size} bmr=${bmrs.size}")

        val labelCache = mutableMapOf<String, String?>()
        // Track join hit counts so a future weight-only result is diagnosable
        // from logcat without re-running with extra logging.
        var matchedBodyFat = 0
        var matchedLean = 0
        var matchedWater = 0
        var matchedBone = 0
        var matchedBmr = 0
        var newlyImported = 0
        var refreshed = 0
        var preservedScaleDecoded = 0
        for (w in weights) {
            val ts = w.time
            val pkg = w.metadata.dataOrigin.packageName
            val label = labelCache.getOrPut(pkg) { resolveAppLabel(pm, pkg) }

            val bf = nearestByTime(bodyFats, ts) { it.time }
            val ln = nearestByTime(leans, ts) { it.time }
            val wt = nearestByTime(waters, ts) { it.time }
            val bn = nearestByTime(bones, ts) { it.time }
            val br = nearestByTime(bmrs, ts) { it.time }
            if (bf != null) matchedBodyFat++
            if (ln != null) matchedLean++
            if (wt != null) matchedWater++
            if (bn != null) matchedBone++
            if (br != null) matchedBmr++

            val entity = WeighInEntity(
                profileId = profile.profileId,
                timestampEpochSec = ts.epochSecond,
                weightKg = w.weight.inKilograms,
                heartRateBpm = null,
                impedanceOhm = null,
                impedanceLowOhm = null,
                rawHex = "",
                bmi = bmiFor(w.weight.inKilograms, profile),
                bodyFatPercent = bf?.percentage?.value,
                leanBodyMassKg = ln?.mass?.inKilograms,
                bodyWaterKg = wt?.mass?.inKilograms,
                boneMassKg = bn?.mass?.inKilograms,
                waterPercent = wt?.let { it.mass.inKilograms / w.weight.inKilograms * 100.0 },
                skeletalMuscleMassKg = null,
                proteinPercent = null,
                visceralFatRating = null,
                bmrKcal = br?.basalMetabolicRate?.inWatts?.let { watts ->
                    watts * 86_400.0 / (4.184 * 1000.0)
                },
                ecwKg = null,
                icwKg = null,
                bcmKg = null,
                ecwTbwRatioPct = null,
                writtenRecordCount = 0,
                dataOriginPackageName = pkg,
                dataOriginAppLabel = label,
            )
            // Scale-decoded rows carry raw BLE frame bytes; HC imports never do.
            // We never clobber scale data with an HC re-import, but we do
            // refresh a stale import so users see newly-joined body composition.
            val existing = dao.findByKey(profile.profileId, ts.epochSecond)
            when {
                existing == null -> {
                    dao.upsert(entity)
                    newlyImported++
                }
                existing.rawHex.isNotEmpty() -> preservedScaleDecoded++
                else -> {
                    dao.upsert(entity)
                    refreshed++
                }
            }
        }
        Log.i(TAG, "joined per ${weights.size} weight(s): bodyFat=$matchedBodyFat " +
            "lean=$matchedLean water=$matchedWater bone=$matchedBone bmr=$matchedBmr")
        Log.i(TAG, "import done: new=$newlyImported refreshed=$refreshed " +
            "preserved=$preservedScaleDecoded of ${weights.size} HC weigh-in(s)")
        Summary(
            totalReadFromHealthConnect = weights.size,
            newlyImported = newlyImported,
            refreshed = refreshed,
            preservedScaleDecoded = preservedScaleDecoded,
        )
    }.onFailure { Log.w(TAG, "import failed", it) }

    /**
     * Source apps don't always write companion body-composition records at the
     * exact same Instant as the WeightRecord — Mi Fitness, for instance, may
     * publish body fat a second or two later once the impedance signal settles.
     * Match by closest timestamp within [JOIN_TOLERANCE_MS] (one minute):
     * comfortably covers any sane single-weigh-in spread, and is short enough
     * that two separate weigh-ins won't collide.
     */
    private fun <T> nearestByTime(
        pool: List<T>,
        target: Instant,
        timeOf: (T) -> Instant,
    ): T? {
        var best: T? = null
        var bestDelta = Long.MAX_VALUE
        val targetMs = target.toEpochMilli()
        for (item in pool) {
            val delta = kotlin.math.abs(timeOf(item).toEpochMilli() - targetMs)
            if (delta <= JOIN_TOLERANCE_MS && delta < bestDelta) {
                best = item
                bestDelta = delta
            }
        }
        return best
    }

    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
    ): List<T> {
        val out = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                    pageToken = pageToken,
                )
            )
            out += resp.records
            pageToken = resp.pageToken
        } while (pageToken != null)
        return out
    }

    private fun resolveAppLabel(pm: PackageManager, pkg: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        // Either the source app is uninstalled, or package visibility blocks
        // us from seeing it. The latter would mean QUERY_ALL_PACKAGES dropped
        // out of the manifest. UI falls back to the raw package name.
        Log.w(TAG, "no label for $pkg (uninstalled or hidden by package visibility)")
        null
    }

    private fun bmiFor(weightKg: Double, p: UserProfile): Double =
        if (p.heightCm > 0) weightKg / (p.heightCm / 100.0).let { it * it } else 0.0

    private companion object {
        const val TAG = "HealthConnectImporter"
        const val JOIN_TOLERANCE_MS = 60_000L
    }
}
