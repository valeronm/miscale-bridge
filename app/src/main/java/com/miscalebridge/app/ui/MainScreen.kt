package com.miscalebridge.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.miscalebridge.app.MiScaleApp
import com.miscalebridge.app.ble.ScaleScanner
import com.miscalebridge.app.ui.screens.HistoryScreen
import com.miscalebridge.app.ui.screens.NowScreen
import com.miscalebridge.app.ui.screens.SettingsScreen

/** Each tab is self-contained; backend state always comes from [MiScaleApp]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(app: MiScaleApp) {
    var current by remember { mutableStateOf(Tab.NOW) }
    val scanStatus by app.scanner.status.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.label) },
                actions = {
                    StatusDot(scanStatus)
                    Spacer(Modifier.size(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { current = tab },
                        icon = {},               // text-only — keeps deps small
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (current) {
                Tab.NOW -> NowScreen(
                    app = app,
                    profileStore = app.profileStore,
                    scanner = app.scanner,
                    healthWriter = app.healthWriter,
                    onOpenSettings = { current = Tab.SETTINGS },
                )
                Tab.HISTORY -> HistoryScreen(history = app.history)
                Tab.SETTINGS -> SettingsScreen(profileStore = app.profileStore)
            }
        }
    }
}

@Composable
private fun StatusDot(status: ScaleScanner.Status) {
    val (color, label) = when (status) {
        ScaleScanner.Status.MEASURING -> Color(0xFF1565C0) to "measuring"
        ScaleScanner.Status.READY -> Color(0xFF2E7D32) to "ready"
        ScaleScanner.Status.SEARCHING -> Color(0xFFE0A800) to "searching"
        ScaleScanner.Status.IDLE -> Color(0xFF9E9E9E) to "idle"
        ScaleScanner.Status.ERROR_NO_PERMISSION -> Color(0xFFB00020) to "no perm"
        ScaleScanner.Status.ERROR_BT_OFF -> Color(0xFFB00020) to "BT off"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.size(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
