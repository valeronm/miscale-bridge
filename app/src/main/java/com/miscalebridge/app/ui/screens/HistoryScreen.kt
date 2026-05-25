package com.miscalebridge.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.miscalebridge.app.MiScaleApp
import com.miscalebridge.app.health.HealthConnectImporter
import com.miscalebridge.app.health.HealthConnectPermissions
import com.miscalebridge.app.history.MeasurementHistory
import com.miscalebridge.app.ui.f
import com.miscalebridge.app.ui.relative
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(history: MeasurementHistory, app: MiScaleApp) {
    val entries by history.entries.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ownPackage = context.packageName
    val snackbar = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }

    val hcReadLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectPermissions.READS)) {
            runImport(scope, app.hcImporter, snackbar) { importing = it }
        } else {
            scope.launch { snackbar.showSnackbar("Read permissions not granted.") }
        }
    }

    val onImport: () -> Unit = {
        scope.launch {
            val granted = HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
            if (granted.containsAll(HealthConnectPermissions.READS)) {
                runImport(scope, app.hcImporter, snackbar) { importing = it }
            } else {
                hcReadLauncher.launch(HealthConnectPermissions.READS)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ImportBar(importing = importing, onImport = onImport)
            HorizontalDivider()
            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No weigh-ins yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Step on the scale, or import existing records from Health Connect.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        entries,
                        key = { it.measurement.profileId to it.measurement.timestamp },
                    ) { e ->
                        TimestampedCard(entry = e, ownPackage = ownPackage)
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun ImportBar(importing: Boolean, onImport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Backfill weigh-ins from Health Connect (this and other apps).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onImport, enabled = !importing) {
            Text(if (importing) "Importing…" else "Import")
        }
    }
}

private fun runImport(
    scope: kotlinx.coroutines.CoroutineScope,
    importer: HealthConnectImporter,
    snackbar: SnackbarHostState,
    setImporting: (Boolean) -> Unit,
) {
    scope.launch {
        setImporting(true)
        val result = importer.import()
        setImporting(false)
        result.fold(
            onSuccess = { s ->
                val touched = s.newlyImported + s.refreshed
                val msg = when {
                    s.totalReadFromHealthConnect == 0 -> "No records in Health Connect to import."
                    touched == 0 -> "Already up to date (${s.totalReadFromHealthConnect} HC record(s), " +
                        "${s.preservedScaleDecoded} preserved as scale-decoded)."
                    else -> buildString {
                        append("Imported ${s.newlyImported} new")
                        if (s.refreshed > 0) append(", refreshed ${s.refreshed}")
                        if (s.preservedScaleDecoded > 0) append(", preserved ${s.preservedScaleDecoded} scale-decoded")
                        append(" of ${s.totalReadFromHealthConnect}.")
                    }
                }
                snackbar.showSnackbar(msg)
            },
            onFailure = { e ->
                snackbar.showSnackbar("Import failed: ${e.message ?: e::class.simpleName}")
            },
        )
    }
}

/** Centered timestamp heading above the card, journal-style. */
@Composable
private fun TimestampedCard(entry: MeasurementHistory.Entry, ownPackage: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            entry.measurement.timestamp.relative(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            textAlign = TextAlign.Center,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entry.measurement.weightKg.f(1)} kg",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    HealthConnectStatusBadge(entry = entry, ownPackage = ownPackage)
                }
                val parts = buildList {
                    entry.derived.bodyFatPercent?.let { add("BF ${it.f(1)} %") }
                    entry.derived.leanBodyMassKg?.let { add("LBM ${it.f(1)} kg") }
                    entry.derived.bodyWaterKg?.let { add("water ${it.f(1)} kg") }
                    entry.derived.boneMassKg?.let { add("bone ${it.f(2)} kg") }
                    entry.derived.bmrKcal?.let { add("BMR ${it.f(0)} kcal") }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Three states, two glyphs, one absence:
 *   - imported from HC → source app label + download arrow ("Fitbit ↓")
 *   - scale-decoded and saved to HC → check (we own it AND it's synced)
 *   - scale-decoded, not in HC → nothing (absence = local only)
 * The source label is omitted for imports that came from our own package
 * (i.e., re-import of our own historical writes) — would be redundant.
 */
@Composable
private fun HealthConnectStatusBadge(
    entry: MeasurementHistory.Entry,
    ownPackage: String,
) {
    val isImported = entry.measurement.rawHex.isEmpty()
    when {
        isImported -> {
            val pkg = entry.dataOriginPackageName
            val labelText = if (pkg != null && pkg != ownPackage) {
                entry.dataOriginAppLabel ?: pkg
            } else null
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (labelText != null) {
                    Text(
                        labelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Imported from Health Connect",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        entry.writtenRecordCount > 0 -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Saved to Health Connect",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        // else: local-only; absence is the indicator.
    }
}
