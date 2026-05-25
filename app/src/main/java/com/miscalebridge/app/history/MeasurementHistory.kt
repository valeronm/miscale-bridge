package com.miscalebridge.app.history

import android.util.Log
import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.compose.DerivedComposition
import com.miscalebridge.app.db.WeighInDao
import com.miscalebridge.app.db.WeighInEntity
import com.miscalebridge.app.db.toEntity
import com.miscalebridge.app.db.toEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Persistent weigh-in store, backed by Room. Survives process death and
 * uninstalls-of-Health-Connect alike. Health Connect remains the canonical
 * record store for cross-app sync; this is the local copy the History tab
 * renders directly without a Health Connect read permission.
 */
class MeasurementHistory(
    private val dao: WeighInDao,
    scope: CoroutineScope,
) {

    data class Entry(
        val measurement: S400Measurement,
        val derived: DerivedComposition,
        val writtenRecordCount: Int,   // 0 if HC write failed or was skipped
        /** Source package (our own for scale-decoded; the writer's for HC imports). */
        val dataOriginPackageName: String? = null,
        /** Human-readable app label snapshot; null = fall back to package name or "this app". */
        val dataOriginAppLabel: String? = null,
    ) {
        /**
         * True when the weigh-in is known to exist in Health Connect — either
         * because we wrote it ourselves on auto-write, or because we just
         * imported it from HC (HC-imported rows are flagged by an empty
         * rawHex, since only a BLE-decoded frame carries raw bytes).
         */
        val isInHealthConnect: Boolean
            get() = writtenRecordCount > 0 || measurement.rawHex.isEmpty()
    }

    val entries: StateFlow<List<Entry>> = dao.observeAll()
        .map { rows -> rows.map(WeighInEntity::toEntry) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // One-shot sanity log on startup so disk state is visible in logcat
        // without spamming on every subsequent emission.
        scope.launch {
            val initial = dao.observeAll().first()
            Log.d(TAG, "loaded ${initial.size} weigh-in(s) from disk")
        }
    }

    suspend fun add(entry: Entry) {
        dao.upsert(entry.toEntity())
        val m = entry.measurement
        Log.d(TAG, "stored ${"%.1f".format(m.weightKg)} kg @${m.timestamp} profile=${m.profileId} hc=${entry.writtenRecordCount}")
    }

    suspend fun clear() {
        dao.clear()
        Log.d(TAG, "cleared all weigh-ins")
    }

    private companion object { const val TAG = "MeasurementHistory" }
}
