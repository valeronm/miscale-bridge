package com.miscalebridge.app.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord

/**
 * Health Connect permissions, split into two sets so the UI can request only
 * what each user-facing flow needs:
 *   - [WRITES] for the auto-write toggle on the Now tab.
 *   - [READS] for the Import button on the History tab.
 *   - [ALL] = both, requested when both are needed in one flow.
 */
object HealthConnectPermissions {
    val WRITES: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
    )

    val READS: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        // Without this HC silently truncates readRecords to the last 30 days,
        // so any year-over-year import would return only the recent month.
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )
}
