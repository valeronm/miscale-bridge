package com.miscalebridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.miscalebridge.app.profile.ProfileStore
import com.miscalebridge.app.profile.Sex
import com.miscalebridge.app.profile.UserProfile
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(profileStore: ProfileStore) {
    val scope = rememberCoroutineScope()
    val saved by profileStore.config.collectAsState(initial = null)

    var mac by remember { mutableStateOf("") }
    var bindkey by remember { mutableStateOf("") }
    var heightCm by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var profileId by remember { mutableStateOf("1") }
    var bindkeyVisible by remember { mutableStateOf(false) }
    var saveResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(saved) {
        saved?.let {
            if (mac.isEmpty()) mac = it.macAddress
            if (bindkey.isEmpty()) bindkey = it.bindkeyHex
            if (heightCm.isEmpty()) heightCm = it.heightCm.toString()
            if (age.isEmpty()) age = it.age.toString()
            sex = it.sex
            profileId = it.profileId.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Scale")
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it.trim().uppercase() },
            label = { Text("MAC address") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bindkey,
            onValueChange = { bindkey = it.trim().lowercase() },
            label = { Text("Bindkey (32 hex chars)") },
            singleLine = true,
            visualTransformation =
                if (bindkeyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { bindkeyVisible = !bindkeyVisible }) {
                    Text(if (bindkeyVisible) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = profileId,
            onValueChange = { profileId = it.filter(Char::isDigit) },
            label = { Text("Scale profile id (1, 2, …)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        SectionLabel("Your body")
        OutlinedTextField(
            value = heightCm,
            onValueChange = { heightCm = it.filter(Char::isDigit) },
            label = { Text("Height (cm)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = age,
            onValueChange = { age = it.filter(Char::isDigit) },
            label = { Text("Age (years)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val sexOptions = listOf(Sex.MALE, Sex.FEMALE)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            sexOptions.forEachIndexed { i, value ->
                SegmentedButton(
                    selected = sex == value,
                    onClick = { sex = value },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = sexOptions.size),
                ) { Text(value.name.lowercase().replaceFirstChar(Char::uppercase)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val h = heightCm.toIntOrNull() ?: 0
                val a = age.toIntOrNull() ?: 0
                val pid = profileId.toIntOrNull() ?: 1
                val problems = buildList {
                    if (mac.length != 17 || mac.count { it == ':' } != 5) add("MAC must be AA:BB:CC:DD:EE:FF")
                    if (bindkey.length != 32 || !bindkey.all { it in "0123456789abcdef" })
                        add("Bindkey must be 32 hex chars")
                    if (h !in 100..250) add("Height must be 100–250 cm")
                    if (a !in 5..120) add("Age must be 5–120")
                    if (pid !in 1..16) add("Profile id must be 1–16")
                }
                if (problems.isNotEmpty()) {
                    saveResult = problems.joinToString("\n")
                    return@Button
                }
                scope.launch {
                    profileStore.save(UserProfile(
                        macAddress = mac, bindkeyHex = bindkey,
                        heightCm = h, age = a, sex = sex, profileId = pid,
                    ))
                    saveResult = "Saved."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        saveResult?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (it == "Saved.") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Bindkey extraction: pair the scale in Mi Home / Xiaomi Home once, " +
                "then run a Xiaomi-cloud-tokens-extractor against your account — it " +
                "lists every BLE device with its MAC and bindkey.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
