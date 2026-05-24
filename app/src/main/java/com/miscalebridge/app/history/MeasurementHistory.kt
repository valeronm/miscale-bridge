package com.miscalebridge.app.history

import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.compose.DerivedComposition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory ring of recent weigh-ins, capped at [MAX_ENTRIES]. The authoritative
 * record store is Health Connect itself; this is only for the History tab's
 * "last few weigh-ins" view and is intentionally lost on process death.
 */
class MeasurementHistory {

    data class Entry(
        val measurement: S400Measurement,
        val derived: DerivedComposition,
        val writtenRecordCount: Int,   // 0 if HC write failed or was skipped
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun add(entry: Entry) {
        val key = entry.measurement.profileId to entry.measurement.timestamp
        val current = _entries.value
        if (current.firstOrNull()?.let { it.measurement.profileId to it.measurement.timestamp } == key) {
            // Same (profile, timestamp) as the most recent — replace, don't duplicate
            // (this handles the merged low-freq update of the same weigh-in).
            _entries.value = listOf(entry) + current.drop(1)
        } else {
            _entries.value = (listOf(entry) + current).take(MAX_ENTRIES)
        }
    }

    fun clear() { _entries.value = emptyList() }

    private companion object { const val MAX_ENTRIES = 50 }
}
