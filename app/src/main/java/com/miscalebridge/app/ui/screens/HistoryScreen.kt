package com.miscalebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miscalebridge.app.history.MeasurementHistory
import com.miscalebridge.app.ui.f
import com.miscalebridge.app.ui.relative

@Composable
fun HistoryScreen(history: MeasurementHistory) {
    val entries by history.entries.collectAsState()

    if (entries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No weigh-ins yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Step on the scale with scanning enabled and the latest readings will show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.measurement.profileId to it.measurement.timestamp }) { e ->
            HistoryCard(e)
        }
    }
}

@Composable
private fun HistoryCard(e: MeasurementHistory.Entry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${e.measurement.weightKg.f(1)} kg",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(0.dp).then(Modifier))
                Text(
                    "   ${e.measurement.timestamp.relative()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val parts = buildList {
                e.derived.bodyFatPercent?.let { add("BF ${it.f(1)} %") }
                e.derived.leanBodyMassKg?.let { add("LBM ${it.f(1)} kg") }
                e.derived.bodyWaterKg?.let { add("water ${it.f(1)} kg") }
                e.derived.boneMassKg?.let { add("bone ${it.f(2)} kg") }
                e.derived.bmrKcal?.let { add("BMR ${it.f(0)} kcal") }
            }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val hcLabel = when (e.writtenRecordCount) {
                0 -> "not written to Health Connect"
                1 -> "1 record → Health Connect"
                else -> "${e.writtenRecordCount} records → Health Connect"
            }
            Text(
                hcLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
