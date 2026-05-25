package com.miscalebridge.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.ble.ScaleScanner
import com.miscalebridge.app.compose.DerivedComposition
import com.miscalebridge.app.compose.computeDerived
import com.miscalebridge.app.db.AppDatabase
import com.miscalebridge.app.health.HealthConnectImporter
import com.miscalebridge.app.health.HealthConnectWriter
import com.miscalebridge.app.history.MeasurementHistory
import com.miscalebridge.app.profile.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "miscale_bridge")

class MiScaleApp : Application() {
    lateinit var profileStore: ProfileStore
        private set
    lateinit var scanner: ScaleScanner
        private set
    lateinit var healthWriter: HealthConnectWriter
        private set
    lateinit var history: MeasurementHistory
        private set
    lateinit var hcImporter: HealthConnectImporter
        private set

    /** Single source of truth for "current measurement + its derived metrics".
     *  Recomputed exactly once per (measurement, profile) change — the UI and
     *  the auto-write coroutine both subscribe here instead of running
     *  computeDerived themselves. */
    private val _latestDerived =
        MutableStateFlow<Pair<S400Measurement, DerivedComposition>?>(null)
    val latestDerived: StateFlow<Pair<S400Measurement, DerivedComposition>?> = _latestDerived

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        profileStore = ProfileStore(dataStore)
        scanner = ScaleScanner(this)
        healthWriter = HealthConnectWriter(this)
        val dao = AppDatabase.build(this).weighInDao()
        history = MeasurementHistory(dao, appScope)
        hcImporter = HealthConnectImporter(this, profileStore, dao)

        appScope.launch {
            combine(
                scanner.latestMeasurement,
                profileStore.config,
                profileStore.autoWrite,
            ) { m, p, auto -> Triple(m, p, auto) }
                .distinctUntilChangedBy { (m, p, _) ->
                    // Recompute only when measurement or profile inputs change.
                    // Auto-write toggling alone doesn't re-fire — that's handled
                    // by the dialog flow in the UI.
                    Triple(
                        m?.let { it.weightKg to it.timestamp },
                        m?.impedanceOhm to m?.impedanceLowOhm,
                        p?.profileId,
                    )
                }
                .collect { (m, p, auto) ->
                    if (m == null || p == null) {
                        _latestDerived.value = null
                        return@collect
                    }
                    val derived = computeDerived(m, p)
                    _latestDerived.value = m to derived
                    val written = if (auto) {
                        healthWriter.write(m, p).getOrDefault(0)
                    } else 0
                    history.add(
                        MeasurementHistory.Entry(
                            measurement = m,
                            derived = derived,
                            writtenRecordCount = written,
                            dataOriginPackageName = packageName,
                        )
                    )
                }
        }
    }
}
