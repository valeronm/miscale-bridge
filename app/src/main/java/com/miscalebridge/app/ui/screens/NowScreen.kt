package com.miscalebridge.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.miscalebridge.app.MiScaleApp
import com.miscalebridge.app.ble.S400Measurement
import com.miscalebridge.app.ble.S400Parser
import com.miscalebridge.app.ble.ScaleScanner
import com.miscalebridge.app.health.HealthConnectPermissions
import com.miscalebridge.app.health.HealthConnectWriter
import com.miscalebridge.app.profile.ProfileStore
import com.miscalebridge.app.ui.f
import com.miscalebridge.app.ui.relative
import kotlinx.coroutines.launch

private val BLE_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
fun NowScreen(
    app: MiScaleApp,
    profileStore: ProfileStore,
    scanner: ScaleScanner,
    healthWriter: HealthConnectWriter,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by profileStore.config.collectAsState(initial = null)
    val measurement by scanner.latestMeasurement.collectAsState()
    val derivedPair by app.latestDerived.collectAsState()
    val status by scanner.status.collectAsState()
    val autoWriteOn by profileStore.autoWrite.collectAsState(initial = false)

    var bleGranted by remember {
        mutableStateOf(BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    // When non-null, an AlertDialog asks "save this reading too?" after the
    // auto-write toggle goes ON with a cached measurement on screen.
    var pendingOneShot by remember { mutableStateOf<S400Measurement?>(null) }
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> bleGranted = result.values.all { it } }

    // Health Connect permission request — only invoked when the user toggles
    // the auto-write switch ON without having previously granted access.
    val hcLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectPermissions.ALL)) {
            scope.launch { profileStore.setAutoWrite(true) }
            // Surface a confirm dialog for the already-visible reading,
            // if any — never write it silently behind the user's back.
            pendingOneShot = measurement
        }
        // Partial / denied → leave autoWrite as it was (false).
    }

    // Auto-start scanning whenever the prerequisites become satisfied. Re-runs
    // when the profile is saved or the user grants Bluetooth permissions.
    LaunchedEffect(bleGranted, config, status) {
        val cfg = config ?: return@LaunchedEffect
        // Only call start() when we're not already scanning in any form.
        val needsStart = status == ScaleScanner.Status.IDLE ||
            status == ScaleScanner.Status.ERROR_NO_PERMISSION ||
            status == ScaleScanner.Status.ERROR_BT_OFF
        if (bleGranted && needsStart) {
            scanner.start(S400Parser.bindkeyFromHex(cfg.bindkeyHex), cfg.macAddress)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (derivedPair != null) {
            val (m, derived) = derivedPair!!
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${m.weightKg.f(1)} kg",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    "BMI ${derived.bmi.f(1)}  ·  ${m.timestamp.relative()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status == ScaleScanner.Status.MEASURING) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Measuring…") },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledLabelColor = Color.White,
                            disabledContainerColor = Color(0xFF1565C0),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Section("Body composition") {
                derived.bodyFatPercent?.let {
                    val fatKg = m.weightKg * it / 100.0
                    MetricRow("Body fat", "${it.f(1)} %", "${fatKg.f(1)} kg")
                }
                derived.leanBodyMassKg?.let { MetricRow("Lean mass", "${it.f(1)} kg") }
                derived.waterPercent?.let { wp ->
                    derived.bodyWaterKg?.let { wk ->
                        MetricRow("Water", "${wp.f(1)} %", "${wk.f(1)} kg")
                    }
                }
                derived.boneMassKg?.let { MetricRow("Bone", "${it.f(2)} kg") }
                derived.skeletalMuscleMassKg?.let { MetricRow("Muscle (SMM)", "${it.f(1)} kg") }
                derived.proteinPercent?.let { MetricRow("Protein", "${it.f(1)} %") }
                derived.visceralFatRating?.let { MetricRow("Visceral fat", it.f(0)) }
                derived.bmrKcal?.let { MetricRow("BMR", "${it.f(0)} kcal/day") }
            }

            if (derived.ecwKg != null) {
                Section("Water compartments") {
                    MetricRow("Extracellular (ECW)", "${derived.ecwKg.f(1)} L")
                    derived.icwKg?.let { MetricRow("Intracellular (ICW)", "${it.f(1)} L") }
                    derived.bcmKg?.let { MetricRow("Body cell mass", "${it.f(1)} kg") }
                    derived.ecwTbwRatioPct?.let {
                        MetricRow("ECW / TBW", "${it.f(1)} %")
                    }
                }
            }

            Section("Impedance") {
                m.impedanceOhm?.let { MetricRow("50 kHz", "${it.f(1)} Ω") }
                m.impedanceLowOhm?.let { MetricRow("250 kHz", "${it.f(1)} Ω") }
            }
        } else if (config == null) {
            EmptyState(
                title = "No profile yet",
                body = "Open the Settings tab to enter your scale's MAC, bindkey, " +
                    "and your height/age/sex.",
            )
        } else {
            EmptyState(
                title = when (status) {
                    ScaleScanner.Status.MEASURING -> "Measuring…"
                    ScaleScanner.Status.READY -> "Ready for measurement"
                    ScaleScanner.Status.SEARCHING -> "Searching for scale…"
                    ScaleScanner.Status.IDLE ->
                        if (!bleGranted) "Bluetooth permission needed" else "Starting…"
                    ScaleScanner.Status.ERROR_NO_PERMISSION -> "Bluetooth permission missing"
                    ScaleScanner.Status.ERROR_BT_OFF -> "Bluetooth is off"
                },
                body = when (status) {
                    ScaleScanner.Status.MEASURING -> "Hold steady — results in a moment."
                    ScaleScanner.Status.READY -> "Step on the scale."
                    ScaleScanner.Status.SEARCHING -> "Wake the scale by touching the platform."
                    ScaleScanner.Status.ERROR_BT_OFF -> "Turn Bluetooth on in system settings."
                    else -> ""
                },
            )
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Auto-write to Health Connect",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    if (autoWriteOn) "Each weigh-in is saved automatically."
                    else "Off — readings stay in the app only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = autoWriteOn,
                onCheckedChange = { wantOn ->
                    if (!wantOn) {
                        scope.launch { profileStore.setAutoWrite(false) }
                        return@Switch
                    }
                    // Permission grant is async — the hcLauncher callback
                    // above flips the toggle on once everything is granted.
                    scope.launch {
                        val granted = HealthConnectClient
                            .getOrCreate(context)
                            .permissionController
                            .getGrantedPermissions()
                        if (granted.containsAll(HealthConnectPermissions.ALL)) {
                            profileStore.setAutoWrite(true)
                            pendingOneShot = measurement
                        } else {
                            hcLauncher.launch(HealthConnectPermissions.ALL)
                        }
                    }
                },
            )
        }

        if (!bleGranted) {
            Button(
                onClick = { bleLauncher.launch(BLE_PERMISSIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant Bluetooth permissions") }
        }
    }

    pendingOneShot?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingOneShot = null },
            title = { Text("Save current measurement?") },
            text = {
                Text(
                    "Send the current reading (${pending.weightKg.f(1)} kg, " +
                        "${pending.timestamp.relative()}) to Health Connect now? " +
                        "Future weigh-ins will save automatically."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cfg = config
                    if (cfg != null) {
                        scope.launch { healthWriter.write(pending, cfg) }
                    }
                    pendingOneShot = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOneShot = null }) { Text("Skip") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        if (trailing != null) {
            Text(
                "  ·  $trailing",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
